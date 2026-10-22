// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/main/java/com/eurobuddha/atomix/swap/OtcController.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import org.json.JSONObject;
import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.CommsTransport;
import com.eurobuddha.comms.CryptoProvider;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.comms.Opened;

import java.security.SecureRandom;

/**
 * The OTC negotiation state machine + message send. Centralizes PROPOSE/COUNTER/ACCEPT/REJECT/EXECUTE so both
 * the foreground Activity and the background Service drive ONE state machine over a shared {@link OtcDb}. The
 * comms transport is stateless coins; all deal/turn state lives in OtcDb, correlated by {@code ref}.
 *
 * Flow: instigator PROPOSE → LP COUNTER/ACCEPT/REJECT → … → someone ACCEPTs → AGREED. The INSTIGATOR then locks
 * leg 1 (otc=TRUE) via {@link SwapEngine#executeOtcDeal} and sends EXECUTE(hash); the LP records the hash and its
 * OTC responder ({@code otcVerify*}) locks the counter-leg only if the on-chain lock matches the agreed terms.
 */
public final class OtcController {

    public interface Ui { void onDealsChanged(); void note(String title, String body); }
    public interface SendResult { void ok(); void err(String msg); }

    private static final long EXPIRE_MS = 60L * 60 * 1000;        // a deal awaiting the PEER this long is abandoned
    // An inbound offer awaiting MY move: the proposer's waiting side expires after EXPIRE_MS, so past that the
    // deal is already dead on their end — an ACCEPT would go to a terminal deal, at a price that only gets staler.
    // Give the user a real window to see it, then age it out like everything else (it used to sit forever).
    static final long INBOUND_EXPIRE_MS = 24L * 60 * 60 * 1000;
    private static final long PRUNE_MS  = 24L * 60 * 60 * 1000;   // drop finished deals after this (bound the table)
    private static final long NOTE_MIN_GAP_MS = 30_000;          // coalesce inbound-PROPOSE notification spam
    private static final long EXEC_RESEND_GAP_MS = 90_000;              // resend a lost EXECUTE at most this often
    private static final long EXEC_RESEND_WINDOW_MS = 90L * 60 * 1000;  // keep retrying EXECUTE well into the ~2h timelock
                                                                        // so a slow LP recovery still completes (not refunds)

    private final com.eurobuddha.atomix.TradingContext market = com.eurobuddha.atomix.TradingContext.active();
    public static final String MARKET_ERROR = "OTC currency changed or is unknown; switch to the deal's market.";
    private boolean currentMarket() { return market == com.eurobuddha.atomix.TradingContext.active(); }
    private boolean sameMarket(OtcDb.Deal deal) { return currentMarket() && deal != null && deal.inMarket(market); }
    private final NodeApi node;
    private final OtcDb otcDb;
    private final SwapEngine engine;
    private final Ui ui;
    private final SecureRandom rnd = new SecureRandom();

    private volatile CryptoProvider crypto;
    private volatile CommsIdentity identity;
    private volatile String myMinimaPk = "", myEthAddr = "";
    private volatile boolean myOfferEnabled = false;
    private volatile double myOfferSellSize = 0, myOfferBuySize = 0;
    private volatile long lastProposeNote = 0;
    private final java.util.Map<String, Long> lastExecResend = new java.util.concurrent.ConcurrentHashMap<>();

    public OtcController(NodeApi node, OtcDb otcDb, SwapEngine engine, Ui ui) {
        this.node = node; this.otcDb = otcDb; this.engine = engine; this.ui = ui;
    }

    public void setSelf(CryptoProvider crypto, CommsIdentity identity, String myMinimaPk, String myEthAddr) {
        this.crypto = crypto; this.identity = identity;
        this.myMinimaPk = myMinimaPk == null ? "" : myMinimaPk;
        this.myEthAddr = myEthAddr == null ? "" : myEthAddr;
    }

    public boolean ready() { return currentMarket() && crypto != null && identity != null && !myMinimaPk.isEmpty() && !myEthAddr.isEmpty(); }

    /** My current published OTC availability — used to validate an inbound PROPOSE (I only accept deals on the side
     *  I advertise and within my advertised size; if I'm not live, I'm not an LP and ignore PROPOSEs). */
    public void setMyOffer(boolean enabled, double sellSize, double buySize) {
        this.myOfferEnabled = enabled; this.myOfferSellSize = OtcOffer.finite(sellSize); this.myOfferBuySize = OtcOffer.finite(buySize);
    }

    private static double parseD(String s) { try { return OtcOffer.finite(Double.parseDouble(s.trim())); } catch (Exception e) { return 0; } }
    public static boolean validSide(String side) { return OtcOffer.LP_SELLS_MINIMA.equals(side) || OtcOffer.LP_BUYS_MINIMA.equals(side); }
    /** Use the existing command decimal guard and 6dp settlement grain; neither leg may round to zero. */
    public static boolean validTerms(String amount, String price) {
        if (!MinimaHtlc.isDecimal(amount) || !MinimaHtlc.isDecimal(price)
                || !amount.equals(amount.trim()) || !price.equals(price.trim())) return false;
        double a = parseD(amount), p = parseD(price);
        if (a <= 0 || p <= 0 || !Double.isFinite(a * p)) return false;
        return MinimaHtlc.positive(MinimaHtlc.grain(amount))
                && new java.math.BigDecimal(amount.trim()).multiply(new java.math.BigDecimal(price.trim()))
                    .setScale(6, java.math.RoundingMode.DOWN).signum() > 0;
    }
    static boolean validDeal(OtcDb.Deal deal) { return deal != null && validSide(deal.side) && validTerms(deal.amount, deal.price); }

    private String rndHex(int bytes) {
        byte[] b = new byte[bytes]; rnd.nextBytes(b);
        StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    public static final String CHANGED_ERROR = "Deal changed. Review the latest terms.";
    private static boolean negotiating(OtcDb.Deal d) {
        return OtcDb.ST_PROPOSED.equals(d.status) || OtcDb.ST_COUNTERED.equals(d.status);
    }
    private static boolean matchingTerms(OtcMessage m, OtcDb.Deal d) {
        return validDeal(d) && java.util.Objects.equals(d.side, m.side)
                && java.util.Objects.equals(d.amount, m.amount) && java.util.Objects.equals(d.price, m.price);
    }

    private static boolean isTerminal(String st) {
        return OtcDb.ST_REJECTED.equals(st) || OtcDb.ST_EXPIRED.equals(st) || OtcDb.ST_COMPLETE.equals(st);
    }

    // ---------- receive (CommsScanner Router) ----------

    /** CommsScanner Router: parse, authenticate, dedup, and drive the deal state machine. */
    public boolean route(String coinid, Opened opened, JSONObject coin) {
        if (!currentMarket() || opened == null || !opened.valid) return false;
        OtcMessage m = OtcMessage.fromWire(opened.plaintext);
        if (m == null || m.ref.isEmpty() || m.randomid.isEmpty()) return false;
        if (identity == null || !identity.publicId().equals(m.to)) return false;               // not for me
        if (opened.fromPublicId == null || !opened.fromPublicId.equals(m.from)) return false;   // bind envelope sender
        if (OtcMessage.PROPOSE.equals(m.type)) {
            // A PROPOSE I can't act on yet (my offer not loaded/live) must NOT burn its dedup slot — the comms coin is
            // re-scanned, so a later delivery can retry once I'm live. Only record it once it actually creates a deal.
            if (otcDb.getDeal(m.ref) != null) return false;   // already have this deal
            boolean created = applyPropose(m);
            if (created) otcDb.addMsg(toRow(m));
            return created;
        }
        if (!sameMarket(otcDb.getDeal(m.ref))) return false;
        if (!otcDb.addMsg(toRow(m))) return false;   // dedup by randomid
        apply(m);
        return true;
    }

    private OtcDb.Msg toRow(OtcMessage m) {
        OtcDb.Msg r = new OtcDb.Msg();
        r.randomid = m.randomid; r.ref = m.ref; r.type = m.type; r.from = m.from;
        r.side = m.side; r.amount = m.amount; r.price = m.price; r.hash = m.hash; r.date = m.date;
        return r;
    }

    /** Handle an inbound PROPOSE. Returns true iff it created a deal (I'm a live LP on that side, within size). A
     *  false result leaves NO dedup trace so a re-scan can retry once my offer is live (see {@link #route}). */
    private boolean applyPropose(OtcMessage m) {
        if (!currentMarket() || otcDb.getDeal(m.ref) != null) return false; // first PROPOSE in this market
        double cap = OtcOffer.LP_SELLS_MINIMA.equals(m.side) ? myOfferSellSize : myOfferBuySize;
        if (!validSide(m.side) || !myOfferEnabled || cap <= 0) return false;                 // I must be a LIVE LP on THIS side
        if (!validTerms(m.amount, m.price) || parseD(m.amount) > cap + 1e-9) return false;
        OtcDb.Deal d = new OtcDb.Deal();
        d.currency = market.key;
        d.ref = m.ref; d.role = OtcDb.ROLE_LP;
        d.peerCommsId = m.from; d.peerMinimaPk = m.minimaPk; d.peerEthAddr = m.ethAddr;
        d.side = m.side; d.amount = m.amount; d.price = m.price;
        d.status = OtcDb.ST_PROPOSED; d.whoseTurn = OtcDb.TURN_ME;
        if (!otcDb.createDeal(d)) return false;
        noteProposeRateLimited(m.amount + " " + market.coinLabel + " @ " + m.price + " — your call");
        ui.onDealsChanged();
        return true;
    }

    private void apply(OtcMessage m) {
        OtcDb.Deal d = otcDb.getDeal(m.ref);
        // CONSENT + AUTHENTICITY: every non-PROPOSE transition needs an existing deal AND must genuinely come from
        // that deal's counterparty (not just any sealed sender). Without this, a hostile instigator could self-ACCEPT.
        if (!sameMarket(d) || m.from == null || !m.from.equals(d.peerCommsId)) return;
        OtcDb.Deal expected = d.copy();
        if (OtcMessage.REJECT.equals(m.type)) {
            if (isTerminal(d.status) || OtcDb.ST_EXECUTING.equals(d.status)) return;
            d.status = OtcDb.ST_REJECTED; if (!otcDb.replaceDeal(expected, d)) return;
            ui.note("OTC rejected", "The counterparty ended the deal");
        } else if (OtcMessage.EXECUTE.equals(m.type)) {
            if (!OtcDb.ROLE_LP.equals(d.role) || m.hash == null || m.hash.isEmpty()) return;
            // Honour EXECUTE if I've AGREED — OR if my last COUNTER is still outstanding (COUNTERED, whoseTurn=PEER):
            // a peer executing my standing offer IS an implicit accept of it. This is ORDER-INDEPENDENT, so it
            // survives the scanner delivering EXECUTE before the matching ACCEPT (coins come newest-first). The
            // on-chain otcVerify* remains the fund gate, and the terms are my own standing counter.
            boolean agreed = OtcDb.ST_AGREED.equals(d.status);
            boolean implicitAccept = OtcDb.ST_COUNTERED.equals(d.status) && OtcDb.TURN_PEER.equals(d.whoseTurn);
            if ((!agreed && !implicitAccept) || !matchingTerms(m, d)) return;
            d.hash = m.hash; d.status = OtcDb.ST_EXECUTING; if (!otcDb.replaceDeal(expected, d)) return;
            if (OtcOffer.LP_SELLS_MINIMA.equals(d.side)) {   // instigator locked USDT → find it via getContract
                engine.addIncomingHashlock(m.hash);
                engine.checkBuyNow(m.hash);
            }   // else instigator locked mxUSDT → the Minima scan (addressed to my key) finds it; no eth polling needed
            ui.note("OTC executing", "Counterparty locked — locking your side");
        } else if (!OtcDb.TURN_PEER.equals(d.whoseTurn)) {
            return;   // CONSENT: a COUNTER/ACCEPT is only legal when I'm genuinely waiting on the peer (their turn)
        } else if (OtcMessage.COUNTER.equals(m.type)) {
            if (!negotiating(d) || !java.util.Objects.equals(d.side, m.side) || !validTerms(m.amount, m.price)) return;
            d.amount = m.amount; d.price = m.price; d.status = OtcDb.ST_COUNTERED; d.whoseTurn = OtcDb.TURN_ME;
            if (!otcDb.replaceDeal(expected, d)) return;
            ui.note("OTC counter", m.amount + " " + market.coinLabel + " @ " + m.price + " — your call");
        } else if (OtcMessage.ACCEPT.equals(m.type)) {
            if (!matchingTerms(m, d)) return;
            if (isTerminal(d.status) || OtcDb.ST_AGREED.equals(d.status) || OtcDb.ST_EXECUTING.equals(d.status)) return;
            d.status = OtcDb.ST_AGREED;
            d.whoseTurn = OtcDb.ROLE_INSTIGATOR.equals(d.role) ? OtcDb.TURN_ME : OtcDb.TURN_PEER;
            if (!otcDb.replaceDeal(expected, d)) return;
            ui.note("OTC agreed", d.amount + " " + market.coinLabel + " @ " + d.price);
            if (OtcDb.ROLE_INSTIGATOR.equals(d.role)) executeDeal(d);   // I opened it → I lock leg 1
        }
        ui.onDealsChanged();
    }

    private void noteProposeRateLimited(String body) {
        long now = System.currentTimeMillis();
        if (now - lastProposeNote < NOTE_MIN_GAP_MS) return;
        lastProposeNote = now;
        ui.note("OTC offer received", body);
    }

    // ---------- send (outbound) ----------

    /** Instigator opens a deal against an LP offer. */
    public void propose(OtcOffer lp, String dealSide, String amount, String price, SendResult res) {
        if (lp == null || !validSide(dealSide) || lp.capFor(dealSide) <= 0) { res.err("enter amount + price"); return; }
        if (lp == null || lp.market != market || !currentMarket()) { res.err(MARKET_ERROR); return; }
        if (!ready()) { res.err("still connecting"); return; }
        if (!validTerms(amount, price)) { res.err("enter amount + price"); return; }
        OtcDb.Deal d = new OtcDb.Deal();
        d.currency = market.key;
        d.ref = rndHex(16); d.role = OtcDb.ROLE_INSTIGATOR;
        d.peerCommsId = lp.commsPublicId; d.peerMinimaPk = lp.minimaPublicKey; d.peerEthAddr = lp.ethAddress;
        d.side = dealSide; d.amount = amount; d.price = price;
        d.status = OtcDb.ST_PROPOSED; d.whoseTurn = OtcDb.TURN_PEER;
        if (!otcDb.createDeal(d)) { res.err(CHANGED_ERROR); return; }
        sendMsg(d, OtcMessage.PROPOSE, "", res);
    }

    public void counter(OtcDb.Deal d, String amount, String price, SendResult res) {
        if (!sameMarket(d)) { res.err(MARKET_ERROR); return; }
        if (!ready() || !negotiating(d) || !OtcDb.TURN_ME.equals(d.whoseTurn)) { res.err("not your turn"); return; }
        if (!validTerms(amount, price)) { res.err("enter amount + price"); return; }
        OtcDb.Deal next = d.copy();
        next.amount = amount; next.price = price; next.status = OtcDb.ST_COUNTERED; next.whoseTurn = OtcDb.TURN_PEER;
        if (!otcDb.replaceDeal(d, next)) { res.err(CHANGED_ERROR); return; }
        sendMsg(next, OtcMessage.COUNTER, "", res);
    }

    public void accept(OtcDb.Deal d, SendResult res) {
        if (!validDeal(d)) { res.err("enter amount + price"); return; }
        if (!sameMarket(d)) { res.err(MARKET_ERROR); return; }
        if (!ready() || !negotiating(d) || !OtcDb.TURN_ME.equals(d.whoseTurn)) { res.err("not your turn"); return; }
        final OtcDb.Deal agreed = d.copy();
        agreed.status = OtcDb.ST_AGREED;
        agreed.whoseTurn = OtcDb.ROLE_INSTIGATOR.equals(d.role) ? OtcDb.TURN_ME : OtcDb.TURN_PEER;
        if (!otcDb.replaceDeal(d, agreed)) { res.err(CHANGED_ERROR); return; }
        sendMsg(agreed, OtcMessage.ACCEPT, "", new SendResult() {
            @Override public void ok() { if (OtcDb.ROLE_INSTIGATOR.equals(agreed.role)) executeDeal(agreed); res.ok(); }
            @Override public void err(String m) { res.err(m); }
        });
    }

    public void reject(OtcDb.Deal d, SendResult res) {
        if (!sameMarket(d)) { res.err(MARKET_ERROR); return; }
        if (!ready()) { res.err("still connecting"); return; }
        if (isTerminal(d.status) || OtcDb.ST_EXECUTING.equals(d.status)) { res.err(CHANGED_ERROR); return; }
        OtcDb.Deal next = d.copy(); next.status = OtcDb.ST_REJECTED;
        if (!otcDb.replaceDeal(d, next)) { res.err(CHANGED_ERROR); return; }
        sendMsg(next, OtcMessage.REJECT, "", res);
    }

    private void sendMsg(OtcDb.Deal d, String type, String hash, SendResult res) {
        if (!sameMarket(d) || !ready()) { res.err(MARKET_ERROR); return; }
        if (!OtcDb.sameSnapshot(d, otcDb.getDeal(d.ref))) { res.err(CHANGED_ERROR); return; }
        OtcMessage m = new OtcMessage();
        m.type = type; m.ref = d.ref; m.randomid = rndHex(12);
        m.from = identity.publicId(); m.to = d.peerCommsId; m.date = System.currentTimeMillis();
        m.side = d.side; m.amount = d.amount; m.price = d.price;
        m.minimaPk = myMinimaPk; m.ethAddr = myEthAddr; m.hash = hash;
        otcDb.addMsg(toRow(m));   // record my own outbound message in the thread
        ui.onDealsChanged();
        m.send(node, crypto, market, new CommsTransport.SendCb() {
            @Override public void onSent(String txpowid) { res.ok(); }
            @Override public void onFailed(String msg) { res.err(msg); }
        });
    }

    /** Atomically reserve the exact agreed snapshot before asking either engine to lock leg 1. */
    public void executeDeal(OtcDb.Deal d) {
        if (!sameMarket(d) || !ready() || !OtcDb.ROLE_INSTIGATOR.equals(d.role)) return;
        OtcDb.Deal current = otcDb.getDeal(d.ref);
        if (!sameMarket(current) || !validDeal(current) || !OtcDb.ST_AGREED.equals(current.status)
                || !OtcDb.sameSnapshot(d, current)) return;
        final OtcDb.Deal executing = current.copy(); executing.status = OtcDb.ST_EXECUTING;
        if (!otcDb.replaceDeal(current, executing)) return;
        ui.onDealsChanged();
        engine.executeOtcDeal(executing.copy(), new SwapEngine.StartCb() {
            private OtcDb.Deal prepared;
            private boolean finished;
            /** Reuse PandaDEX's persist-before-submit callback. Called on either engine thread. */
            @Override public synchronized boolean onPrepared(String hash) {
                if (finished || prepared != null || hash == null
                        || !OtcDb.normHash(hash).matches("[0-9a-f]{64}")) return false;
                OtcDb.Deal next = executing.copy(); next.hash = hash;
                if (!otcDb.replaceDeal(executing, next)) return false;
                prepared = next;
                return true;
            }
            @Override public synchronized void ok(String hash) {
                if (finished || prepared == null || !OtcDb.normHash(hash).equals(prepared.hash)
                        || !OtcDb.sameSnapshot(prepared, otcDb.getDeal(prepared.ref))) return;
                finished = true;
                sendMsg(prepared, OtcMessage.EXECUTE, prepared.hash, new SendResult() { public void ok() {} public void err(String m) {} });
                ui.note("OTC executing", "Locked your side — waiting for the counterparty");
                ui.onDealsChanged();
            }
            @Override public synchronized void err(String m) {
                if (finished) return;
                finished = true;
                if (prepared != null) {
                    // The first leg may already be on-chain. Keep its identity through restart;
                    // expireStale/resendExecute and the swap watcher recover this exact attempt.
                    if (!OtcDb.sameSnapshot(prepared, otcDb.getDeal(prepared.ref))) return;
                    ui.onDealsChanged();
                    ui.note("OTC executing", "Submission outcome is unknown. The saved swap will be checked; do not create a replacement deal.");
                    return;
                }
                OtcDb.Deal retry = executing.copy(); retry.status = OtcDb.ST_AGREED;
                if (!otcDb.replaceDeal(executing, retry)) return;
                ui.onDealsChanged();
                ui.note("OTC execute failed", m);
            }
        });
    }

    /** Expire deals stuck awaiting the PEER past {@link #EXPIRE_MS} and inbound offers ignored past
     *  {@link #INBOUND_EXPIRE_MS}; prune long-finished deals so the table can't grow unboundedly under
     *  message spam. Also settle EXECUTING deals whose on-chain swap has completed/refunded. */
    public void expireStale(long now) {
        for (OtcDb.Deal d : otcDb.allDeals()) {
            boolean stale = now - d.updated > EXPIRE_MS;
            if (OtcDb.ST_EXECUTING.equals(d.status) && d.hash != null && !d.hash.isEmpty()) {
                String ss = engine.swapStatus(d.hash);
                if (SwapDb.ST_COMPLETE.equals(ss)) { changeStatus(d, OtcDb.ST_COMPLETE); }
                else if (SwapDb.ST_REFUNDED.equals(ss) || SwapDb.ST_ERROR.equals(ss)) { changeStatus(d, OtcDb.ST_REJECTED); }
                else if (ss == null && stale) { changeStatus(d, OtcDb.ST_EXPIRED); }   // a mismatched EXECUTE never produced a swap
                else if (OtcDb.ROLE_INSTIGATOR.equals(d.role) && SwapDb.ST_STARTED.equals(ss)
                        && now - d.created < EXEC_RESEND_WINDOW_MS) resendExecute(d, now);   // leg 1 locked but LP silent → my EXECUTE may have been lost
            } else if (stale && (OtcDb.ST_AGREED.equals(d.status)
                    || (OtcDb.ST_EXECUTING.equals(d.status) && (d.hash == null || d.hash.isEmpty()))   // execute never produced a hash
                    || ((OtcDb.ST_PROPOSED.equals(d.status) || OtcDb.ST_COUNTERED.equals(d.status)) && OtcDb.TURN_PEER.equals(d.whoseTurn)))) {
                changeStatus(d, OtcDb.ST_EXPIRED);   // no progress → abandon (any locked leg refunds at timelock)
            } else if ((OtcDb.ST_PROPOSED.equals(d.status) || OtcDb.ST_COUNTERED.equals(d.status))
                    && OtcDb.TURN_ME.equals(d.whoseTurn) && now - d.updated > INBOUND_EXPIRE_MS) {
                changeStatus(d, OtcDb.ST_EXPIRED);   // ignored inbound offer → zombie on the peer's side, stale price on mine
            }
            if (isTerminal(d.status) && now - d.updated > PRUNE_MS) { if (otcDb.deleteDeal(d)) lastExecResend.remove(d.ref); }
        }
    }

    private void changeStatus(OtcDb.Deal expected, String status) {
        OtcDb.Deal next = expected.copy(); next.status = status;
        if (otcDb.replaceDeal(expected, next)) ui.onDealsChanged();
    }

    /** Leg 1 is locked but the counterparty still hasn't responded — my one-shot EXECUTE may have failed to send.
     *  Resend it (throttled per deal) so a lost notification doesn't strand the deal until refund. Each resend is a
     *  fresh message (new randomid); the LP's apply(EXECUTE) is idempotent, and re-recording the same hash is harmless. */
    private void resendExecute(OtcDb.Deal d, long now) {
        if (!sameMarket(d) || !ready()) return;
        Long last = lastExecResend.get(d.ref);
        if (last != null && now - last < EXEC_RESEND_GAP_MS) return;
        lastExecResend.put(d.ref, now);
        sendMsg(d, OtcMessage.EXECUTE, d.hash, new SendResult() { public void ok() {} public void err(String m) {} });
    }
}

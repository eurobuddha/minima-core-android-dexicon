// VENDORED VERBATIM from apks/atomix 0.1.82 app/src/main/java/com/eurobuddha/atomix/swap/SwapEngine.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.atomix.eth.EthHtlc;
import com.eurobuddha.atomix.eth.EthNet;
import com.eurobuddha.atomix.eth.EthRpc;
import com.eurobuddha.atomix.eth.EthSend;
import com.eurobuddha.atomix.eth.EthTx;
import com.eurobuddha.atomix.eth.EthWallet;
import com.eurobuddha.atomix.SwapLog;
import org.web3j.crypto.Credentials;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The end-to-end atomic-swap engine — a faithful native port of the bridge MiniDapp's service.js loop
 * plus its apiminima.js / apieth.js collect / expired / discovery routines.
 *
 * <p><b>Trustless model (verbatim upstream).</b> There is no server and no direct messaging. The party
 * who locks FIRST (the <i>initiator</i>) generates the secret and the hashlock and gets the LONGER
 * timelock; the counterparty (the <i>responder</i>, whose published order was taken) discovers that leg by
 * scanning the chain, validates it against their <b>own published order</b>, and locks the SECOND leg with
 * a SHORTER timelock. The initiator then claims the second leg, revealing the secret on-chain; the
 * responder reads the secret from that chain and claims the first leg. {@code contractId = sha256(hashlock)}
 * is deterministic, so nothing needs to be exchanged off-chain.
 *
 * <p><b>Idempotency / resume.</b> Every action is guarded by {@link SwapDb} (secrets / event log) exactly
 * like upstream, so the watcher can fire the same checks every cycle and a kill-and-restart simply
 * re-discovers in-flight swaps from the chains + DB. An in-memory {@link #inflight} set additionally stops
 * a duplicate post within overlapping cycles before the DB guard lands.
 *
 * <p><b>Timelocks (htlcvars.js).</b> first Minima leg = block+144; first ETH leg = now+7200s; second ETH
 * leg = now+1800s; second Minima leg = block+36. A responder refuses to lock the second leg unless the
 * first leg still has ≥ half its window left (72 Minima blocks / 3600 ETH secs).
 */
public final class SwapEngine {

    // timelock constants — verbatim from htlcvars.js (MINIMA_BLOCK_TIME 50, main 2h)
    public static final int TIMELOCK_BLOCKS              = MinimaHtlc.TIMELOCK_BLOCKS;       // 144
    public static final long TIMELOCK_SECS              = 60 * 60 * 2;                       // 7200
    public static final int  CP_BLOCKS_CHECK            = TIMELOCK_BLOCKS / 2;               // 72
    public static final long CP_SECS_CHECK              = TIMELOCK_SECS / 2;                 // 3600
    public static final int  CP_BLOCKS                  = (TIMELOCK_BLOCKS / 2) / 2;         // 36
    public static final long CP_SECS                    = (TIMELOCK_SECS / 2) / 2;           // 1800
    private static final int  NOTIFY_SCAN_DEPTH         = 256;                               // bounded notify scan
    private static final int  HTLC_SCAN_DEPTH           = 256;                               // bounded HTLC coin scan
    /** REFUND discovery only. The node's `coins depth:` is a walk-back over BLOCKS from the tip
     *  (TxPoWSearcher.searchCoins: `if(depth++>zDepth) break;`), so HTLC_SCAN_DEPTH=256 made a coin invisible
     *  ~3h33m after it was created — while the responder leg only becomes refundable 36 blocks in. Miss that
     *  window (app closed, phone off, a wedged retry) and the coin was stranded for good: the engine never saw
     *  it again, and there is no manual refund anywhere in the UI. Cost a real 35.014005 MINIMA lock.
     *
     *  1024 is the practical ceiling — MINIMA_CASCADE_START_DEPTH, past which the tree cascades and
     *  `tip.getParent()` returns null, so a larger number buys nothing without MegaMMR (see scanExpired). The
     *  hot path keeps 256: this depth is used ONLY by the DB-driven expired sweep, which fires per-hash and
     *  only for a swap already known to be past its timelock, so the reply stays small and it is rare. */
    static final int  REFUND_SCAN_DEPTH                 = 1024;   // package-private: asserted by RefundRetryTest
    // F1: an ETH withdraw/refund broadcast is only an ACK, not a mined receipt. We treat a swap as settled
    // ONLY when the contract's own withdrawn/refunded flag confirms it (read every cycle via getContract);
    // between broadcast and that confirmation we re-attempt no more often than this. Chosen > EthTx's 120 s
    // nonce-heal window so a genuinely dropped/replaced tx gets a healed nonce on retry instead of a second
    // tx wedged behind a stuck one. Without this a dropped refund/withdraw would strand funds forever.
    private static final long ETH_RETRY_SECS            = 150;
    private static final BigInteger MAX_UINT = BigInteger.valueOf(2).pow(256).subtract(BigInteger.ONE);

    public interface Notifier {
        void notify(String title, String body);   // OS notification for a meaningful transition
        void onSwapsChanged();                     // ask the UI to re-render swap cards
    }
    public interface StartCb {
        /** Persist the recovery identity before the first leg is submitted. False stops submission. */
        default boolean onPrepared(String hash) { return true; }
        void ok(String hash);
        void err(String msg);
    }
    public interface ConfirmCb { void done(boolean onChain); }
    public interface InspectCb { void report(java.util.List<String> lines); }

    private final NodeApi node;
    private final MinimaHtlc minima;
    private final SwapDb db;
    private final EthWallet wallet;
    private final Handler ui;
    private final Notifier notifier;
    private final ExecutorService io = Executors.newFixedThreadPool(2);

    private static final long APPROVE_TTL_MS = 5 * 60 * 1000;   // re-fire a stuck approve after this
    private static final long ETH_SCAN_CAP = 5000;             // max getLogs span (catch-up after downtime)

    private volatile EthRpc rpc;
    private volatile EthNet net;
    private volatile String myMinimaPk;                         // my persisted swap identity (one of 64)
    private volatile Set<String> myPubkeys = Collections.emptySet();  // all 64 default keys (owner/refund match)
    private volatile Order myOrder;                 // my published order — the responder-side match guard
    private volatile OtcDb otcDb;                   // OTC deal store — the OTC responder's agreed-terms gate

    private volatile long lastEthScanned = -1;      // ETH block bookmark (New-contract discovery)
    // STATIC, like CP_LOCKING below. MainActivity and SwapService each construct their OWN SwapEngine in
    // the SAME process, so an instance-scoped guard let both engines drive the same swap at once — two
    // refunds/claims of one coin, built as two DIFFERENT transactions, both signed. CP_LOCKING was made
    // process-wide for exactly this reason on the counter-leg path; the rest of the markers ("refundM:",
    // "claimM:", "cpEth:", "wdEth:") need the same treatment.
    private static final Set<String> inflight = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Long> approvePending = Collections.synchronizedMap(new HashMap<>());
    private final Set<String> incoming = Collections.synchronizedSet(new HashSet<>());   // hashlocks announced by a buyer's handshake
    private final Set<String> declined = Collections.synchronizedSet(new HashSet<>());   // handshake buys we've already notified as declined
    private final Set<String> lowEth   = Collections.synchronizedSet(new HashSet<>());   // swaps we've already told the user need more ETH for gas
    // F1: last broadcast time (unix secs) of an ETH terminal action, keyed "wdEth:"/"refundE:"+hash. Gates
    // re-broadcast to ≥ ETH_RETRY_SECS apart. In-memory only: a restart re-drives terminal state from the
    // on-chain withdrawn/refunded flags anyway, so losing these timestamps just allows an immediate retry.
    // STATIC for the same reason as `inflight`: with one map per engine the effective retry interval
    // HALVED whenever the foreground and background engines were both alive, so a persistently failing
    // claim re-signed twice as often. Each retry burns a one-time key leaf permanently.
    private static final Map<String, Long> ethAttempt = new java.util.concurrent.ConcurrentHashMap<>();
    // H2: throttle the heavy market-history HTLC scan to at most every 5 min, and pause it while a claim is
    // pending (see poll()), so the single node command thread isn't starved when a claim is racing a timelock.
    private static final long MARKET_MIN_INTERVAL_MS = 5 * 60 * 1000;
    // Review MINOR: static (like inflight/ethAttempt/CP_LOCKING) so the fg Activity engine and the bg Service
    // engine share ONE market-poll bookmark — a per-instance one let the heavy whole-address scan run from both.
    private static volatile long lastMarketPollMs = 0;

    public SwapEngine(NodeApi node, MinimaHtlc minima, SwapDb db, EthWallet wallet,
                      Handler ui, Notifier notifier) {
        this.node = node; this.minima = minima; this.db = db; this.wallet = wallet;
        this.ui = ui; this.notifier = notifier;
    }

    public void setNetwork(EthRpc rpc, EthNet net) {
        this.rpc = rpc; this.net = net;
        lastEthScanned = -1;
        approvePending.clear();
        EthTx.resetAll();                 // cold-start the nonce serializer against the new node's "pending"
    }
    public void setMyMinimaPk(String pk) { this.myMinimaPk = pk; }

    /** One alarm per process — both engines route through setMyPubkeys, and this must not renotify per poll.
     *  (Kept as a same-tick guard; the authoritative, continuously re-checked verdict lives in IdentityWatch.) */
    private static volatile boolean identityAlarmRaised = false;

    /** The node's full 64-key set, so refunds work for a coin locked under any default key.
     *
     *  IDENTITY GUARD: the swap identity (address + pubkey) is persisted ONCE and reused forever — but a node
     *  reset with a different seed silently orphans it. The app then keeps publishing a receiver key the node
     *  cannot sign for, routes lock-change to an address the wallet no longer owns, and every incoming leg /
     *  change output lands in coins the wallet cannot see or spend. That is a silent, systemic fund leak
     *  (2,623 MINIMA of change + 4 unclaimable counter-legs in the field). Detect it HERE — the one point
     *  where the persisted key and the node's actual key set meet — and alarm loudly. */
    public void setMyPubkeys(Set<String> keys) {
        // Normalise on ingestion — membership checks compare normKey forms, so a caller passing 0x-prefixed
        // or lower-case keys must not silently break ownership matching (or falsely trip the guard below).
        Set<String> norm = new java.util.HashSet<>();
        if (keys != null) for (String k : keys) { String n = MinimaHtlc.normKey(k); if (!n.isEmpty()) norm.add(n); }
        this.myPubkeys = norm.isEmpty() ? Collections.emptySet() : norm;
        if (myMinimaPk != null && !myMinimaPk.isEmpty() && !this.myPubkeys.isEmpty()
                && !this.myPubkeys.contains(MinimaHtlc.normKey(myMinimaPk)) && !identityAlarmRaised) {
            identityAlarmRaised = true;
            SwapLog.w("IDENTITY ORPHANED: node does not own persisted swap identity " + myMinimaPk
                    + " (" + this.myPubkeys.size() + " node keys checked). Node reset with a different seed?"
                    + " Incoming swap legs and lock-change route to this identity and CANNOT be signed.");
            // Hand the verdict to the single source of truth so the UI blocker, the FGS line and every
            // new-liability gate agree — and so it survives being re-checked on a cadence.
            com.eurobuddha.atomix.IdentityWatch.noteMinimaOrphaned(myMinimaPk, notifier);
        }
    }

    /** No NEW liabilities while the app's keys don't belong to the node. Settlement (claim/refund of swaps
     *  already in flight) deliberately keeps running — freezing that is how funds got stranded twice — but
     *  anything that commits fresh money must stop dead. */
    private boolean halted() { return com.eurobuddha.atomix.IdentityWatch.halted(); }
    public void setMyOrder(Order o) { this.myOrder = o; }
    public void setOtcDb(OtcDb o) { this.otcDb = o; }
    public String swapStatus(String hash) { SwapDb.Swap s = db.getSwap(hash); return s == null ? null : s.status; }

    // ── maker coin-readiness: keep the ask ladder backed by enough separately-spendable mxUSDT coins ──────────
    // Minima is UTXO-based: locking N concurrent counter-legs for a swept N-tranche ask ladder needs N coins each
    // ≥ a tranche. With one big coin, lock 1 spends it and its change is unconfirmed, so later locks starve (the
    // 750-sweep leg-1 non-fill). We clamp the advertised ladder to what's lockable NOW and split coins toward full
    // depth in the background. The split is a self-send (all coins stay mine) → benign if it ever races a lock.
    private volatile long lastSplitMs = 0;
    private static final long SPLIT_MIN_INTERVAL_MS = 120_000;   // ≥ ~2 blocks between splits (rate limit)

    // Bounded-BURST responder locking: up to CP_LOCK_BURST counter-leg locks in flight at once, each PINNED to a
    // DISTINCT coin (lockFromCoin) so the concurrent sends can never double-select (the losers would be rejected
    // as double-spends but still look "locked", and the taker could never discover them). A slot frees when its
    // lock confirms on-chain (or a watchdog fires — that one leg refunds). Serial (K=1) was correct but ~1
    // leg/cycle; this is ~CP_LOCK_BURST× faster with the same coin-collision safety.
    private static final int  CP_LOCK_BURST = 2;            // max concurrent responder locks (each on a distinct coin-set)
    static final int  MAX_LOCK_COINS = 50;          // cap UTXOs combined into ONE counter-leg lock (tx-size bound)
    private static final long CP_LOCK_TIMEOUT_SECS = 600;   // per-leg watchdog (its leg refunds if it never confirms); ≫ a normal ~2-block confirm
    private static final Map<String,String> cpInFlight  = Collections.synchronizedMap(new HashMap<>());  // hash → pinned coinid ("" = slot reserved, coin not yet picked)
    private static final Map<String,Long>   cpLockSince = Collections.synchronizedMap(new HashMap<>());  // hash → lock time (watchdog)
    // PROCESS-WIDE per-hash marker: MainActivity + SwapService run SEPARATE engines (same process). The mxUSDT leg
    // has no on-chain hash-uniqueness, so without this a fg↔bg handoff mid-lock could lock a SECOND coin for the
    // SAME hash → the taker claims both with one secret → the maker loses the second coin. Shared + released on
    // err/watchdog/confirm; keyed by hash so it blocks a same-hash re-lock WITHOUT blocking distinct-hash bursts.
    private static final Map<String,Long> CP_LOCKING = Collections.synchronizedMap(new HashMap<>());

    // All cpInFlight/CP_LOCKING mutations go through the shared CP_LOCKING monitor (single lock, one invariant:
    // cpInFlight.size() ≤ CP_LOCK_BURST) — never two monitors on the same state.
    private final java.util.Set<String> cpNoted = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /** One-time notify when the mxUSDT counter-leg can't be locked (insufficient / too-fragmented) — replaces the
     *  old silent return so an unfillable deal is visible to the LP instead of a mystery hang. */
    private void declineCpNote(String hash, String reason) {
        if (!cpNoted.add(hash)) return;
        ui.post(() -> notifier.notify("Can't lock your " + com.eurobuddha.atomix.TradingContext.active().coinLabel, reason));
    }

    private static long lastFragmentationNoteMs;
    private static synchronized boolean shouldNoteFragmentation() {
        long now = System.currentTimeMillis();
        if (lastFragmentationNoteMs != 0 && now - lastFragmentationNoteMs < 30 * 60_000L) return false;
        lastFragmentationNoteMs = now;
        return true;
    }

    int cpInFlightSizeForTest() { synchronized (CP_LOCKING) { return cpInFlight.size(); } }
    static synchronized void resetFragmentationNoteForTest() { lastFragmentationNoteMs = 0; }

    // The existing CP maps reserve coins; this identity additionally owns delayed callbacks.
    // All accesses use their existing CP_LOCKING monitor, shared by Activity and Service engines.
    private static final Map<String, CounterLeg> cpAttempts = new HashMap<>();
    private final class CounterLeg {
        final EthHtlc.Contract contract;
        final String hash;
        final com.eurobuddha.atomix.TradingContext market;
        final long generation;
        final EthNet network = net;
        final EthRpc connection = rpc;
        CounterLeg(EthHtlc.Contract contract, com.eurobuddha.atomix.TradingContext market, long generation) {
            this.contract = contract; hash = contract.hashlock; this.market = market; this.generation = generation;
        }
        boolean owned() { synchronized (CP_LOCKING) { return cpAttempts.get(hash) == this; } }
        boolean current() {
            synchronized (CP_LOCKING) {
                return owned() && CP_LOCKING.containsKey(hash) && cpInFlight.containsKey(hash)
                        && !io.isShutdown() && !halted() && network == net && connection == rpc
                        && generation == PublishGate.gen() && market == com.eurobuddha.atomix.TradingContext.active()
                        && market.tokenId.equalsIgnoreCase(minima.activeToken());
            }
        }
        boolean authorized() { return current() && terms(); }
        // Reuse the original acceptance boundaries immediately before creating a new liability.
        boolean terms() {
            if (contract.otc) {
                OtcDb.Deal deal = otcLpDeal(hash);
                return deal != null && otcVerifyBuy(contract, deal);
            }
            return myOrder != null && myOrder.market() == market && acceptTakerBuyMinima(contract);
        }
        void release() {
            synchronized (CP_LOCKING) {
                if (!owned()) return;
                releaseCpLeg(hash); inflight.remove("cpMin:" + hash);
            }
        }
    }

    private void releaseCpLeg(String hash) {
        synchronized (CP_LOCKING) { cpInFlight.remove(hash); cpLockSince.remove(hash); CP_LOCKING.remove(hash); cpAttempts.remove(hash); }
    }

    /** Fresh bid backing through the existing worker/main callback boundary, then fresh Minima backing. */
    public void publishFresh(com.goterl.lazysodium.LazySodium sodium,
                             com.eurobuddha.comms.CommsIdentity identity, Order order, long generation,
                             com.eurobuddha.comms.CommsTransport.SendCb cb) {
        final EthRpc connection=rpc;
        final EthNet network=net;
        final Credentials owner=wallet.creds();
        final String minimaKey=myMinimaPk;
        final com.eurobuddha.atomix.TradingContext market=order==null?null:order.market;
        final java.util.concurrent.atomic.AtomicReference<com.eurobuddha.minimaapi.ethereum.EthereumTransactions.FundingSnapshot> funding=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.function.BooleanSupplier current=()->order!=null&&market!=null&&market==com.eurobuddha.atomix.TradingContext.active()
                &&generation==PublishGate.gen()&&!io.isShutdown()&&!halted()&&connection==rpc&&network==net
                &&owner!=null&&owner==wallet.creds()&&owner.getAddress().equalsIgnoreCase(order.ethAddress)
                &&java.util.Objects.equals(minimaKey,myMinimaPk)&&java.util.Objects.equals(minimaKey,order.minimaPublicKey)
                &&(funding.get()==null||funding.get().unchanged());
        if(!current.getAsBoolean()){cb.onFailed(SwapOrderBook.MARKET_ERROR);return;}
        boolean bids=false;
        for(String symbol:order.pairs.keySet())if(!order.effectiveBids(symbol).isEmpty())bids=true;
        if(!bids) {
            order.usdtAvail=0;
            SwapOrderBook.publishFresh(node,sodium,identity,order,generation,current,cb);return;
        }
        try { io.execute(()->{
            if(!current.getAsBoolean()){ui.post(()->cb.onFailed(SwapOrderBook.MARKET_ERROR));return;}
            final BigDecimal available;
            try {
                EthNet.Token token=network==null?null:network.token("USDT");
                if(token==null)throw new IllegalStateException();
                com.eurobuddha.minimaapi.ethereum.EthereumTransactions.FundingSnapshot snapshot=
                        com.eurobuddha.minimaapi.ethereum.EthereumTransactions.shared().fundingSnapshot(network.chainId,owner.getAddress());
                funding.set(snapshot);
                available=new BigDecimal(EthWallet.erc20AvailableRaw(connection,network,token.address,owner.getAddress(),snapshot),token.decimals);
            } catch(Exception unavailable){ui.post(()->cb.onFailed(SwapOrderBook.BALANCE_ERROR));return;}
            ui.post(()->{
                if(!current.getAsBoolean()){cb.onFailed(SwapOrderBook.MARKET_ERROR);return;}
                order.usdtAvail=available.doubleValue();order.clampBidsToBalance(available);
                SwapOrderBook.publishFresh(node,sodium,identity,order,generation,current,cb);
            });
        }); }catch(java.util.concurrent.RejectedExecutionException stopped){cb.onFailed(SwapOrderBook.MARKET_ERROR);}
    }

    /** At publish: trim the ask ladder to the tranches I can actually lock right now, run {@code afterPublish},
     *  then (if short + allowed) notify + split toward full depth. */
    public void ensureLadderCoins(Order o, boolean allowSplit, Runnable afterPublish) {
        checkLadderCoins(o, allowSplit, true, afterPublish);
    }

    /** Between publishes (poll tick): replenish coins consumed by fills so the already-advertised ladder stays
     *  backable — no clamp, no republish. */
    public void maintainLadderCoins(boolean allowSplit) {
        Order o = myOrder;
        if (o != null) checkLadderCoins(o, allowSplit, false, null);
    }

    private void checkLadderCoins(Order o, boolean allowSplit, boolean clamp, Runnable afterPublish) {
        // Single mxUSDT/USDT pair today: inspect the first enabled ask ladder. A second mxUSDT-quoted pair would
        // draw asks from the SAME coin pool, so multi-pair support would need pooled accounting across pairs here.
        String sym = null;
        for (java.util.Map.Entry<String, Order.Pair> e : o.pairs.entrySet()) {
            if (e.getValue().enable && !e.getValue().asks.isEmpty()) { sym = e.getKey(); break; }
        }
        final int N = sym == null ? 0 : o.effectiveAsks(sym).size();
        if (N < 2 || !clamp) { if (afterPublish != null) afterPublish.run(); return; }   // single/no ladder, or no clamp → as-is
        final String fsym = sym;
        minima.tokenBalance(balance -> {
            double totalFree = safeDouble(balance.sendable);
            // The responder locks a tranche by COMBINING coins (lockMinimaCounterLeg), like a wallet send — so
            // advertise the largest PREFIX of ask tranches whose CUMULATIVE amount the total free balance covers.
            // No "single coin ≥ tranche" gate, and no coin pre-splitting.
            double cum = 0; int backable = 0;
            for (Order.Level l : o.effectiveAsks(fsym)) { cum += l.amount; if (cum <= totalFree + 1e-9) backable++; else break; }
            o.trimAsks(fsym, backable);
            if (afterPublish != null) afterPublish.run();
        }, e -> {
            // Coin read failed → fail SAFE, not open: advertise only the best (1st) tranche rather than the full
            // unbacked ladder, so a node hiccup can't silently re-enable an over-advertise.
            o.trimAsks(fsym, 1);
            if (afterPublish != null) afterPublish.run();
        });
    }

    // (removed maybeSplitLadderCoins — obsolete with multi-UTXO counter-leg locking; the responder combines whatever
    //  coins exist, so we no longer pre-split coins into tranche-sized chunks.)

    private static double safeDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    /** True while a coin-split self-send is in flight — a SELL sweep should wait, since both pick mxUSDT coins
     *  unpinned and could select the same one (fund-safe: the loser is dropped, but the sweep could abort). */
    public boolean isSplitting() { return inflight.contains("split"); }

    /** Coin selection must be excluded in BOTH directions, across Activity and Service engines. */
    public static boolean isConsolidating() { return inflight.contains("consolidate"); }
    public static boolean beginConsolidate() {
        synchronized (CP_LOCKING) {
            if (!CP_LOCKING.isEmpty() || !cpInFlight.isEmpty() || inflight.contains("sell")
                    || inflight.contains("split") || com.eurobuddha.atomix.MainActivity.SWEEP_ACTIVE) return false;
            return inflight.add("consolidate");
        }
    }
    public static void endConsolidate() { synchronized (CP_LOCKING) { inflight.remove("consolidate"); } }

    /** A taker told us (via the sealed handshake) the hashlock of a USDT lock addressed to us. We discover it
     *  by deterministic contractId via getContract (free-RPC-safe) instead of eth_getLogs, then respond. */
    public void addIncomingHashlock(String hash) {
        if (hash != null && !hash.isEmpty()) { incoming.add(MinimaHtlc.normKey(hash)); SwapLog.d("RX buy handshake hash=" + hash); }
    }
    public SwapDb db() { return db; }
    public void shutdown() {
        for (Runnable task : io.shutdownNow()) if (task instanceof IoTask) ((IoTask) task).cancelQueued();
    }

    private boolean ready() { return rpc != null && net != null && wallet.ready() && myMinimaPk != null && minima.ready(); }
    private String myEth() { return wallet.address(); }

    // ============================================================ initiate (user-driven)

    /** I give mxUSDT, want an ERC20 token. I lock mxUSDT FIRST (block+144) and generate the secret. */
    public void startMinimaToErc20(Order maker, String sellMinima, String tokenSymbol, String buyTokenAmount, StartCb cb) {
        startMinimaToErc20(maker, sellMinima, tokenSymbol, buyTokenAmount, false, cb);
    }

    /** As above, with the OTC bit: {@code otc=true} sets HTLC state[7]=TRUE so the ladder auto-responder skips it
     *  and only the negotiated OTC responder locks the counter-leg. */
    public void startMinimaToErc20(Order maker, String sellMinima, String tokenSymbol, String buyTokenAmount, boolean otc, StartCb result) {
        startMinimaToErc20(maker, sellMinima, tokenSymbol, buyTokenAmount, otc, com.eurobuddha.atomix.TradingContext.active(), result);
    }

    void startMinimaToErc20(Order maker, String sellMinima, String tokenSymbol, String buyTokenAmount, boolean otc, com.eurobuddha.atomix.TradingContext market, StartCb result) {
        if (!ready()) { result.err("Not ready"); return; }
        if (market == null || market != com.eurobuddha.atomix.TradingContext.active() || !market.tokenId.equalsIgnoreCase(minima.activeToken())) { result.err(OtcController.MARKET_ERROR); return; }
        if (!otc && (maker == null || maker.market != market)) { result.err(SwapOrderBook.MARKET_ERROR); return; }
        // M3: a responder burst-lock (or a coin-split) draws mxUSDT from the SAME coin pool that this
        // user-initiated node-auto-select lock does; both could pick the same coin → one tx is rejected as a
        // double-spend (fund-safe) but can leave a phantom LOCKED row. Decline briefly while a responder lock or
        // split is in flight so the user just retries a moment later instead of racing it.
        final EthNet.Token token = net.token(tokenSymbol);
        if (token == null) { result.err("Unknown token " + tokenSymbol); return; }
        synchronized (CP_LOCKING) {
            if (!cpInFlight.isEmpty() || isSplitting() || isConsolidating() || !inflight.add("sell")) {
                result.err("Wallet busy — wait for the current lock or consolidation"); return;
            }
        }
        final StartCb cb = new StartCb() {
            public boolean onPrepared(String hash) { return result.onPrepared(hash); }
            public void ok(String hash) { synchronized (CP_LOCKING) { inflight.remove("sell"); } result.ok(hash); }
            public void err(String error) { synchronized (CP_LOCKING) { inflight.remove("sell"); } result.err(error); }
        };
        final String reqToken = "ETH:" + token.address;
        minima.generateSecret(new MinimaHtlc.SecretCb() {
            @Override public void ok(String secret, String hash) {
                if (market != com.eurobuddha.atomix.TradingContext.active()) { cb.err(OtcController.MARKET_ERROR); return; }
                minima.currentBlock(new MinimaHtlc.BlockCb() {
                    @Override public void ok(int block) {
                        if (market != com.eurobuddha.atomix.TradingContext.active()) { cb.err(OtcController.MARKET_ERROR); return; }
                        final int timelock = block + TIMELOCK_BLOCKS;
                        // M2: record the secret + swap row BEFORE the lock broadcast (parity with
                        // startErc20ToMinima). If lock() mines but its response is lost, the secret + row survive
                        // so the swap can still be claimed; the chain-scan refund (scanMyHtlcByKey →
                        // checkExpiredMinima) reclaims the coin at the timelock regardless of the DB. A row whose
                        // broadcast never landed is a harmless phantom (no coin at the HTLC address for this hash
                        // → no responder engages, nothing to refund).
                        SwapDb.Swap s = baseSwap(hash, "INITIATOR", "MINIMA_TO_ERC20",
                                market.coinLabel, sellMinima, tokenSymbol, buyTokenAmount, maker.ethAddress);
                        s.myTimelock = timelock; s.myLegIsMinima = true; s.status = SwapDb.ST_STARTED;
                        try {
                            db.prepareInitiator(s, secret, buyTokenAmount, reqToken);
                            if (!cb.onPrepared(hash)) {
                                cb.err("The swap lock was not submitted because recovery data could not be saved."); return;
                            }
                        } catch (RuntimeException storageFailure) {
                            cb.err("The swap lock was not submitted because recovery data could not be saved."); return;
                        }
                        notifier.onSwapsChanged();
                        minima.lock(sellMinima, buyTokenAmount, token.address, maker.minimaPublicKey,
                                myEth(), hash, timelock, otc ? "TRUE" : "FALSE", new MinimaHtlc.PostCb() {
                            @Override public void ok(String txpowid) {
                                db.logEvent(hash, SwapDb.EV_STARTED, "minima", sellMinima, txpowid);
                                cb.ok(hash);
                            }
                            @Override public void err(String m) { cb.err(m); }
                        });
                    }
                    @Override public void err(String m) { cb.err(m); }
                });
            }
            @Override public void err(String m) { cb.err(m); }
        });
    }

    /** I give an ERC20 token, want mxUSDT. I lock the ERC20 FIRST (now+7200s) and generate the secret. */
    public void startErc20ToMinima(Order maker, String tokenSymbol, String sellTokenAmount, String buyMinima, StartCb cb) {
        startErc20ToMinima(maker, tokenSymbol, sellTokenAmount, buyMinima, false, cb);
    }

    /** Instigator side: on an AGREED OTC deal, start the HTLC swap against the LP with otc=TRUE. Direction is
     *  derived from the LP's side; amounts from the negotiated terms (USDT = amount × price, at 6-dp). Returns the
     *  hashlock via cb — the caller then sends an EXECUTE message so the LP responds against this exact hash. */
    public void executeOtcDeal(OtcDb.Deal deal, StartCb cb) {
        if (!OtcController.validDeal(deal)) { cb.err("enter amount + price"); return; }
        final com.eurobuddha.atomix.TradingContext market = deal == null ? null : deal.market();
        if (market == null || market != com.eurobuddha.atomix.TradingContext.active()
                || !market.tokenId.equalsIgnoreCase(minima.activeToken())) {
            cb.err(OtcController.MARKET_ERROR); return;
        }
        Order lp = new Order();
        lp.minimaPublicKey = deal.peerMinimaPk;
        lp.ethAddress = deal.peerEthAddr;
        lp.commsPublicId = deal.peerCommsId;
        String usdt = dec(deal.amount).multiply(dec(deal.price)).setScale(6, java.math.RoundingMode.DOWN).toPlainString();
        if (OtcOffer.LP_SELLS_MINIMA.equals(deal.side))
            startErc20ToMinima(lp, "USDT", usdt, deal.amount, true, market, cb);   // LP sells mxUSDT → I BUY: lock USDT
        else
            startMinimaToErc20(lp, deal.amount, "USDT", usdt, true, market, cb);    // LP buys mxUSDT → I SELL: lock mxUSDT
    }

    /** As above, with the OTC bit: {@code otc=true} sets the ETH contract's otc flag so the ladder auto-responder
     *  skips it and only the negotiated OTC responder locks the counter-leg. */
    public void startErc20ToMinima(Order maker, String tokenSymbol, String sellTokenAmount, String buyMinima, boolean otc, StartCb cb) {
        startErc20ToMinima(maker, tokenSymbol, sellTokenAmount, buyMinima, otc, com.eurobuddha.atomix.TradingContext.active(), cb);
    }

    void startErc20ToMinima(Order maker, String tokenSymbol, String sellTokenAmount, String buyMinima, boolean otc, com.eurobuddha.atomix.TradingContext market, StartCb cb) {
        if (!ready()) { cb.err("Not ready"); return; }
        if (market == null || market != com.eurobuddha.atomix.TradingContext.active() || !market.tokenId.equalsIgnoreCase(minima.activeToken())) { cb.err(OtcController.MARKET_ERROR); return; }
        if (!otc && (maker == null || maker.market != market)) { cb.err(SwapOrderBook.MARKET_ERROR); return; }
        final EthNet.Token token = net.token(tokenSymbol);
        if (token == null) { cb.err("Unknown token " + tokenSymbol); return; }
        // M1: request the mxUSDT at the 6dp trade grain. The responder locks grain(amount) (6dp), so a >6dp
        // request would be locked SHORT and the initiator's strict want>got check (amountTokenOk) would refuse
        // to claim → the whole deal mutual-refunds. Quantizing the request here makes it exactly fillable.
        final String reqMinima = MinimaHtlc.grain(buyMinima);
        minima.generateSecret(new MinimaHtlc.SecretCb() {
            @Override public void ok(String secret, String hash) {
                if (market != com.eurobuddha.atomix.TradingContext.active()) { cb.err(OtcController.MARKET_ERROR); return; }
                submitIo(() -> {
                    try {
                        if (market != com.eurobuddha.atomix.TradingContext.active()) { ui.post(() -> cb.err(OtcController.MARKET_ERROR)); return; }
                        Credentials creds = wallet.creds();
                        EthHtlc eth = new EthHtlc(rpc, creds, net);
                        BigInteger sellRaw = parseUnits(sellTokenAmount, token.decimals);
                        BigInteger reqRaw = parseUnits(reqMinima, 18);
                        ensureAllowanceBlocking(eth, token.address, sellRaw);
                        // F2: base the timelock on CHAIN time (what the vault enforces), not the device clock.
                        final long timelock = ethChainNow() + TIMELOCK_SECS;
                        if (market != com.eurobuddha.atomix.TradingContext.active()) { ui.post(() -> cb.err(OtcController.MARKET_ERROR)); return; }
                        // CR-1: record the secret + swap row SYNCHRONOUSLY (on this io thread) BEFORE the lock
                        // broadcast. These were posted to the UI thread, but a busy or destroyed main thread can
                        // DROP the post while newContract has already mined — and there is NO contractsAsSender
                        // discovery scan, so a mined contract with no DB row is USDT stranded in the vault with no
                        // in-app recovery. Written before the broadcast, checkEthContractFor finds the row in
                        // db.allSwaps() and refunds at timelock even if the broadcast response is lost. A row whose
                        // broadcast never landed is a harmless phantom (getContract → null → no-op). Only the UI
                        // callback goes to ui.post.
                        SwapDb.Swap s = baseSwap(hash, "INITIATOR", "ERC20_TO_MINIMA",
                                tokenSymbol, sellTokenAmount, market.coinLabel, reqMinima, maker.minimaPublicKey);
                        s.myTimelock = timelock; s.myLegIsMinima = false; s.status = SwapDb.ST_STARTED;
                        s.contractId = EthHtlc.contractId(hash);
                        try {
                            db.prepareInitiator(s, secret, reqMinima, "minima");
                            if (!cb.onPrepared(hash)) {
                                ui.post(() -> cb.err("The swap lock was not submitted because recovery data could not be saved.")); return;
                            }
                        } catch (RuntimeException storageFailure) {
                            ui.post(() -> cb.err("The swap lock was not submitted because recovery data could not be saved.")); return;
                        }
                        ui.post(notifier::onSwapsChanged);
                        String txhash = eth.newContract(myMinimaPk, maker.ethAddress, hash,
                                BigInteger.valueOf(timelock), token.address, sellRaw, reqRaw, otc);
                        db.logEvent(hash, SwapDb.EV_STARTED, "ETH:" + token.address, sellTokenAmount, txhash);
                        ui.post(() -> cb.ok(hash));
                    } catch (Exception e) {
                        ui.post(() -> cb.err(e.getMessage()));
                    }
                }, () -> ui.post(() -> cb.err("Engine stopped — try again")));   // Review MINOR: don't drop cb on shutdown
            }
            @Override public void err(String m) { cb.err(m); }
        });
    }

    /**
     * Is MY first-leg lock for {@code hash} visible ON-CHAIN yet? Read-only. buy (myLegIsMinima=false) → the
     * ETH HTLC contract reads back with a non-zero amount; sell → my Minima HTLC coin (state[5]==hash) exists.
     * Used to gate a market sweep so the next (worse-priced) leg starts ONLY once the current best leg is
     * actually locked in — and so a dropped leg halts the sweep instead of executing the worse one.
     */
    public void confirmMyLock(String hash, boolean myLegIsMinima, ConfirmCb cb) {
        if (myLegIsMinima) {
            // My own lock, found by its hashlock at coinage:1 — one confirmation already spends the inputs, so
            // the next sweep leg can't re-select them. Reliable state-filter scan (not relevant:true).
            minima.scanHtlcByHash(hash, 1, HTLC_SCAN_DEPTH, arr -> {
                boolean found = false;
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject c = arr.optJSONObject(i);
                    if (c != null && isMyOwnedKey(MinimaHtlc.stateAt(c, 0)) && sameHash(MinimaHtlc.stateAt(c, 5), hash)) { found = true; break; }
                }
                final boolean f = found;
                ui.post(() -> cb.done(f));
            }, e -> ui.post(() -> cb.done(false)));
        } else {
            if (io.isShutdown()) return;   // engine torn down (Activity destroyed) — the sweep is already abandoned
            try {
                io.execute(() -> {
                    boolean ok = false;
                    try {
                        EthHtlc eth = new EthHtlc(rpc, wallet.creds(), net);
                        EthHtlc.Contract c = eth.getContract(EthHtlc.contractId(hash));
                        ok = c != null && c.amount != null && c.amount.signum() > 0;
                    } catch (Exception ignore) {}
                    final boolean f = ok;
                    ui.post(() -> cb.done(f));
                });
            } catch (java.util.concurrent.RejectedExecutionException ignore) { /* shut down mid-call — drop */ }
        }
    }

    private static boolean sameHash(String a, String b) {
        if (a == null || b == null) return false;
        a = a.trim(); b = b.trim();
        if (a.regionMatches(true, 0, "0x", 0, 2)) a = a.substring(2);
        if (b.regionMatches(true, 0, "0x", 0, 2)) b = b.substring(2);
        return !a.isEmpty() && a.equalsIgnoreCase(b);
    }

    // ============================================================ inspect (live diagnostic for one swap)

    /** Probe both legs on-chain for one swap and report a plain-language status (why it's stuck / where it is). */
    public void inspect(String hash, InspectCb cb) {
        if (!ready()) { ui.post(() -> cb.report(java.util.Collections.singletonList("Wallet/node not ready yet — open the app and wait a moment."))); return; }
        final SwapDb.Swap s = db.getSwap(hash);
        if (s == null) { ui.post(() -> cb.report(java.util.Collections.singletonList("No record of this swap."))); return; }
        // Review MINOR: if the io pool is shut down, report an error rather than silently dropping the task
        // and leaving the "inspecting…" UI hung forever.
        final Runnable onStopped = () -> ui.post(() -> cb.report(java.util.Collections.singletonList("Engine stopped — reopen the app to inspect.")));
        // Read the Minima side first (async node.cmd), then the ETH side (blocking, on io), then report.
        minima.currentBlock(new MinimaHtlc.BlockCb() {
            @Override public void ok(int block) {
                // DEEP: the whole point of inspecting a stuck swap is that it is OLD — a shallow walk-back
                // would report a fully-locked coin as "not found" (the exact lie that hid the 0.1.16 stall).
                minima.scanHtlcByHashDeep(hash, 2, REFUND_SCAN_DEPTH, coins -> {
                    JSONObject myMinimaCoin = null, counterMinimaCoin = null;
                    for (int i = 0; i < coins.length(); i++) {
                        JSONObject c = coins.optJSONObject(i);
                        if (c == null || !MinimaHtlc.normKey(MinimaHtlc.stateAt(c, 5)).equals(MinimaHtlc.normKey(hash))) continue;
                        if (isMyOwnedKey(MinimaHtlc.stateAt(c, 0))) myMinimaCoin = c;        // a coin I locked
                        if (isMyPublishKey(MinimaHtlc.stateAt(c, 4))) counterMinimaCoin = c; // a coin locked to me
                    }
                    final JSONObject myMin = myMinimaCoin, cpMin = counterMinimaCoin;
                    submitIo(() -> reportInspection(s, hash, block, myMin, cpMin, null, cb), onStopped);
                }, err -> submitIo(() -> reportInspection(s, hash, block, null, null, err, cb), onStopped));
            }
            @Override public void err(String m) { submitIo(() -> reportInspection(s, hash, -1, null, null, m, cb), onStopped); }
        });
    }

    /** [io] compose the inspection report from the Minima coins (already scanned) + a live ETH getContract read. */
    private void reportInspection(SwapDb.Swap s, String hash, int block, JSONObject myMin, JSONObject cpMin, String scanError, InspectCb cb) {
        java.util.List<String> L = new java.util.ArrayList<>();
        try {
            boolean sell = s.myLegIsMinima;
            boolean secretKnown = db.getSecret(hash) != null;
            L.add((sell ? "Sell " : "Buy ") + s.sellAmount + " " + s.sellToken + " → " + s.buyAmount + " " + s.buyToken
                    + "  ·  " + s.status.toLowerCase());
            L.add("Hashlock: " + hash);
            L.add("Minima lookup: " + (scanError == null ? "completed, depth " + REFUND_SCAN_DEPTH + ", minimum coin age 2"
                    : "FAILED — " + scanError + ". Leg presence is unknown; this is not evidence of a missing or spent coin."));
            if (block > 0) L.add("Node block: " + block + " · recorded refund block: " + (s.myLegIsMinima ? s.myTimelock : "not your Minima leg"));
            EthHtlc eth = new EthHtlc(rpc, wallet.creds(), net);

            // ---- my leg ----
            if (s.myLegIsMinima) {
                String myLeg = "• Your " + s.sellAmount + " " + s.sellToken + ": ";
                if (myMin != null) {
                    int tl = parseInt(MinimaHtlc.stateAt(myMin, 3));
                    L.add(myLeg + "LOCKED — refundable at block " + tl
                            + (block > 0 ? " (~" + Math.max(0, (tl - block)) * 50 / 60 + " min)" : ""));
                } else if (scanError != null) {
                    L.add(myLeg + "UNKNOWN — node lookup failed");
                } else if (SwapDb.ST_REFUNDED.equals(s.status)) {
                    L.add(myLeg + "refunded");
                } else if (SwapDb.ST_COMPLETE.equals(s.status)) {
                    L.add(myLeg + "claimed by the counterparty (complete)");
                } else {
                    // NEVER render an empty bounded scan as "spent". The lookup walks back a fixed number of
                    // blocks from the tip, so a lock that is simply OLD returns nothing while sitting perfectly
                    // unspent on chain — observed live 2026-10-01 on a 1,304-block-old lock the owner was told
                    // was "spent/claimed". Say which of the two it is, and what happens next.
                    boolean recorded = db.rememberedLockCoin(hash) != null;
                    L.add(myLeg + "not returned by the " + REFUND_SCAN_DEPTH + "-block lookup. This does NOT mean "
                            + "it was spent — the lookup only reaches " + REFUND_SCAN_DEPTH + " blocks back, so a "
                            + "lock older than that returns nothing whether or not it is still there."
                            + (recorded
                                ? " The coin was recorded when it was in range, so the refund is built from that record and does not need the scan."
                                : " No coin was recorded for this swap, so it needs manual recovery — the coin id can be read from an archive node."));
                }
            } else {
                boolean stillLocked = eth.canCollect(s.contractId);
                L.add("• Your " + s.sellAmount + " " + s.sellToken + ": " + (stillLocked ? "LOCKED on Ethereum" : "claimed or refunded"));
            }

            // ---- counterparty leg ----
            // Keyed on WHOSE leg sits on which chain (myLegIsMinima), NOT on s.direction: direction names the
            // INITIATOR's flow, so for a RESPONDER row the old `sell` branch read my OWN ETH lock and reported
            // it as the counterparty's leg — "withdrawn (complete)" on a swap that was stuck. That mislabel is
            // what made the 4 frozen CLAIMING swaps look settled while 2036 MINIMA sat unclaimed.
            if (s.myLegIsMinima) {
                // counter = ERC20 to me — read by deterministic contractId (no eth_getLogs)
                EthHtlc.Contract gc = eth.getContract(EthHtlc.contractId(hash));
                if (gc == null) {
                    L.add("• Counterparty " + s.buyToken + " leg: NOT FOUND yet — the maker hasn't locked it.");
                } else {
                    boolean open = !gc.withdrawn && !gc.refunded;
                    boolean claimable = open && secretKnown;
                    L.add("• Counterparty " + s.buyToken + " leg: FOUND " + EthWallet.format(gc.amount, decimalsOf(gc.tokenContract), 6)
                            + " " + s.buyToken + " — " + ethClaimStatus(gc, secretKnown));
                    if (claimable) L.add("The settlement poll can attempt collection, subject to amount/token validation and gas.");
                    else if (open) L.add("Waiting for the counterparty to claim the Minima leg and reveal the secret. Ethereum funds cannot be collected without it.");
                    else if (gc.refunded) L.add(myMin != null
                            ? "The counterparty refunded. Your visible Minima lock is refundable after block " + s.myTimelock + "; settlement will attempt recovery."
                            : "The counterparty refunded. No Minima lock was found to refund; the recorded submission needs on-chain verification.");
                }
            } else {
                // counter = a Minima-chain coin to me — real on-chain check of the counterparty's lock
                if (cpMin != null) {
                    L.add("• Counterparty " + s.buyToken + " leg: FOUND " + MinimaHtlc.coinAmount(cpMin) + " " + s.buyToken + " — "
                            + (secretKnown ? "secret known; settlement must validate and confirm the claim" : "waiting for the secret"));
                } else if (scanError != null) {
                    L.add("• Counterparty " + s.buyToken + " leg: UNKNOWN — node lookup failed");
                } else {
                    L.add("• Counterparty " + s.buyToken + " leg: no matching unspent coin returned — may be unposted, younger than 2 confirmations, spent/refunded, or outside available history.");
                }
            }

            L.add("• Secret: " + (secretKnown ? "known locally; collection also requires a valid matching leg" : "not revealed yet"));
            int recorded = 0;
            for (SwapDb.Event e : db.getEvents(hash)) {
                if (e.note != null && MinimaHtlc.isHex(e.note)) { L.add("Recorded " + e.event + " transaction: " + e.note); recorded++; }
                String n = e.note == null ? "" : e.note.toLowerCase();
                if (n.contains("mismatch") || n.contains("invalid") || n.contains("incorrect")
                        || n.contains("too close") || n.contains("fail")) L.add("⚠ " + e.note);
            }
            L.add(recordedTxnSummary(recorded));
            if (!SwapDb.ST_COMPLETE.equals(s.status) && !SwapDb.ST_REFUNDED.equals(s.status))
                L.add("(swaps take a few minutes — ~90s polls + 2 confirmations + on-phone PoW per step)");
        } catch (Exception e) {
            L.add("Check failed (RPC/node): " + e.getMessage());
        }
        final java.util.List<String> out = L;
        ui.post(() -> cb.report(out));
    }

    /** A missing local acknowledgement cannot distinguish an unposted write from a lost reply.
     * The swap and secret are saved before broadcast; only PostCb.ok records the transaction id. */
    static String recordedTxnSummary(int recordedCount) {
        if (recordedCount > 0) return "Recorded transactions: " + recordedCount + " (listed above).";
        return "Recorded transactions: NONE. No transaction id is recorded locally. A broadcast may have completed despite a lost reply. Check the on-chain state before retrying or deciding whether funds need recovery.";
    }

    /** Body of the "Add ETH for gas" notification. RULE 1: the wallet is named IN FULL, because this is the
     *  one surface the user reads while the swap is blocked and a truncated address cannot be pasted into a
     *  wallet. The old wording ("top up this wallet's ETH") named nothing at all — proven useless live on
     *  2026-09-20, when a maker sat on 0 ETH through five blocked swaps and the prompt never said where to send.
     *  A null address (wallet not derived yet) falls back to pointing at the Wallet tab rather than printing
     *  "null" — the address is still exactly one tap away there, never abbreviated. */
    static String gasShortfallMessage(String shortEth, String ethAddress) {
        return "This swap needs about " + shortEth + " more ETH for gas. Send ETH on Ethereum to "
                + (ethAddress == null || ethAddress.isEmpty()
                        ? "this app's Ethereum wallet — open the Wallet tab to copy the address"
                        : ethAddress);
    }

    static String ethClaimStatus(EthHtlc.Contract contract, boolean secretKnown) {
        if (contract.withdrawn) return "withdrawn (complete)";
        if (contract.refunded) return "refunded";
        return secretKnown ? "locked; secret available for collection" : "locked; waiting for the secret";
    }

    // ============================================================ watcher poll

    /** One watcher cycle: drive both chains. Safe to call repeatedly (every action is idempotency-guarded). */
    public void poll() {
        if (!ready()) return;
        minima.currentBlock(new MinimaHtlc.BlockCb() {
            @Override public void ok(int block) {
                runMinimaChecks(block);
                submitIo(() -> runEthChecks(block));
                // H2: the market-history collector scans the WHOLE shared HTLC address (heavy) on the node's
                // SINGLE command thread. Run it at most every MARKET_MIN_INTERVAL_MS, and SKIP it entirely while
                // a claim is pending — so a time-critical claim (racing a counter-leg timelock) is never starved
                // of the node thread by a background price-feed scan.
                long now = System.currentTimeMillis();
                if (now - lastMarketPollMs >= MARKET_MIN_INTERVAL_MS && pendingClaimMinimaHashes().isEmpty()) {
                    lastMarketPollMs = now;
                    MarketCollector.poll(minima, db, block);   // accrue network-wide trade history
                }
            }
            @Override public void err(String m) { /* node busy; next cycle */ }
        });
    }

    // ---- Minima side (node.cmd; main thread) ----

    void runMinimaChecks(final int block) {   // (package-private for tests)
        confirmPendingMinima();
        // Harvest the revealed secret for each leg I locked that's still waiting — one hashlock-FILTERED query
        // per pending swap, so we never pull the whole (global, unbounded) notify address.
        for (String h : pendingSecretHashes()) {
            minima.scanNotifySecret(h, NOTIFY_SCAN_DEPTH, coins -> harvestNotifySecrets(coins), e -> {});
        }
        // CLAIM discovery — find each counter mxUSDT leg I'm owed BY ITS HASHLOCK (reliable), not via
        // relevant:true (which can miss a coin I only RECEIVE — the second-leg-of-a-sweep bug). checkCanSwapCoin's
        // own guards (secret-known, haveCollect, inflight, amountTokenOk) are unchanged.
        // DEEP, like the refund sweep — the secret can be revealed at any time (the counterparty claims my ETH
        // leg on their own schedule), so by the time I learn it the counter-coin may be older than the shallow
        // walk-back. The shallow scan then returns nothing forever and the swap freezes in CLAIMING with the
        // funds sitting claimable on-chain — the claim-side twin of the 0.1.15 stranded-refund bug.
        // Throttled HARD, unlike the shallow scan it replaces: the deep scan is a heavy per-hash node query
        // (coinnotify + a 1024-block walk), this loop fires on EVERY ~30s poll from BOTH engines, and the node
        // runs commands on ONE thread. Unthrottled (4 pending claims = 16 heavy commands/poll) it built a
        // backlog that outlived every callback timeout — nothing settled and the responses drained as a
        // hundreds-deep "Invalid ResponseID" flood. So: ONE scan per cycle (the break), one scan per hash per
        // ETH_RETRY_SECS window (the gate, static → shared by both engines), round-robin across hashes.
        for (String h : pendingClaimMinimaHashes()) {
            if (!tryEthAttempt("claimScan:" + h)) continue;   // MA-20: atomic due-check-and-mark
            final String hh = h;
            minima.scanHtlcByHashDeep(h, 2, REFUND_SCAN_DEPTH, coins -> {
                SwapLog.d("claimScan " + hh + " -> " + coins.length() + " coin(s)");
                for (int i = 0; i < coins.length(); i++) {
                    JSONObject coin = coins.optJSONObject(i);
                    if (coin == null) continue;
                    if (isMyPublishKey(MinimaHtlc.stateAt(coin, 4)) && sameHash(MinimaHtlc.stateAt(coin, 5), hh))
                        checkCanSwapCoin(coin, block);      // a mxUSDT leg locked to me — claim it (I hold the secret)
                }
            }, e -> SwapLog.w("claimScan " + hh + " ERR: " + e));
            break;   // one deep scan per cycle — the next due hash goes next poll
        }
        // RESPONDER (incoming sell-take → lock ETH counter-leg) + REFUND (my expired coins) discovery — via a
        // state-filter scan bounded to MY key (owner state[0] or receiver state[4]): reliable like the per-hash
        // scans AND bounded like the old relevant:true, so it adds no unbounded global-address reply. coinage:2
        // STAYS: the maker commits real USDT against a taker's mxUSDT lock, so it must be ≥2-conf (reorg guard).
        minima.scanMyHtlcByKey(myMinimaPk, 2, HTLC_SCAN_DEPTH, coins -> {
            for (int i = 0; i < coins.length(); i++) {
                JSONObject coin = coins.optJSONObject(i);
                if (coin == null) continue;
                try {
                    String owner = MinimaHtlc.stateAt(coin, 0);
                    String receiver = MinimaHtlc.stateAt(coin, 4);
                    if (isMyPublishKey(receiver)) {
                        checkCanSwapCoin(coin, block);      // a swap addressed to my published identity
                    } else if (isMyOwnedKey(owner)) {
                        checkExpiredMinima(coin, block);    // a coin I locked under any of my 64 keys
                    }
                } catch (Exception ignore) {}
            }
        }, e -> {});
        // The scan above only reaches HTLC_SCAN_DEPTH blocks back, so it stops finding my own expired locks long
        // before they stop being refundable. Drive those from the DB instead — it has no depth window at all.
        sweepExpiredMinima(block);
    }

    /** REFUND backstop, driven from the DB rather than from a bounded chain scan.
     *
     *  The `coins depth:` walk-back meant a coin I locked vanished from runMinimaChecks ~3h33m after creation,
     *  while the refund only opens 36 blocks (responder) or 144 blocks (initiator) in. Anything that consumed
     *  that window — phone off, app closed, a wedged retry — stranded the coin permanently, because discovery
     *  and eligibility were both tied to the same short scan.
     *
     *  `swaps` knows every leg I locked and its absolute timelock, so eligibility is decided WITHOUT the chain,
     *  exactly as runEthChecks already decides the ETH side from db.allSwaps(). Only once a swap is known to be
     *  past its timelock do we go to the chain, per-hash and deep. That keeps the common case free: no active
     *  expired lock means not a single extra node command.
     *
     *  This is a backstop, not a replacement — the shallow scan above still handles the fast path. */
    void sweepExpiredMinima(int block) {
        SwapDb.Swap selected = null;
        long oldest = Long.MAX_VALUE;
        for (SwapDb.Swap s : db.allSwaps()) {
            if (s == null || s.hash == null || !s.myLegIsMinima) continue;
            // ERROR describes the trade outcome, not whether my own locked funds were recovered.
            if (SwapDb.ST_COMPLETE.equals(s.status) || SwapDb.ST_REFUNDED.equals(s.status)) continue;
            if (s.myTimelock <= 0 || block <= s.myTimelock || db.haveCollectExpired(s.hash)) continue;
            if (!ethRetryDue("refundM:" + s.hash)) continue;
            long attempted = ethAttempt.getOrDefault("refundScan:" + s.hash, 0L);
            if (nowUnix() - attempted >= ETH_RETRY_SECS && attempted < oldest) { selected = s; oldest = attempted; }
        }
        if (selected == null || !tryEthAttempt("refundScan:" + selected.hash)) return;
        final String hash = selected.hash;
        minima.scanHtlcByHashDeep(hash, 2, REFUND_SCAN_DEPTH, coins -> {
            boolean found = false;
            for (int i = 0; i < coins.length(); i++) {
                JSONObject coin = coins.optJSONObject(i);
                if (coin == null) continue;
                if (isMyOwnedKey(MinimaHtlc.stateAt(coin, 0)) && sameHash(MinimaHtlc.stateAt(coin, 5), hash)) {
                    found = true;
                    checkExpiredMinima(coin, block);
                }
            }
            if (!found) refundFromRecord(hash);
            // Deliberately NOT on the error path below: an empty scan is a result, a FAILED scan is not.
            // Refunding on a node error would sign against a coin we could not look up, and MinimaHtlc.refund
            // signs BEFORE txncheck - so every failed attempt burns a one-time Winternitz leaf for no possible
            // benefit, in exactly the condition (node unreachable) where there is nothing to gain. The sweep
            // retries on its own window.
        }, e -> SwapLog.w("refundScan " + hash + " ERR: " + e + " (unknown, not gone — retrying next window)"));
    }

    /**
     * Refund a lock the chain scan can no longer SEE, from what we recorded when we could.
     *
     * `coins depth:` is a fixed walk back from the tip, so a lock becomes invisible once it ages past the
     * window — and from then on the engine could never refund it, because finding it was a precondition of
     * refunding it. Observed live 2026-10-01: a 26.99025 MxUSD lock, 1,304 blocks old, provably UNSPENT in
     * the archive, invisible to the app, and reported to its owner as "spent/claimed" on the strength of an
     * empty scan. Waiting could never have fixed it; every new block made it worse.
     *
     * An empty scan is not evidence the coin is gone, so this does not need it to be. The spend is built from
     * the recorded coin and the NODE decides: a coin that really was spent, or a timelock not yet passed,
     * fails at txncheck and nothing is broadcast. The caller has already established from the DB that this
     * swap is non-terminal and past its own timelock.
     */
    private void refundFromRecord(String hash) {
        JSONObject coin = db.rememberedLockCoin(hash);
        if (coin == null) {
            // Nothing recorded — the lock predates the record (v3 DBs) or we never saw it in range.
            SwapLog.w("refund " + hash + ": out of scan range and no recorded coin — needs manual recovery");
            return;
        }
        if (db.haveCollectExpired(hash) || !tryEthAttempt("refundM:" + hash)) return;
        SwapLog.w("refund " + hash + ": coin is outside the " + REFUND_SCAN_DEPTH
                + "-block lookup — refunding from the recorded coin instead");
        submitMinimaRefund(hash, coin);
    }

    /** The refund submit + bookkeeping, shared by the scan path and the recorded-coin path. */
    private void submitMinimaRefund(final String hash, final JSONObject coin) {
        minima.refund(coin, new MinimaHtlc.PostCb() {
            @Override public void ok(String txpowid) {
                db.logEvent(hash, SwapDb.EV_MINIMA_REFUND_SUBMITTED, coin.optString("tokenid", "0x00"), MinimaHtlc.coinAmount(coin), txpowid);
                notifier.onSwapsChanged();
                SwapLog.d("refund SUBMITTED " + hash + " tx=" + txpowid + " — awaiting confirmation");
            }
            @Override public void err(String m) {
                SwapLog.w("refund ERR " + hash + ": " + m + " (retries after the window)");
                // leave the attempt timestamp → the next poll after ETH_RETRY_SECS retries it
            }
        });
    }

    /** Hashes of active swaps where I must CLAIM a mxUSDT counter-leg (my own leg is the ETH one): I hold the
     *  secret and haven't collected yet. Drives the per-hash counter-leg discovery in runMinimaChecks. */
    private java.util.List<String> pendingClaimMinimaHashes() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (SwapDb.Swap s : db.allSwaps()) {
            if (s == null || s.hash == null || s.myLegIsMinima) continue;   // my leg is ETH → I claim the mxUSDT leg
            if (SwapDb.ST_COMPLETE.equals(s.status) || SwapDb.ST_REFUNDED.equals(s.status)
                    || SwapDb.ST_ERROR.equals(s.status)) continue;
            // ZOMBIE-SCAN REAPER (0.1.41): an ERC20→mxUSDT INITIATOR whose own ETH leg is already past its refund
            // window can never still claim the (shorter, block+36) mxUSDT counter-leg — it expired long before my
            // ~2h ETH leg opened. If that ETH-leg refund never CONFIRMED on-chain (dropped/replaced tx, or a free
            // RPC that never surfaces gc.refunded) the row stays non-terminal forever and claimScan polls its dead
            // hash every ~90s. Membership only — no fund decision (the ETH refund scan is unchanged). Scoped to
            // this role+direction: a MINIMA_TO_ERC20 RESPONDER is also myLegIsMinima=false but its counter-leg
            // claim stays live ~2h, so a blanket cutoff would strand it.
            if ("INITIATOR".equals(s.role) && "ERC20_TO_MINIMA".equals(s.direction)
                    && s.myTimelock > 0 && nowUnix() > s.myTimelock) continue;
            if (db.getSecret(s.hash) != null && !db.haveCollect(s.hash)) out.add(s.hash);
        }
        return out;
    }

    /** Port of _checkCanSwapCoin: I am the receiver(state[4]) of a Minima HTLC coin. (package-private for tests) */
    void checkCanSwapCoin(JSONObject coin, int block) {
        final com.eurobuddha.atomix.TradingContext market = com.eurobuddha.atomix.TradingContext.active();
        final long generation = PublishGate.gen();
        String hash = MinimaHtlc.stateAt(coin, 5);
        if (hash.isEmpty()) return;
        int timelock = parseInt(MinimaHtlc.stateAt(coin, 3));
        String reqTokenAddr = stripReqToken(MinimaHtlc.stateAt(coin, 2));   // ERC20 the maker wants back
        String secret = db.getSecret(hash);

        if (secret != null) {
            // I know the secret → claim this coin (reveals it via the notify coin).
            // FUND-SAFETY (backport from atomix-mds): the coin locked to me MUST be the currency I'm buying — a
            // taker's reqToken is the currency-agnostic literal 'minima', so amountTokenOk can't catch a maker
            // who locks a WORTHLESS coloured token of the right AMOUNT. Verify the coin's OWN tokenid against
            // the swap's buy-currency before revealing my secret (an honest maker always locks the right token).
            String expTok = expectedTokenId(db.getSwap(hash));
            if (!expTok.equalsIgnoreCase(coin.optString("tokenid", "0x00"))) {
                logMismatchOnce(hash, "minima", "wrong token locked to me");
                return;
            }
            String[] req = db.getRequest(hash);
            if (req != null && !amountTokenOk(req, MinimaHtlc.coinAmount(coin), reqTokenAddr, false)) {
                logMismatchOnce(hash, "minima", "counterparty amount/token mismatch");
                return;
            }
            // H1: SELF-HEALING gate (reuses the generic ethAttempt/ethRetryDue retry-window from F1, keyed
            // "claimM:"+hash). The OLD guard `inflight.add("claimM:")` was cleared ONLY in the claim callback —
            // so if a node command timed out and the callback was lost, the marker stuck forever and the claim
            // NEVER retried (the 30-min stall we hit). Now the timestamp is the gate: markEthAttempt stamps NOW
            // (same-cycle dedup), and after ETH_RETRY_SECS a lost/failed claim re-fires on its own. No inflight.
            if (db.haveCollect(hash) || !tryEthAttempt("claimM:" + hash)) return;   // MA-20: atomic due-check-and-mark
            db.setSwapStatus(hash, SwapDb.ST_CLAIMING);   // counterparty leg found — claiming now
            notifier.onSwapsChanged();
            SwapLog.d("claim MINIMA leg " + hash + " amount=" + MinimaHtlc.coinAmount(coin));
            minima.claim(coin, hash, secret, new MinimaHtlc.PostCb() {
                @Override public void ok(String txpowid) {
                    db.logEvent(hash, SwapDb.EV_MINIMA_CLAIM_SUBMITTED, coin.optString("tokenid", "0x00"), MinimaHtlc.coinAmount(coin), txpowid);
                    notifier.onSwapsChanged();
                    SwapLog.d("claim SUBMITTED " + hash + " tx=" + txpowid + " — awaiting confirmation");
                }
                @Override public void err(String m) {
                    SwapLog.w("claim ERR " + hash + ": " + m + " (retries after the window)");
                    // leave the attempt timestamp → the next poll after ETH_RETRY_SECS retries it
                }
            });
            return;
        }

        // I don't know the secret → I'm a responder; lock the ETH counter-leg.
        // FUND-SAFETY (combine): only ever respond to a NEW take in the ACTIVE currency. The Minima settlement
        // scans are token-agnostic (so an in-flight swap in the OTHER currency is never stranded), which means a
        // STALE take coin of the non-active currency can surface here after a currency switch — matching it
        // against my active-currency order/price would misprice the counter-leg. Claiming a coin I'm OWED (secret
        // known, above) and refunding my own expired coins (checkExpiredMinima) stay token-agnostic; only this
        // new-liability path is gated.
        if (io.isShutdown() || halted()) return;   // stopped host/key mismatch: no new liability
        if (!minima.activeToken().equalsIgnoreCase(coin.optString("tokenid", "0x00"))) return;
        if (db.haveSentCounterParty(hash)) return;
        if (timelock - block < CP_BLOCKS_CHECK) return;                    // first leg too close to expiry
        if ("TRUE".equals(MinimaHtlc.stateAt(coin, 7))) {
            // OTC: respond ONLY if I'm the LP of an AGREED deal whose on-chain lock EXACTLY matches (otcVerifySell
            // is the fund-safety boundary), replacing the ladder acceptance gate.
            OtcDb.Deal d = otcLpDeal(hash);
            if (d == null || !otcVerifySell(coin, d, reqTokenAddr)) return;
        } else if (!acceptTakerSellMinima(coin, reqTokenAddr)) return;   // must match my published order
        final JSONObject operationCoin;
        try { operationCoin = new JSONObject(coin.toString()); }
        catch (org.json.JSONException invalid) { return; }
        final java.util.function.BooleanSupplier authorized = ethCounterLegGuard(operationCoin, hash, reqTokenAddr, market, generation);
        if (!authorized.getAsBoolean() || !inflight.add("cpEth:" + hash)) return;
        // CR-2: if the pool is shut down mid-call, release the marker we just claimed so the hash isn't wedged.
        submitIo(() -> lockEthCounterLeg(operationCoin, hash, reqTokenAddr, market, authorized), () -> inflight.remove("cpEth:" + hash));
    }

    /** TEST SEAM: the retry window is ETH_RETRY_SECS of real time, so a test proves the self-healing re-fire by
     *  ageing the marker rather than sleeping. Package-private — never called from app code. */
    static void ageRetryMarkerForTest(String key, long secondsAgo) {
        Long t = ethAttempt.get(key);
        if (t != null) ethAttempt.put(key, t - secondsAgo);
    }
    static void clearRetryMarkersForTest() { ethAttempt.clear(); }
    static void resetIdentityAlarmForTest() { identityAlarmRaised = false; }

    /** (package-private for tests) */
    void checkExpiredMinima(JSONObject coin, int block) {
        long timelock = parseBlock(MinimaHtlc.stateAt(coin, 3));   // MI-1: -1 on garbage/overflow (never 0)
        String seenHash = MinimaHtlc.stateAt(coin, 5);
        // Record the coin the FIRST time we see it, whether or not it is expired yet. `coins depth:` walks
        // back a fixed number of blocks from the tip, so this lock will age out of view (256 on the hot path,
        // 1024 on the expired sweep) and from then on we could never refund it, because we could never find
        // it again. Write-once, so the earliest sighting is the one kept.
        db.rememberLockCoin(seenHash, coin.optString("coinid", ""), MinimaHtlc.coinAmount(coin),
                coin.optString("tokenid", "0x00"), MinimaHtlc.stateAt(coin, 0));
        if (timelock < 0 || block <= timelock) return;             // unparseable timelock → NEVER treat as expired
        String hash = seenHash;
        // SELF-HEALING gate, identical to the claim path above. The old guard was `inflight.add("refundM:")`
        // cleared ONLY inside ok()/err() — so when a node command's callback was lost (NodeApi drops pending
        // callbacks once the hosting Activity is finishing) the marker was never released and the refund NEVER
        // retried for the life of the process. `inflight` is static, so one lost callback wedged the foreground
        // AND background engines at once. The claim path was moved off this exact pattern; the refund path was
        // not, and a real 35.014005 MINIMA lock sat refundable-but-unrefunded because of it. A timestamp cannot
        // leak: worst case a lost callback costs one ETH_RETRY_SECS window before the next attempt.
        if (db.haveCollectExpired(hash) || !tryEthAttempt("refundM:" + hash)) return;   // MA-20: atomic due-check-and-mark
        submitMinimaRefund(hash, coin);
    }

    /** Reuse the ETH settlement rule: submission records are retryable; only chain evidence is terminal.
     * One due receipt per poll, shared throttle across foreground/background engines. The stored event
     * survives a restart and is checked even after the spent HTLC coin disappears from discovery. */
    void confirmPendingMinima() {
        SwapDb.Event selected = null;
        String selectedHash = null;
        long oldestAttempt = Long.MAX_VALUE;
        for (SwapDb.Swap swap : db.allSwaps()) {
            if (swap == null || swap.hash == null || SwapDb.ST_COMPLETE.equals(swap.status)
                    || SwapDb.ST_REFUNDED.equals(swap.status)) continue;
            for (SwapDb.Event e : db.getEvents(swap.hash)) {
                if (!SwapDb.EV_MINIMA_REFUND_SUBMITTED.equals(e.event) && !SwapDb.EV_MINIMA_CLAIM_SUBMITTED.equals(e.event)) continue;
                if (e.note == null || !e.note.matches("(?i)0x[0-9a-f]{64}")) continue;
                long attempted = ethAttempt.getOrDefault("receiptM:" + e.note, 0L);
                // Rotate fairly: repeatedly missing recent receipts must not starve an older valid receipt.
                if (nowUnix() - attempted >= ETH_RETRY_SECS && attempted < oldestAttempt) {
                    selected = e; selectedHash = swap.hash; oldestAttempt = attempted;
                }
            }
        }
        if (selected == null || !tryEthAttempt("receiptM:" + selected.note)) return;
        final SwapDb.Event receipt = selected;
        final String hash = selectedHash;
        final boolean refund = SwapDb.EV_MINIMA_REFUND_SUBMITTED.equals(receipt.event);
        minima.confirmationDepth(receipt.note, depth -> {
            if (depth < 2) return;
            SwapDb.Swap current = db.getSwap(hash);
            if (current == null || SwapDb.ST_COMPLETE.equals(current.status) || SwapDb.ST_REFUNDED.equals(current.status)) return;
            // Status first: a crash must not leave a permanent event veto on a nonterminal row.
            db.setSwapStatus(hash, refund ? SwapDb.ST_REFUNDED : SwapDb.ST_COMPLETE);
            db.logEvent(hash, refund ? SwapDb.EV_EXPIRED : SwapDb.EV_COLLECT, "minima", receipt.amount, receipt.note);
            ethAttempt.remove((refund ? "refundM:" : "claimM:") + hash);
            notifier.notify(refund ? "Swap refunded" : "Swap complete",
                    (refund ? "Reclaimed " : "Claimed ") + receipt.amount + " "
                            + com.eurobuddha.atomix.TradingContext.labelFor(receipt.token) + " — confirmed on-chain"
                            + (refund ? " (" + refundReason(hash) + ")" : ""));
            notifier.onSwapsChanged();
        }, error -> SwapLog.w("receipt " + hash + " ERR: " + error));
    }

    /** [io] lock the ETH counter-leg for a mxUSDT→ERC20 swap I'm responding to (now+1800s). */
    // Capture the same market/generation boundary used by the Minima responder before entering the IO queue.
    private java.util.function.BooleanSupplier ethCounterLegGuard(JSONObject coin, String hash, String reqTokenAddr,
            com.eurobuddha.atomix.TradingContext market, long generation) {
        final EthNet network = net;
        final EthRpc connection = rpc;
        final String ownerKey = myMinimaPk, ownerEth = myEth();
        return () -> {
            if (io.isShutdown() || halted() || network != net || connection != rpc
                    || generation != PublishGate.gen() || market != com.eurobuddha.atomix.TradingContext.active()
                    || !market.tokenId.equalsIgnoreCase(minima.activeToken())
                    || !market.tokenId.equalsIgnoreCase(coin.optString("tokenid", "0x00"))
                    || !java.util.Objects.equals(ownerKey, myMinimaPk) || !java.util.Objects.equals(ownerEth, myEth())) return false;
            if ("TRUE".equals(MinimaHtlc.stateAt(coin, 7))) {
                OtcDb.Deal deal = otcLpDeal(hash);
                return deal != null && otcVerifySell(coin, deal, reqTokenAddr);
            }
            return myOrder != null && myOrder.market() == market && acceptTakerSellMinima(coin, reqTokenAddr);
        };
    }

    private void lockEthCounterLeg(JSONObject coin, String hash, String reqTokenAddr,
                                   com.eurobuddha.atomix.TradingContext market,
                                   java.util.function.BooleanSupplier authorized) {
        try {
            EthTx.requireAuthorized(authorized);
            EthNet.Token token = net.tokenByAddress(reqTokenAddr);
            if (token == null) { inflight.remove("cpEth:" + hash); return; }
            Credentials creds = wallet.creds();
            EthHtlc eth = new EthHtlc(rpc, creds, net, authorized);
            String tokenHuman = MinimaHtlc.stateAt(coin, 1);               // ERC20 amount the maker requested
            String reqMinimaHuman = MinimaHtlc.coinAmount(coin);        // mxUSDT they locked
            String receiverEth = MinimaHtlc.stateAt(coin, 6);             // maker's ETH address (ownereth)
            BigInteger sellRaw = parseUnits(tokenHuman, token.decimals);
            BigInteger reqRaw = parseUnits(reqMinimaHuman, 18);
            // Don't commit this counter-leg if the wallet can't afford the newContract gas — an approve/lock that
            // the RPC rejects for gas would otherwise end as a silent mutual refund. GAS_LOCK is the dominant cost
            // (> the approve), so this one check covers the whole approve→lock sequence.
            if (!ethGasAffordable(hash, EthHtlc.GAS_LOCK)) { inflight.remove("cpEth:" + hash); return; }
            if (!approveIfReady(eth, token.address, sellRaw)) { inflight.remove("cpEth:" + hash); return; }
            // F2: the counter-leg MUST anchor to CHAIN time (block.timestamp) — STRICTLY. A device-clock
            // fallback here would re-open the very loss F2 closes: a fast phone clock pushes this leg's expiry
            // past the taker's first leg, letting them refund their leg AND claim mine. So if the chain read
            // fails (or is grossly implausible) we THROW → the catch below aborts this lock and the responder
            // retries next cycle; we never lock the counter-leg on a guessed clock.
            final SwapDb.Swap existing = db.getSwap(hash);
            final long chainNow = ethChainNowStrict();
            final long timelock = existing != null && !existing.myLegIsMinima && existing.myTimelock > 0
                    ? existing.myTimelock : chainNow + CP_SECS;
            if (timelock <= chainNow) throw new java.io.IOException("Saved Ethereum counter-leg expired; no new lock was sent.");
            EthTx.requireAuthorized(authorized);
            // CR-1: record the swap row SYNCHRONOUSLY (this io thread) BEFORE the broadcast, and log EV_CPSENT
            // synchronously AFTER it — both were in ui.post()s that a destroyed main thread can DROP. Losing the
            // row leaves a mined counter-leg with nothing to refund it (no contractsAsSender scan); losing
            // EV_CPSENT leaves haveSentCounterParty false, so the responder re-locks and DOUBLE-locks the leg.
            // The cpEth marker is likewise released here on the io thread, not in a droppable post.
            SwapDb.Swap s = baseSwap(hash, "RESPONDER", "MINIMA_TO_ERC20",
                    token.symbol, tokenHuman, market.coinLabel, reqMinimaHuman, receiverEth);
            s.myTimelock = timelock; s.myLegIsMinima = false; s.contractId = EthHtlc.contractId(hash);
            s.status = SwapDb.ST_LOCKED;
            db.upsertSwap(s);
            ui.post(notifier::onSwapsChanged);
            String txhash = eth.newContract(myMinimaPk, receiverEth, hash,
                    BigInteger.valueOf(timelock), token.address, sellRaw, reqRaw, false);
            db.logEvent(hash, SwapDb.EV_CPSENT, "ETH:" + token.address, tokenHuman, txhash);
            inflight.remove("cpEth:" + hash);
            ui.post(() -> notifier.notify("Locked your " + token.symbol, "Waiting for the counterparty to reveal the secret"));
        } catch (Exception e) {
            inflight.remove("cpEth:" + hash);
        }
    }

    // ---- Ethereum side ([io]; blocking RPC) ----

    private void runEthChecks(final int minimaBlock) {
        EthHtlc eth;
        try { eth = new EthHtlc(rpc, wallet.creds(), net); } catch (Exception e) { return; }
        final String myEth = wallet.address();

        // Free any burst slot whose lock has confirmed on-chain (coinage:1 → its coin is safely spent, so the
        // NEXT take can lock), or whose watchdog fired (never confirmed → that one leg refunds; no wedge).
        final java.util.List<String> pendLegs;
        synchronized (CP_LOCKING) {
            pendLegs = new java.util.ArrayList<>(cpInFlight.keySet());
            // Prune stale CP_LOCKING markers (owning engine died mid-flight so its confirm-release never ran;
            // the DB row + haveSentCounterParty already dedup). Bounds the static map in a long-running process.
            CP_LOCKING.entrySet().removeIf(e -> nowUnix() - e.getValue() > CP_LOCK_TIMEOUT_SECS);
            cpAttempts.keySet().retainAll(CP_LOCKING.keySet());
        }
        for (String h : pendLegs) {
            Long since = cpLockSince.get(h);
            // MI-2: releaseCpLeg cleans the cp* maps but NOT the process-wide "cpMin:" marker — drop it here too,
            // so a leg whose lock callback was lost (dropped on a finishing Activity) doesn't leak the marker and
            // wedge that hash forever.
            if (since != null && nowUnix() - since > CP_LOCK_TIMEOUT_SECS) { releaseCpLeg(h); inflight.remove("cpMin:" + h); continue; }
            final CounterLeg attempt;
            synchronized (CP_LOCKING) { attempt = cpAttempts.get(h); }
            confirmMyLock(h, true, onChain -> {
                synchronized (CP_LOCKING) {
                    if (onChain && cpAttempts.get(h) == attempt) { releaseCpLeg(h); inflight.remove("cpMin:" + h); }
                }
            });
        }

        // PRIMARY (works on free/keyless RPCs): for every known swap, read its ETH leg by deterministic
        // contractId = sha256(hashlock) via getContract (eth_call) — claim, harvest the revealed preimage,
        // or refund. No eth_getLogs, which free nodes gate as an "archive" request.
        java.util.Set<String> termRaw = new java.util.HashSet<>(), termNorm = new java.util.HashSet<>();
        for (SwapDb.Swap s : db.allSwaps()) {
            if (SwapDb.ST_COMPLETE.equals(s.status) || SwapDb.ST_REFUNDED.equals(s.status) || SwapDb.ST_ERROR.equals(s.status)) {
                if (s.hash != null) { termRaw.add(s.hash); termNorm.add(MinimaHtlc.normKey(s.hash)); }
                continue;
            }
            try { checkEthContractFor(eth, s, myEth); } catch (Exception ignore) {}
        }
        // Review MINOR: reap per-hash guard entries for terminal swaps so the static ethAttempt (and the
        // instance dedup sets) don't grow unbounded over a multi-day service — only CP_LOCKING was pruned before.
        if (!termRaw.isEmpty()) {
            ethAttempt.keySet().removeIf(k -> { int c = k.lastIndexOf(':'); return c >= 0 && termRaw.contains(k.substring(c + 1)); });
            cpNoted.removeAll(termRaw);         // keyed by raw hash
            incoming.removeAll(termNorm);       // keyed by normKey(hash)
            declined.removeAll(termNorm);
        }

        // Re-arm OTC buy-responder hashes from persisted EXECUTING deals. The EXECUTE that first added a hash to the
        // in-memory `incoming` set may have been handled by the OTHER engine (fg vs bg service) or a prior process,
        // leaving THIS engine's set empty after a restart/handoff. Deriving from the shared OtcDb makes the responder
        // fire regardless of which engine polls (and lets a stuck deal recover after an update).
        if (otcDb != null) {
            for (OtcDb.Deal d : otcDb.allDeals()) {
                if (OtcDb.ROLE_LP.equals(d.role) && OtcDb.ST_EXECUTING.equals(d.status)
                        && OtcOffer.LP_SELLS_MINIMA.equals(d.side) && d.hash != null && !d.hash.isEmpty())
                    incoming.add(d.hash);
            }
        }

        // BUY handshake (free-RPC-safe): a buyer told us the hashlock of a USDT lock addressed to us. Find it by
        // deterministic contractId via getContract (no eth_getLogs) and run the normal responder path (lock the
        // mxUSDT counter-leg). Once it becomes a known swap, the loop above + the secondary path take over.
        for (String hash : new java.util.ArrayList<>(incoming)) {
            if (processIncomingBuy(eth, myEth, hash, minimaBlock)) incoming.remove(hash);
        }

        // SECONDARY (best-effort): eth_getLogs to discover a brand-new incoming ERC20→mxUSDT lock whose
        // hashlock we don't know yet (the only case getContract can't cover). Needs an archive RPC; silently
        // skipped if the endpoint rejects eth_getLogs, so the sell-mxUSDT path keeps working on free nodes.
        try {
            long ethBlock = rpc.blockNumber().longValue();
            long cap = Math.max(0, ethBlock - ETH_SCAN_CAP);
            long recvFrom = scanFrom(ethBlock, 500, cap);
            for (EthHtlc.Contract c : eth.contractsAsReceiver(BigInteger.valueOf(recvFrom), BigInteger.valueOf(ethBlock))) {
                if (db.getSwap(c.hashlock) != null || db.haveCollect(c.hashlock)) continue;   // known swaps handled above
                try { checkCanCollectEth(eth, c, minimaBlock); } catch (Exception ignore) {}
            }
            lastEthScanned = ethBlock;
        } catch (Exception ignore) { /* free RPC without eth_getLogs — getContract path above still works */ }
    }

    /** Process ONE announced buy hashlock's ETH leg: if the taker's USDT lock is visible + addressed to me, run the
     *  responder path (lock the mxUSDT counter-leg). Shared by the runEthChecks incoming loop and the checkBuyNow
     *  fast-path. Returns true if the hash is terminal/handled and can be dropped from {@code incoming}. */
    private boolean processIncomingBuy(EthHtlc eth, String myEth, String hash, int minimaBlock) {
        if (db.getSwap(hash) != null || db.haveSentCounterParty(hash)) return true;   // already known — drop
        try {
            EthHtlc.Contract c = eth.getContract(EthHtlc.contractId(hash));
            if (c == null) { SwapLog.d("buy " + hash + ": getContract NULL (USDT lock not visible on RPC yet) — retry"); return false; }
            if (c.withdrawn || c.refunded) return true;                   // terminal — stop polling it
            if (c.receiver != null && c.receiver.equalsIgnoreCase(myEth)) { SwapLog.d("buy " + hash + ": USDT lock visible, receiver=me → evaluate"); checkCanCollectEth(eth, c, minimaBlock); }
            else SwapLog.w("buy " + hash + ": USDT lock receiver=" + (c.receiver == null ? "null" : c.receiver) + " ≠ my eth " + myEth + " (took a stale/foreign order?)");
        } catch (Exception ignore) {}
        return false;
    }

    /** Discovery fast-path: act on ONE freshly-announced buy handshake IMMEDIATELY instead of waiting for the next
     *  ~90s poll (cuts a full poll cycle off buy discovery). Same guards as the poll loop (the getSwap/CP_LOCKING
     *  dedup in checkCanCollectEth) → idempotent and fund-safe, just earlier. Called from addIncoming on a NEW hash. */
    public void checkBuyNow(final String hash) {
        if (!ready() || hash == null || hash.isEmpty()) return;
        if (db.getSwap(hash) != null || db.haveSentCounterParty(hash)) return;   // already handled
        minima.currentBlock(new MinimaHtlc.BlockCb() {
            @Override public void ok(int block) {
                submitIo(() -> {
                    EthHtlc eth;
                    try { eth = new EthHtlc(rpc, wallet.creds(), net); } catch (Exception e) { return; }
                    processIncomingBuy(eth, wallet.address(), hash, block);
                });
            }
            @Override public void err(String m) { /* node busy; the next poll covers it */ }
        });
    }

    /** Read one swap's ETH leg by deterministic contractId and drive it: claim (receiver), harvest the
     *  revealed secret / refund (sender). All via getContract/withdraw/refund — no eth_getLogs. */
    private void checkEthContractFor(EthHtlc eth, SwapDb.Swap s, String myEth) throws Exception {
        final String hash = s.hash;
        final String contractId = EthHtlc.contractId(hash);
        EthHtlc.Contract gc = eth.getContract(contractId);
        if (gc == null) return;   // the ETH leg isn't locked yet

        boolean iAmReceiver = gc.receiver != null && gc.receiver.equalsIgnoreCase(myEth);
        boolean iAmSender = gc.owner != null && gc.owner.equalsIgnoreCase(myEth);

        if (iAmReceiver) {
            // gc.withdrawn is AUTHORITATIVE: only the receiver (me) can withdraw, so it means MY claim settled.
            // Finalize on it — never on the broadcast ack — so a dropped tx retries below instead of being
            // marked COMPLETE while the USDT is still in the vault.
            if (gc.withdrawn) { confirmEthWithdrawn(hash, s); return; }
            if (gc.refunded) { finalizeEthLost(hash); return; }   // counterparty refunded before I claimed — this leg is gone
            String secret = db.getSecret(hash);
            if (secret == null) return;            // (responder before harvesting the secret — nothing to do yet)
            String[] req = db.getRequest(hash);
            if (req != null) {
                String tokenHuman = EthWallet.format(gc.amount, decimalsOf(gc.tokenContract), 18);
                if (!amountTokenOk(req, tokenHuman, gc.tokenContract, true)) {
                    logMismatchOnce(hash, "ETH:" + gc.tokenContract, "counterparty amount/token mismatch");
                    return;
                }
            }
            broadcastEthWithdraw(eth, contractId, hash, secret);   // retryable; confirmed next cycle via gc.withdrawn
        } else if (iAmSender) {
            if (gc.withdrawn) {
                // The counterparty revealed the preimage IN the contract — read it directly (no eth_getLogs).
                if (gc.preimage != null && EthRpc.hexToBig(gc.preimage).signum() != 0 && MinimaHtlc.verifyPreimage(gc.preimage, hash) && db.insertSecret(hash, gc.preimage)) {
                    ui.post(() -> { notifier.notify("Secret revealed", "Claiming your side of the swap"); notifier.onSwapsChanged(); });
                }
            } else if (gc.refunded) {
                confirmEthRefunded(hash);          // AUTHORITATIVE: the vault says this leg is refunded — finalize
            } else if (nowUnix() > gc.timelock) {
                broadcastEthRefund(eth, contractId, hash);   // retryable; confirmed next cycle via gc.refunded
            }
        }
    }

    // ---- F1: broadcast vs. confirmation, split apart -----------------------------------------------------
    // The bug this fixes: refund()/withdraw() used to write the terminal status (+ the EV_ guard row) right
    // after eth_sendRawTransaction — a broadcast ACK, not a mined receipt. A dropped or fee-replaced tx (very
    // possible: the ETH wallet is shared byte-for-byte with minimaSwap, so nonces can collide) then looked
    // "done", was skipped by allSwaps(), and never retried — stranding the USDT with no in-app recovery. Now
    // broadcast is retryable + idempotent, and the terminal status is written ONLY when the contract's own
    // withdrawn/refunded flag — re-read every cycle by checkEthContractFor — confirms it.

    /** Broadcast a withdraw (reveals the preimage, claims the USDT). Never finalizes — {@code gc.withdrawn}
     *  does, on a later cycle. Spaced by ETH_RETRY_SECS so a dropped tx re-sends (with a healed nonce) rather
     *  than stranding, and so a still-pending tx isn't piled behind. */
    private void broadcastEthWithdraw(EthHtlc eth, String contractId, String hash, String secret) {
        if (!ethRetryDue("wdEth:" + hash) || !inflight.add("wdEth:" + hash)) return;
        if (!ethGasAffordable(hash, EthHtlc.GAS_WITHDRAW)) { inflight.remove("wdEth:" + hash); return; }
        try {
            markEthAttempt("wdEth:" + hash);
            db.setSwapStatus(hash, SwapDb.ST_CLAIMING);
            ui.post(notifier::onSwapsChanged);
            eth.withdraw(contractId, secret);      // ack only; success is confirmed on-chain, not here
        } catch (Exception e) {
            // pre-mine failure (RPC/nonce/gas) → nothing committed; the next cycle retries after the window.
            // LOGGED: a swap can sit in CLAIMING for hours on repeated failures here — silence made that
            // undiagnosable from logcat.
            SwapLog.w("wdEth ERR " + hash + ": " + e.getMessage() + " (retries after the window)");
        } finally { inflight.remove("wdEth:" + hash); }
    }

    /** Broadcast a refund of my own expired leg. Never finalizes — {@code gc.refunded} does, on a later cycle. */
    private void broadcastEthRefund(EthHtlc eth, String contractId, String hash) {
        if (!ethRetryDue("refundE:" + hash) || !inflight.add("refundE:" + hash)) return;
        if (!ethGasAffordable(hash, EthHtlc.GAS_REFUND)) { inflight.remove("refundE:" + hash); return; }
        try {
            markEthAttempt("refundE:" + hash);
            eth.refund(contractId);
        } catch (Exception e) {
            // pre-mine failure → nothing committed; retry next cycle after the window
            SwapLog.w("refundE ERR " + hash + ": " + e.getMessage() + " (retries after the window)");
        } finally { inflight.remove("refundE:" + hash); }
    }

    /** Finalize a withdraw once the contract confirms it settled. Idempotent (single terminal write + notify). */
    private void confirmEthWithdrawn(String hash, SwapDb.Swap s) {
        ethAttempt.remove("wdEth:" + hash);
        SwapDb.Swap cur = db.getSwap(hash);
        if (cur != null && SwapDb.ST_COMPLETE.equals(cur.status)) return;
        if (!db.haveCollect(hash)) db.logEvent(hash, SwapDb.EV_COLLECT, "ETH", "", "confirmed on-chain");
        db.setSwapStatus(hash, SwapDb.ST_COMPLETE);
        ui.post(() -> { notifier.notify("Swap complete", "Withdrew your " + s.buyToken); notifier.onSwapsChanged(); });
    }

    /** Finalize a refund once the contract confirms it. Idempotent (single terminal write + notify). */
    private void confirmEthRefunded(String hash) {
        ethAttempt.remove("refundE:" + hash);
        SwapDb.Swap cur = db.getSwap(hash);
        if (cur != null && SwapDb.ST_REFUNDED.equals(cur.status)) return;
        if (!db.haveCollectExpired(hash)) db.logEvent(hash, SwapDb.EV_EXPIRED, "ETH", "", "confirmed on-chain");
        db.setSwapStatus(hash, SwapDb.ST_REFUNDED);
        final String reason = "Reclaimed your tokens — " + refundReason(hash);   // 0.1.41: say WHY, not a bare "Reclaimed your tokens"
        SwapLog.w("refund CONFIRMED " + hash + " — " + reason);
        ui.post(() -> { notifier.notify("Swap refunded", reason); notifier.onSwapsChanged(); });
    }

    /** The counterparty refunded the ETH leg I was to receive (I missed my claim window), so this swap failed
     *  for me. Mark it terminal ONCE so checkEthContractFor stops re-polling it forever; my own first leg, if
     *  any, refunds independently at its own timelock (that path is not gated on this status). */
    private void finalizeEthLost(String hash) {
        SwapDb.Swap cur = db.getSwap(hash);
        if (cur == null || SwapDb.ST_ERROR.equals(cur.status) || SwapDb.ST_REFUNDED.equals(cur.status)
                || SwapDb.ST_COMPLETE.equals(cur.status)) return;
        db.setSwapStatus(hash, SwapDb.ST_ERROR);
        ui.post(notifier::onSwapsChanged);
    }

    private boolean ethRetryDue(String key) { return nowUnix() - ethAttempt.getOrDefault(key, 0L) >= ETH_RETRY_SECS; }
    private void markEthAttempt(String key) { ethAttempt.put(key, nowUnix()); }

    /** True iff this wallet can cover gas for one HTLC op of {@code gasLimit} at the price {@link EthTx} will
     *  broadcast at. On a genuine shortfall: notify ONCE ("Add ETH for gas — ~X"), log it, and return false so
     *  the caller SKIPS this broadcast (the poll loop retries once funded) instead of firing a doomed tx that
     *  the RPC rejects for "insufficient funds for gas" and that ends as a SILENT mutual refund. A brand-new
     *  node-derived ETH wallet holds almost nothing, so this is the difference between a clear prompt and an
     *  invisible failure (proven live: a new user's swaps mutual-refunded at 0.0005 ETH, completed at 0.001).
     *  On any read error returns true — never block a send on a transient RPC hiccup; let it try and surface. */
    private boolean ethGasAffordable(String hash, BigInteger gasLimit) {
        try {
            BigInteger rawGp = EthRpc.hexToBig(rpc.callStr("eth_gasPrice", new JSONArray()));
            if (rawGp.signum() <= 0) rawGp = BigInteger.valueOf(1_000_000_000L);   // mirror EthTx.FALLBACK_GAS_PRICE
            BigInteger need = EthSend.gasReserveWei(rawGp, rpc.baseFeePerGasOrZero(), gasLimit);
            BigInteger have = wallet.ethBalanceWei(rpc);
            if (have.compareTo(need) >= 0) { lowEth.remove(hash); return true; }
            if (lowEth.add(hash)) {   // once per hash, until the wallet clears the bar again
                String shortEth = new BigDecimal(need.subtract(have)).movePointLeft(18)
                        .stripTrailingZeros().toPlainString();
                final String gasAddr = wallet.address();
                final String body = gasShortfallMessage(shortEth, gasAddr);
                SwapLog.w("swap " + hash + " BLOCKED: needs ~" + shortEth + " more ETH for gas at " + gasAddr);
                ui.post(() -> notifier.notify("Add ETH for gas", body));
            }
            return false;
        } catch (Exception e) {
            return true;   // couldn't read balance/price → don't block; let the broadcast try
        }
    }

    /** MA-20: atomic check-and-set on the shared retry-window map — true (and stamps NOW) iff the key is due
     *  (≥ ETH_RETRY_SECS since its last attempt). Replaces the racy ethRetryDue()+markEthAttempt() pair on the
     *  claim/refund paths so the foreground and background engines can't BOTH pass the check before either marks
     *  and then both sign the same coin, permanently burning two one-time key leaves instead of one. */
    private boolean tryEthAttempt(String key) {
        long now = nowUnix();
        Long prev = ethAttempt.putIfAbsent(key, now);
        if (prev == null) return true;                        // never attempted → claim it
        if (now - prev < ETH_RETRY_SECS) return false;        // still inside the window → not due
        return ethAttempt.replace(key, prev, now);            // due → win the slot atomically (false if another thread already did)
    }

    /** Unix seconds from the CHAIN (latest block timestamp) for setting an ETH HTLC timelock — the clock the
     *  vault enforces (F2). Falls back to the device clock only if the chain read fails. Safe ONLY for the
     *  INITIATOR's first leg, where a fast clock merely makes that leg LONGER (the safe direction) and a slow
     *  clock is independently rejected by the responder's own half-window gate. NOT for the counter-leg —
     *  use {@link #ethChainNowStrict()} there. */
    private long ethChainNow() {
        try { return rpc.latestBlockTimestamp(); } catch (Exception e) { return nowUnix(); }
    }

    /** Chain time for the RESPONDER counter-leg — no device-clock fallback. Throws if the chain read fails so
     *  the caller aborts the lock (retrying next cycle) rather than anchoring a fund-critical timelock to a
     *  possibly-skewed phone clock. Also refuses a grossly implausible RPC timestamp (>24 h from the device
     *  clock = a broken/hostile node or a broken device) — catches gross lies while tolerating any realistic
     *  device skew (the returned value is always chain time; the device clock is only a sanity bound). */
    private long ethChainNowStrict() throws java.io.IOException {
        long chain = rpc.latestBlockTimestamp();
        if (Math.abs(chain - nowUnix()) > 24L * 60 * 60)
            throw new java.io.IOException("chain/device clock skew too large (" + (chain - nowUnix()) + "s) — not locking");
        return chain;
    }

    // ETH chain time for the ADVISORY responder half-window guard (backport from atomix-mds 0.1.2): prefer the
    // clock the vault actually enforces; a slow DEVICE clock inflated the taker's apparent remaining window and
    // eroded the 2× safety margin. Cached ~30s (the guard runs per contract per poll); device-clock fallback on
    // an RPC failure — never worse than the old behavior. NOT for lock timelocks (those use ethChainNowStrict).
    private long ethNowCache = 0, ethNowCacheAt = 0;
    private long ethNowSoft() {
        long dev = nowUnix();
        if (ethNowCacheAt > 0 && dev - ethNowCacheAt < 30) return ethNowCache + (dev - ethNowCacheAt);
        try { long chain = rpc.latestBlockTimestamp(); ethNowCache = chain; ethNowCacheAt = dev; return chain; }
        catch (Exception e) { return dev; }
    }


    /** Port of _checkCanCollectETHCoin: I am the receiver of an ETH HTLC contract. */
    private void checkCanCollectEth(EthHtlc eth, EthHtlc.Contract c, int minimaBlock) throws Exception {
        final com.eurobuddha.atomix.TradingContext market = com.eurobuddha.atomix.TradingContext.active();
        final long generation = PublishGate.gen();
        String hash = c.hashlock;
        String secret = db.getSecret(hash);

        if (secret != null) {
            String[] req = db.getRequest(hash);
            if (req != null) {
                String tokenHuman = EthWallet.format(c.amount, decimalsOf(c.tokenContract), 18);
                if (!amountTokenOk(req, tokenHuman, c.tokenContract, true)) {
                    logMismatchOnce(hash, "ETH:" + c.tokenContract, "counterparty amount/token mismatch");
                    return;
                }
            }
            if (!eth.canCollect(c.contractId)) return;   // already withdrawn/refunded on-chain
            // F1: retryable broadcast; the terminal status is confirmed by checkEthContractFor via gc.withdrawn,
            // never on this ack (a dropped withdraw must not read as COMPLETE).
            broadcastEthWithdraw(eth, c.contractId, hash, secret);
            return;
        }

        // I don't know the secret → I'm an ERC20→mxUSDT responder; lock the mxUSDT counter-leg.
        if (io.isShutdown() || halted()) return;   // stopped host/key mismatch: no new liability
        if (db.haveSentCounterParty(hash)) return;
        // Persistent dedup (survives a restart / a lost txnpost response that the in-memory CP_LOCKING can't):
        // lockMinimaCounterLeg records the swap row BEFORE broadcast, so a row here means this hash is already
        // being (or was) locked — never re-lock it (the mxUSDT leg has no on-chain hash-uniqueness).
        if (db.getSwap(hash) != null) return;
        if (c.timelock - ethNowSoft() < CP_SECS_CHECK) { if (!c.otc) declineNote(hash, "their USDT lock is too close to its timeout"); return; }
        if (c.otc) {
            // OTC: the ladder gate doesn't apply. Respond ONLY if I'm the LP of an AGREED deal whose on-chain lock
            // EXACTLY matches the agreed terms (otcVerifyBuy = the fund-safety boundary). Then use the SAME dedup +
            // lock path below — c's own request-amount/pubkey (already verified == agreed) drive the counter-leg.
            OtcDb.Deal d = otcLpDeal(hash);
            if (d == null || !otcVerifyBuy(c, d)) return;
        } else {
            if (myOrder == null) return;                                  // order not loaded yet — retry next cycle
            if (!acceptTakerBuyMinima(c)) { declineNote(hash, "it didn't fit any level of your ladder (price, level size, or minimum)"); return; }
        }
        // Claim a burst slot AND the cross-engine per-hash marker atomically (under the shared CP_LOCKING monitor):
        // up to CP_LOCK_BURST distinct-hash locks in flight (each gets a DISTINCT coin in lockMinimaCounterLeg),
        // and no OTHER engine can re-lock this same hash mid-flight. Both released together in releaseCpLeg.
        synchronized (CP_LOCKING) {
            if (isConsolidating() || inflight.contains("sell")) {
                declineCpNote(hash, "Wallet busy consolidating or locking coins. Retry after confirmation. Deal: " + hash);
                return;
            }
            if (db.getSwap(hash) != null) return;                                        // re-check the persistent row ATOMICALLY with the reserve (closes a stale-getSwap TOCTOU)
            Long lk = CP_LOCKING.get(hash);
            if (lk != null && nowUnix() - lk < CP_LOCK_TIMEOUT_SECS) return;              // another engine is locking this hash
            if (cpInFlight.size() >= CP_LOCK_BURST || cpInFlight.containsKey(hash)) return; // burst full / already mine
            CP_LOCKING.put(hash, nowUnix());
            cpInFlight.put(hash, "");
            cpLockSince.put(hash, nowUnix());   // MA-18: inside the CP_LOCKING monitor with cpInFlight (lines 202-203)
        }
        if (!inflight.add("cpMin:" + hash)) { releaseCpLeg(hash); return; }
        lockMinimaCounterLeg(c, minimaBlock, market, generation);
    }

    /** Tell the maker (once) why a handshake-announced buy was declined, so a reject isn't silent. */
    private void declineNote(String hash, String reason) {
        if (!incoming.contains(MinimaHtlc.normKey(hash)) || !declined.add(MinimaHtlc.normKey(hash))) return;
        ui.post(() -> notifier.notify("Buy request declined", reason));
    }

    /** Lock the mxUSDT counter-leg for an ERC20→mxUSDT swap I'm responding to (block+36). */
    private void lockMinimaCounterLeg(EthHtlc.Contract c, int minimaBlock,
                                      com.eurobuddha.atomix.TradingContext market, long generation) {
        final String hash = c.hashlock;
        final CounterLeg attempt;
        synchronized (CP_LOCKING) {
            if (!CP_LOCKING.containsKey(hash) || !cpInFlight.containsKey(hash) || cpAttempts.containsKey(hash)) return;
            attempt = new CounterLeg(c, market, generation);
            cpAttempts.put(hash, attempt);
        }
        if (!attempt.current()) { attempt.release(); return; }
        if (isConsolidating()) {
            attempt.release();
            declineCpNote(hash, "Consolidation in progress. Retry after confirmation. Deal: " + hash);
            return;
        }
        final int timelock = minimaBlock + CP_BLOCKS;
        final String reqMinimaHuman = EthWallet.format(c.requestAmount, 18, 18);   // mxUSDT they want from me
        final String receiverPubkey = c.minimaPublicKey;                           // initiator's Minima pubkey
        if (!ui.post(() -> {
            if (!attempt.current() || !attempt.terms()) { attempt.release(); return; }
            minima.myFreeCoins(coins -> {
                if (!attempt.current() || !attempt.terms()) { attempt.release(); return; }
                // Gather DISTINCT confirmed coins totalling ≥ the lock amount (largest-first → fewest inputs), skipping
                // any coin reserved by another in-flight burst lock. This fills a deal larger than any SINGLE coin (like
                // a wallet send auto-selecting UTXOs) while burst siblings still never double-select. Pick + reserve atomically.
                final java.math.BigDecimal need = new java.math.BigDecimal(reqMinimaHuman);
                final java.util.List<String> pickIds = new java.util.ArrayList<>();
                java.math.BigDecimal pickSumTmp = java.math.BigDecimal.ZERO, freeTotal = java.math.BigDecimal.ZERO;
                synchronized (CP_LOCKING) {                       // single monitor for all cpInFlight mutations
                    if (!attempt.current()) { attempt.release(); return; }
                    java.util.Set<String> used = new java.util.HashSet<>();   // coinids reserved by other in-flight locks (values are comma-joined lists)
                    for (String v : cpInFlight.values()) if (v != null && !v.isEmpty()) for (String u : v.split(",")) if (!u.isEmpty()) used.add(u);
                    java.util.List<String> ids = new java.util.ArrayList<>();
                    java.util.List<java.math.BigDecimal> amts = new java.util.ArrayList<>();
                    for (int i = 0; i < coins.length(); i++) {
                        JSONObject cc = coins.optJSONObject(i); if (cc == null) continue;
                        String cid = cc.optString("coinid", ""), amt = MinimaHtlc.coinAmount(cc);
                        if (cid.isEmpty() || used.contains(cid)) continue;
                        try { java.math.BigDecimal a = new java.math.BigDecimal(amt); ids.add(cid); amts.add(a); freeTotal = freeTotal.add(a); } catch (Exception ignore) {}
                    }
                    Integer[] ord = new Integer[ids.size()];
                    for (int i = 0; i < ord.length; i++) ord[i] = i;
                    java.util.Arrays.sort(ord, (x, y) -> amts.get(y).compareTo(amts.get(x)));   // largest-first
                    for (int oi = 0; oi < ord.length && pickSumTmp.compareTo(need) < 0 && pickIds.size() < MAX_LOCK_COINS; oi++) {
                        pickIds.add(ids.get(ord[oi])); pickSumTmp = pickSumTmp.add(amts.get(ord[oi]));
                    }
                    if (pickSumTmp.compareTo(need) >= 0) cpInFlight.put(hash, android.text.TextUtils.join(",", pickIds));
                    else pickIds.clear();
                }
                if (pickIds.isEmpty()) {   // can't cover `need` right now — DON'T hang silently; tell the LP why
                    attempt.release();
                    declineCpNote(hash, freeTotal.compareTo(need) < 0
                            ? "Can't fill deal — need " + reqMinimaHuman + " mxUSDT, only " + freeTotal.toPlainString() + " free"
                            : "mxUSDT too fragmented to lock " + reqMinimaHuman + " in one tx — consolidate your coins");
                    return;
                }
                final String totalSel = pickSumTmp.toPlainString();
                // RECORD-BEFORE-BROADCAST (mirrors lockEthCounterLeg's Fix D): persist the swap row NOW, so a process
                // kill or a lost txnpost response between broadcast and ok() can't re-lock this hash on restart. The
                // row (gated in checkCanCollectEth via getSwap!=null) is the only restart-proof dedup the mxUSDT leg
                // has (no on-chain hash-uniqueness). EV_CPSENT stays AFTER (→ haveSentCounterParty).
                EthNet.Token tk = net.tokenByAddress(c.tokenContract);
                String sym = tk == null ? "token" : tk.symbol;
                SwapDb.Swap s = baseSwap(hash, "RESPONDER", "ERC20_TO_MINIMA",
                        attempt.market.coinLabel, reqMinimaHuman, sym, EthWallet.format(c.amount, decimalsOf(c.tokenContract), 18),
                        receiverPubkey);
                s.myTimelock = timelock; s.myLegIsMinima = true; s.contractId = c.contractId;
                s.status = SwapDb.ST_LOCKED;
                synchronized (CP_LOCKING) {
                    if (!attempt.current() || !attempt.terms()) { attempt.release(); return; }
                    try { db.upsertSwap(s); }
                    catch (RuntimeException storageFailure) {
                        attempt.release(); SwapLog.w("Counter-leg could not be saved before signing"); return;
                    }
                }
                notifier.onSwapsChanged();
                minima.lockFromCoins(pickIds, totalSel, reqMinimaHuman, reqMinimaHuman, "minima", receiverPubkey,
                        myEth(), hash, timelock, "FALSE", attempt::authorized, new MinimaHtlc.PostCb() {
                    @Override public void ok(String txpowid) {
                        db.logEvent(hash, SwapDb.EV_CPSENT, "minima", reqMinimaHuman, txpowid);
                        notifier.notify("Locked your " + attempt.market.coinLabel, "Waiting for the counterparty to reveal the secret");
                        notifier.onSwapsChanged();
                        synchronized (CP_LOCKING) {
                            if (attempt.owned()) inflight.remove("cpMin:" + hash);
                        }
                        // keep cpInFlight[hash] reserved until the lock CONFIRMS (freed in runEthChecks) — holds the coin
                    }
                    @Override public void err(String m) {
                        synchronized (CP_LOCKING) {
                        if (!attempt.owned()) return;
                        // Retry ONLY if the failure provably didn't broadcast (build phase, no "POSTED:" tag); a
                        // txnpost failure may have landed with a lost response → keep the row so we never re-lock.
                        if (m == null || !m.startsWith("POSTED:")) db.deleteSwap(hash);
                        attempt.release();
                        }
                    }
                });
            }, e -> {
                boolean report = attempt.current();
                attempt.release();
                if (!report) return;
                SwapLog.w("Buy " + hash + " counter-leg declined: " + e);
                if (e != null && e.startsWith(MinimaHtlc.ERR_TOO_MANY_COINS) && shouldNoteFragmentation())
                    declineCpNote(hash, e + ". Open Wallet → Consolidate before accepting buys. Deal: " + hash);
            });
        })) attempt.release();
    }

    // ============================================================ order-match guards (fund safety)

    /** Reject NaN/Infinity/≤0 before any BigDecimal.valueOf (valueOf throws on NaN/Infinity). */
    private static boolean validPos(double d) { return !Double.isNaN(d) && !Double.isInfinite(d) && d > 0; }

    /**
     * Responder guard when the taker is SELLING mxUSDT to me (they locked mxUSDT wanting USDT). I am BUYING
     * mxUSDT, so I pay one of my BID prices (USDT per mxUSDT). I auto-lock only if the pair is enabled, the
     * mxUSDT I'd receive meets my minimum, and it fits SOME enabled BID tranche — the take amount is within
     * that tranche's cap AND the USDT I'd pay is ≤ (mxUSDT received × tranche price). Per-take cap only; no
     * cross-take decrement (my real balance is the hard limit, enforced at the lock step).
     */
    /* package-private (not private) so unit tests can drive this fund-safety boundary directly. */
    boolean acceptTakerSellMinima(JSONObject coin, String reqTokenAddr) {
        Order.Pair p = pairFor(reqTokenAddr);
        if (p == null || !p.enable) return false;
        BigDecimal recvMinima = dec(MinimaHtlc.coinAmount(coin));   // mxUSDT the taker locked, I receive
        BigDecimal giveUsdt = dec(MinimaHtlc.stateAt(coin, 1));       // USDT they requested, I'd pay
        if (recvMinima.signum() <= 0) return false;
        if (validPos(p.min) && recvMinima.compareTo(BigDecimal.valueOf(p.min)) < 0) return false;   // min>0 = floor; min≤0/NaN = no floor (0.8.2 parity)
        if (p.bids.isEmpty())   // legacy single-price order (pre-0.9.0) — behave exactly as before
            return validPos(p.sell) && giveUsdt.compareTo(recvMinima.multiply(BigDecimal.valueOf(p.sell))) <= 0;
        for (Order.Level t : p.bids) {   // sanitized: valid, sorted desc, ≤ MAX_LEVELS
            if (!validPos(t.price) || !validPos(t.amount)) continue;
            if (recvMinima.compareTo(BigDecimal.valueOf(t.amount)) > 0) continue;             // per-take cap
            if (giveUsdt.compareTo(recvMinima.multiply(BigDecimal.valueOf(t.price))) <= 0) return true;
        }
        return false;
    }

    /**
     * Responder guard when the taker is BUYING mxUSDT from me (they locked USDT wanting mxUSDT). I am SELLING
     * mxUSDT, so I get one of my ASK prices. I auto-lock only if the pair is enabled, the mxUSDT I'd give meets
     * my minimum, and it fits SOME enabled ASK tranche — within that tranche's cap AND the USDT I'd receive is
     * ≥ (mxUSDT given × tranche price).
     */
    /* package-private (not private) so unit tests can drive this fund-safety boundary directly. */
    boolean acceptTakerBuyMinima(EthHtlc.Contract c) {
        Order.Pair p = pairFor(c.tokenContract);
        if (p == null || !p.enable) return false;
        BigDecimal giveMinima = dec(EthWallet.format(c.requestAmount, 18, 18));            // mxUSDT I'd give
        BigDecimal recvUsdt = dec(EthWallet.format(c.amount, decimalsOf(c.tokenContract), 18)); // USDT I'd receive
        if (giveMinima.signum() <= 0) return false;
        if (validPos(p.min) && giveMinima.compareTo(BigDecimal.valueOf(p.min)) < 0) return false;   // min>0 = floor; min≤0/NaN = no floor (0.8.2 parity)
        if (p.asks.isEmpty())   // legacy single-price order — behave exactly as before
            return validPos(p.buy) && recvUsdt.compareTo(giveMinima.multiply(BigDecimal.valueOf(p.buy))) >= 0;
        for (Order.Level t : p.asks) {
            if (!validPos(t.price) || !validPos(t.amount)) continue;
            if (giveMinima.compareTo(BigDecimal.valueOf(t.amount)) > 0) continue;             // per-take cap
            if (recvUsdt.compareTo(giveMinima.multiply(BigDecimal.valueOf(t.price))) >= 0) return true;
        }
        return false;
    }

    // ---- OTC responder gate: an AGREED deal REPLACES the ladder acceptance above (the fund-safety boundary) ----

    /** An AGREED/EXECUTING OTC deal I'm the LP for, keyed by this hash; null ⇒ do NOT respond to this otc lock. */
    private OtcDb.Deal otcLpDeal(String hash) {
        if (otcDb == null) return null;
        OtcDb.Deal d = otcDb.dealByHash(hash);
        if (d == null || !OtcDb.ROLE_LP.equals(d.role)) return null;
        // Both responder paths need explicit attribution. Unknown legacy deals remain visible,
        // but cannot create a new liability using a guessed market. Existing claim/refund paths are separate.
        if (!d.inActiveMarket() || !d.market().tokenId.equalsIgnoreCase(minima.activeToken())) return null;
        if (!OtcDb.ST_AGREED.equals(d.status) && !OtcDb.ST_EXECUTING.equals(d.status)) return null;
        String ss = swapStatus(hash);   // never re-respond to a hash whose swap already finished
        if (SwapDb.ST_COMPLETE.equals(ss) || SwapDb.ST_REFUNDED.equals(ss) || SwapDb.ST_ERROR.equals(ss)) return null;
        return d;
    }

    /** Compare two pubkeys/hex tolerantly of a 0x prefix / case, matching the rest of the engine (normKey). */
    private static boolean keyEq(String a, String b) {
        if (a == null || b == null) return false;
        return MinimaHtlc.normKey(a).equals(MinimaHtlc.normKey(b));
    }

    private static boolean approxEq(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return false;
        // Tolerance covers USDT's 6-dp truncation of amount×price (≤1e-6); ~1e-5 absolute caps any discrepancy at
        // a negligible fraction of a cent / a mxUSDT while never rejecting a correctly-rounded on-chain lock.
        return a.subtract(b).abs().compareTo(new BigDecimal("0.00001")) <= 0;
    }

    /** Verify an incoming ERC20 lock (instigator BUYS mxUSDT, I'm the LP selling) EXACTLY matches the agreed deal
     *  before I lock the mxUSDT counter-leg: the USDT must be addressed to me, the mxUSDT must go to the agreed
     *  counterparty, and both amounts must equal the negotiated terms. */
    /* package-private (not private) so unit tests can drive this OTC fund-safety boundary directly. */
    boolean otcVerifyBuy(EthHtlc.Contract c, OtcDb.Deal d) {
        if (!OtcController.validDeal(d)) return false;
        if (!OtcOffer.LP_SELLS_MINIMA.equals(d.side)) return false;
        EthNet.Token usdt = net.token("USDT");
        if (usdt == null || c.tokenContract == null || !c.tokenContract.equalsIgnoreCase(usdt.address)) return false;  // MUST settle in real USDT
        if (c.receiver == null || !c.receiver.equalsIgnoreCase(myEth())) return false;                       // USDT to me
        if (!keyEq(c.minimaPublicKey, d.peerMinimaPk)) return false;                                         // mxUSDT to the agreed peer
        BigDecimal wantMinima = dec(d.amount), wantUsdt = dec(d.amount).multiply(dec(d.price));
        BigDecimal gotMinima = dec(EthWallet.format(c.requestAmount, 18, 18));                        // mxUSDT they request = what I lock
        BigDecimal gotUsdt = dec(EthWallet.format(c.amount, decimalsOf(c.tokenContract), 18));        // USDT they locked
        return gotMinima.signum() > 0 && gotUsdt.signum() > 0 && approxEq(gotMinima, wantMinima) && approxEq(gotUsdt, wantUsdt);
    }

    /** Verify an incoming Minima lock (instigator SELLS mxUSDT, I'm the LP buying) EXACTLY matches the agreed deal
     *  before I lock the USDT counter-leg: I must be the coin receiver, the USDT must go to the agreed eth, the
     *  token must be the agreed USDT, and both amounts must equal the negotiated terms. */
    /* package-private (not private) so unit tests can drive this OTC fund-safety boundary directly. */
    boolean otcVerifySell(JSONObject coin, OtcDb.Deal d, String reqTokenAddr) {
        if (!OtcController.validDeal(d)) return false;
        if (!OtcOffer.LP_BUYS_MINIMA.equals(d.side)) return false;
        if (!minima.activeToken().equalsIgnoreCase(coin.optString("tokenid", "0x00"))) return false;  // active-currency token only (OTC negotiated live in the active market)
        if (!keyEq(MinimaHtlc.stateAt(coin, 4), myMinimaPk)) return false;                            // mxUSDT to me
        String payEth = MinimaHtlc.stateAt(coin, 6);
        if (payEth == null || !payEth.equalsIgnoreCase(d.peerEthAddr)) return false;                  // I'll pay USDT to the agreed eth
        EthNet.Token usdt = net.token("USDT");
        if (usdt == null || reqTokenAddr == null || !reqTokenAddr.equalsIgnoreCase(usdt.address)) return false;  // agreed token
        BigDecimal wantMinima = dec(d.amount), wantUsdt = dec(d.amount).multiply(dec(d.price));
        BigDecimal gotMinima = dec(MinimaHtlc.coinAmount(coin));                    // mxUSDT they locked
        BigDecimal gotUsdt = dec(MinimaHtlc.stateAt(coin, 1));                        // USDT they request = what I lock
        return gotMinima.signum() > 0 && gotUsdt.signum() > 0 && approxEq(gotMinima, wantMinima) && approxEq(gotUsdt, wantUsdt);
    }

    private Order.Pair pairFor(String tokenAddr) {
        Order o = myOrder;
        EthNet.Token tk = net.tokenByAddress(tokenAddr);
        if (o == null || tk == null) return null;
        return o.pairs.get(tk.symbol);
    }

    /** Log a counterparty amount/token mismatch ONCE, as EV_MISMATCH — NEVER as EV_COLLECT (backport from
     *  atomix-mds 0.1.3). The claim gates on {@code haveCollect}, so the old EV_COLLECT log here let ANY third
     *  party permanently poison a victim's REAL claim with one hostile dust coin carrying the victim's active
     *  hash + receiver key and a wrong amount/token — a guaranteed mutual-refund grief for pennies. The
     *  once-guard also stops the per-poll event-table spam the old unguarded log produced. */
    void logMismatchOnce(String hash, String leg, String note) {
        if (!db.haveMismatch(hash)) {
            db.logEvent(hash, SwapDb.EV_MISMATCH, leg, "0", note);
            SwapLog.w("swap " + hash + " counter-leg REJECTED (" + leg + "): " + note);   // 0.1.41: was silent
        }
    }

    /** A human reason a swap refunded, for the notification subtext + log. Prefers a stored EV_MISMATCH note
     *  (the swap aborted on a counterparty amount/token mismatch); else by role: an INITIATOR's leg refunds
     *  because the counterparty never locked, a RESPONDER's counter-leg because the counterparty locked but
     *  never claimed (proven live 2026-08-27: a first-time buyer locked 438 USDT, never claimed the matching
     *  433.663366 mxUSDT counter-leg, and the bare "Timelock passed" left the operator diagnosing on-chain).
     *  Read-only; changes no refund decision (0.1.41, role-aware since 0.1.44). */
    String refundReason(String hash) {
        for (SwapDb.Event e : db.getEvents(hash))
            if (SwapDb.EV_MISMATCH.equals(e.event) && e.note != null && !e.note.isEmpty())
                return e.note;
        SwapDb.Swap sw = db.getSwap(hash);
        if (sw != null && "RESPONDER".equals(sw.role))
            return "the counterparty locked their side but never claimed yours before the timeout";
        return "the counterparty never locked their side before the timeout";
    }

    /** The tokenid of the currency a swap BOUGHT (from its buyToken label) — verifies the received Minima coin
     *  (backport from atomix-mds; fund gate: a maker could otherwise lock a WORTHLESS coloured token of the
     *  right AMOUNT, and amountTokenOk's currency-agnostic 'minima' literal would pass it). */
    static String expectedTokenId(SwapDb.Swap sw) {
        com.eurobuddha.atomix.TradingContext c =
                sw == null ? null : com.eurobuddha.atomix.TradingContext.forCoinLabel(sw.buyToken);
        return c != null ? c.tokenId : com.eurobuddha.atomix.TradingContext.active().tokenId;
    }

    /** Initiator's check that the counterparty locked at least what I asked, in the right token. */
    private boolean amountTokenOk(String[] req, String counterpartyAmountHuman, String tokenAddr, boolean ethLeg) {
        try {
            BigDecimal want = dec(req[0]);
            BigDecimal got = dec(counterpartyAmountHuman);
            if (want.compareTo(got) > 0) return false;                    // they locked less than I asked
            String reqToken = req[1] == null ? "" : req[1];
            if (reqToken.startsWith("ETH:")) reqToken = reqToken.substring(4);
            if (ethLeg) return reqToken.equalsIgnoreCase(tokenAddr);
            return reqToken.equalsIgnoreCase("minima") || reqToken.equalsIgnoreCase(tokenAddr);
        } catch (Exception e) { return false; }
    }

    // ============================================================ secret harvesting (Minima notify)

    /** Hashlocks of legs I locked as an ERC20→mxUSDT responder that are still waiting for the revealed secret. */
    private java.util.List<String> pendingSecretHashes() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (SwapDb.Swap s : db.allSwaps()) {
            if ("RESPONDER".equals(s.role) && "ERC20_TO_MINIMA".equals(s.direction)
                    && SwapDb.ST_LOCKED.equals(s.status) && db.getSecret(s.hash) == null) out.add(s.hash);
        }
        return out;
    }

    private void harvestNotifySecrets(JSONArray coins) {
        boolean changed = false;
        for (int i = 0; i < coins.length(); i++) {
            JSONObject coin = coins.optJSONObject(i);
            if (coin == null) continue;
            String hash = MinimaHtlc.stateAt(coin, 101);
            String secret = MinimaHtlc.stateAt(coin, 100);
            if (hash.isEmpty() || secret.isEmpty()) continue;
            if (db.getSwap(hash) == null) continue;                       // only my swaps
            if (!MinimaHtlc.verifyPreimage(secret, hash)) continue;       // FUND-SAFETY: reject forged notify preimages (anyone-can-write sink)
            if (db.insertSecret(hash, secret)) changed = true;
        }
        if (changed) { notifier.notify("Secret revealed", "Claiming your side of the swap"); notifier.onSwapsChanged(); }
    }

    // ============================================================ ETH allowance helpers

    /** Blocking (user-initiated start): approve MAX if needed and wait until the allowance lands. */
    private void ensureAllowanceBlocking(EthHtlc eth, String token, BigInteger needed) throws Exception {
        BigInteger cur = eth.allowance(token);
        if (cur.compareTo(needed) >= 0) return;
        // F4: Tether reverts a non-zero → non-zero approve (require value==0 || allowed==0). A residual sub-MAX
        // allowance — e.g. left by minimaSwap on the shared seed-derived wallet — must be zeroed first, or EVERY
        // approve reverts and the swap can never start. A fresh wallet (cur==0) skips straight to the MAX approve.
        if (cur.signum() > 0) {
            eth.approve(token, BigInteger.ZERO);
            for (int i = 0; i < 40 && eth.allowance(token).signum() != 0; i++) Thread.sleep(3000);
        }
        eth.approve(token, MAX_UINT);
        for (int i = 0; i < 40; i++) {                                    // up to ~2 min
            Thread.sleep(3000);
            if (eth.allowance(token).compareTo(needed) >= 0) return;
        }
        throw new Exception("Token approval not confirmed in time — try again");
    }

    /** Non-blocking (watcher auto-lock): if allowance is short, fire an approve and defer to a later cycle.
     *  Re-fires after a TTL so a dropped/failed approve doesn't strand the swap forever. */
    private boolean approveIfReady(EthHtlc eth, String token, BigInteger needed) throws Exception {
        BigInteger cur = eth.allowance(token);
        if (cur.compareTo(needed) >= 0) { approvePending.remove(token); approvePending.remove(token + "|0"); return true; }
        long now = System.currentTimeMillis();
        // F4: Tether can't go non-zero → non-zero. Zero a stale residual first (its own TTL key, so it doesn't
        // rate-limit against the MAX approve); the next cycle, with cur==0, fires the MAX approve.
        if (cur.signum() > 0) {
            Long z = approvePending.get(token + "|0");
            if (z == null || now - z > APPROVE_TTL_MS) { approvePending.put(token + "|0", now); eth.approve(token, BigInteger.ZERO); }
            return false;
        }
        Long sent = approvePending.get(token);
        if (sent == null || now - sent > APPROVE_TTL_MS) { approvePending.put(token, now); eth.approve(token, MAX_UINT); }
        return false;
    }

    // ============================================================ small helpers

    /** A coin addressed to my published swap identity (the key I post in orders + lock under). */
    private boolean isMyPublishKey(String pk) {
        return myMinimaPk != null && MinimaHtlc.normKey(pk).equals(MinimaHtlc.normKey(myMinimaPk));
    }
    /** A coin I own / can refund — owner is any of my 64 default keys (or my publish key before they load). */
    private boolean isMyOwnedKey(String pk) {
        String n = MinimaHtlc.normKey(pk);
        return (!n.isEmpty() && myPubkeys.contains(n)) || isMyPublishKey(pk);
    }

    /** Discovery look-back start: a {@code window} back normally, extended to lastEthScanned+1 after
     *  downtime, never older than {@code cap}, never below 0. */
    private long scanFrom(long ethBlock, long window, long cap) {
        long base = ethBlock - window;
        if (lastEthScanned >= 0) base = Math.min(base, lastEthScanned + 1);
        return Math.max(0, Math.max(cap, base));
    }

    private SwapDb.Swap baseSwap(String hash, String role, String dir,
                                 String sellTok, String sellAmt, String buyTok, String buyAmt, String cp) {
        SwapDb.Swap s = new SwapDb.Swap();
        s.hash = hash; s.role = role; s.direction = dir;
        s.sellToken = sellTok; s.sellAmount = sellAmt; s.buyToken = buyTok; s.buyAmount = buyAmt;
        s.counterparty = cp;
        return s;
    }

    private int decimalsOf(String tokenAddr) {
        EthNet.Token t = net.tokenByAddress(tokenAddr);
        return t == null ? 18 : t.decimals;
    }

    /** Strip the "[ETH:…]" / "[…]" wrapper off a Minima requesttoken state value. */
    private static String stripReqToken(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String s = raw;
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        if (s.startsWith("ETH:")) s = s.substring(4);
        return s;
    }

    private static BigInteger parseUnits(String human, int decimals) {
        return new BigDecimal(human).movePointRight(decimals).toBigInteger();
    }
    private static BigDecimal dec(String s) {
        try { return (s == null || s.isEmpty()) ? BigDecimal.ZERO : new BigDecimal(s); }
        catch (Exception e) { return BigDecimal.ZERO; }
    }
    private static int parseInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }
    /** MI-1: parse an HTLC timelock/block state field, returning -1 (not 0) on any non-integer or overflow. The
     *  refund path fails CLOSED on -1 — the old parseInt returned 0, which made an unparseable timelock read as
     *  already-expired and drove a doomed refund that burned a one-time key leaf on every retry. */
    private static long parseBlock(String s) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1; } }

    /** CR-2: submit to the io pool, swallowing rejection once it is shut down (Activity destroyed / Service
     *  stopped). {@code onReject} runs if the task could not be scheduled (e.g. to release an in-flight marker
     *  claimed just before the call). Replaces bare io.execute(), which threw RejectedExecutionException on the
     *  calling thread — often a node callback thread — and could take down the process. */
    private void submitIo(Runnable task) { submitIo(task, null); }
    private static final class IoTask implements Runnable {
        final Runnable task, onReject;
        final java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger();
        IoTask(Runnable task, Runnable onReject) { this.task = task; this.onReject = onReject; }
        public void run() {
            if (!state.compareAndSet(0, 1)) return;
            try { task.run(); } finally { state.set(2); }
        }
        void cancelQueued() { if (state.compareAndSet(0, 2) && onReject != null) onReject.run(); }
    }
    private void submitIo(Runnable task, Runnable onReject) {
        IoTask queued = new IoTask(task, onReject);
        if (io.isShutdown()) { queued.cancelQueued(); return; }
        try { io.execute(queued); }
        catch (java.util.concurrent.RejectedExecutionException e) { queued.cancelQueued(); }
    }
    private static long nowUnix() { return System.currentTimeMillis() / 1000L; }
}

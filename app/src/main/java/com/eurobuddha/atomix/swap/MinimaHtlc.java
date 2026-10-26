// VENDORED VERBATIM from apks/atomix 0.1.84 app/src/main/java/com/eurobuddha/atomix/swap/MinimaHtlc.java by scripts/vendor-atomix.sh — do not edit here; re-vendor.
package com.eurobuddha.atomix.swap;

import org.json.JSONObject;
import com.eurobuddha.atomix.SwapLog;
import com.eurobuddha.comms.NodeApi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Minima leg of the atomic swap — the native equivalent of the bridge MiniDapp's apiminima.js.
 *
 * The HTLC is a KISS script coin: a Minima coin locked with 7 PREVSTATE fields. It can be spent two
 * ways — CLAIM (the counterparty reveals the secret whose SHA2 equals the hashlock, and must pay a
 * 0.0001 "notify" coin to {@link #NOTIFY} so the reveal is observable on-chain) or REFUND (the owner
 * reclaims after the block timelock passes). Script + address + notify address are verbatim from the
 * upstream so coins are spend-compatible with the bridge MiniDapp.
 *
 * NodeApi delivers one JSONObject per command, so the multi-step claim/refund transactions are issued
 * as a *sequence* of single commands tied together by a shared txn id (Minima keeps the half-built txn
 * in memory until txnpost/txndelete).
 */
public final class MinimaHtlc {

    /** Verbatim from bridge dapp/js/scripts.js — changing a byte changes the address. */
    public static final String HTLC_SCRIPT =
            "LET version=1.2 LET owner=PREVSTATE(0) LET requestamount=PREVSTATE(1) LET requesttoken=PREVSTATE(2) "
          + "LET timelock=PREVSTATE(3) LET counterparty=PREVSTATE(4) LET hash=PREVSTATE(5) LET ownerethkey=PREVSTATE(6) "
          + "IF SIGNEDBY(owner) AND (@BLOCK GT timelock) THEN RETURN TRUE ENDIF LET secret=STATE(100) "
          + "ASSERT SIGNEDBY(counterparty) AND (SHA2(secret) EQ hash) ASSERT STATE(101) EQ hash "
          + "ASSERT STATE(102) EQ STRING(owner) ASSERT STATE(103) EQ STRING(counterparty) "
          + "RETURN VERIFYOUT(@INPUT 0xFFEEDD9999 0.0001 @TOKENID TRUE)";

    public static final String HTLC_ADDRESS = "MxG080CRJB1D4NHGRYGNF7Q52FK7023UM3FUUPVD1W1WCQZSA8MDQ25982N842G";
    public static final String NOTIFY = "0xFFEEDD9999";
    /** The two Minima-side traded assets AtomiX supports. The vault script + address are token-independent
     *  (unchanged), so PUBLISHING/liquidity/market-feed scans filter on {@link #activeToken} to isolate the
     *  ACTIVE currency's coins at the SAME shared HTLC_ADDRESS. SETTLEMENT scans (by unique hashlock / my key)
     *  deliberately DROP the token filter so an in-flight swap in the OTHER currency is never stranded when the
     *  user switches the active currency — claim()/refund() read each coin's own tokenid, so both settle. */
    public static final String USDT_TOKENID   = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90"; // mxUSDT (8dp, coloured)
    public static final String MINIMA_TOKENID = "0x00";                                                                 // native MINIMA (44dp)

    /** The token AtomiX is currently PUBLISHING/market-making in (the selected currency). Governs new locks,
     *  the ask-ladder liquidity, the market feed and balance display — NOT settlement (see class notes). */
    private volatile String activeToken = USDT_TOKENID;
    public void setActiveToken(String tokenid) { if (tokenid != null && !tokenid.isEmpty()) this.activeToken = tokenid; }
    public String activeToken() { return activeToken; }
    /** Grain the active-currency lock amount: mxUSDT quantizes DOWN to 6dp (the 1:1 ERC20-USDT grain); native
     *  MINIMA has no counter-leg grain so it passes through untouched. */
    private String maybeGrain(String amt) { return USDT_TOKENID.equals(activeToken) ? grain(amt) : amt; }
    public static final int MINIMA_BLOCK_TIME = 50;                 // seconds/block (upstream htlcvars.js)
    public static final int TIMELOCK_BLOCKS = (60 * 60 * 2) / MINIMA_BLOCK_TIME;   // 2h ≈ 144 blocks

    private final NodeApi node;
    private String myAddress;   // Mx… address (fromaddress + change recipient)
    private String myPubkey;    // 0x… public key (signkey + state owner)

    public MinimaHtlc(NodeApi node) { this.node = node; }

    public String myAddress() { return myAddress; }
    public String myPubkey() { return myPubkey; }
    public boolean ready() { return myAddress != null && myPubkey != null; }

    public interface SetupCb { void ok(String address, String pubkey); void err(String msg); }
    public interface SecretCb { void ok(String secret, String hash); void err(String msg); }
    public interface BlockCb { void ok(int block); void err(String msg); }
    public interface PostCb { void ok(String txpowid); void err(String msg); }
    public interface KeysCb { void ok(java.util.Set<String> pubkeys); void err(String msg); }

    // ---- setup: register the HTLC script + resolve a STABLE address/pubkey ----

    /**
     * Register the HTLC script and establish my swap identity. A Minima node has 64 permanent default
     * keys and {@code getaddress} returns a *different* one each call — so the identity MUST be persisted
     * once and reused, or discovery/resume/refund break across restarts. Pass the previously-saved
     * {@code savedAddress}/{@code savedPubkey} to reuse them; pass null on first run to pick one (returned
     * via {@code cb.ok} for the caller to persist). The chosen key is one of the 64 the node controls
     * forever, so it survives restarts.
     */
    public void setup(final String savedAddress, final String savedPubkey, final SetupCb cb) {
        cmd("newscript script:\"" + HTLC_SCRIPT + "\" trackall:false", r1 -> {
            if (savedAddress != null && !savedAddress.isEmpty() && savedPubkey != null && !savedPubkey.isEmpty()) {
                // Verify the node still OWNS the persisted identity, then ADOPT IT EITHER WAY and let
                // IdentityWatch halt the app if it is orphaned.
                //
                // 0.1.18 re-picked a fresh identity here (self-heal). That is now deliberately gone (0.1.19,
                // user decision): silently swapping the identity hides the thing that actually matters — that
                // coins were routed to a key the node can no longer derive, and that only a rescue + clean
                // reinstall puts every piece (identity, ETH wallet, swap DB) back in line. Adopting the saved
                // key while HALTED is the safe combination: settlement of in-flight swaps still runs against
                // the key those swaps were made with, while no new liability can be taken on.
                loadMyKeys(new KeysCb() {
                    @Override public void ok(java.util.Set<String> keys) {
                        myAddress = savedAddress; myPubkey = savedPubkey;
                        // empty = node busy/locked: cannot verify, never treat that as orphaned.
                        if (!keys.isEmpty() && !keys.contains(normKey(savedPubkey)))
                            SwapLog.w("IDENTITY ORPHANED: node does not own persisted identity " + savedPubkey
                                    + " (" + keys.size() + " node keys checked) — halting; reinstall required");
                        cb.ok(myAddress, myPubkey);
                    }
                    @Override public void err(String m) {   // cannot verify now — trust it; IdentityWatch re-checks
                        myAddress = savedAddress; myPubkey = savedPubkey;
                        cb.ok(myAddress, myPubkey);
                    }
                });
                return;
            }
            pickFreshIdentity(cb);
        }, cb::err);
    }

    /** First run only (nothing persisted yet): take one of the node's default keys as our identity. */
    private void pickFreshIdentity(final SetupCb cb) {
        cmd("getaddress", r2 -> {
            JSONObject resp = r2.optJSONObject("response");
            if (resp == null) { cb.err("getaddress returned nothing"); return; }
            myAddress = resp.optString("miniaddress", resp.optString("address", ""));
            myPubkey  = resp.optString("publickey", "");
            if (myAddress.isEmpty() || myPubkey.isEmpty()) { cb.err("Could not resolve my Minima address/key"); return; }
            cb.ok(myAddress, myPubkey);
        }, cb::err);
    }

    /** All 64 of my node's public keys (normalised), so refund/owner matching works for a coin locked
     *  under any default key — not just the one persisted swap identity. */
    public void loadMyKeys(KeysCb cb) {
        cmd("keys", r -> {
            java.util.Set<String> out = new java.util.HashSet<>();
            JSONObject resp = r.optJSONObject("response");
            org.json.JSONArray arr = resp == null ? null : resp.optJSONArray("keys");
            if (arr == null && r.opt("response") instanceof org.json.JSONArray) arr = (org.json.JSONArray) r.opt("response");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                JSONObject k = arr.optJSONObject(i);
                if (k != null) { String pk = k.optString("publickey", ""); if (!pk.isEmpty()) out.add(normKey(pk)); }
            }
            cb.ok(out);
        }, cb::err);
    }

    /** Canonical form of a Minima public key for set membership: no 0x, upper-case. */
    public static String normKey(String pk) {
        if (pk == null) return "";
        String s = pk.trim().toUpperCase();
        return s.startsWith("0X") ? s.substring(2) : s;
    }

    /** FUND-SAFETY: verify a candidate preimage hashes (SHA-256) to the lock BEFORE it is pinned. The NOTIFY sink
     *  is anyone-can-write and SwapDb.insertSecret is first-write-wins, so an unverified harvest lets a forged
     *  preimage poison the secret store and permanently block MY claim (fund loss). SHA-256 over the preimage bytes
     *  is byte-identical to the node's `random type:sha2` hashlock (verified). Returns true iff SHA-256(secret)==hash. */
    public static boolean verifyPreimage(String secret, String hash) {
        if (secret == null || hash == null || secret.isEmpty() || hash.isEmpty()) return false;
        try {
            String s = normKey(secret);                       // strip 0x, upper — hex nibbles only
            if ((s.length() & 1) != 0) return false;
            byte[] pre = new byte[s.length() / 2];
            for (int i = 0; i < pre.length; i++) pre[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
            byte[] dig = java.security.MessageDigest.getInstance("SHA-256").digest(pre);
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02X", b));
            return sb.toString().equals(normKey(hash));
        } catch (Exception e) { return false; }
    }

    /** Strict hex guard for any value INTERPOLATED into a node command string. Minima commands are flat,
     *  space-separated {@code key:value} tokens handed verbatim to the node (see {@link #cmd}), so a space —
     *  or any non-hex byte — in a peer- or on-chain-sourced value would inject an extra command parameter into
     *  a fund-moving command (e.g. a crafted state value {@code "0x.. tokenid:EVIL"}). Accepts an optional
     *  0x/0X prefix; rejects null/empty and anything outside {@code [0-9A-Fa-f]}. Real pubkeys, hashlocks,
     *  coinids, token ids and ETH keys are ALWAYS strict hex, so this only ever rejects malformed/hostile input.
     *  Package-private so the swap-package tests can assert it directly. */
    static boolean isHex(String v) {
        if (v == null) return false;
        String s = v.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    /** Decimal-amount guard for values interpolated into node commands (a lock/refund amount read from an
     *  on-chain coin or a peer order). Rejects anything carrying a space/letter that could inject a parameter. */
    static boolean isDecimal(String v) {
        return v != null && v.trim().matches("[0-9]+(\\.[0-9]+)?");
    }

    // ---- helpers ----

    public void currentBlock(BlockCb cb) {
        cmd("block", r -> {
            JSONObject resp = r.optJSONObject("response");
            cb.ok(resp == null ? 0 : resp.optInt("block", 0));
        }, cb::err);
    }

    /** Generate a fresh 32-byte secret + its SHA2 (SHA256) hashlock, the same way the bridge does. */
    public void generateSecret(SecretCb cb) {
        cmd("random type:sha2", r -> {
            JSONObject resp = r.optJSONObject("response");
            if (resp == null) { cb.err("random returned nothing"); return; }
            String secret = resp.optString("random", "");
            String hash = resp.optString("hashed", "");
            if (secret.isEmpty() || hash.isEmpty()) { cb.err("random missing secret/hash"); return; }
            cb.ok(secret, hash);
        }, cb::err);
    }

    // ---- LOCK: send a coin to the HTLC with the 7 PREVSTATE fields ----

    /**
     * @param amount        mxUSDT amount to lock (the owner's side)
     * @param requestAmount the ERC20 amount requested in return (string)
     * @param reqToken      the ERC20 token contract address (the script stores "[reqToken]")
     * @param receiverPubkey counterparty's Minima public key (who can claim with the secret)
     * @param ownerEthKey   the owner's ETH address
     * @param hashlock      SHA2(secret)
     * @param timelockBlock absolute Minima block after which the owner can refund
     * @param otc           "TRUE"/"FALSE"
     */
    public void lock(String amount, String requestAmount, String reqToken, String receiverPubkey,
                     String ownerEthKey, String hashlock, int timelockBlock, String otc, PostCb cb) {
        if (!ready()) { cb.err("Minima wallet not ready"); return; }
        // MA-19: every value below is interpolated into the `send` command; reject non-hex/non-decimal so a
        // hostile peer's order (receiverPubkey/ownerEthKey come from the order book) can't inject parameters.
        if (!isHex(receiverPubkey)) { cb.err("lock: non-hex counterparty key"); return; }
        if (!isHex(hashlock))       { cb.err("lock: non-hex hashlock"); return; }
        if (!isHex(ownerEthKey))    { cb.err("lock: non-hex owner ETH key"); return; }
        // reqToken is either an ETH token contract address (hex) or the protocol's currency-agnostic literal
        // "minima" (the mxUSDT counter-leg marker) — BOTH are our own values, never peer input. Accept both;
        // reject only genuinely malformed values. (0.1.23 wrongly required hex here, which silently rejected
        // every "minima" counter-leg lock and broke all ERC20->mxUSDT buys.)
        if (!isHex(reqToken) && !"minima".equalsIgnoreCase(reqToken.trim())) { cb.err("lock: bad request token"); return; }
        if (!isDecimal(requestAmount)) { cb.err("lock: non-decimal request amount"); return; }
        String lockAmt = maybeGrain(amount);
        if (!isDecimal(lockAmt))    { cb.err("lock: non-decimal amount"); return; }
        JSONObject state = new JSONObject();
        try {
            state.put("0", myPubkey);
            state.put("1", requestAmount);
            state.put("2", "[" + reqToken + "]");
            state.put("3", timelockBlock);
            state.put("4", receiverPubkey);
            state.put("5", hashlock);
            state.put("6", ownerEthKey);
            state.put("7", otc);
        } catch (Exception e) { cb.err("state build: " + e.getMessage()); return; }

        // Fund from ANY of my 64 default addresses (no fromaddress/signkey constraint) — the node has no
        // dedicated "bridge wallet" here, so pinning to one address would fail when funds sit elsewhere.
        // The refund owner is set explicitly via state[0]=myPubkey, so the coin stays mine to reclaim.
        String send = "send amount:" + lockAmt + " mine:true address:" + HTLC_ADDRESS
                + " state:" + state.toString() + " tokenid:" + activeToken;
        // M1: a `send` SIGNS on the node, so it must go through SignGate like every other signing path — a
        // user-initiated lock can otherwise sign a one-time key leaf concurrently with an autonomous claim/refund.
        runSigned(send, r -> {
            JSONObject resp = r.optJSONObject("response");
            cb.ok(resp == null ? "" : resp.optString("txpowid", ""));
        }, cb::err);
    }

    /** Lock the HTLC counter-leg from MULTIPLE pinned coins (combined), so the responder can fill a deal larger than
     *  any single coin — the way a wallet {@code send} auto-selects UTXOs, but with the coins pinned by the caller
     *  (no node coin-selection, so concurrent burst locks can't double-select). One {@code txninput} per coinid,
     *  one HTLC output ({@code amount}), change ({@code totalSelected − amount}) → myAddress. Same state[0..7],
     *  record-before-broadcast ordering and "POSTED:"-tag safety as the single-coin path. */
    public void lockFromCoins(List<String> coinids, String totalSelected, String amount, String requestAmount,
                              String reqToken, String receiverPubkey, String ownerEthKey, String hashlock,
                              int timelockBlock, String otc, PostCb cb) {
        lockFromCoins(coinids, totalSelected, amount, requestAmount, reqToken, receiverPubkey,
                ownerEthKey, hashlock, timelockBlock, otc, null, cb);
    }

    /** New responder liabilities retain their authorization through SignGate and the node worker. */
    public void lockFromCoins(List<String> coinids, String totalSelected, String amount, String requestAmount,
                              String reqToken, String receiverPubkey, String ownerEthKey, String hashlock,
                              int timelockBlock, String otc, java.util.function.BooleanSupplier authorized, PostCb cb) {
        final String token = activeToken;
        final java.util.function.BooleanSupplier current = authorized == null ? null
                : () -> token.equals(activeToken) && authorized.getAsBoolean();
        if (!ready()) { cb.err("Minima wallet not ready"); return; }
        if (coinids == null || coinids.isEmpty()) { cb.err("no coins to lock"); return; }
        // MA-19: reject non-hex/non-decimal before building any txn command (receiverPubkey can come from an
        // on-chain event / peer order; each coinid pins an input).
        if (!isHex(receiverPubkey)) { cb.err("lock: non-hex counterparty key"); return; }
        if (!isHex(hashlock))       { cb.err("lock: non-hex hashlock"); return; }
        if (!isHex(ownerEthKey))    { cb.err("lock: non-hex owner ETH key"); return; }
        // reqToken is either an ETH token contract address (hex) or the protocol's currency-agnostic literal
        // "minima" (the mxUSDT counter-leg marker) — BOTH are our own values, never peer input. Accept both;
        // reject only genuinely malformed values. (0.1.23 wrongly required hex here, which silently rejected
        // every "minima" counter-leg lock and broke all ERC20->mxUSDT buys.)
        if (!isHex(reqToken) && !"minima".equalsIgnoreCase(reqToken.trim())) { cb.err("lock: bad request token"); return; }
        if (!isDecimal(requestAmount)) { cb.err("lock: non-decimal request amount"); return; }
        for (String cid : coinids) if (!isHex(cid)) { cb.err("lock: non-hex coinid"); return; }
        amount = maybeGrain(amount);                     // active-currency trade grain — the change output below follows from it
        String change = subtract(totalSelected, amount);
        if (!isDecimal(amount)) { cb.err("lock: non-decimal amount"); return; }
        if (positive(change) && !isDecimal(change)) { cb.err("lock: non-decimal change"); return; }
        String id = txnId();
        List<String> seq = new ArrayList<>();
        seq.add("txncreate id:" + id);
        for (String cid : coinids) seq.add("txninput id:" + id + " coinid:" + cid);
        seq.add("txnstate id:" + id + " port:0 value:" + myPubkey);
        seq.add("txnstate id:" + id + " port:1 value:" + requestAmount);
        seq.add("txnstate id:" + id + " port:2 value:[" + reqToken + "]");
        seq.add("txnstate id:" + id + " port:3 value:" + timelockBlock);
        seq.add("txnstate id:" + id + " port:4 value:" + receiverPubkey);
        seq.add("txnstate id:" + id + " port:5 value:" + hashlock);
        seq.add("txnstate id:" + id + " port:6 value:" + ownerEthKey);
        seq.add("txnstate id:" + id + " port:7 value:" + otc);
        seq.add("txnoutput id:" + id + " amount:" + amount + " address:" + HTLC_ADDRESS + " tokenid:" + token + " storestate:true");
        if (positive(change)) seq.add("txnoutput id:" + id + " amount:" + change + " address:" + myAddress + " tokenid:" + token + " storestate:false");
        seq.add("txnsign id:" + id + " publickey:auto");
        addValidation(seq, id);
        // Split the broadcast (txnpost) from the build: a build failure PROVABLY didn't broadcast (caller may
        // retry); a txnpost failure MAY have broadcast with a lost response, so it's tagged "POSTED:" and the
        // caller must NOT retry (the mxUSDT leg has no on-chain hash-uniqueness → a retry would double-lock).
        runSeq(seq, current, built -> cmd("txnpost id:" + id + " mine:true txndelete:true", current,
                posted -> cb.ok(txpowOf(posted)),
                e -> { deleteTxn(id); cb.err(NodeApi.ERR_COMMAND_CHANGED.equals(e) ? e : "POSTED:" + e); }),
            e -> { deleteTxn(id); cb.err(e); });
    }

    // Measured MxUSD/native row costs with a margin below the observed fatal 270,864-byte parcel.
    public static final int PARCEL_CHAR_BUDGET = 60_000;
    public static final int CHARS_PER_TOKEN_COIN = 1_120;
    public static final int CHARS_PER_NATIVE_COIN = 300;
    public static final String ERR_TOO_MANY_COINS = "TOO_MANY_COINS";

    public static int maxSafeCoinRows(String tokenid) {
        return PARCEL_CHAR_BUDGET / (MINIMA_TOKENID.equals(tokenid)
                ? CHARS_PER_NATIVE_COIN : CHARS_PER_TOKEN_COIN);
    }

    public static final class TokenBalance {
        public final int coins;
        public final String sendable, confirmed, unconfirmed;
        TokenBalance(int coins, String sendable, String confirmed, String unconfirmed) {
            this.coins = coins; this.sendable = sendable;
            this.confirmed = confirmed; this.unconfirmed = unconfirmed;
        }
    }

    /** Fresh, small balance reply. Missing/malformed fields must never authorise a wallet coin read. */
    public void tokenBalance(Consumer<TokenBalance> ok, Consumer<String> err) {
        tokenBalance(activeToken, ok, err);
    }

    private void tokenBalance(String token, Consumer<TokenBalance> ok, Consumer<String> err) {
        cmd("balance tokenid:" + token, r -> {
            final TokenBalance balance;
            try {
                if (!r.getBoolean("status")) throw new IllegalArgumentException("unsuccessful balance");
                Object resp = r.get("response");
                JSONObject row;
                if (resp instanceof org.json.JSONArray) {
                    org.json.JSONArray rows = (org.json.JSONArray) resp;
                    // The node legitimately returns [] when this wallet has none of the requested token.
                    if (rows.length() == 0) { ok.accept(new TokenBalance(0, "0", "0", "0")); return; }
                    if (rows.length() != 1) throw new IllegalArgumentException("multiple token balances");
                    row = rows.getJSONObject(0);
                } else if (resp instanceof JSONObject) row = (JSONObject) resp;
                else throw new IllegalArgumentException("missing token balance");
                if (row.has("tokenid") && !token.equalsIgnoreCase(row.getString("tokenid")))
                    throw new IllegalArgumentException("wrong token balance");
                int count = new java.math.BigDecimal(row.get("coins").toString()).intValueExact();
                if (count < 0) throw new IllegalArgumentException("negative coin count");
                String sendable = balanceAmount(row, "sendable");
                String confirmed = balanceAmount(row, "confirmed");
                String unconfirmed = balanceAmount(row, "unconfirmed");
                balance = new TokenBalance(count, sendable, confirmed, unconfirmed);
            } catch (Exception bad) { err.accept("balance: invalid reply (" + bad.getMessage() + ")"); return; }
            ok.accept(balance);
        }, err);
    }

    private static String balanceAmount(JSONObject row, String field) throws org.json.JSONException {
        java.math.BigDecimal value = new java.math.BigDecimal(row.get(field).toString());
        if (value.signum() < 0) throw new IllegalArgumentException("negative " + field);
        return value.toPlainString();
    }

    private void guardedCoinRead(String token, String command, Consumer<org.json.JSONArray> ok, Consumer<String> err) {
        guardedCoinRead(token, command, ok, err, null);
    }

    private void guardedCoinRead(String token, String command, Consumer<org.json.JSONArray> ok, Consumer<String> err,
                                 Consumer<TokenBalance> oversized) {
        tokenBalance(token, balance -> {
            int cap = maxSafeCoinRows(token);
            if (balance.coins > cap) {
                if (oversized != null) { oversized.accept(balance); return; }
                err.accept(ERR_TOO_MANY_COINS + ": " + balance.coins + " coins, safe limit " + cap);
                return;
            }
            if (!token.equals(activeToken)) { err.accept("Trading currency changed; retry"); return; }
            cmd(command, r -> {
                if (!token.equals(activeToken)) { err.accept("Trading currency changed; retry"); return; }
                Object resp = r.opt("response");
                if (!(resp instanceof org.json.JSONArray)) { err.accept("coins: invalid reply"); return; }
                ok.accept((org.json.JSONArray) resp);
            }, err);
        }, err);
    }

    /** Spendable active-token coins. Preflight is mandatory: legacy nodes can kill the app delivering rows. */
    public void myFreeCoins(Consumer<org.json.JSONArray> ok, Consumer<String> err) {
        String token = activeToken;
        guardedCoinRead(token, "coins relevant:true sendable:true tokenid:" + token + " coinage:1 checkmempool:true", ok, err);
    }

    /** Diagnostic wallet read uses the same preflight and compact state representation. */
    public void myRelevantCoins(Consumer<org.json.JSONArray> ok, Consumer<String> err) {
        String token = activeToken;
        guardedCoinRead(token, "coins relevant:true tokenid:" + token + " simplestate:true", ok, err);
    }

    public void myRelevantCoins(Consumer<org.json.JSONArray> ok, Consumer<String> err, Consumer<TokenBalance> oversized) {
        String token = activeToken;
        guardedCoinRead(token, "coins relevant:true tokenid:" + token + " simplestate:true", ok, err, oversized);
    }

    /** Is there any UNCONFIRMED native mxUSDT in my wallet (a split/lock/payment still settling)? The chain's
     *  unconfirmed pool is global, so this is a cross-process check — it stops a second engine (after a
     *  foreground↔background handoff) from re-issuing a split whose coins from the first engine haven't landed yet. */
    public void hasPendingMinima(Consumer<Boolean> ok, Consumer<String> err) {
        tokenBalance(b -> ok.accept(new java.math.BigDecimal(b.unconfirmed).signum() > 0), err);
    }

    /** Split my own coins into {@code count} equal coins totalling {@code totalAmount} mxUSDT, in ONE tx, from
     *  CONFIRMED inputs only (coinage:1) — so a multi-tranche ask ladder has enough separately-spendable coins to
     *  lock every leg of a sweep concurrently (native {@code send split:} sends to my own address). */
    public void splitCoins(int count, String totalAmount, PostCb cb) {
        String send = "send amount:" + totalAmount + " address:" + myAddress
                + " tokenid:" + activeToken + " split:" + count + " coinage:1 mine:true";
        // M1: `send split:` signs on the node → route through SignGate (same reason as lock()).
        runSigned(send, r -> {
            JSONObject resp = r.optJSONObject("response");
            cb.ok(resp == null ? "" : resp.optString("txpowid", ""));
        }, cb::err);
    }

    /** One manual self-send, signed through the same gate as lock/split. */
    public void consolidateCoins(int maxCoins, PostCb cb) {
        if (maxCoins < 3 || maxCoins > 20) { cb.err("Consolidate requires 3–20 inputs"); return; }
        runSigned(consolidateCommand(activeToken, maxCoins), r -> cb.ok(txpowOf(r)), cb::err);
    }

    /** The node constructs the transaction without signing or posting it. */
    public void previewConsolidate(int maxCoins, Consumer<JSONObject> ok, Consumer<String> err) {
        if (maxCoins < 3 || maxCoins > 20) { err.accept("Consolidate requires 3–20 inputs"); return; }
        cmd(consolidateCommand(activeToken, maxCoins) + " dryrun:true", ok, err);
    }

    private static String consolidateCommand(String token, int maxCoins) {
        return "consolidate tokenid:" + token + " coinage:3 maxcoins:" + maxCoins + " maxsigs:5";
    }

    // ---- CLAIM: counterparty reveals the secret + pays the notify coin ----

    /** coin = the HTLC coin JSON (needs coinid, tokenid, amount, state[]). I am the receiver (state[4]). */
    public void claim(JSONObject coin, String hash, String secret, PostCb cb) {
        if (!ready()) { cb.err("Minima wallet not ready"); return; }
        String coinid = coin.optString("coinid", "");
        if (coinid.isEmpty()) { cb.err("claim: coin has no coinid"); return; }   // fail safe — never build a spend with an empty input
        String tokenid = coin.optString("tokenid", "0x00");
        String amount = MinimaHtlc.coinAmount(coin);
        String owner = stateAt(coin, 0);
        String receiver = stateAt(coin, 4);
        // MA-19: coinid/tokenid/owner/receiver come from a coin at the anyone-can-write shared HTLC address, and
        // secret is harvested from the anyone-can-write NOTIFY sink — reject non-hex/non-decimal before any command.
        if (!isHex(coinid))                    { cb.err("claim: non-hex coinid"); return; }
        if (!isHex(tokenid))                   { cb.err("claim: non-hex tokenid"); return; }
        if (!isHex(owner) || !isHex(receiver)) { cb.err("claim: non-hex coin state key"); return; }
        if (!isHex(secret) || !isHex(hash))    { cb.err("claim: non-hex secret/hash"); return; }
        if (!isDecimal(amount))                { cb.err("claim: non-decimal coin amount"); return; }
        String change = subtract(amount, "0.0001");
        String id = txnId();

        List<String> seq = new ArrayList<>();
        seq.add("txncreate id:" + id);
        seq.add("txninput id:" + id + " coinid:" + coinid);
        // notify coin MUST be output 0 (the script's VERIFYOUT(@INPUT 0xFFEEDD9999 ...))
        seq.add("txnoutput id:" + id + " tokenid:" + tokenid + " amount:0.0001 address:" + NOTIFY);
        // skip the change output for a dust-only lock (amount == 0.0001 → change == 0)
        if (positive(change)) seq.add("txnoutput id:" + id + " tokenid:" + tokenid + " amount:" + change + " address:" + myAddress);
        seq.add("txnstate id:" + id + " port:100 value:" + secret);
        seq.add("txnstate id:" + id + " port:101 value:" + hash);
        seq.add("txnstate id:" + id + " port:102 value:[" + owner + "]");
        seq.add("txnstate id:" + id + " port:103 value:[" + receiver + "]");
        // sign with the coin's receiver key (the counterparty the script requires) — one of my 64 defaults.
        seq.add("txnsign id:" + id + " publickey:" + receiver);
        addValidation(seq, id);
        seq.add("txnpost id:" + id + " mine:true txndelete:true");
        runSeq(seq, last -> cb.ok(txpowOf(last)), e -> { deleteTxn(id); cb.err(e); });
    }

    // ---- REFUND: owner reclaims after the timelock ----

    public void refund(JSONObject coin, PostCb cb) {
        if (!ready()) { cb.err("Minima wallet not ready"); return; }
        String coinid = coin.optString("coinid", "");
        if (coinid.isEmpty()) { cb.err("refund: coin has no coinid"); return; }   // fail safe — never build a spend with an empty input
        String tokenid = coin.optString("tokenid", "0x00");
        String amount = MinimaHtlc.coinAmount(coin);
        String owner = stateAt(coin, 0);                    // the script's refund signer = state[0]
        // MA-19: coinid/tokenid/owner/amount come from a coin at the anyone-can-write shared HTLC address —
        // reject non-hex/non-decimal before building the refund command (owner is interpolated into txnsign).
        if (!isHex(coinid))     { cb.err("refund: non-hex coinid"); return; }
        if (!isHex(tokenid))    { cb.err("refund: non-hex tokenid"); return; }
        if (!isHex(owner))      { cb.err("refund: non-hex owner key"); return; }
        if (!isDecimal(amount)) { cb.err("refund: non-decimal coin amount"); return; }
        String id = txnId();

        List<String> seq = new ArrayList<>();
        seq.add("txncreate id:" + id);
        seq.add("txninput id:" + id + " coinid:" + coinid);
        seq.add("txnoutput id:" + id + " tokenid:" + tokenid + " amount:" + amount + " address:" + myAddress);
        // sign with the coin's owner key (SIGNEDBY(owner)) — one of my 64 defaults, whichever locked it.
        seq.add("txnsign id:" + id + " publickey:" + owner);
        addValidation(seq, id);
        seq.add("txnpost id:" + id + " mine:true txndelete:true");
        runSeq(seq, last -> cb.ok(txpowOf(last)), e -> { deleteTxn(id); cb.err(e); });
    }

    /**
     * EVERY currently-open lock at the shared HTLC address, network-wide (the market data feed). The address is
     * shared/unowned, so {@code relevant:false} returns 0 until tracked — coinnotify-add it first (idempotent),
     * then query BARE with simplestate. Bounded by {@code depth} (a heavy reply on a busy shared address can
     * crash the node over the IPC). Includes the upstream miniSwap dapp's locks (same contract).
     */
    public void scanAllHtlcCoins(int coinageMin, int depth, Consumer<org.json.JSONArray> ok, Consumer<String> err) {
        cmd("coinnotify action:add address:" + HTLC_ADDRESS, r -> doScanAllHtlc(coinageMin, depth, ok, err), e -> doScanAllHtlc(coinageMin, depth, ok, err));
    }

    private void doScanAllHtlc(int coinageMin, int depth, Consumer<org.json.JSONArray> ok, Consumer<String> err) {
        cmd("coins coinage:" + coinageMin + " tokenid:" + activeToken + " simplestate:true depth:" + depth + " address:" + HTLC_ADDRESS, r -> {
            Object resp = r.opt("response");
            ok.accept(resp instanceof org.json.JSONArray ? (org.json.JSONArray) resp : new org.json.JSONArray());
        }, err);
    }

    /** Find HTLC coin(s) carrying a specific hashlock (state[5]==hash) via the reliable coinnotify-add +
     *  state-filter path. Discovers a counterparty's mxUSDT leg — which we only RECEIVE (state[4]), so the
     *  node's one-shot relevant:true set can miss it (the second-leg-of-a-sweep bug) — and confirms our own
     *  lock. The server-side state: filter returns only our coin(s), so it stays cheap at any global volume. */
    public void scanHtlcByHash(String hash, int coinageMin, int depth, Consumer<org.json.JSONArray> ok, Consumer<String> err) {
        scanHtlcByState(hash, coinageMin, depth, ok, err);
    }

    /** Per-hash scan for the REFUND sweep: same filter, but asks the node to fall through into the MegaMMR once
     *  the TxPoW tree runs out. `coins` accepts megammr and downgrades it itself — coins.java does
     *  {@code checkmegammr = GeneralParams.IS_MEGAMMR} — so a node not run with -megammr silently ignores it and
     *  this behaves exactly like scanHtlcByHash. On a MegaMMR node a coin I locked is findable at ANY age, which
     *  is the only way to refund a lock older than the ~1024-block tree. */
    public void scanHtlcByHashDeep(String hash, int coinageMin, int depth, Consumer<org.json.JSONArray> ok, Consumer<String> err) {
        cmd("coinnotify action:add address:" + HTLC_ADDRESS,
            r -> doScanHtlcByState(hash, coinageMin, depth, true, ok, err),
            e -> doScanHtlcByState(hash, coinageMin, depth, true, ok, err));
    }

    /** All open HTLC coins carrying MY key (owner state[0] for coins I locked, or receiver state[4] for coins
     *  locked to me) — the bounded, relevance-independent replacement for the old relevant:true scan. The
     *  server-side state: filter keeps the reply small even though the HTLC address is a global shared sink. */
    public void scanMyHtlcByKey(String myPubkey, int coinageMin, int depth, Consumer<org.json.JSONArray> ok, Consumer<String> err) {
        scanHtlcByState(myPubkey, coinageMin, depth, ok, err);
    }

    private void scanHtlcByState(String value, int coinageMin, int depth, Consumer<org.json.JSONArray> ok, Consumer<String> err) {
        cmd("coinnotify action:add address:" + HTLC_ADDRESS,
            r -> doScanHtlcByState(value, coinageMin, depth, false, ok, err),
            e -> doScanHtlcByState(value, coinageMin, depth, false, ok, err));
    }

    private void doScanHtlcByState(String value, int coinageMin, int depth, boolean megammr, Consumer<org.json.JSONArray> ok, Consumer<String> err) {
        if (!isHex(value)) { err.accept("scan: non-hex state value"); return; }   // MA-19: value is interpolated into the coins command
        // SETTLEMENT scan: NO tokenid filter. The state: value (a unique hashlock, or my pubkey) already scopes the
        // reply, so this stays cheap; and dropping the token filter is what lets a claim/refund find an in-flight
        // swap's coin in EITHER currency after the user switched the active currency. claim()/refund() read the
        // coin's own tokenid, so both mxUSDT and native-MINIMA legs settle correctly.
        cmd("coins coinage:" + coinageMin + " simplestate:true state:" + normKey(value)
          + " address:" + HTLC_ADDRESS + " depth:" + depth + (megammr ? " megammr:true" : ""), r -> {
            Object resp = r.opt("response");
            ok.accept(resp instanceof org.json.JSONArray ? (org.json.JSONArray) resp : new org.json.JSONArray());
        }, err);
    }

    // (removed scanMyHtlcCoins: relevant:true reads the node's one-shot block-time relevance set, which is
    //  unreliable for a coin I only RECEIVE — it missed the 2nd+ legs of a sweep. All callers now use the
    //  coinnotify-add + state-filter scans above: scanHtlcByHash (per hash) and scanAllHtlcCoins (bare).)

    /**
     * Scan the notify address for revealed-secret coins (the 0.0001 coins a claim forces to {@link #NOTIFY}).
     * The ERC20→mxUSDT responder reads the secret here. Bounded by {@code depth} — the address is global,
     * so we never query it unbounded; the caller filters by the hashlocks it actually cares about.
     */
    /**
     * Find the revealed-secret notify coin for ONE hashlock. The notify address is a SHARED, global sink —
     * EVERY bridge claim on the network drops a dust coin here forever — so we must (a) {@code coinnotify}-add
     * it (else a shared address returns 0 coins until tracked) and (b) filter by {@code state:<hash>} so the
     * node returns only OUR coin (the claim writes the hashlock to state[101]), not the whole address. Without
     * the state filter a busy mainnet could return a multi-MB reply and crash Minima Core over the IPC.
     */
    public void scanNotifySecret(String hash, int depth, Consumer<org.json.JSONArray> ok, Consumer<String> err) {
        cmd("coinnotify action:add address:" + NOTIFY, r -> doScanNotify(hash, depth, ok, err), e -> doScanNotify(hash, depth, ok, err));
    }

    private void doScanNotify(String hash, int depth, Consumer<org.json.JSONArray> ok, Consumer<String> err) {
        if (!isHex(hash)) { err.accept("scan: non-hex hash"); return; }   // MA-19: hash is interpolated into the coins command
        // The node stores state hex UPPER-CASE ("0x259C…") and matches `state:` case-sensitively (a .contains).
        // normKey → UPPER-CASE, no 0x — a guaranteed substring of the stored value regardless of the caller's
        // case (SwapDb keys are lower-case). Passing the raw lower-case hash here would match NOTHING and
        // re-strand the maker's USDT — the exact bug coinnotify-add fixed.
        cmd("coins simplestate:true depth:" + depth + " state:" + normKey(hash) + " address:" + NOTIFY, r -> {  // SETTLEMENT: no token filter (hash is unique across currencies)
            Object resp = r.opt("response");
            ok.accept(resp instanceof org.json.JSONArray ? (org.json.JSONArray) resp : new org.json.JSONArray());
        }, err);
    }

    // ---- command plumbing ----

    private void cmd(String command, Consumer<JSONObject> ok, Consumer<String> err) {
        cmd(command, null, ok, err);
    }
    private void cmd(String command, java.util.function.BooleanSupplier authorized,
                     Consumer<JSONObject> ok, Consumer<String> err) {
        NodeApi.Cb callback = new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!j.optBoolean("status", true)) { err.accept(shortCmd(command) + ": " + j.optString("error", j.optString("message", "command failed"))); return; }
                ok.accept(j);
            }
            @Override public void onError(String m) { err.accept(m); }
        };
        if (authorized == null) node.cmd(command, callback);
        else node.cmd(command, authorized, callback);
    }

    /**
     * Run a command sequence behind the {@link com.eurobuddha.comms.SignGate}, so this app never has two
     * signing sequences in flight at once.
     *
     * Every sequence routed through here ends in a txnsign. Signing one key concurrently makes the node
     * issue the SAME one-time leaf for two different transactions, which leaks that leaf's private key —
     * confirmed on a live node, 7 of 64 keys. AtomiX is especially exposed: MainActivity and SwapService
     * each build their own engine and MinimaHtlc, and the swap identity is deliberately pinned to a
     * single key so the maker's published key stays constant.
     */
    private void runSeq(List<String> cmds, Consumer<JSONObject> finalOk, Consumer<String> err) {
        runSeq(cmds, null, finalOk, err);
    }
    private void runSeq(List<String> cmds, java.util.function.BooleanSupplier authorized,
                        Consumer<JSONObject> finalOk, Consumer<String> err) {
        com.eurobuddha.comms.SignGate.submit(gate -> runSeqAt(cmds, 0, authorized,
                r -> { gate.free(); finalOk.accept(r); },
                e -> { gate.free(); err.accept(e); }));
    }

    /** A single node command that SIGNS on the node ({@code send} or {@code consolidate}) — gated through {@link
     *  com.eurobuddha.comms.SignGate} exactly like a multi-step txnsign sequence, so a lock/split can never
     *  sign a one-time key leaf concurrently with an autonomous claim/refund (M1). */
    private void runSigned(String sendCmd, Consumer<JSONObject> ok, Consumer<String> err) {
        runSeq(java.util.Collections.singletonList(sendCmd), ok, err);
    }
    private void runSeqAt(List<String> cmds, int i, java.util.function.BooleanSupplier authorized,
                          Consumer<JSONObject> finalOk, Consumer<String> err) {
        cmd(cmds.get(i), authorized, resp -> {
            if (cmds.get(i).startsWith("txncheck ")) {
                String invalid = transactionCheckFailure(resp);
                if (invalid != null) { err.accept(invalid); return; }
            }
            if (i == cmds.size() - 1) finalOk.accept(resp);
            else runSeqAt(cmds, i + 1, authorized, finalOk, err);
        }, err);
    }

    /** Small read-only receipt lookup. A locally mined TxPoW is not necessarily on-chain. */
    public void confirmationDepth(String txpowid, Consumer<Integer> ok, Consumer<String> err) {
        if (txpowid == null || !txpowid.matches("(?i)0x[0-9a-f]{64}")) { err.accept("Invalid TxPoW identifier"); return; }
        cmd("txpow onchain:" + txpowid, reply -> ok.accept(confirmedDepth(reply)), err);
    }

    /** Reuses NFT TransactionEvidence.inclusion's shape and height-consistency checks.
     * Returns observed depth; the swap controller retains its existing settlement threshold. */
    static int confirmedDepth(JSONObject reply) {
        JSONObject r = reply == null ? null : reply.optJSONObject("response");
        if (reply == null || !Boolean.TRUE.equals(reply.opt("status"))
                || r == null || !Boolean.TRUE.equals(r.opt("found"))) return -1;
        try {
            Object raw = r.opt("blockid");
            if (!(raw instanceof String) || !((String) raw).matches("(?i)0x[0-9a-f]{64}")) return -1;
            if (new java.math.BigInteger(((String) raw).substring(2), 16).signum() == 0) return -1;
            java.math.BigInteger block = whole(r, "block"), tip = whole(r, "tip"), depth = whole(r, "confirmations");
            if (block.signum() <= 0 || !tip.subtract(block).equals(depth)) return -1;
            return new java.math.BigDecimal(depth).intValueExact();
        } catch (Exception invalid) { return -1; }
    }
    private static java.math.BigInteger whole(JSONObject object, String key) {
        Object raw = object.opt(key);
        if (!(raw instanceof String) || !((String) raw).matches("[0-9]{1,30}"))
            throw new IllegalArgumentException("Incomplete chain evidence");
        return new java.math.BigInteger((String) raw);
    }

    private static void addValidation(List<String> seq, String id) {
        // Core appends proofs: txnpost must NOT use auto:true after txnbasics.
        seq.add("txnbasics id:" + id);
        seq.add("txncheck id:" + id);
    }

    /** Reused from PandaPools TxPost.checkFailure: command success alone is not transaction validity. */
    static String transactionCheckFailure(JSONObject reply) {
        JSONObject r = reply == null ? null : reply.optJSONObject("response");
        JSONObject valid = r == null ? null : r.optJSONObject("valid");
        if (!checkFlag(reply, "status")) return "The node could not check the transaction. Nothing was posted.";
        if (!checkFlag(valid, "mmrproofs")) return "An input coin was already spent or its proof is invalid. Nothing was posted.";
        if (!checkFlag(r, "validamounts")) return "The transaction amounts do not balance. Nothing was posted.";
        if (!checkFlag(valid, "scripts")) return "The contract rejects this transaction. Nothing was posted.";
        if (!checkFlag(valid, "basic") || !checkFlag(r, "allsignaturesvalid") || !checkFlag(r, "validtransaction"))
            return "The node did not validate the complete transaction and signatures. Nothing was posted.";
        return null;
    }

    private static boolean checkFlag(JSONObject o, String key) {
        Object v = o == null ? null : o.opt(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) {
            try { return new java.math.BigDecimal(v.toString()).compareTo(java.math.BigDecimal.ONE) == 0; }
            catch (NumberFormatException e) { return false; }
        }
        if (v instanceof String) return "1".equals(((String) v).trim()) || "true".equalsIgnoreCase(((String) v).trim());
        return false;
    }

    private void deleteTxn(String id) { node.cmd("txndelete id:" + id, new NodeApi.Cb() {
        @Override public void onResult(JSONObject j) {} @Override public void onError(String m) {} }); }

    /** Read a coin state port. State may be a simplestate object {"4":…} or an array [{port,data}]. */
    static String stateAt(JSONObject coin, int port) {
        Object st = coin.opt("state");
        if (st instanceof JSONObject) {
            return ((JSONObject) st).optString(String.valueOf(port), "");
        }
        if (st instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) st;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && o.optInt("port", -1) == port) return o.optString("data", "");
            }
        }
        return "";
    }

    private static String txpowOf(JSONObject postResp) {
        JSONObject resp = postResp.optJSONObject("response");
        return resp == null ? "" : resp.optString("txpowid", "");
    }

    /** package-private (not private) so the swap-package unit tests can assert the change arithmetic directly —
     *  it computes the change output on every claim/refund, so a bug here silently burns funds. */
    static String subtract(String a, String b) {
        try { return new java.math.BigDecimal(a).subtract(new java.math.BigDecimal(b)).stripTrailingZeros().toPlainString(); }
        catch (Exception e) { return a; }
    }

    /** Quantize a Minima-leg (mxUSDT) lock amount DOWN to the 6dp trade grain. mxUSDT is 8dp but the ERC20 USDT
     *  counter-leg is 6dp, so 6dp is the 1:1 grain; this is the last-line guard that a >6dp amount from any caller
     *  (an odd-precision peer order, a hand-typed amount) can never reach the node as a sub-grain send it rejects. */
    public static String grain(String amt) {
        try { return new java.math.BigDecimal(amt.trim()).setScale(6, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString(); }
        catch (Exception e) { return amt; }
    }

    /** The traded VALUE of a coin. mxUSDT is a COLOURED token: a coin's `amount` field is the tiny underlying
     *  coloured-Minima (~1e-37 at this token's scale), while the real token value is `tokenamount`. For native
     *  0x00 there is no `tokenamount` and `amount` IS the value. ALWAYS read a traded coin's amount through here —
     *  reading `amount` directly on an mxUSDT coin gives ~1e-37, which breaks coin selection / change / claim /
     *  refund (fund loss). (`send`/`txnoutput amount:` conversely take the TOKEN amount, so those stay as-is.) */
    public static String coinAmount(JSONObject coin) {
        if (coin == null) return "0";
        String ta = coin.optString("tokenamount", "");
        if (!ta.isEmpty()) return ta;
        String a = coin.optString("amount", "");   // native 0x00 fallback (no tokenamount → amount IS the value)
        return a.isEmpty() ? "0" : a;
    }

    /** package-private (not private) so unit tests can assert it directly — it decides whether a change/dust
     *  output is emitted at all, so its boundary (exactly-zero change → no output) is fund-relevant. */
    static boolean positive(String a) {
        try { return new java.math.BigDecimal(a).signum() > 0; } catch (Exception e) { return false; }
    }

    private static String txnId() { return "swap_" + Long.toHexString(System.nanoTime()); }
    private static String shortCmd(String c) { int sp = c.indexOf(' '); return sp < 0 ? c : c.substring(0, sp); }
}

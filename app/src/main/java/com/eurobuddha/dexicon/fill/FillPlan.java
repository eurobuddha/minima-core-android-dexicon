package com.eurobuddha.dexicon.fill;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The two-party fill (SPEC §5) money math — a port of mds/dexicon/lib/fill.js plan() and verifyAsMaker().
 * Pure BigDecimal, no node. Rounding is always in the MAKER's favour: a taker pays MxUSD rounded UP (sell order)
 * and receives MxUSD rounded DOWN (buy order), to the 8-dp grain. Sums/differences are EXACT (change outputs
 * must equal inputs minus payment); only a result beyond 64 significant digits is rounded DOWN (dust burns, never up).
 */
public final class FillPlan {
    private FillPlan() {}

    public static final int GRAIN = 8;
    /** decimal.js precision:64 ROUND_DOWN — the node stores amounts with up to 64 digits / 44 dp. */
    public static final MathContext MC = new MathContext(64, RoundingMode.DOWN);

    public static BigDecimal d(String s) { return (s == null || s.isEmpty()) ? BigDecimal.ZERO : new BigDecimal(s); }
    public static BigDecimal up8(BigDecimal x) { return x.setScale(GRAIN, RoundingMode.UP); }
    public static BigDecimal down8(BigDecimal x) { return x.setScale(GRAIN, RoundingMode.DOWN); }
    /** Plain decimal string without trailing zeros (decimal.js toFixed()). */
    public static String plain(BigDecimal x) { return x.signum() == 0 ? "0" : x.stripTrailingZeros().toPlainString(); }
    public static BigDecimal fit64(BigDecimal x) { return x.round(MC); }
    static boolean eq(String a, String b) { return String.valueOf(a).toLowerCase(Locale.ROOT).equals(String.valueOf(b).toLowerCase(Locale.ROOT)); }

    /** Same hash as fill.js spreadIndex: h = h*31 + charCode over the lower-cased key, unsigned 32-bit, mod n. */
    public static int spreadIndex(String key, int n) {
        long h = 0; String k = key.toLowerCase();
        for (int i = 0; i < k.length(); i++) h = ((h * 31) + k.charAt(i)) & 0xFFFFFFFFL;
        return (int) (h % n);
    }
    public static final class Pick { public final List<Coin> coins = new ArrayList<>(); public BigDecimal sum = BigDecimal.ZERO; }

    /** Pick coins (largest first, stable) until sum >= need; null if they cannot cover it within max. */
    public static Pick pick(List<Coin> coins, BigDecimal need, int max) { return pick(coins, need, max, null); }
    /** Concurrent takers (mirror of fill.js): when several coins each cover the take alone, the taker's own key picks
     *  one deterministically, so two takers arriving together spend DIFFERENT backing coins and both fills mine. */
    public static Pick pick(List<Coin> coins, BigDecimal need, int max, String spreadKey) {
        List<Coin> sorted = new ArrayList<>(coins);
        sorted.sort((a, b) -> b.value.compareTo(a.value));
        if (spreadKey != null && !spreadKey.isEmpty()) {
            List<Coin> single = new ArrayList<>();
            for (Coin c : sorted) if (c.value.compareTo(need) >= 0) single.add(c);
            if (single.size() > 1) { Pick one = new Pick(); Coin c = single.get(spreadIndex(spreadKey, single.size())); one.coins.add(c); one.sum = c.value; return one; }
        }
        Pick p = new Pick();
        for (Coin c : sorted) {
            if (p.coins.size() >= max) break;
            p.coins.add(c); p.sum = p.sum.add(c.value);
            if (p.sum.compareTo(need) >= 0) return p;
        }
        return null;
    }

    public static final class Output {
        public final String address, amount, tokenid;
        public Output(String address, String amount, String tokenid) { this.address = address; this.amount = amount; this.tokenid = tokenid; }
    }

    public static final class Plan {
        public final List<String> inputs = new ArrayList<>();
        public final List<Output> outputs = new ArrayList<>();
        public final List<String> scripts = new ArrayList<>();
        public String filled, makerGives, takerGives, payToken, recvToken, makerChange, takerChange;
    }

    /** The order fields the math needs (an Envelope or a MyOrder both provide them). */
    public static final class Order {
        public final String side, price, amount, address;
        public final List<String> coins, scripts;
        public Order(String side, String price, String amount, String address, List<String> coins, List<String> scripts) {
            this.side = side; this.price = price; this.amount = amount; this.address = address; this.coins = coins; this.scripts = scripts;
        }
    }

    /**
     * plan(order, takeMinima, makerCoins, takerCoins, takerAddress) → the taker's transaction.
     * makerCoins: the maker's advertised coins as fetched from the chain. takerCoins: my spendable coins of the token I pay.
     */
    public static Plan plan(Order order, String takeMinima, List<Coin> makerCoins, List<Coin> takerCoins, String takerAddress) {
        BigDecimal take = down8(d(takeMinima));
        if (take.signum() <= 0) throw new IllegalArgumentException("take must be positive");
        if (take.compareTo(d(order.amount)) > 0) throw new IllegalArgumentException("take exceeds order amount");
        BigDecimal price = d(order.price);
        boolean sell = "sell".equals(order.side);
        String makerTok = sell ? Tokens.MINIMA : Tokens.MXUSD(), takerTok = sell ? Tokens.MXUSD() : Tokens.MINIMA;
        BigDecimal makerGives = sell ? take : down8(take.multiply(price, MC));   // buy: maker pays MxUSD rounded DOWN
        BigDecimal takerGives = sell ? up8(take.multiply(price, MC)) : take;     // sell: taker pays MxUSD rounded UP
        if (makerGives.signum() <= 0 || takerGives.signum() <= 0) throw new IllegalArgumentException("amount below grain");
        List<Coin> mc = new ArrayList<>();
        for (Coin c : makerCoins) {
            if (!c.sameToken(makerTok)) throw new IllegalArgumentException("maker coin " + c.coinid + " is not " + (sell ? "MINIMA" : "MxUSD"));
            if (!c.spendable()) throw new IllegalArgumentException("maker coin " + c.coinid + " spent or carries state");
            mc.add(c);
        }
        Pick m = pick(mc, makerGives, 32, takerAddress);
        if (m == null) throw new IllegalArgumentException("maker coins do not cover " + plain(makerGives));
        List<Coin> tc = new ArrayList<>();
        for (Coin c : takerCoins) if (c.sameToken(takerTok) && c.spendable()) tc.add(c);
        Pick t = pick(tc, takerGives, 20);
        if (t == null) throw new IllegalArgumentException("your wallet does not cover " + plain(takerGives) + (sell ? " MxUSD" : " MINIMA"));
        Plan p = new Plan();
        for (Coin c : m.coins) p.inputs.add(c.coinid);
        for (Coin c : t.coins) p.inputs.add(c.coinid);
        // maker → taker
        p.outputs.add(new Output(takerAddress, plain(makerGives), makerTok));
        BigDecimal makerChange = fit64(m.sum.subtract(makerGives));
        if (makerChange.signum() > 0) p.outputs.add(new Output(m.coins.get(0).address, plain(makerChange), makerTok));
        // taker → maker (the order's pay-to address)
        p.outputs.add(new Output(order.address, plain(takerGives), takerTok));
        BigDecimal takerChange = fit64(t.sum.subtract(takerGives));
        if (takerChange.signum() > 0) p.outputs.add(new Output(takerAddress, plain(takerChange), takerTok));
        if (order.scripts != null) p.scripts.addAll(order.scripts);
        p.filled = plain(take); p.makerGives = plain(makerGives); p.takerGives = plain(takerGives);
        p.payToken = takerTok; p.recvToken = makerTok; p.makerChange = plain(makerChange); p.takerChange = plain(takerChange);
        return p;
    }

    public static final class Verdict {
        public final boolean ok; public final String reason, filled, received, paid;
        Verdict(boolean ok, String reason, String filled, String received, String paid) { this.ok = ok; this.reason = reason; this.filled = filled; this.received = received; this.paid = paid; }
        static Verdict bad(String r) { return new Verdict(false, r, null, null, null); }
    }

    /**
     * verifyAsMaker(inputs, outputs, hasState, order, advertisedCoinIds, remaining, myAddresses) — inputs/outputs are
     * txnview's transaction. Every input the taker spends on my behalf must be one I advertised; what I net out
     * (inputs minus change back to my addresses) is the fill; what lands on the order's pay-to address in the other
     * token must cover it at my price, rounded my way.
     */
    public static Verdict verifyAsMaker(List<Coin> inputs, List<Coin> outputs, boolean hasState, Order order,
                                        List<String> advertisedCoinIds, String remaining, List<String> myAddresses) {
        boolean sell = "sell".equals(order.side);
        String myTok = sell ? Tokens.MINIMA : Tokens.MXUSD(), wantTok = sell ? Tokens.MXUSD() : Tokens.MINIMA;
        Set<String> adv = lower(advertisedCoinIds), mine = lower(myAddresses), myInAddrs = new HashSet<>();
        BigDecimal myIn = BigDecimal.ZERO;
        for (Coin inp : inputs) {
            boolean isAdv = adv.contains(inp.coinid.toLowerCase(Locale.ROOT));
            boolean isMineAddr = mine.contains(inp.address.toLowerCase(Locale.ROOT));
            if (!isAdv && !isMineAddr) continue;                                  // the taker's own input
            if (!isAdv) return Verdict.bad("input " + inp.coinid + " is mine but was not advertised");
            if (!inp.sameToken(myTok)) return Verdict.bad("advertised coin has the wrong token");
            myIn = myIn.add(inp.value); myInAddrs.add(inp.address.toLowerCase(Locale.ROOT));
        }
        if (myIn.signum() <= 0) return Verdict.bad("none of my coins are spent");
        BigDecimal myChange = BigDecimal.ZERO, received = BigDecimal.ZERO;
        for (Coin out : outputs) {
            String addr = out.address.toLowerCase(Locale.ROOT);
            if (out.sameToken(myTok) && (myInAddrs.contains(addr) || mine.contains(addr) || eq(out.address, order.address))) myChange = myChange.add(out.value);
            if (out.sameToken(wantTok) && eq(out.address, order.address)) received = received.add(out.value);
        }
        BigDecimal filled = myIn.subtract(myChange);
        if (filled.signum() <= 0) return Verdict.bad("nothing filled");
        BigDecimal price = d(order.price), rem = d(remaining);
        if (sell) {
            if (filled.compareTo(rem) > 0) return Verdict.bad("fill " + plain(filled) + " exceeds remaining " + plain(rem));
            BigDecimal due = up8(filled.multiply(price, MC));
            if (received.compareTo(due) < 0) return Verdict.bad("payment " + plain(received) + " MxUSD below " + plain(due));
        } else {
            if (received.compareTo(rem) > 0) return Verdict.bad("fill " + plain(received) + " MINIMA exceeds remaining " + plain(rem));
            if (filled.compareTo(down8(received.multiply(price, MC))) > 0) return Verdict.bad("I would pay " + plain(filled) + " MxUSD for " + plain(received) + " MINIMA, above my price");
        }
        if (hasState) return Verdict.bad("unexpected transaction state");
        return new Verdict(true, null, sell ? plain(filled) : plain(received), plain(received), plain(filled));
    }

    private static Set<String> lower(List<String> l) {
        Set<String> s = new HashSet<>();
        if (l != null) for (String x : l) if (x != null) s.add(x.toLowerCase(Locale.ROOT));
        return s;
    }
}

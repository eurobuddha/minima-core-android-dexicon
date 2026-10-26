package com.eurobuddha.dexicon;

import com.eurobuddha.atomix.swap.SwapDb;
import com.eurobuddha.dexicon.doors.DoorForwards;
import com.eurobuddha.dexicon.doors.DoorStatus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The ledger lines the ACTIVITY, HISTORY and ATOMIX screens paint, as pure data (no Android types, JVM-tested): one
 * headline per trade / fill / door swap, its plain-English steps with the times the row really recorded (blank when
 * unknown — never invented), the facts, and the proof list. Every identifier is carried in full; each appears once.
 * Amounts are the stored strings or the existing Util formatters — never rounded here.
 */
public final class Ledger {
    private Ledger() {}

    public enum Tone { OK, BAD, WARN, LIVE, DIM }
    public enum Mark { OK, WAIT, WARN, NOTE }

    public static final class Step {
        public final Mark mark; public final String time, text;
        Step(Mark mark, String time, String text) { this.mark = mark; this.time = time == null ? "" : time; this.text = text; }
        @Override public String toString() { return mark + " " + time + " " + text; }
    }
    public static final class Proof {
        public final String label, value;
        Proof(String label, String value) { this.label = label; this.value = value; }
        @Override public String toString() { return label + "=" + value; }
    }
    public static final class Group {
        public final String label; public final List<Proof> ids = new ArrayList<>();
        Group(String label) { this.label = label; }
        @Override public String toString() { return label + ids; }
    }

    public static final class Item {
        public String id = "", icon = "", title = "", chip = "", amount = "", sub = "", time = "";
        public Tone tone = Tone.DIM, chipTone = Tone.DIM;
        /** live = still in flight (steps always visible); usd = an MxUSD swap (AtomiX green, not orange);
         *  header = a day label row; ticking = re-derive every second (a refund countdown). */
        public boolean live, usd, header, ticking, retryForward, refundEnabled;
        public String refundLabel;
        public long sortMs;
        public final List<Step> steps = new ArrayList<>();
        public final List<String[]> facts = new ArrayList<>();
        public final List<Proof> proof = new ArrayList<>();
        public final List<Group> groups = new ArrayList<>();

        void step(Mark m, String time, String text) { steps.add(new Step(m, time, text)); }
        void fact(String k, String v) { if (v != null && !v.isEmpty()) facts.add(new String[]{k, v}); }
        /** Adds an id unless empty or already listed (each id once). */
        void proof(String label, String value) {
            if (value == null || value.trim().isEmpty()) return;
            for (Proof p : proof) if (p.value.equalsIgnoreCase(value)) return;
            for (Group g : groups) for (Proof p : g.ids) if (p.value.equalsIgnoreCase(value)) return;
            proof.add(new Proof(label, value));
        }
        void group(String label, String each, JSONArray ids) {
            int n = ids == null ? 0 : ids.length();
            if (n == 0) return;
            Group g = new Group(label + " (" + n + ")");
            for (int i = 0; i < n; i++) g.ids.add(new Proof(each + " " + (i + 1) + " of " + n, ids.optString(i)));
            groups.add(g);
        }
        public String stepsKey() { return steps.toString(); }
        public String bodyKey() {
            StringBuilder b = new StringBuilder();
            for (String[] f : facts) b.append(f[0]).append(':').append(f[1]).append('\n');
            return b.append(proof).append(groups).toString();
        }
    }

    // ------------------------------------------------------------------ time

    static String fmt(String pattern, long ms, TimeZone tz) {
        if (ms <= 0) return "";
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(tz);
        return f.format(new Date(ms));
    }
    static String hms(long ms, TimeZone tz) { return fmt("HH:mm:ss", ms, tz); }
    static String hm(long ms, TimeZone tz) { return fmt("HH:mm", ms, tz); }
    static String dayTime(long ms, TimeZone tz) { return fmt("dd MMM HH:mm", ms, tz); }

    /** "Today · 26 Oct", "24 Oct", or "24 Oct 2025" for another year. */
    public static String dayLabel(long ms, long nowMs, TimeZone tz) {
        Calendar a = Calendar.getInstance(tz), b = Calendar.getInstance(tz);
        a.setTimeInMillis(ms); b.setTimeInMillis(nowMs);
        boolean sameYear = a.get(Calendar.YEAR) == b.get(Calendar.YEAR);
        if (sameYear && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)) return "Today · " + fmt("dd MMM", ms, tz);
        return fmt(sameYear ? "dd MMM" : "dd MMM yyyy", ms, tz);
    }

    /** Newest first; with {@code liveFirst} the live items lead under "Now", the rest go under day labels. */
    public static List<Item> arrange(List<Item> items, boolean liveFirst, long nowMs, TimeZone tz) {
        List<Item> sorted = new ArrayList<>(items), out = new ArrayList<>();
        sorted.sort((x, y) -> Long.compare(y.sortMs, x.sortMs));
        if (liveFirst) {
            boolean any = false;
            for (Item it : sorted) if (it.live) { if (!any) out.add(header("Now")); any = true; out.add(it); }
        }
        String day = null;
        for (Item it : sorted) {
            if (liveFirst && it.live) continue;
            String d = dayLabel(it.sortMs, nowMs, tz);
            if (!d.equals(day)) { out.add(header(d)); day = d; }
            out.add(it);
        }
        return out;
    }
    static Item header(String label) { Item h = new Item(); h.header = true; h.id = "day_" + label; h.title = label; return h; }

    // ------------------------------------------------------------------ trades (ActivityLog rows)

    static String routeWord(String route) {
        switch (route == null ? "" : route) {
            case "FAST": return "fast book";
            case "POOL": return "pool";
            case "BOOK": return "book";
            case "BOOK+POOL": return "book + pool";
            default: return route == null ? "" : route.toLowerCase(Locale.ROOT);
        }
    }

    /** One ACTIVITY row: headline, steps, proof. */
    public static Item activity(JSONObject a, long chainBlock, long nowMs, TimeZone tz) {
        Item it = new Item();
        String stage = a.optString("stage"), why = MainActivity.here(a.optString("reason", ""));
        boolean buy = "buy".equals(a.optString("side")), maker = "maker".equals(a.optString("role")), fast = ActivityLog.fast(a);
        boolean failed = ActivityLog.failedTerminal(a), confirmed = ActivityLog.confirmed(a, chainBlock);
        long ts = a.optLong("ts"), sentMs = a.optLong("sentMs"), cosignMs = a.optLong("cosignMs"), postedMs = a.optLong("postedMs");
        it.id = "act_" + a.optString("id");
        it.sortMs = ts;
        it.live = !ActivityLog.settled(a, chainBlock);
        it.icon = failed ? "!" : it.live ? "●" : buy ? "↓" : "↑";
        it.tone = failed ? Tone.BAD : it.live ? Tone.LIVE : buy ? Tone.OK : Tone.BAD;
        it.title = (confirmed ? (buy ? "Bought" : "Sold") : (buy ? "Buy" : "Sell")) + " MINIMA"
                + (maker ? (confirmed ? " · you were the maker" : " · you are the maker") : "");
        it.amount = a.optString("amount") + " MINIMA @ " + Util.fmtPrice(Util.dec(a.optString("price")));
        it.chip = ActivityLog.chip(a, chainBlock);
        it.chipTone = failed ? Tone.BAD : confirmed ? Tone.OK : Tone.LIVE;
        String mx = a.optString("mxusd", "");
        it.sub = dayTime(ts, tz) + " · " + routeWord(a.optString("route")) + (mx.isEmpty() ? "" : " · " + (buy ? "paid " : "received ") + mx + " MxUSD")
                + (it.live ? " · " + Util.ago(nowMs - ts).replace(" ago", "") + " elapsed" : "");

        // ---- what happened
        if (maker) {
            it.step(Mark.OK, hms(ts, tz), "A taker took your order");
            if (cosignMs > 0) it.step(Mark.OK, hms(ts + cosignMs, tz), "Your node co-signed in " + ActivityLog.secs(cosignMs));
        } else if (fast) {
            if (ActivityLog.BUILDING.equals(stage)) it.step(Mark.WAIT, hms(ts, tz), why.isEmpty() ? "Building and signing your side…" : why);
            else it.step(Mark.OK, hms(ts, tz), "Built and signed your side");
            int relays = a.optInt("relays");
            String sentTo = "Take sent to " + relays + (relays == 1 ? " relay" : " relays");
            if (ActivityLog.SENT.equals(stage)) it.step(Mark.WAIT, hms(sentMs, tz), sentTo + " — waiting for the maker to co-sign ("
                    + Math.max(0, (nowMs - (sentMs > 0 ? sentMs : nowMs)) / 1000) + "s)");
            else if (sentMs > 0) it.step(Mark.OK, hms(sentMs, tz), sentTo);
            String answered = sentMs > 0 && cosignMs > 0 ? hms(sentMs + cosignMs, tz) : "";
            if (ActivityLog.REFUSED.equals(stage)) it.step(Mark.WARN, answered, "The maker refused — " + why);
            else if (cosignMs > 0) it.step(Mark.OK, answered, "The maker co-signed in " + ActivityLog.secs(cosignMs));
        } else {
            if (ActivityLog.BUILDING.equals(stage)) it.step(Mark.WAIT, hms(ts, tz), why.isEmpty() ? "Building and signing…" : why);
            else it.step(Mark.OK, hms(ts, tz), "Built and signed on this node");
            long post = a.optLong("postBlock", 0);
            if (postedMs > 0 || !a.optString("txpowid").isEmpty()) it.step(Mark.OK, hms(postedMs, tz), "Posted to the network" + (post > 0 ? " at block " + post : ""));
        }
        long block = a.optLong("block", 0), depth = ActivityLog.depth(a, chainBlock);
        if (block > 0) {
            it.step(Mark.OK, "", "In block " + block);
            if (depth >= ActivityLog.CONFIRM_BLOCKS) it.step(Mark.OK, hms(a.optLong("settledMs"), tz), (fast ? "Done" : "Confirmed") + " — " + ActivityLog.confirmationText(depth));
            else if (!failed) it.step(Mark.WAIT, "", "Confirming — " + ActivityLog.confirmationText(depth) + " of " + ActivityLog.CONFIRM_BLOCKS);
        } else if (ActivityLog.POSTED.equals(stage) || ActivityLog.COSIGNED.equals(stage)) {
            String w = ActivityLog.waiting(a, chainBlock);
            it.step(Mark.WAIT, "", Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        switch (stage) {
            case ActivityLog.FAILED: it.step(Mark.WARN, "", "Failed — " + why); break;
            case ActivityLog.DROPPED: it.step(Mark.WARN, "", "Dropped — " + why); break;
            case ActivityLog.LOST: it.step(Mark.WARN, "", "Lost — " + why); break;
            default:
        }

        // ---- proof
        it.proof("Transaction (txpow)", a.optString("txpowid"));
        it.proof("Transaction id", a.optString("txnid"));
        it.proof(maker ? "Your order" : "Order", a.optString("orderId"));
        if (fast) it.proof(maker ? "Taker ID" : "Maker's pay-to address", a.optString("counterparty"));
        it.group("Coins you received", "Received", a.optJSONArray("outs"));
        it.group("Coins spent", "Spent", a.optJSONArray("ins"));
        return it;
    }

    // ------------------------------------------------------------------ fills (HISTORY)

    /** One HISTORY fill. {@code act} = the ACTIVITY row with the same txpowid, if still kept (adds txn id, co-sign
     *  time and the spent coins); may be null. */
    public static Item fill(JSONObject f, JSONObject act, TimeZone tz) {
        Item it = new Item();
        boolean buy = "buy".equals(f.optString("side")), maker = "maker".equals(f.optString("role"));
        String orderId = f.optString("orderId", ""), st = f.optString("status", ""), cp = f.optString("counterparty", "");
        boolean fastTaker = !maker && !orderId.isEmpty();
        long ms = f.optLong("ts", 0) * 1000;
        it.id = "fill_" + f.optString("txpowid");
        it.sortMs = ms;
        it.icon = buy ? "↓" : "↑";
        it.tone = buy ? Tone.OK : Tone.BAD;
        it.title = buy ? "Bought" : "Sold";
        it.amount = f.optString("filled") + " MINIMA";
        it.sub = "@ " + Util.fmtPrice(Util.dec(f.optString("price", "0"))) + " · " + (maker ? "you were the maker" : fastTaker ? "fast book" : routeWord(cp));
        it.time = hm(ms, tz);
        if (!"done".equals(st)) { it.chip = "lost".equals(st) ? "LOST" : st.toUpperCase(Locale.ROOT); it.chipTone = "lost".equals(st) ? Tone.BAD : Tone.LIVE; }

        String usd = maker ? f.optString(buy ? "paid" : "received", "") : f.optString(buy ? "gave" : "got", "");
        it.fact(buy ? "You paid" : "You got", usd.isEmpty() ? "" : usd + " MxUSD");
        it.fact("Route", maker ? "Fast book · a taker took your order" : fastTaker ? "Fast book · you took a maker's order"
                : "POOL".equals(cp) ? "Pool · you took the price" : "BOOK".equals(cp) ? "Book · you took resting orders"
                : "BOOK+POOL".equals(cp) ? "Book + pool · you took the price" : cp);
        long cosign = act == null ? 0 : act.optLong("cosignMs");
        if (cosign > 0) it.fact("Co-signed", "in " + ActivityLog.secs(cosign));
        it.fact("Settled", "done".equals(st) ? "block " + f.optString("block", "") : "lost".equals(st) ? "not in a block after 2h — coins were not spent" : "waiting for a block");
        it.fact("When", fmt("dd MMM yyyy HH:mm:ss", ms, tz));

        it.proof("Transaction (txpow)", f.optString("txpowid"));
        if (act != null) it.proof("Transaction id", act.optString("txnid"));
        it.proof(maker ? "Your order" : "Order", orderId);
        if (maker) it.proof("Taker ID", cp);
        else if (fastTaker) it.proof("Maker's pay-to address", cp);
        it.group("Coins you received", "Received", f.optJSONArray("coins"));
        if (act != null) it.group("Coins spent", "Spent", act.optJSONArray("ins"));
        return it;
    }

    // ------------------------------------------------------------------ door swaps (AtomiX)

    static boolean isTx(String n) { return n != null && n.matches("0x[0-9a-fA-F]{64}"); }

    /** One door swap: headline, steps ({@link DoorStatus} + claim / done / forward), refund button state, proof. */
    public static Item swap(SwapDb.Swap s, List<SwapDb.Event> events, JSONObject fwd, long nowSec, int block, String ethAddress, String answer, TimeZone tz) {
        Item it = new Item();
        String sell = DoorForwards.label(s.sellToken), buy = DoorForwards.label(s.buyToken), st = s.status == null ? "" : s.status;
        String fs = fwd == null ? "" : fwd.optString("status");
        boolean fwdOpen = SwapDb.ST_COMPLETE.equals(st) && (DoorForwards.PENDING.equals(fs) || DoorForwards.FAILED.equals(fs) || DoorForwards.UNCERTAIN.equals(fs));
        boolean running = SwapDb.ST_STARTED.equals(st) || SwapDb.ST_LOCKED.equals(st) || SwapDb.ST_CLAIMING.equals(st);
        boolean complete = SwapDb.ST_COMPLETE.equals(st), refunded = SwapDb.ST_REFUNDED.equals(st), error = SwapDb.ST_ERROR.equals(st);
        it.id = "swap_" + DoorForwards.norm(s.hash);
        it.sortMs = s.created;
        it.usd = "MxUSD".equals(sell) || "MxUSD".equals(buy);
        it.live = running || fwdOpen;
        it.ticking = SwapDb.ST_STARTED.equals(st);
        it.retryForward = DoorForwards.FAILED.equals(fs) || DoorForwards.UNCERTAIN.equals(fs);
        it.title = "ERC20_TO_MINIMA".equals(s.direction) ? "AtomiX entry" : "AtomiX exit";
        it.amount = s.sellAmount + " " + sell + " → " + s.buyAmount + " " + buy;
        String pill = DoorForwards.pill(s, fwd);
        it.chip = running ? "LIVE" : error ? "FAILED" : pill;
        it.chipTone = running || "FORWARDING".equals(pill) ? Tone.LIVE : complete && "DONE".equals(pill) ? Tone.OK : complete ? Tone.WARN : Tone.BAD;
        it.icon = it.live ? "●" : complete ? "✓" : refunded ? "↺" : "!";
        it.tone = it.live ? Tone.LIVE : complete ? Tone.OK : Tone.BAD;

        long claimAt = 0, refundAt = 0;
        String makerId = null;
        if (events != null) for (SwapDb.Event e : events) {   // newest first: keep overwriting → the earliest wins
            if (SwapDb.EV_MINIMA_CLAIM_SUBMITTED.equals(e.event) || SwapDb.EV_COLLECT.equals(e.event)) claimAt = e.date;
            else if ((SwapDb.EV_REFUND_SENT.equals(e.event) || SwapDb.EV_MINIMA_REFUND_SUBMITTED.equals(e.event)) && isTx(e.note)) refundAt = e.date;
            else if (SwapDb.EV_TAKE_SENT.equals(e.event) && makerId == null) makerId = e.note;
        }
        String outcome = SwapDb.ST_STARTED.equals(st) ? "waiting for the maker" : running ? "claiming"
                : DoorForwards.PENDING.equals(fs) ? "forwarding" : DoorForwards.FAILED.equals(fs) ? "the forward failed"
                : DoorForwards.UNCERTAIN.equals(fs) ? "the forward is unconfirmed" : DoorForwards.SENT.equals(fs) ? "forwarded"
                : complete ? ("USDT".equals(buy) ? "at your Ethereum address" : "in your wallet")
                : refunded ? "maker didn't lock · " + sell + " back" + (refundAt > 0 ? " " + hm(refundAt, tz) : "") : error ? "failed" : st;
        it.sub = dayTime(s.created, tz) + " · " + outcome;

        // ---- steps
        DoorStatus.Card card = DoorStatus.build(s, events, nowSec, block, answer, tz);
        for (DoorStatus.Line l : card.lines) {
            Mark m = l.kind == DoorStatus.Kind.WAIT ? Mark.WAIT : l.kind == DoorStatus.Kind.INFO ? Mark.NOTE
                    : l.kind == DoorStatus.Kind.WARN || l.kind == DoorStatus.Kind.NOTICE ? Mark.WARN : Mark.OK;
            it.step(m, l.time, l.text);
        }
        it.refundLabel = card.refundLabel; it.refundEnabled = card.refundEnabled;
        boolean usdtOut = "USDT".equals(buy);
        if (running && !SwapDb.ST_STARTED.equals(st)) {
            if (claimAt > 0) it.step(Mark.OK, hms(claimAt, tz), usdtOut ? "Withdrawal sent on Ethereum" : "Claim sent");
            it.step(Mark.WAIT, "", (usdtOut ? "Withdrawing your " : "Claiming your ") + s.buyAmount + " " + buy + " now");
        }
        if (complete) {
            if (claimAt > 0) it.step(Mark.OK, hms(claimAt, tz), usdtOut ? "Withdrew your USDT on Ethereum" : "Claimed into your wallet");
            if (fwd == null) it.step(Mark.OK, hms(s.updated, tz), "Done — " + s.buyAmount + " " + buy + (usdtOut ? " is at this node’s Ethereum address"
                    : " is in your wallet (less the " + DoorForwards.NOTIFY_AMOUNT + " " + buy + " notify output)"));
            else if (DoorForwards.SENT.equals(fs)) it.step(Mark.OK, "", "Forwarded " + fwd.optString("amount") + " " + buy + " to the address you named");
            else if (DoorForwards.FAILED.equals(fs)) it.step(Mark.WARN, "", "The forward failed: " + fwd.optString("err", "unknown") + " — tap Retry forward.");
            else if (DoorForwards.UNCERTAIN.equals(fs)) it.step(Mark.WARN, "", "The forward is unconfirmed: the node lost the reply to the send (" + fwd.optString("err")
                    + "). Check this node’s wallet history before you retry — it may already have gone.");
            else it.step(Mark.WAIT, "", "Forwarding " + buy + " to the address you named" + (fwd.optString("err").isEmpty() ? "…" : " (retrying: " + fwd.optString("err") + ")"));
        }
        if (error) it.step(Mark.WARN, hms(s.updated, tz), "Failed — see the recorded transactions; the counterparty took the leg you paid or refunded theirs.");

        // ---- proof: each id once, in full
        it.proof("Swap", s.hash);
        List<SwapDb.Event> oldest = new ArrayList<>();
        if (events != null) for (SwapDb.Event e : events) oldest.add(0, e);
        for (SwapDb.Event e : oldest) if (SwapDb.EV_STARTED.equals(e.event) && isTx(e.note))
            it.proof("Your " + sell + " lock (" + (s.myLegIsMinima ? "Minima txpow" : "Ethereum tx") + ")", e.note);
        it.proof("Ethereum contract", s.contractId);
        it.proof("Maker key", s.counterparty);
        it.proof("Maker ID (who was told)", makerId);
        for (SwapDb.Event e : oldest) {
            if (!isTx(e.note)) continue;
            boolean eth = (e.token == null ? "" : e.token).toUpperCase(Locale.ROOT).startsWith("ETH");
            String label = SwapDb.EV_REFUND_SENT.equals(e.event) ? "Refund (Ethereum tx)"
                    : SwapDb.EV_MINIMA_REFUND_SUBMITTED.equals(e.event) ? "Refund (Minima txpow)"
                    : SwapDb.EV_MINIMA_CLAIM_SUBMITTED.equals(e.event) ? "Your Minima claim (txpow)"
                    : SwapDb.EV_COLLECT.equals(e.event) ? (eth ? "Your USDT withdrawal (Ethereum tx)" : "Your claim (Minima tx)")
                    : eth ? "Ethereum tx" : "Minima tx";
            it.proof(label, e.note);
        }
        if (refunded && !s.myLegIsMinima) it.proof("Refunded to", ethAddress);
        if (fwd != null) { it.proof("Forward to", fwd.optString("to")); it.proof("Forward tx", fwd.optString("tx")); }
        return it;
    }

    /** A finished swap as a HISTORY line: "Swapped X → Y" (or a muted "Refunded X"), with facts. */
    public static Item asHistory(Item it, SwapDb.Swap s, TimeZone tz) {
        String sell = DoorForwards.label(s.sellToken), buy = DoorForwards.label(s.buyToken), st = s.status == null ? "" : s.status;
        String door = "ERC20_TO_MINIMA".equals(s.direction) ? "AtomiX entry" : "AtomiX exit";
        it.time = hm(s.created, tz);
        if (SwapDb.ST_REFUNDED.equals(st)) {
            it.icon = "↺"; it.tone = Tone.DIM; it.title = "Refunded"; it.amount = s.sellAmount + " " + sell;
            it.sub = door + " · the maker didn't lock";
            it.fact("Nothing traded", "your " + s.sellAmount + " " + sell + " came back");
        } else if (SwapDb.ST_ERROR.equals(st)) {
            it.icon = "!"; it.tone = Tone.BAD; it.title = "Swap failed"; it.sub = door;
        } else {
            it.icon = "⇄"; it.tone = Tone.OK; it.title = "Swapped";
            it.sub = door + " · " + it.sub.substring(it.sub.indexOf(" · ") + 3);
            it.fact("You paid", s.sellAmount + " " + sell + ("USDT".equals(sell) ? " (Ethereum)" : ""));
            it.fact("You got", s.buyAmount + " " + buy + ("USDT".equals(buy) ? "" : ", less the " + DoorForwards.NOTIFY_AMOUNT + " notify output"));
            it.fact("Finished", fmt("dd MMM yyyy HH:mm:ss", s.updated, tz));
        }
        if ("DONE".equals(it.chip)) it.chip = "";   // a settled ledger line needs no chip; anything else keeps it
        return it;
    }

    // ------------------------------------------------------------------ identity

    /** The Dexicon publicId ("0x" + boxPk(32 B) + signPk(32 B)) split into its two keys, each "0x" + 64 hex. */
    public static String[] keysOf(String publicId) {
        String h = publicId == null ? "" : publicId.startsWith("0x") || publicId.startsWith("0X") ? publicId.substring(2) : publicId;
        if (!h.matches("[0-9a-fA-F]{128}")) return null;
        return new String[]{"0x" + h.substring(0, 64), "0x" + h.substring(64)};
    }
}

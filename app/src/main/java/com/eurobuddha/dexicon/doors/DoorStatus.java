package com.eurobuddha.dexicon.doors;

import com.eurobuddha.atomix.swap.SwapDb;
import com.eurobuddha.atomix.swap.SwapEngine;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The status card of a door swap I started, as pure data (no Views): steps with full transaction ids, the maker's
 * respond-by deadline, its latest sealed notice, the timeout / refund window, and the "Refund now" button state. Built
 * from the swap row + its SwapDb events + the clock, so the DOORS card is a dumb painter and this is unit-testable.
 * Every id is emitted in full — never shortened.
 */
public final class DoorStatus {
    private DoorStatus() {}

    /** ~50 s per Minima block, for turning a block deadline into a wall-clock estimate. */
    public static final long SECS_PER_BLOCK = 50;

    public enum Kind { DONE, WAIT, INFO, NOTICE, WARN, OK }
    public static final class Line {
        public final Kind kind; public final String text;
        Line(Kind kind, String text) { this.kind = kind; this.text = text; }
        @Override public String toString() { return kind + " " + text; }
    }
    public static final class Card {
        public final List<Line> lines = new ArrayList<>();
        /** null = no refund button; otherwise its label and whether it can be tapped. */
        public String refundLabel; public boolean refundEnabled;
        void add(Kind k, String t) { lines.add(new Line(k, t)); }
        /** Everything except the per-second button label — repaint the lines only when this changes. */
        public String linesKey() { StringBuilder b = new StringBuilder(); for (Line l : lines) b.append(l).append('\n'); return b.toString(); }
    }

    /**
     * @param events      SwapDb.getEvents(hash) — newest first
     * @param nowSec      wall clock, unix seconds
     * @param block       last seen Minima block (0 = unknown)
     * @param ethAddress  this node's AtomiX ETH address (an ETH refund lands there); may be null
     * @param answer      the last "Refund now" answer for this swap, or null
     */
    public static Card build(SwapDb.Swap s, List<SwapDb.Event> events, long nowSec, int block, String ethAddress, String answer, TimeZone tz) {
        Card c = new Card();
        if (s == null || !"INITIATOR".equals(s.role)) return c;
        String sell = DoorForwards.label(s.sellToken), buy = DoorForwards.label(s.buyToken), st = s.status == null ? "" : s.status;
        String lockTx = null, makerId = null, notice = null, refundTx = null;
        boolean timedOut = false;
        if (events != null) for (SwapDb.Event e : events) {   // newest first: the first hit is the latest
            String n = e.note == null ? "" : e.note;
            if (SwapDb.EV_STARTED.equals(e.event) && lockTx == null) lockTx = n;
            else if (SwapDb.EV_TAKE_SENT.equals(e.event) && makerId == null) makerId = n;
            else if (SwapDb.EV_MAKER_NOTICE.equals(e.event) && notice == null) notice = n;
            else if (SwapDb.EV_TIMEOUT.equals(e.event)) timedOut = true;
            else if ((SwapDb.EV_REFUND_SENT.equals(e.event) || SwapDb.EV_MINIMA_REFUND_SUBMITTED.equals(e.event)) && refundTx == null && !n.isEmpty()) refundTx = n;
        }
        boolean started = SwapDb.ST_STARTED.equals(st), refunded = SwapDb.ST_REFUNDED.equals(st);
        boolean makerLocked = SwapDb.ST_LOCKED.equals(st) || SwapDb.ST_CLAIMING.equals(st) || SwapDb.ST_COMPLETE.equals(st);

        // ---- steps
        c.add(Kind.DONE, s.sellAmount + " " + sell + " locked" + (lockTx == null || lockTx.isEmpty() ? "" : " · tx " + lockTx));
        // A USDT-first lock is announced to its maker over the TAKE channel (recorded since 0.1.26; older swaps have no row).
        if (!s.myLegIsMinima && makerId != null) c.add(Kind.DONE, "Maker notified · maker id " + makerId);
        if (makerLocked) c.add(Kind.DONE, "Maker locked " + s.buyAmount + " " + buy);
        else if (started) c.add(Kind.WAIT, "Waiting for the maker to lock " + s.buyAmount + " " + buy);

        // ---- the maker's own word (latest sealed notice)
        if (notice != null && !makerLocked) {
            int bar = notice.indexOf('|');
            String state = bar < 0 ? notice : notice.substring(0, bar), why = bar < 0 ? "" : notice.substring(bar + 1);
            c.add(Kind.NOTICE, (SwapEngine.NOTICE_DECLINED.equals(state) ? "Maker DECLINED: " : "Maker is retrying: ") + why);
        }

        // ---- deadlines + refund (only while my lock is still out there unanswered)
        SwapEngine.Deadlines d = SwapEngine.deadlines(s);
        if (started && d != null) {
            long now = d.blocks ? block : nowSec;
            boolean known = !d.blocks || block > 0;
            boolean past = timedOut || (known && now >= d.respondBy);
            boolean open = known && now >= d.refundAt;
            if (!past) c.add(Kind.INFO, "Maker must respond by " + when(d.blocks, d.respondBy, block, nowSec, tz));
            else if (!open) c.add(Kind.WARN, (declined(notice) ? "Maker declined" : "Maker did not respond") + " — refund opens at "
                    + when(d.blocks, d.refundAt, block, nowSec, tz) + (known ? " (in " + minsLeft(d, now) + " min)" : "") + ", automatic, app open or not.");
            else c.add(Kind.WARN, "Refund window is open since " + when(d.blocks, d.refundAt, block, nowSec, tz) + " — it refunds automatically, app open or not. Tap Refund now to push it.");
            if (!past && declined(notice)) c.add(Kind.INFO, "Your lock refunds automatically at " + when(d.blocks, d.refundAt, block, nowSec, tz) + ", app open or not.");
            c.refundEnabled = open;
            c.refundLabel = open ? "REFUND NOW" : known ? "REFUND NOW · opens in " + countdown(d, now) : "REFUND NOW · opens at block " + d.refundAt;
        }
        if (refundTx != null) c.add(refunded ? Kind.DONE : Kind.INFO, "Refund sent: " + refundTx);
        if (refunded) c.add(Kind.OK, "Refunded " + s.sellAmount + " " + sell + " to "
                + (s.myLegIsMinima ? "this node’s Minima wallet" : ethAddress == null || ethAddress.isEmpty() ? "this node’s ETH address" : ethAddress));
        if (answer != null && !answer.isEmpty() && !refunded) c.add(Kind.INFO, answer);
        return c;
    }

    static boolean declined(String notice) { return notice != null && notice.startsWith(SwapEngine.NOTICE_DECLINED + "|"); }

    private static long secsLeft(SwapEngine.Deadlines d, long now) { return Math.max(0, d.refundAt - now) * (d.blocks ? SECS_PER_BLOCK : 1); }
    static long minsLeft(SwapEngine.Deadlines d, long now) { return (secsLeft(d, now) + 59) / 60; }
    static String countdown(SwapEngine.Deadlines d, long now) {
        long s = secsLeft(d, now);
        return (d.blocks ? "~" : "") + (s >= 3600 ? (s / 3600) + ":" + two((s % 3600) / 60) + ":" + two(s % 60) : (s / 60) + ":" + two(s % 60));
    }
    private static String two(long v) { return v < 10 ? "0" + v : String.valueOf(v); }

    /** Local wall-clock for a deadline: unix seconds as is; a block as "block N (~HH:mm)" estimated at ~50 s/block. */
    static String when(boolean blocks, long at, int block, long nowSec, TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
        f.setTimeZone(tz);
        if (!blocks) return f.format(new Date(at * 1000));
        if (block <= 0) return "block " + at;
        return "block " + at + " (~" + f.format(new Date((nowSec + (at - block) * SECS_PER_BLOCK) * 1000)) + ")";
    }
}

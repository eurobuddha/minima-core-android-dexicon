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
 * The status of a door swap I started, as pure data (no Views): plain-English steps with their times, the maker's
 * deadline, its latest sealed notice, the refund window and the "Refund now" button state. Built from the swap row +
 * its SwapDb events + the clock, so the screens are dumb painters and this is unit-testable. Transaction ids are NOT in
 * these lines: each id is listed once, in full, in the swap's proof list ({@link com.eurobuddha.dexicon.Ledger#swap}).
 */
public final class DoorStatus {
    private DoorStatus() {}

    /** ~50 s per Minima block, for turning a block deadline into a wall-clock estimate. */
    public static final long SECS_PER_BLOCK = 50;

    public enum Kind { DONE, WAIT, INFO, NOTICE, WARN, OK }
    public static final class Line {
        public final Kind kind; public final String time, text;
        Line(Kind kind, String time, String text) { this.kind = kind; this.time = time; this.text = text; }
        @Override public String toString() { return kind + " " + text; }
    }
    public static final class Card {
        public final List<Line> lines = new ArrayList<>();
        /** null = no refund button; otherwise its label and whether it can be tapped. */
        public String refundLabel; public boolean refundEnabled;
        void add(Kind k, String time, String t) { lines.add(new Line(k, time, t)); }
        /** Everything except the per-second button label — repaint the lines only when this changes. */
        public String linesKey() { StringBuilder b = new StringBuilder(); for (Line l : lines) b.append(l).append('\n'); return b.toString(); }
    }

    /**
     * @param events      SwapDb.getEvents(hash) — newest first
     * @param nowSec      wall clock, unix seconds
     * @param block       last seen Minima block (0 = unknown)
     * @param answer      the last "Refund now" answer for this swap, or null
     */
    public static Card build(SwapDb.Swap s, List<SwapDb.Event> events, long nowSec, int block, String answer, TimeZone tz) {
        Card c = new Card();
        if (s == null || !"INITIATOR".equals(s.role)) return c;
        String sell = DoorForwards.label(s.sellToken), buy = DoorForwards.label(s.buyToken), st = s.status == null ? "" : s.status;
        String makerId = null, notice = null, refundTx = null;
        long lockAt = 0, toldAt = 0, noticeAt = 0, refundSentAt = 0;
        boolean timedOut = false;
        if (events != null) for (SwapDb.Event e : events) {   // newest first: the first hit is the latest
            String n = e.note == null ? "" : e.note;
            if (SwapDb.EV_STARTED.equals(e.event) && lockAt == 0) lockAt = e.date;
            else if (SwapDb.EV_TAKE_SENT.equals(e.event) && makerId == null) { makerId = n; toldAt = e.date; }
            else if (SwapDb.EV_MAKER_NOTICE.equals(e.event) && notice == null) { notice = n; noticeAt = e.date; }
            else if (SwapDb.EV_TIMEOUT.equals(e.event)) timedOut = true;
            else if ((SwapDb.EV_REFUND_SENT.equals(e.event) || SwapDb.EV_MINIMA_REFUND_SUBMITTED.equals(e.event)) && refundTx == null && !n.isEmpty()) { refundTx = n; refundSentAt = e.date; }
        }
        boolean started = SwapDb.ST_STARTED.equals(st), refunded = SwapDb.ST_REFUNDED.equals(st);
        boolean makerLocked = SwapDb.ST_LOCKED.equals(st) || SwapDb.ST_CLAIMING.equals(st) || SwapDb.ST_COMPLETE.equals(st);
        SwapEngine.Deadlines d = started ? SwapEngine.deadlines(s) : null;
        long now = d == null ? 0 : d.blocks ? block : nowSec;
        boolean known = d != null && (!d.blocks || block > 0);
        boolean past = d != null && (timedOut || (known && now >= d.respondBy));
        boolean open = known && now >= d.refundAt;

        // ---- steps
        c.add(Kind.DONE, hms(lockAt, tz), "Your " + s.sellAmount + " " + sell + " is locked on " + (s.myLegIsMinima ? "Minima" : "Ethereum"));
        // A USDT-first lock is announced to its maker over the TAKE channel (recorded since 0.1.26; older swaps have no row).
        if (!s.myLegIsMinima && makerId != null) c.add(Kind.DONE, hms(toldAt, tz), "The maker has been told");
        if (makerLocked) c.add(Kind.DONE, "", "The maker locked " + s.buyAmount + " " + buy);
        else if (started) c.add(Kind.WAIT, "", "Waiting for the maker to lock your " + s.buyAmount + " " + buy
                + (d != null && !past ? " — it has until " + when(d.blocks, d.respondBy, block, nowSec, tz) : ""));

        // ---- the maker's own word (latest sealed notice)
        if (notice != null && !makerLocked) {
            int bar = notice.indexOf('|');
            String state = bar < 0 ? notice : notice.substring(0, bar), why = bar < 0 ? "" : notice.substring(bar + 1);
            c.add(Kind.NOTICE, hms(noticeAt, tz), (SwapEngine.NOTICE_DECLINED.equals(state) ? "The maker declined: " : "The maker is retrying: ") + why);
        }

        // ---- deadlines + refund (only while my lock is still out there unanswered)
        if (d != null) {
            String refundWhen = when(d.blocks, d.refundAt, block, nowSec, tz);
            if (!past) c.add(Kind.INFO, "", declined(notice)
                    ? "Your lock refunds automatically at " + refundWhen + ", app open or not."
                    : "If the maker doesn't lock in time, your " + s.sellAmount + " " + sell + " comes back by itself from " + refundWhen + " — app open or not.");
            else if (!open) c.add(Kind.WARN, "", (declined(notice) ? "The maker declined" : "The maker didn't lock in time") + " — your refund opens at "
                    + refundWhen + (known ? " (in " + minsLeft(d, now) + " min)" : "") + ", automatically, app open or not.");
            else c.add(Kind.WARN, "", "The refund window is open since " + refundWhen + " — it refunds automatically, app open or not. Tap Refund now to push it.");
            c.refundEnabled = open;
            c.refundLabel = open ? "REFUND NOW" : known ? "REFUND NOW · opens in " + countdown(d, now) : "REFUND NOW · opens at block " + d.refundAt;
        }
        if (refundTx != null) c.add(Kind.DONE, hms(refundSentAt, tz), "Refund sent");
        if (refunded) c.add(Kind.OK, "", s.sellAmount + " " + sell + " is back in " + (s.myLegIsMinima ? "this node’s Minima wallet" : "this node’s Ethereum wallet"));
        if (answer != null && !answer.isEmpty() && !refunded) c.add(Kind.INFO, "", answer);
        return c;
    }

    /** Local HH:mm:ss of an epoch-ms instant; "" when unknown (a time is never invented). */
    public static String hms(long ms, TimeZone tz) {
        if (ms <= 0) return "";
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.US);
        f.setTimeZone(tz);
        return f.format(new Date(ms));
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

package com.eurobuddha.account;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.minima.utils.BIP39;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Guided entry of the 24 Trading Account words: one box per word. Ported from apks/entropy LastWordView (owner, 13 Nov
 * 2026: "we have a wonderful one already in entropy"). One free-text box let a line break, a number or a comma glue two
 * words together, and BIP39.cleanSeedPhrase then rejected a correct phrase as "Unknown BIP39 word found!".
 *
 * <p>Minima phrases carry NO BIP39 checksum ({@code BIP39.getNewWordList} draws 24 independent words), so each word is
 * checked and the phrase never is (owner: "minima has no checksum"). The words are Minima's own list
 * ({@code BIP39.WORD_LIST}, the standard English 2048 in the standard order). {@link #phrase()} only joins the boxes with
 * single spaces; the caller still runs {@link AccountVault#cleanPhrase}, so the stored phrase and the keys are exactly
 * those of typing the words in one line. Used by every 24-word box in Dexicon, AtomiX and PandaPools (owner: "dexicon atomix
 * and pandapool seed phrase entry not just 1").
 */
public final class WordEntry {
    static final int WORDS = 24;
    private static final int GREEN = 0xFF2E9E5B, RED = 0xFFD64545, BORDER = 0x55888888;

    // ---- pure helpers (WordEntryTest) ----

    /** Lower-case letters only: anything else can never be part of a word. */
    static String letters(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""); }

    /** The words in pasted text, in order: numbers, line breaks, commas and full stops separate words. */
    static List<String> splitPasted(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String w : raw.toLowerCase(Locale.ROOT).split("[^a-z]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    static boolean isWord(String w) {
        if (w == null || w.isEmpty()) return false;
        for (String x : BIP39.WORD_LIST) if (x.equals(w)) return true;
        return false;
    }

    /** Up to {@code max} words starting with {@code prefix} (none for an empty prefix). */
    static List<String> matches(String prefix, int max) {
        List<String> out = new ArrayList<>();
        if (prefix == null || prefix.isEmpty()) return out;
        for (String w : BIP39.WORD_LIST) if (w.startsWith(prefix)) { out.add(w); if (out.size() >= max) break; }
        return out;
    }

    /** The 24 words joined by single spaces, or null while any box is empty or not a word. */
    static String join(List<String> words) {
        if (words.size() != WORDS) return null;
        for (String w : words) if (!isWord(w)) return null;
        return String.join(" ", words);
    }

    /** The Bears wallet's existing any-phrase rule: at least 16 characters (bear_strings l10n_wallet_import_seed_body). */
    static final int ANY_MIN = 16;

    /**
     * Why a free-form phrase cannot be used, or null. Owner, 13 Nov 2026: any-phrase is for RESTORING a wallet made with
     * exactly that phrase. Refused: under {@link #ANY_MIN} characters; exactly 24 list words (those go in the boxes - hashed
     * raw they would open a DIFFERENT wallet than the boxes do); and, for a phrase sent in a node command
     * ({@code forCommand}), {@code " ; :} which the node's command parser splits, strips or rewrites (CommandRunner).
     */
    public static String anyPhraseProblem(String phrase, boolean forCommand) {
        String t = phrase == null ? "" : phrase.trim();
        if (t.length() < ANY_MIN) return "A phrase needs at least " + ANY_MIN + " characters.";
        String[] tokens = t.replaceAll("[^A-Za-z]+", " ").trim().split(" ");
        if (tokens.length == WORDS && join(splitPasted(t)) != null)
            return "These are 24 list words: enter them in the word boxes instead.";
        if (forCommand && (t.indexOf('"') >= 0 || t.indexOf(';') >= 0 || t.indexOf(':') >= 0))
            return "A phrase restored by the node cannot contain \" ; or :";
        return null;
    }

    // ---- the view ----

    private final Context c;
    private final Runnable onChange;
    private final Typeface mono;
    private final int textColor;
    public final LinearLayout view;
    private final List<EditText> boxes = new ArrayList<>();
    private final List<Integer> prevLens = new ArrayList<>();
    private final LinearLayout suggRow;
    private final HorizontalScrollView suggScroll;
    private final TextView count;
    private final Button hide;
    private boolean hidden, mute;
    private final LinearLayout wordsPanel;
    private LinearLayout anyPanel;
    private EditText any1, any2;
    private TextView toggle;
    private boolean anyMode, forCommand;

    /** @param textColor 0 = the theme's text colour; @param mono null = monospace; @param onChange may be null */
    public WordEntry(Context c, int textColor, Typeface mono, Runnable onChange) {
        this.c = c; this.onChange = onChange; this.mono = mono == null ? Typeface.MONOSPACE : mono;
        // 0 = the app gave no colour: use the dialog theme's own text colour (a fixed light grey vanished on light themes).
        this.textColor = textColor != 0 ? textColor : new TextView(c).getCurrentTextColor();
        view = new LinearLayout(c); view.setOrientation(LinearLayout.VERTICAL);
        wordsPanel = new LinearLayout(c); wordsPanel.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(c);
        hide = button("Hide words", v -> setHidden(!hidden));
        Button clear = button("Clear all", v -> clearAll());
        top.addView(hide, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        top.addView(clear, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        view.addView(top);
        view.addView(wordsPanel);

        LinearLayout grid = new LinearLayout(c); grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < WORDS; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(c);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rl.topMargin = dp(6); grid.addView(row, rl);
            }
            EditText e = box(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            lp.leftMargin = i % 3 == 0 ? 0 : dp(6);
            row.addView(e, lp);
            boxes.add(e); prevLens.add(0);
        }
        wordsPanel.addView(grid);

        suggScroll = new HorizontalScrollView(c); suggScroll.setHorizontalScrollBarEnabled(false);
        suggRow = new LinearLayout(c); suggScroll.addView(suggRow);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(6); wordsPanel.addView(suggScroll, sl);

        count = new TextView(c); count.setTextColor(this.textColor); count.setTextSize(12); count.setTypeface(this.mono);
        wordsPanel.addView(count);
        refresh();
    }

    /**
     * Offer "My phrase is not 24 list words": a free-form phrase, typed twice, for RESTORING a wallet that was made with
     * exactly that phrase (owner, 13 Nov 2026: restore only, with a warning). {@code forCommand}: the phrase goes into a
     * node command, so {@code " ; :} are refused. Returns this.
     */
    public WordEntry enableAnyPhrase(boolean forCommand) {
        if (anyPanel != null) return this;
        this.forCommand = forCommand;
        anyPanel = new LinearLayout(c); anyPanel.setOrientation(LinearLayout.VERTICAL); anyPanel.setVisibility(View.GONE);
        TextView warn = new TextView(c);
        warn.setText("Only use this to RESTORE a wallet that was made with exactly this phrase. Anyone who guesses a short "
                + "or well-known phrase can take everything on it. Capital letters, spaces and punctuation all count: type it "
                + "exactly as it was made, twice.");
        warn.setTextColor(RED); warn.setTextSize(13);
        anyPanel.addView(warn);
        any1 = anyField("Your phrase"); any2 = anyField("The same phrase again");
        anyPanel.addView(any1); anyPanel.addView(any2);
        view.addView(anyPanel);
        toggle = new TextView(c); toggle.setTextColor(this.textColor); toggle.setTextSize(13);
        toggle.setPadding(0, dp(10), 0, dp(4));
        toggle.setOnClickListener(v -> setAnyMode(!anyMode));
        view.addView(toggle);
        setAnyMode(false);
        return this;
    }

    /** True while the free-form phrase is chosen instead of the 24 boxes. */
    public boolean isAnyPhrase() { return anyMode; }

    /** Boxes: the 24 words joined by single spaces. Any-phrase: the phrase, trimmed, once both copies match and it is
     *  allowed ({@link #anyPhraseProblem}). Null until then - {@link #problem()} says why. */
    public String phrase() {
        if (anyMode) {
            String a = any1.getText().toString().trim();
            return a.equals(any2.getText().toString().trim()) && anyPhraseProblem(a, forCommand) == null ? a : null;
        }
        List<String> w = new ArrayList<>(WORDS);
        for (EditText e : boxes) w.add(e.getText().toString());
        return join(w);
    }

    /** What stops {@link #phrase()}, in words for the user (null when it is ready). */
    public String problem() {
        if (anyMode) {
            String a = any1.getText().toString().trim();
            String why = anyPhraseProblem(a, forCommand);
            if (why != null) return why;
            return a.equals(any2.getText().toString().trim()) ? null : "The two copies of the phrase are not the same.";
        }
        int n = firstProblem();
        return n == 0 ? null : "Word " + n + " is empty or not one of the 2048 words.";
    }

    private void setAnyMode(boolean on) {
        anyMode = on;
        wordsPanel.setVisibility(on ? View.GONE : View.VISIBLE);
        anyPanel.setVisibility(on ? View.VISIBLE : View.GONE);
        toggle.setText(on ? "Use the 24 word boxes instead" : "My phrase is not 24 list words");
        if (on) { any1.requestFocus(); suggRow.removeAllViews(); }
        if (onChange != null) onChange.run();
    }

    private EditText anyField(String hint) {
        EditText e = new EditText(c);
        e.setHint(hint); e.setTextColor(textColor); e.setHintTextColor(BORDER | 0xFF000000); e.setTextSize(14);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        e.setTypeface(mono);
        e.setPadding(dp(8), dp(8), dp(8), dp(8));
        e.setBackground(rounded(BORDER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8); e.setLayoutParams(lp);
        return e;
    }

    /** Position of the first box that is empty or not a word (1-based), 0 when all are words. */
    public int firstProblem() {
        for (int i = 0; i < boxes.size(); i++) if (!isWord(boxes.get(i).getText().toString())) return i + 1;
        return 0;
    }

    private EditText box(int i) {
        EditText e = new EditText(c);
        e.setHint(String.valueOf(i + 1));
        e.setTextColor(textColor); e.setHintTextColor(BORDER | 0xFF000000);
        e.setTextSize(14); e.setSingleLine(true);
        // VISIBLE_PASSWORD: keyboards treat it as a password - no personal-dictionary learning, no cloud sync of typed
        // words (NO_SUGGESTIONS alone does not stop that). The typeface goes AFTER the input type, which resets it.
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_ACTION_NEXT);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        e.setTypeface(mono);
        e.setPadding(dp(8), dp(6), dp(8), dp(6));
        e.setBackground(rounded(BORDER));
        final int idx = i;
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int x) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int x) {}
            @Override public void afterTextChanged(Editable s) { if (!mute) onEdit(idx); }
        });
        e.setOnFocusChangeListener((v, has) -> { if (has) showSuggestions(idx); });
        return e;
    }

    private void onEdit(int idx) {
        EditText e = boxes.get(idx);
        String raw = e.getText().toString();
        List<String> pasted = splitPasted(raw);
        // Pasted words (or a typed space): spread them over this box and the ones after it.
        if (pasted.size() > 1) { spread(idx, pasted); return; }
        String v = letters(raw);
        if (!v.equals(raw)) set(e, v);
        boolean grew = v.length() > prevLens.get(idx);
        prevLens.set(idx, v.length());
        List<String> m = matches(v, 2);
        // A word is certain once only one matches (4 letters always suffice). Only while TYPING: completing on a
        // backspace would make a finished word impossible to edit.
        if (grew && m.size() == 1) { commit(idx, m.get(0)); return; }
        style(idx); showSuggestions(idx); refresh();
    }

    private void spread(int idx, List<String> words) {
        int i = idx;
        for (String w : words) {
            if (i >= boxes.size()) break;
            List<String> m = matches(w, 2);
            String v = isWord(w) ? w : m.size() == 1 ? m.get(0) : w;   // a unique prefix completes, as when typing
            set(boxes.get(i), v); prevLens.set(i, v.length()); style(i); i++;
        }
        focusNextProblem(idx);
        refresh();
    }

    private void commit(int idx, String w) {
        set(boxes.get(idx), w); prevLens.set(idx, w.length());
        style(idx); refresh();
        focusNextProblem(idx);
    }

    private void focusNextProblem(int from) {
        for (int j = 0; j < boxes.size(); j++) {
            int k = (from + 1 + j) % boxes.size();
            if (!isWord(boxes.get(k).getText().toString())) { boxes.get(k).requestFocus(); showSuggestions(k); return; }
        }
        boxes.get(from).clearFocus(); suggRow.removeAllViews();
    }

    private void set(EditText e, String v) { mute = true; e.setText(v); e.setSelection(v.length()); mute = false; }

    private void style(int idx) {
        String v = boxes.get(idx).getText().toString();
        int border = BORDER;
        if (!v.isEmpty()) border = isWord(v) ? GREEN : matches(v, 1).isEmpty() ? RED : BORDER;
        boxes.get(idx).setBackground(rounded(border));
    }

    private void showSuggestions(int idx) {
        suggRow.removeAllViews();
        for (String w : matches(boxes.get(idx).getText().toString(), 8)) {
            Button b = button(w, v -> commit(idx, w));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6); suggRow.addView(b, lp);
        }
    }

    private void refresh() {
        int ok = 0; for (EditText e : boxes) if (isWord(e.getText().toString())) ok++;
        count.setText(ok == WORDS ? "All 24 words entered" : ok + " of 24 words");
        count.setTextColor(ok == WORDS ? GREEN : textColor);
        if (onChange != null) onChange.run();
    }

    private void setHidden(boolean on) {
        hidden = on;
        hide.setText(on ? "Show words" : "Hide words");
        for (EditText e : boxes) e.setTransformationMethod(on ? PasswordTransformationMethod.getInstance() : null);
        if (any1 != null) { any1.setTransformationMethod(on ? PasswordTransformationMethod.getInstance() : null); any2.setTransformationMethod(on ? PasswordTransformationMethod.getInstance() : null); }
        suggScroll.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
    }

    private void clearAll() {
        for (int i = 0; i < boxes.size(); i++) { set(boxes.get(i), ""); prevLens.set(i, 0); style(i); }
        if (any1 != null) { any1.setText(""); any2.setText(""); }
        setHidden(false);
        if (anyMode) any1.requestFocus(); else { boxes.get(0).requestFocus(); showSuggestions(0); }
        refresh();
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(c); b.setText(text); b.setAllCaps(false); b.setOnClickListener(l); return b;
    }

    private GradientDrawable rounded(int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x22000000); g.setCornerRadius(dp(6)); g.setStroke(dp(stroke == BORDER ? 1 : 2), stroke);
        return g;
    }

    private int dp(int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
}

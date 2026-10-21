package com.eurobuddha.dexicon;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eurobuddha.dexicon.relay.RelayClient;
import com.eurobuddha.dexicon.relay.RelayStore;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/** RELAYS tab: the list with health, add / remove, and bootstrap discovery (GET /dex/relays — discovered ones marked). */
@SuppressLint("ViewConstructor")
public final class RelaysTab extends LinearLayout {

    private final MainActivity act;
    private final LinearLayout body;
    private final EditText in;
    private boolean discovering;

    public RelaysTab(MainActivity act) {
        super(act);
        this.act = act;
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12);
        setPadding(pad, pad, pad, pad);

        LinearLayout add = card(this);
        add.addView(t("ADD RELAY", Design.DIM(), 10f, Design.sansBold()));
        add.addView(t("A Dexicon relay is the dexrelay cape of parlons-node.jar / maxima-server.jar (http://host:port). Orders are published to EVERY relay here; the book is the union.", Design.DIM2(), 9.5f, Design.sans()));
        in = new EditText(act);
        in.setHint("http://relay.example:19702"); in.setTextColor(Design.TEXT()); in.setHintTextColor(Design.DIM2()); in.setTypeface(Design.mono()); in.setTextSize(11f);
        in.setBackground(Design.stroked(act, Design.SURFACE2(), 8));
        int p = Design.dp(act, 8); in.setPadding(p, p, p, p);
        add.addView(in);
        LinearLayout btns = new LinearLayout(act);
        TextView addBtn = button("ADD"); addBtn.setOnClickListener(v -> {
            String u = RelayClient.clean(in.getText().toString());
            if (u == null) { act.toast("Enter an http(s) URL"); return; }
            RelayStore.add(act, u, false); in.setText(""); act.applySettings(); render();
        });
        TextView disc = button("DISCOVER"); disc.setOnClickListener(v -> discover());
        LayoutParams bl = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f); bl.setMargins(0, p, Design.dp(act, 4), 0);
        btns.addView(addBtn, bl);
        LayoutParams bl2 = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f); bl2.setMargins(Design.dp(act, 4), p, 0, 0);
        btns.addView(disc, bl2);
        add.addView(btns);

        body = new LinearLayout(act); body.setOrientation(VERTICAL);
        addView(body);
    }

    private TextView t(String s, int color, float size, android.graphics.Typeface tf) {
        TextView v = new TextView(getContext());
        v.setText(s); v.setTextColor(color); v.setTextSize(size); v.setTypeface(tf);
        return v;
    }
    private TextView button(String s) {
        TextView b = t(s, Design.ACCENT(), 10.5f, Design.mono());
        b.setGravity(Gravity.CENTER); int p = Design.dp(getContext(), 9); b.setPadding(0, p, 0, p);
        b.setBackground(Design.stroked(getContext(), Design.SURFACE2(), 10));
        Design.pressable(b);
        return b;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(getContext());
        c.setOrientation(VERTICAL);
        c.setBackground(Design.card(getContext(), 12));
        int p = Design.dp(getContext(), 12); c.setPadding(p, p, p, p);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); lp.bottomMargin = Design.dp(getContext(), 10);
        parent.addView(c, lp);
        return c;
    }

    private void discover() {
        if (discovering) return;
        discovering = true; act.toast("Asking relays for /dex/relays…");
        Executors.newSingleThreadExecutor().execute(() -> {
            List<String> found = act.relay().discover();
            post(() -> {
                discovering = false;
                for (String u : found) RelayStore.add(act, u, true);
                act.applySettings();
                act.toast(found.isEmpty() ? "No new relays announced (endpoint absent or nothing new)" : "Discovered " + found.size() + " relay(s)");
                render();
            });
        });
    }

    public void render() {
        body.removeAllViews();
        List<String> relays = RelayStore.load(act);
        Set<String> discovered = RelayStore.discovered(act);
        Map<String, RelayClient.Status> status = act.relay().status();
        if (relays.isEmpty()) { body.addView(t("No relays configured. Add one above.", Design.DIM2(), 11f, Design.sans())); return; }
        for (String u : relays) {
            LinearLayout c = card(body);
            LinearLayout top = new LinearLayout(getContext());
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView url = t(u, Design.TEXT(), 11f, Design.mono());
            url.setOnClickListener(v -> act.copy("Relay", u));
            top.addView(url, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            if (discovered.contains(u)) top.addView(Design.pill(getContext(), "DISCOVERED", Design.SURFACE2(), Design.DIM()));
            TextView rm = t(" ✕ ", Design.RED(), 14f, Design.sans());
            rm.setOnClickListener(v -> { RelayStore.remove(act, u); act.applySettings(); render(); });
            top.addView(rm);
            c.addView(top);
            RelayClient.Status s = status.get(u);
            String health = s == null ? "not polled yet" : s.ok ? "OK · " + s.orders + " orders in last poll · " + Util.ago(System.currentTimeMillis() - s.lastMs) : "ERROR · " + s.error + " · " + Util.ago(System.currentTimeMillis() - s.lastMs);
            c.addView(t(health, s == null ? Design.DIM2() : s.ok ? Design.IN() : Design.RED(), 9.5f, Design.mono()));
        }
    }
}

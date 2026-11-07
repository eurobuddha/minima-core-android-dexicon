// VENDORED VERBATIM from apks/pandapools 0.9.89 app/src/main/java/com/eurobuddha/pandapools/CollectConfirmation.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Reuses NFT TransactionRecovery's bounded, resumable history scan and exact transaction identity. */
final class CollectConfirmation {
    private final Context context;
    private final NodeApi node;
    private int pageSize = 8, pages;
    CollectConfirmation(Context context, NodeApi node) { this.context = context; this.node = node; }

    static void beforePost(NodeApi node, String handle, String address, Runnable post, Consumer<String> fail) {
        node.cmd("txnlist id:" + handle + " transactiononly:true", new NodeApi.Cb() {
            public void onResult(JSONObject reply) {
                String transaction = CollectionEvidence.draftTransaction(reply, handle);
                if (transaction.isEmpty() || !PendingCollect.track(node.context(), address, transaction)) {
                    fail.accept("Could not save withdrawal collection evidence. Nothing was posted."); return;
                }
                post.run();
            }
            public void onError(String message) {
                fail.accept("Could not read withdrawal transaction identity. Nothing was posted.");
            }
        });
    }
    void scan(Runnable done) { pages = 0; page(PendingCollect.scanOffset(context), done); }
    private void page(int start, Runnable done) {
        int requested = pageSize;
        node.cmd("history relevant:true max:" + requested + " offset:" + start, new NodeApi.Cb() {
            public void onResult(JSONObject reply) {
                JSONObject response = reply == null ? null : reply.optJSONObject("response");
                JSONArray txpows = response == null ? null : response.optJSONArray("txpows");
                if (!Boolean.TRUE.equals(reply == null ? null : reply.opt("status"))
                        || txpows == null || txpows.length() > requested) { done.run(); return; }
                for (int i = 0; i < txpows.length(); i++) PendingCollect.observe(context, txpows.optJSONObject(i));
                pages++;
                int next = txpows.length() < requested || start > Integer.MAX_VALUE - txpows.length()
                        ? 0 : start + txpows.length();
                PendingCollect.scanOffset(context, next);
                if (next == 0 || pages >= 4) done.run(); else page(next, done);
            }
            public void onError(String message) {
                if (!NodeApi.ERR_TOO_LONG.equals(message)) { done.run(); return; }
                if (pageSize > 1) { pageSize = Math.max(1, pageSize / 2); page(start, done); return; }
                int next = start == Integer.MAX_VALUE ? 0 : start + 1;
                PendingCollect.scanOffset(context, next);
                if (++pages >= 4 || next == 0) done.run(); else page(next, done);
            }
        });
    }
    /** Recheck every expected payout on the current chain. Cached confirmation never clears a job. */
    void confirmed(PendingCollect.Entry entry, Consumer<Boolean> done) {
        if (entry == null || entry.legacy || entry.transactions.isEmpty()) { done.accept(false); return; }
        List<String> ids = new ArrayList<>(entry.transactions.values());
        for (String id : ids) if (!CollectionEvidence.id(id)) { done.accept(false); return; }
        verify(ids, 0, done);
    }
    private void verify(List<String> ids, int index, Consumer<Boolean> done) {
        if (index >= ids.size()) { done.accept(true); return; }
        node.cmd("txpow onchain:" + ids.get(index), new NodeApi.Cb() {
            public void onResult(JSONObject reply) {
                if (!CollectionEvidence.confirmed(reply)) { done.accept(false); return; }
                verify(ids, index + 1, done);
            }
            public void onError(String message) { done.accept(false); }
        });
    }
}

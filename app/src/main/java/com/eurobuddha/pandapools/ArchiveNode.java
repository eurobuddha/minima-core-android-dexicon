// VENDORED VERBATIM from apks/pandapools 0.9.88 app/src/main/java/com/eurobuddha/pandapools/ArchiveNode.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-configured, read-only MegaMMR RPC. Reuses WebValidate's worker + NetFetch transport.
 * Never obtains an endpoint from a backup, token metadata, or an archive reply. */
final class ArchiveNode implements FundingCoins.Command {
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String endpoint;
    private ArchiveNode(String endpoint) { this.endpoint = endpoint; }
    static String setting(Context ctx) {
        return ctx.getSharedPreferences("pandapools_recovery", Context.MODE_PRIVATE).getString("archive", "");
    }
    static boolean save(Context ctx, String url) {
        String value = url.trim();
        return (value.isEmpty() || validEndpoint(value)) && ctx.getSharedPreferences("pandapools_recovery", Context.MODE_PRIVATE)
                .edit().putString("archive", value).commit();
    }
    static FundingCoins.Command configured(Context ctx) {
        // Pandamonium recovery uses only the user-designated non-pruning native archive.
        return new MegaArchive();
    }
    static boolean validEndpoint(String url) {
        try {
            URI u = new URI(url);
            return "https".equals(u.getScheme()) && u.getHost() != null && u.getRawUserInfo() == null
                    && u.getRawQuery() == null && u.getRawFragment() == null;
        } catch (Exception invalid) { return false; }
    }
    static boolean allowedCommand(String command) {
        return "status".equals(command)
                || command.matches("(?:balance|coins) address:0x[0-9a-fA-F]{64} megammr:true")
                || command.matches("coins coinid:0x[0-9a-fA-F]{64} megammr:true")
                || command.matches("coinexport coinid:0x[0-9a-fA-F]{64}");
    }
    public void run(String command, NodeApi.Cb cb) {
        if (!allowedCommand(command)) { cb.onError("Archive command refused."); return; }
        EXEC.execute(() -> {
            JSONObject reply = null;
            try {
                String url = endpoint + URLEncoder.encode(command, "UTF-8").replace("+", "%20");
                byte[] body = NetFetch.get(url, 256000, 8000, 15000, false);
                if (body != null) reply = new JSONObject(new String(body, StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
            final JSONObject result = reply;
            main.post(() -> {
                if (result == null) cb.onError("Archive unavailable, refused, or reply too large.");
                else cb.onResult(result);
            });
        });
    }
}

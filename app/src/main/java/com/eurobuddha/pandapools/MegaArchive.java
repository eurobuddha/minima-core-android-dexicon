// VENDORED VERBATIM from apks/pandapools 0.10.0 app/src/main/java/com/eurobuddha/pandapools/MegaArchive.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.minima.objects.CoinProof;
import org.minima.objects.base.MiniData;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Adapts native archive proofs to the existing, locally verified ReserveRecovery pipeline. */
final class MegaArchive implements FundingCoins.Command {
    static final String ENDPOINT = MegaArchiveProofs.HOST + ":" + MegaArchiveProofs.PORT;
    private static final ThreadPoolExecutor EXEC = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16));
    private final Handler main = new Handler(Looper.getMainLooper());
    interface Fetch { List<CoinProof> get(String address) throws IOException; }
    static final class Session {
        final Fetch fetch;
        String address;
        JSONArray coins;
        final Map<String, String> proofs = new LinkedHashMap<>();
        Session(Fetch fetch) { this.fetch = fetch; }
        JSONObject query(String command) throws Exception {
            if (!ArchiveNode.allowedCommand(command)) throw new IOException("ARCHIVE_COMMAND_REFUSED");
            if ("status".equals(command)) return ok(new JSONObject().put("megammr", true).put("archive", ENDPOINT));
            if (command.startsWith("balance address:")) {
                String wanted = command.substring("balance address:".length(), command.indexOf(" megammr:true"));
                address = null; coins = null; proofs.clear();
                List<CoinProof> found = fetch.get(wanted);
                JSONArray rows = new JSONArray();
                for (CoinProof proof : found) {
                    JSONObject coin = new JSONObject(proof.getCoin().toJSON().toString());
                    rows.put(coin);
                    proofs.put(coin.getString("coinid").toLowerCase(Locale.ROOT), MiniData.getMiniDataVersion(proof).to0xString());
                }
                address = wanted; coins = rows;
                return ok(new JSONArray().put(new JSONObject().put("coins", rows.length())));
            }
            if (coins == null) throw new IOException("ARCHIVE_QUERY_ORDER: request this pool's reserves first.");
            if (command.equals("coins address:" + address + " megammr:true")) return ok(new JSONArray(coins.toString()));
            if (command.startsWith("coins coinid:")) {
                String id = command.substring("coins coinid:".length(), command.indexOf(" megammr:true"));
                JSONArray rows = new JSONArray();
                for (int i = 0; i < coins.length(); i++) if (id.equalsIgnoreCase(coins.getJSONObject(i).getString("coinid"))) rows.put(coins.getJSONObject(i));
                return ok(rows);
            }
            if (command.startsWith("coinexport coinid:")) {
                String proof = proofs.get(command.substring("coinexport coinid:".length()).toLowerCase(Locale.ROOT));
                if (proof == null) throw new IOException("ARCHIVE_COIN_MISSING: requested reserve was not returned.");
                return ok(new JSONObject().put("data", proof));
            }
            throw new IOException("ARCHIVE_ADDRESS_MISMATCH");
        }
        private static JSONObject ok(Object value) throws Exception { return new JSONObject().put("status", true).put("response", value); }
    }
    private final Session session = new Session(MegaArchiveProofs::fetch);
    public void run(String command, NodeApi.Cb cb) {
        if (!ArchiveNode.allowedCommand(command)) { cb.onError("ARCHIVE_COMMAND_REFUSED"); return; }
        try {
            EXEC.execute(() -> {
                try {
                    JSONObject reply = session.query(command);
                    main.post(() -> cb.onResult(reply));
                } catch (Exception failure) {
                    String reason = failure instanceof java.net.SocketTimeoutException ? "ARCHIVE_TIMEOUT: try again when the archive is reachable."
                            : failure instanceof java.net.UnknownHostException ? "ARCHIVE_DNS: could not resolve the archive host."
                            : failure instanceof java.net.ConnectException ? "ARCHIVE_CONNECTION: could not connect to the archive."
                            : failure.getMessage() != null && failure.getMessage().startsWith("ARCHIVE_") ? failure.getMessage()
                            : "ARCHIVE_READ_FAILED: incomplete or incompatible archive response.";
                    main.post(() -> cb.onError(ENDPOINT + " — " + reason));
                }
            });
        } catch (RejectedExecutionException busy) { cb.onError("ARCHIVE_BUSY: retry this pool after the current archive requests finish."); }
    }
}

package com.eurobuddha.dexicon;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import org.json.JSONObject;
import com.eurobuddha.minimaapi.MinimaAPI;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** The existing native SDK runs alone here. Large broadcasts never enter the UI process.
 * Private cache files carry payloads between our same-UID processes; Binder carries only a UUID.
 * No exported entry point, additional node permissions, RPC listener, or signing policy. */
public final class NodeTransportService extends Service {
    static final String EVENT_ACTION = "com.eurobuddha.dexicon.NODE_EVENT";
    private MinimaAPI api;
    private android.content.BroadcastReceiver notifications;
    private long lastEvent;

    private final RegisterGate gate = new RegisterGate();   // declared before the handler that uses it

    private final Handler handler = new Handler(Looper.getMainLooper(), msg -> {
        if ((msg.what != 1 && msg.what != 2) || msg.replyTo == null || msg.sendingUid != android.os.Process.myUid()) return true;
        String id = msg.getData().getString("id", "");
        if (!NodeTransport.validId(id)) return true;
        Messenger target = msg.replyTo;
        File request = NodeTransport.file(this, id, ".cmd");
        try {
            String command = NodeTransport.read(request);
            request.delete();
            if (msg.what == 2) {   // PandaPools FILE request: {"action","path","newPath"?,"uri"?}
                JSONObject file = new JSONObject(command);
                final String action = file.getString("action"), path = file.getString("path");   // read BEFORE the gate counts it
                final int run = gate.begin();
                try {
                    api.FileCommand(action, path, file.optString("newPath", null),
                            file.has("uri") ? android.net.Uri.parse(file.getString("uri")) : null,
                            reply -> { respond(target, id, reply); gate.end(run); });
                } catch (Exception thrown) { gate.end(run); throw thrown; }   // a request that never started is not pending
            } else if ("__register__".equals(command)) {
                // review 23 Nov 2026: onDestroy() drops every pending reply handler - a write in flight would lose its
                // outcome. Re-registration waits until no request is pending (each ends by its reply or the SDK's expiry).
                // a reply that never comes must not hold the re-registration for ever: after REGISTER_WAIT_MS it runs anyway
                new Handler(Looper.getMainLooper()).postDelayed(() -> gate.force(), REGISTER_WAIT_MS);
                gate.register(() -> {
                    api.onDestroy();
                    api = new MinimaAPI(getApplicationContext(), reply -> {
                        JSONObject result = new JSONObject();
                        try { result.put("status", true); result.put("enabled", reply.optBoolean("enabled", false)); }
                        catch (Exception ignored) { }
                        respond(target, id, result);
                    });
                });
            } else {
                String invalid = CommandSafety.failure(command);
                if (invalid != null) respond(target, id, NodeTransport.failure(invalid));
                else {
                    final int run = gate.begin();
                    try { api.Command(command, reply -> { respond(target, id, reply); gate.end(run); }); }
                    catch (Exception thrown) { gate.end(run); throw thrown; }
                }
            }
        } catch (Exception error) { respond(target, id, NodeTransport.failure("Node transport could not read the request.")); }
        return true;
    });
    private final Messenger messenger = new Messenger(handler);

    /** how long a re-registration waits for replies still pending before it runs anyway */
    static final long REGISTER_WAIT_MS = 60000;

    /** Requests in flight on the current SDK instance, and re-registrations waiting for them (main thread only).
     *  Each request carries the gate's run number: a reply that arrives after the SDK was replaced (force, or a
     *  re-registration that ran) belongs to an older run and does not count against the new one. */
    static final class RegisterGate {
        private int pending, run;
        private final java.util.List<Runnable> waiting = new java.util.ArrayList<>();
        int begin() { pending++; return run; }
        void end(int zRun) {
            if (zRun != run) return;
            if (pending > 0) pending--;
            drain();
        }
        void register(Runnable r) { waiting.add(r); drain(); }
        /** the replies did not all come: forget them and run what waits */
        void force() { if (waiting.isEmpty()) return; pending = 0; drain(); }
        private void drain() {
            while (pending == 0 && !waiting.isEmpty()) { run++; waiting.remove(0).run(); }
        }
        int pending() { return pending; }
    }

    @Override public void onCreate() {
        super.onCreate();
        api = new MinimaAPI(getApplicationContext(), reply -> {});
        notifications = new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context context, Intent intent) {
                try {
                    if (!MinimaAPI.checkMinimaID(context, intent)) return;
                    String data = com.eurobuddha.minimaapi.MinimaAPI.notifyData(intent);
                    if (data == null || data.length() > NodeTransport.MAX_BYTES) return;
                    String event = new JSONObject(data).optString("event", "");
                    if (!"NEWBLOCK".equals(event) && !"NEWBALANCE".equals(event)) return;
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastEvent < 1000) return;
                    lastEvent = now;
                    sendBroadcast(new Intent(EVENT_ACTION).setPackage(getPackageName()).putExtra("event", event));
                } catch (Exception malformed) { /* An unauthenticated/malformed notification is not a command reply. */ }
            }
        };
        androidx.core.content.ContextCompat.registerReceiver(this, notifications,
                com.eurobuddha.minimaapi.MinimaAPI.notifyFilter(),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
        File[] files = NodeTransport.directory(this).listFiles();
        if (files != null) for (File f : files)
            if (System.currentTimeMillis() - f.lastModified() > 10 * 60_000L) f.delete();
    }
    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
    @Override public void onDestroy() {
        if (notifications != null) try { unregisterReceiver(notifications); } catch (Exception ignored) { }
        if (api != null) api.onDestroy();
        super.onDestroy();
    }

    static final int INLINE_BYTES = 64 * 1024;

    private void respond(Messenger target, String id, JSONObject reply) {
        File file = NodeTransport.file(this, id, ".json");
        Message result = Message.obtain(null, 1);
        Bundle data = new Bundle(); data.putString("id", id);
        try {
            // security review 30 Oct: a seed phrase never leaves this service, and a small reply (every secret one is
            // small) never touches the disk — it rides the in-process Binder; only bulky coin lists are staged
            JSONObject r = reply == null ? null : reply.optJSONObject("response");
            if (r != null) r.remove("phrase");
            String json = reply == null ? "{}" : reply.toString();
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > NodeTransport.MAX_BYTES) throw new java.io.IOException("Oversized reply");
            if (bytes.length <= INLINE_BYTES) data.putString("inline", json);
            else Files.write(file.toPath(), bytes);
        } catch (Exception error) { data.putString("error", "Node transport could not save the reply."); }
        result.setData(data);
        try { target.send(result); }
        catch (RemoteException gone) { file.delete(); }
    }
}

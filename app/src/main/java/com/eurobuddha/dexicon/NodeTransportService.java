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

    private final Handler handler = new Handler(Looper.getMainLooper(), msg -> {
        if (msg.what != 1 || msg.replyTo == null || msg.sendingUid != android.os.Process.myUid()) return true;
        String id = msg.getData().getString("id", "");
        if (!NodeTransport.validId(id)) return true;
        Messenger target = msg.replyTo;
        File request = NodeTransport.file(this, id, ".cmd");
        try {
            String command = NodeTransport.read(request);
            request.delete();
            if ("__register__".equals(command)) {
                api.onDestroy();
                api = new MinimaAPI(getApplicationContext(), reply -> {
                    JSONObject result = new JSONObject();
                    try { result.put("status", true); result.put("enabled", reply.optBoolean("enabled", false)); }
                    catch (Exception ignored) { }
                    respond(target, id, result);
                });
            } else {
                String invalid = CommandSafety.failure(command);
                if (invalid != null) respond(target, id, NodeTransport.failure(invalid));
                else api.Command(command, reply -> respond(target, id, reply));
            }
        } catch (Exception error) { respond(target, id, NodeTransport.failure("Node transport could not read the request.")); }
        return true;
    });
    private final Messenger messenger = new Messenger(handler);

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

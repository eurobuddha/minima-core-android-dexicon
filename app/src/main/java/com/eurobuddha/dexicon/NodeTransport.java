package com.eurobuddha.dexicon;

import android.content.*;
import android.os.*;
import org.json.JSONObject;
import com.eurobuddha.minimaapi.MinimaAPIListener;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Same SDK callback contract as MinimaAPI, with small internal Binder messages. */
final class NodeTransport {
    static final int MAX_BYTES = com.eurobuddha.minimaapi.MinimaAPIResponse.MAX_BYTES;
    static final int MAX_PENDING = 128;
    static final long REPLY_RETENTION_MS = 10 * 60_000L + 5_000;
    private Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Same process-wide worker/cancellation pattern as ExportChecks and the SDK reader.
    private static final ThreadPoolExecutor reader = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), r -> {
                Thread thread = new Thread(r, "dexicon-private-reply"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Map<String, MinimaAPIListener> callbacks = new LinkedHashMap<>();
    private final Set<String> unsent = new LinkedHashSet<>();
    private final Set<String> reading = new HashSet<>();
    private final Map<String, FutureTask<Void>> reads = new HashMap<>();
    private final Map<String, Runnable> deadlines = new HashMap<>();
    private final Map<String, Runnable> connectDeadlines = new HashMap<>();
    private Messenger remote;
    private boolean bound, closed;
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        if (closed || msg.what != 1 || msg.sendingUid != android.os.Process.myUid()) return true;
        String id = msg.getData().getString("id", "");
        if (!validId(id) || !callbacks.containsKey(id) || !reading.add(id)) return true;
        String error = msg.getData().getString("error");
        File file = file(context, id, ".json");
        FutureTask<Void> task = new FutureTask<>(() -> {
            JSONObject reply;
            try { reply = error == null ? com.eurobuddha.minimaapi.MinimaAPIResponse.parse(read(file)) : failure(error); }
            catch (Exception bad) { reply = failure("Node transport could not read the reply."); }
            finally { file.delete(); }
            final JSONObject answer = reply;
            main.post(() -> complete(id, answer));
        }, null);
        reads.put(id, task);
        try { reader.execute(task); } catch (RejectedExecutionException busy) {
            file.delete();
            complete(id, failure("Node reply reader is busy. Outcome is unknown."));
        }
        return true;
    }));
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed) return;
            remote = new Messenger(binder);
            for (String id : new ArrayList<>(unsent)) send(id);
        }
        public void onServiceDisconnected(ComponentName name) {
            remote = null;
            // Never replay a dispatched command after process death: a write might have completed.
            failAll("The node reply connection stopped. The app is still running.");
        }
        public void onBindingDied(ComponentName name) {
            onServiceDisconnected(name);
            if (closed) return;
            if (bound) { try { context.unbindService(this); } catch (Exception ignored) { } bound = false; }
            bound = context.bindService(new Intent(context, NodeTransportService.class), this, Context.BIND_AUTO_CREATE);
        }
        public void onNullBinding(ComponentName name) {
            onServiceDisconnected(name);
            if (bound) { try { context.unbindService(this); } catch (Exception ignored) { } bound = false; }
        }
    };

    NodeTransport(Context ctx, MinimaAPIListener registration) {
        context = ctx.getApplicationContext();
        bound = context.bindService(new Intent(context, NodeTransportService.class), connection, Context.BIND_AUTO_CREATE);
        Command("checkmode", reply -> {
            JSONObject state = new JSONObject();
            try { state.put("enabled", reply.optBoolean("enabled", reply.optBoolean("status", false))); }
            catch (Exception ignored) { }
            registration.response(state);
        });
    }

    void Command(String command, MinimaAPIListener cb) {
        if (closed || !bound) { cb.response(failure("Node reply service is unavailable.")); return; }
        if (callbacks.size() >= MAX_PENDING) { cb.response(failure("Too many unanswered node requests.")); return; }
        String id = UUID.randomUUID().toString();
        try {
            byte[] bytes = command.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) throw new IOException("Oversized command");
            java.nio.file.Files.write(file(context, id, ".cmd").toPath(), bytes);
        } catch (Exception error) { cb.response(failure("Could not stage node command.")); return; }
        callbacks.put(id, cb); unsent.add(id);
        Runnable expiry = () -> complete(id, failure("Node reply was not received. Outcome remains unknown."));
        deadlines.put(id, expiry);
        main.postDelayed(expiry, REPLY_RETENTION_MS);
        // Cancel a command that has NOT crossed the service boundary before the outer read timeout.
        Runnable connectExpiry = () -> {
            if (unsent.remove(id)) complete(id, failure("Node reply service did not connect. Command not sent."));
        };
        connectDeadlines.put(id, connectExpiry);
        main.postDelayed(connectExpiry, 20_000);
        if (remote != null) send(id);
    }
    private void send(String id) {
        if (!unsent.remove(id)) return;
        Runnable connectExpiry = connectDeadlines.remove(id);
        if (connectExpiry != null) main.removeCallbacks(connectExpiry);
        Message request = Message.obtain(null, 1);
        Bundle b = new Bundle(); b.putString("id", id); request.setData(b); request.replyTo = replies;
        try { remote.send(request); }
        catch (RemoteException error) { complete(id, failure("Node reply service disconnected.")); }
    }
    private void complete(String id, JSONObject reply) {
        unsent.remove(id); reading.remove(id);
        FutureTask<Void> task = reads.remove(id);
        if (task != null) { task.cancel(true); reader.remove(task); }
        file(context, id, ".json").delete();
        Runnable expiry = deadlines.remove(id);
        if (expiry != null) main.removeCallbacks(expiry);
        Runnable connectExpiry = connectDeadlines.remove(id);
        if (connectExpiry != null) main.removeCallbacks(connectExpiry);
        file(context, id, ".cmd").delete();
        MinimaAPIListener cb = callbacks.remove(id);
        if (cb != null) cb.response(reply);
    }
    private void failAll(String error) {
        for (String id : new ArrayList<>(callbacks.keySet())) complete(id, failure(error));
    }
    void onDestroy() {
        closed = true;
        failAll("Node reply connection closed.");
        if (bound) { context.unbindService(connection); bound = false; }
    }
    static boolean isCompleteReply(JSONObject reply) {
        return reply != null && (reply.opt("status") instanceof Boolean || Boolean.FALSE.equals(reply.opt("enabled")));
    }
    static boolean validId(String id) { return id != null && id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"); }
    static File directory(Context ctx) {
        File d = new File(ctx.getCacheDir(), "node-transport");
        if (!d.isDirectory() && !d.mkdirs()) throw new IllegalStateException("Cannot create private transport directory");
        return d;
    }
    static File file(Context ctx, String id, String suffix) {
        if (!validId(id)) throw new IllegalArgumentException("Invalid request ID");
        return new File(directory(ctx), id + suffix);
    }
    static String read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n, count = 0;
            while ((n = in.read(buffer)) != -1) {
                count += n; if (count > MAX_BYTES) throw new IOException("Payload too large");
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    static JSONObject failure(String message) {
        JSONObject result = new JSONObject();
        try { result.put("transporterror", message); } catch (Exception ignored) { }
        // Deliberately NO status: a transport failure must never clear a pending signing marker.
        return result;
    }
}

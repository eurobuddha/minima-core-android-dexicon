// Adapted for PandaDEX: bounded transport, durable pairing and uncertain-outcome errors.
package com.eurobuddha.minimaapi;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.Hashtable;
import java.security.SecureRandom;
import java.util.concurrent.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class MinimaAPI {

    public static boolean LOGGING_ENABLED = false;

    /**
     * Minima Receiver will need to check The MinimaID
     *
     * Call this from your Main Minima Receiver
     */
    public static boolean checkMinimaID(Context zContext, Intent zIntent){
        if (zIntent == null) return false;
        try {
            String expected = zContext.getSharedPreferences("minima_api_prefs", Context.MODE_PRIVATE)
                    .getString("minima_uid", "");
            String actual = zIntent.getStringExtra(MinimaAPIMessages.MINIMA_API_REGISTER_MINIMAID);
            return expected != null && !expected.isEmpty() && expected.equals(actual);
        } catch (RuntimeException malformed) { return false; }
    }

    //Details used by the CMD receiver
    private String mPackage;
    private String MY_APP_ID;
    private String MINIMA_ID;

    private volatile boolean destroyed;
    // Discover only by REGISTER; commands (including writes) always target ONE pinned node.
    // Reuse the existing main-thread deadlines handler.
    private final java.util.Map<String, JSONObject> registrations = new java.util.LinkedHashMap<>();
    private final java.util.List<Runnable> awaitingNode = new java.util.ArrayList<>();
    private String nodePackage;
    private boolean discovering;
    private boolean ambiguous;
    private int discoveryGeneration;
    public static final String BLOCK_PACKAGE = "com.eurobuddha.minimablock";
    public static final String PANDAMONIUM_PACKAGE = "com.eurobuddha.pandamonium";
    Context mContext;

    static final int MAX_PENDING = 128;
    // Longer than PandaDEX's three-minute write timeout, to capture late transaction IDs.
    // Expiry is an unknown outcome, never authority to replay a command.
    static final long REPLY_RETENTION_MS = 10 * 60_000L;
    final Handler deadlines = new Handler(Looper.getMainLooper());
    final Hashtable<String, MinimaAPIListener> mResponseHandlers = new Hashtable<>();
    final Set<String> claimed = new HashSet<>();
    final Map<String, Runnable> expiries = new HashMap<>();
    final Map<String, FutureTask<Void>> fileTasks = new HashMap<>();

    MinimaAPIReceive mMinimaAPIReceiver;

    //Large responses arrive as a content:// URI - read the file OFF the main
    //thread (ResponseReceived runs in a BroadcastReceiver on main)
    // Reuse ExportChecks' process-wide physical-worker bound. Re-registering must not
    // create another reader while an old provider ignores interruption.
    static final ThreadPoolExecutor mFileExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), r -> {
                Thread thread = new Thread(r, "dexicon-sdk-reply"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public MinimaAPI(Context zContext, MinimaAPIListener zRegisterListener){
        mContext = zContext;
        mPackage = zContext.getPackageName();

        // Preserve an existing node approval. Never silently rotate a partial/corrupt pair.
        try {
            synchronized (MinimaAPI.class) {
                SharedPreferences prefs = zContext.getSharedPreferences("minima_api_prefs", Context.MODE_PRIVATE);
                MY_APP_ID = prefs.getString("myapp_uid", "");
                MINIMA_ID = prefs.getString("minima_uid", "");
                if ("".equals(MY_APP_ID) && "".equals(MINIMA_ID)) {
                    MY_APP_ID = getRandomString();
                    MINIMA_ID = getRandomString();
                }
                if (!validPairingId(MY_APP_ID) || !validPairingId(MINIMA_ID))
                    throw new IllegalStateException("Incomplete node pairing");
                // Retry commit even when values already appear in memory after a failed disk write.
                if (!prefs.edit().putString("myapp_uid", MY_APP_ID).putString("minima_uid", MINIMA_ID).commit())
                    throw new IllegalStateException("Could not save node pairing");
            }
        } catch (RuntimeException unavailable) {
            destroyed = true;
            zRegisterListener.response(MinimaAPIResponse.failure("Node pairing could not be loaded or saved."));
            return;
        }

        //Create a Receiver..
        mMinimaAPIReceiver = new MinimaAPIReceive(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(MinimaAPIMessages.MINIMA_API_RESPONSE);

        ContextCompat.registerReceiver(zContext, mMinimaAPIReceiver, filter, ContextCompat.RECEIVER_EXPORTED);

        //Always send the Resgister broadcast
        Register(zRegisterListener);
    }

    static boolean validPairingId(String id) { return id != null && id.matches("0x[0-9a-fA-F]{32}"); }

    private String getRandomString() {
        //These IDs are the only thing authenticating broadcast replies to our EXPORTED
        //receiver, so they must be unguessable - never java.util.Random here.
        String SALTCHARS = "ABCDEF1234567890";
        StringBuilder salt = new StringBuilder();
        SecureRandom rnd = new SecureRandom();
        while (salt.length() < 32) { // length of the random string.
            salt.append(SALTCHARS.charAt(rnd.nextInt(SALTCHARS.length())));
        }
        return "0x"+salt.toString();
    }

    public void onDestroy(){
        destroyed = true;
        discoveryGeneration++;
        awaitingNode.clear();
        synchronized (mResponseHandlers) {
            mResponseHandlers.clear(); claimed.clear();
            for (Runnable expiry : expiries.values()) deadlines.removeCallbacks(expiry);
            expiries.clear();
            for (FutureTask<Void> task : fileTasks.values()) cancelRead(task);
            fileTasks.clear();
        }
        try{
            mContext.unregisterReceiver(mMinimaAPIReceiver);
        }catch(Exception exc){}
    }

    public void ResponseReceived(Intent zIntent){
        if (destroyed || zIntent == null) return;
        final String responseid, uristr, result;
        try {
            String minimaid = zIntent.getStringExtra(MinimaAPIMessages.MINIMA_API_REGISTER_MINIMAID);
            if (minimaid == null || minimaid.isEmpty() || !minimaid.equals(MINIMA_ID)) return;
            responseid = zIntent.getStringExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_ID);
            if (responseid == null || responseid.isEmpty() || !mResponseHandlers.containsKey(responseid)) return;
            uristr = zIntent.getStringExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_URI);
            result = zIntent.getStringExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_RESULT);
            if (uristr == null && (result == null || result.isEmpty())) return;
            if (uristr != null && (!"content".equals(Uri.parse(uristr).getScheme())
                    || Uri.parse(uristr).getAuthority() == null)) return;
        } catch (RuntimeException malformedExtras) {
            return;
        }
        synchronized (mResponseHandlers) {
            if (destroyed || !mResponseHandlers.containsKey(responseid) || !claimed.add(responseid)) return;
        }
        // Claim before scheduling: duplicate broadcasts cannot queue repeated URI reads.
        if (uristr != null) {
            FutureTask<Void> task = new FutureTask<>(() -> {
                synchronized (mResponseHandlers) {
                    if (destroyed || !mResponseHandlers.containsKey(responseid)) return;
                }
                JSONObject payload;
                try { payload = MinimaAPIResponse.parse(readResponseUri(uristr)); }
                catch (Exception exc) { payload = MinimaAPIResponse.failure("Could not read the node response file."); }
                deliver(responseid, payload);
            }, null);
            try {
                synchronized (mResponseHandlers) {
                    if (destroyed || !mResponseHandlers.containsKey(responseid)) return;
                    fileTasks.put(responseid, task);
                    mFileExecutor.execute(task);
                }
            } catch (RejectedExecutionException busy) {
                finish(responseid, MinimaAPIResponse.failure("Node response reader is busy."), true);
            }
        } else {
            deliver(responseid, MinimaAPIResponse.parse(result));
        }
    }

    private String readResponseUri(String uri) throws Exception {
        try (InputStream in = mContext.getContentResolver().openInputStream(Uri.parse(uri))) {
            if (in == null) throw new java.io.IOException("Response file unavailable");
            return MinimaAPIResponse.read(in);
        }
    }

    private static void cancelRead(FutureTask<Void> task) {
        task.cancel(true);
        // Cancellation may not stop provider I/O. Keep that physical worker occupied;
        // remove only queued work, never shut down or replace the shared executor.
        mFileExecutor.remove(task);
    }

    private void deliver(String id, JSONObject json) { finish(id, json, false); }

    private void finish(String id, JSONObject json, boolean cancelFile) {
        MinimaAPIListener listener;
        synchronized (mResponseHandlers) {
            listener = mResponseHandlers.remove(id);
            claimed.remove(id);
            FutureTask<Void> task = fileTasks.remove(id);
            if (cancelFile && task != null) cancelRead(task);
            Runnable expiry = expiries.remove(id);
            if (expiry != null) deadlines.removeCallbacks(expiry);
        }
        if (listener != null && !destroyed) listener.response(json);
    }

    private void dispatch(Intent intent, MinimaAPIListener listener) {
        String id = getRandomString();
        boolean accepted;
        synchronized (mResponseHandlers) {
            accepted = !destroyed && mResponseHandlers.size() < MAX_PENDING;
            if (accepted) {
                intent.putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_ID, id);
                mResponseHandlers.put(id, listener);
                Runnable expiry = () -> finish(id, MinimaAPIResponse.failure("Node response was not received. Outcome remains unknown."), true);
                expiries.put(id, expiry);
                deadlines.postDelayed(expiry, REPLY_RETENTION_MS);
            }
        }
        if (!accepted) {
            listener.response(MinimaAPIResponse.failure("Node connection is unavailable or has too many unanswered requests."));
            return;
        }
        try { mContext.sendBroadcast(intent); }
        catch (RuntimeException failure) {
            deliver(id, MinimaAPIResponse.failure("Could not dispatch the node request. Outcome is unknown."));
        }
    }

    private Intent getBaseIntent(String zType){
        //Create Intent
        Intent intent = new Intent(zType);

        //ALWAYS say who you are..
        intent.putExtra(MinimaAPIMessages.MINIMA_API_PACKAGE_CLASS, mPackage);

        //Add the PRIVATE app uid
        intent.putExtra(MinimaAPIMessages.MINIMA_API_APP_UID, MY_APP_ID);

        //Add the PRIVATE Minima uid
        intent.putExtra(MinimaAPIMessages.MINIMA_API_REGISTER_MINIMAID, MINIMA_ID);

        //Set to send ONLY to the Minima Core APK
        intent.setPackage(nodePackage == null ? MinimaAPIMessages.MINIMA_BASE_CLASS : nodePackage);

        return intent;
    }

    private void Register(MinimaAPIListener zListener){
        discovering = true;
        registrations.clear();
        final int generation = ++discoveryGeneration;
        final java.util.List<String> requestIds = new java.util.ArrayList<>();
        for (String target : new String[]{MinimaAPIMessages.MINIMA_BASE_CLASS, BLOCK_PACKAGE, PANDAMONIUM_PACKAGE}) {
            Intent intent = getBaseIntent(MinimaAPIMessages.MINIMA_API_REGISTER);
            intent.setPackage(target);
            dispatch(intent, reply -> deadlines.post(() -> {
                if (destroyed || !discovering || generation != discoveryGeneration
                        || !reply.optBoolean("status", false)) return;
                registrations.put(target, reply);
            }));
            requestIds.add(intent.getStringExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_ID));
        }
        deadlines.postDelayed(() -> {
            if (destroyed || generation != discoveryGeneration) return;
            for (String id : requestIds) {
                if (id != null) finish(id, MinimaAPIResponse.failure("Node discovery finished."), true);
            }
            discovering = false;
            ambiguous = registrations.size() > 1;
            nodePackage = registrations.size() == 1 ? registrations.keySet().iterator().next() : null;
            JSONObject result = nodePackage == null ? routingFailure() : registrations.get(nodePackage);
            java.util.List<Runnable> waiting = new java.util.ArrayList<>(awaitingNode);
            awaitingNode.clear();
            if (zListener != null) zListener.response(result);
            for (Runnable request : waiting) request.run();
        }, 1500);
    }

    private JSONObject routingFailure() {
        return MinimaAPIResponse.failure(ambiguous
                ? "Multiple Minima Core builds are running. Keep one running and reopen this companion. No command was sent."
                : "No Minima node replied. Start Minima Core (PandaBear, BlackBear or Pandamonium) and try again. No command was sent.");
    }

    private void withNode(Runnable request, MinimaAPIListener listener) {
        if (destroyed) {
            listener.response(MinimaAPIResponse.failure("Node connection is closed."));
            return;
        }
        if (nodePackage != null) { request.run(); return; }
        if (awaitingNode.size() >= MAX_PENDING) {
            listener.response(MinimaAPIResponse.failure("Too many requests awaiting node discovery."));
            return;
        }
        awaitingNode.add(() -> {
            if (nodePackage != null) request.run();
            else if (listener != null) listener.response(routingFailure());
        });
        if (!discovering) Register(null);
    }

    public void Command(String zCommand, MinimaAPIListener zListener){
        deadlines.post(() -> withNode(() -> sendCommand(zCommand, zListener), zListener));
    }

    private void sendCommand(String zCommand, MinimaAPIListener zListener){
        if (destroyed) {
            zListener.response(MinimaAPIResponse.failure("Node connection is closed."));
            return;
        }

        //Create the register Intent
        Intent intent = getBaseIntent(MinimaAPIMessages.MINIMA_API_CMD);

        //What you expect from Minima responses
        intent.putExtra(MinimaAPIMessages.MINIMA_API_CMD_ACTION, zCommand);

        //We can consume oversized results as a content:// file (old nodes ignore this)
        intent.putExtra(MinimaAPIMessages.MINIMA_API_CMD_FILERESP, true);

        dispatch(intent, zListener);
    }

    /**
     * File bridge - operate on files inside the node's base folder. ADMIN-gated on the node.
     *
     * @param zAction  list | get | put | mkdir | move | delete
     * @param zPath    path relative to the node's base folder ("/" = root)
     * @param zNewPath move only - the destination path (relative), otherwise null
     * @param zUri     put only - a content:// uri this app has granted the node read on, otherwise null
     */
    public void FileCommand(String zAction, String zPath, String zNewPath, Uri zUri, MinimaAPIListener zListener){
        deadlines.post(() -> withNode(() -> sendFileCommand(zAction, zPath, zNewPath, zUri, zListener), zListener));
    }

    private void sendFileCommand(String zAction, String zPath, String zNewPath, Uri zUri, MinimaAPIListener zListener){
        if (destroyed) {
            zListener.response(MinimaAPIResponse.failure("Node connection is closed."));
            return;
        }

        Intent intent = getBaseIntent(MinimaAPIMessages.MINIMA_API_FILE);

        intent.putExtra(MinimaAPIMessages.MINIMA_API_FILE_ACTION, zAction);
        intent.putExtra(MinimaAPIMessages.MINIMA_API_FILE_PATH, zPath);

        if(zNewPath != null){
            intent.putExtra(MinimaAPIMessages.MINIMA_API_FILE_NEWPATH, zNewPath);
        }

        if(zUri != null){
            intent.putExtra(MinimaAPIMessages.MINIMA_API_FILE_URI, zUri.toString());

            //Belt and braces - the caller should ALSO grantUriPermission to the node package,
            //but a ClipData grant travels with the Intent on newer Android
            intent.setClipData(android.content.ClipData.newRawUri("minima_file", zUri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        //Oversized results (huge directory listings) may come back as a content:// file
        intent.putExtra(MinimaAPIMessages.MINIMA_API_CMD_FILERESP, true);

        dispatch(intent, zListener);
    }
}

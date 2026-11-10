// VENDORED VERBATIM from apks/pandapools 0.9.97 app/src/main/java/com/eurobuddha/pandapools/NodeBackupCapture.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Captures native wallet state through the existing command and FILE transports. Never exports a seed.
 * Only successful native backup + matching wallet checks + verified encrypted bundle can succeed. */
final class NodeBackupCapture {
    interface Done { void ready(File encrypted, String poolJson); void failed(String reason); }
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean running;
    static File directory(Context context) throws IOException {
        File directory = new File(context.getCacheDir(), "combined-backups");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private backup storage.");
        return directory;
    }
    static void start(Context context, String poolJson, String password, Done done) {
        if (running) { done.failed("BACKUP_BUSY: a combined backup is already running."); return; }
        if (!PoolBackupBundle.validPassword(password)) { done.failed("PASSWORD_REQUIRED: use at least 12 characters."); return; }
        running = true;
        try { new Capture(context.getApplicationContext(), new NodeApi(context.getApplicationContext(), null), poolJson, password, done).start(); }
        catch(Exception failure){running=false;done.failed("NODE_UNAVAILABLE: could not start backup. Reconnect to the node and retry.");}
    }
    private static final class Capture {
        final Context context; final NodeApi node; final String json; final Done done;
        String password, inner = PoolBackupBundle.newNodePassword();
        final String name = "pandapools-node-" + UUID.randomUUID() + ".bak";
        String fingerprint, mode; boolean ended, requested;
        Capture(Context context, NodeApi node, String json, String password, Done done) {
            this.context=context;this.node=node;this.json=json;this.password=password;this.done=done;
        }
        void start() {
            String invalid = BackupState.verifyExport(json, OwnPoolStore.all(context), 0);
            if (invalid != null) { fail("POOL_BACKUP_INVALID: " + invalid); return; }
            node.cmd("status", new NodeApi.Cb() {
                public void onResult(JSONObject reply) {
                    JSONObject r = reply.optJSONObject("response");
                    if (!TxPost.truthy(reply,"status") || r==null || !Boolean.FALSE.equals(r.opt("locked"))) {
                        fail("WALLET_LOCKED: unlock the node wallet before making its complete backup."); return;
                    }
                    readMode();
                }
                public void onError(String ignored) { fail("NODE_UNAVAILABLE: connect to the node before backing up."); }
            });
        }
        void readMode() {
            node.cmd(BlockOwnerRecovery.MODE_COMMAND, new NodeApi.Cb() {
                public void onResult(JSONObject reply) {
                    BlockOwnerRecovery.Mode actual=BlockOwnerRecovery.mode(reply);
                    if(actual==BlockOwnerRecovery.Mode.UNKNOWN){fail("KEY_MODE_UNKNOWN: cannot identify the wallet's key mode.");return;}
                    mode=actual==BlockOwnerRecovery.Mode.BLOCK?"block":"classic";
                    node.cmd("keys", new NodeApi.Cb() {
                        public void onResult(JSONObject keys) {
                            fingerprint=BlockOwnerRecovery.fingerprint(keys);
                            if(fingerprint==null||!ownersMatch(keys)){fail("OWNER_KEYS_MISSING: this wallet does not contain every saved pool's owner key and a usable counter. Restore the matching wallet first.");return;}
                            createNodeBackup();
                        }
                        public void onError(String ignored){fail("WALLET_UNREADABLE: could not read owner keys. Nothing exported.");}
                    });
                }
                public void onError(String ignored){fail("KEY_MODE_UNKNOWN: node did not answer the key-mode check.");}
            });
        }
        boolean ownersMatch(JSONObject keys) { return BlockOwnerRecovery.backupOwnersMatch(json, keys, mode); }
        void createNodeBackup() {
            requested=true;
            node.cmd("backup file:"+name+" password:"+inner+" confirm:"+inner, new NodeApi.Cb() {
                public void onResult(JSONObject reply) {
                    if(!TxPost.truthy(reply,"status")||TxPost.truthy(reply,"pending")){
                        fail("NODE_BACKUP_FAILED: the node did not confirm a complete backup. Check app permissions and node storage.");return;
                    }
                    // Retrieve only our generated filename. Never follow a path returned by a backup or node reply.
                    node.file("get",name,null,null,new NodeApi.Cb(){
                        public void onResult(JSONObject result){
                            if(!TxPost.truthy(result,"status")){fail("NODE_FILE_ACCESS: enable this app's Admin file access in Minima Core, then retry.");return;}
                            Uri uri=Uri.parse(result.optString("uri",""));
                            if(!"content".equals(uri.getScheme())){fail("NODE_BACKUP_FILE: node did not provide a readable backup file.");return;}
                            long expected=result.optLong("size",-1);
                            if(expected<32||expected>PoolBackupBundle.MAX_NODE){fail("NODE_BACKUP_SIZE: node backup is empty or exceeds 128 MiB.");return;}
                            verifyWallet(uri,expected);
                        }
                        public void onError(String ignored){fail("NODE_FILE_ACCESS: this node could not export its backup. Update the node or enable Admin file access.");}
                    });
                }
                public void onError(String ignored){fail("NODE_BACKUP_UNCONFIRMED: the node did not finish in time. Wait for its backup task before retrying.");}
            });
        }
        void verifyWallet(Uri uri,long expected) {
            node.cmd("keys",new NodeApi.Cb(){
                public void onResult(JSONObject keys){
                    if(!fingerprint.equals(BlockOwnerRecovery.fingerprint(keys))||!ownersMatch(keys)){
                        fail("WALLET_CHANGED: the wallet changed during backup. Nothing exported; retry on the correct wallet.");return;
                    }
                    IO.execute(()->build(uri,expected));
                }
                public void onError(String ignored){fail("WALLET_UNREADABLE: could not confirm which wallet was backed up.");}
            });
        }
        void build(Uri uri,long expected) {
            File nodeFile=null,bundle=null;
            try {
                File directory=directory(context);
                nodeFile=File.createTempFile("capture-",".bak",directory);
                try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(nodeFile)){
                    if(PoolBackupBundle.transfer(in,out,PoolBackupBundle.MAX_NODE)!=expected)throw new IOException("NODE_BACKUP_TRUNCATED: file size changed during export.");
                }
                bundle=File.createTempFile("pandapools-full-",".ppbackup",directory);
                PoolBackupBundle.create(password,json,fingerprint,mode,inner,nodeFile,bundle,directory);
                final File output=bundle;
                MAIN.post(()->finish(output));
            }catch(Exception failure){
                if(bundle!=null)bundle.delete();
                MAIN.post(()->fail("BACKUP_BUILD_FAILED: could not encrypt and verify the complete backup. Check free storage and retry."));
            }finally{if(nodeFile!=null)nodeFile.delete();password=null;inner=null;}
        }
        void finish(File bundle){
            if(ended){bundle.delete();return;}ended=true;running=false;cleanup();done.ready(bundle,json);
        }
        void fail(String reason){
            if(ended)return;ended=true;running=false;password=null;inner=null;cleanup();done.failed(reason);
        }
        void cleanup(){
            if(!requested){node.onDestroy();return;}
            node.file("delete",name,null,null,new NodeApi.Cb(){public void onResult(JSONObject ignored){node.onDestroy();}public void onError(String ignored){node.onDestroy();}});
        }
    }
}

// VENDORED VERBATIM from apks/pandapools 0.9.84 app/src/main/java/com/eurobuddha/pandapools/CombinedBackupUi.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import android.net.Uri;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Required combined backup UI. Passwords stay in memory; save is complete only after read-back verification. */
final class CombinedBackupUi {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    static void backup(MainActivity act, Recovery recovery, List<Pool> live, Consumer<String> status, Runnable done) {
        password(act, true, pass -> {
            status.accept("Preparing encrypted pool + node backup…");
            final List<Pool> owned = OwnPoolStore.all(act);
            recovery.backup(act, live, act.chainBlock(), new Recovery.BackupCb() {
                public void onError(String message) { status.accept(message); done.run(); }
                public void onBackup(String json) {
                    NodeBackupCapture.start(act, json, pass, new NodeBackupCapture.Done() {
                        public void failed(String reason) { status.accept(reason); done.run(); show(act,"Backup still required",reason); }
                        public void ready(File encrypted,String recipes) {
                            if(act.isFinishing()||act.isDestroyed()){encrypted.delete();done.run();return;}
                            act.pickSaveFile("pandapools-full-"+System.currentTimeMillis()+".ppbackup",uri->{
                                if(uri==null){encrypted.delete();status.accept("Backup not saved. Save it before creating another pool.");done.run();return;}
                                status.accept("Saving and checking the encrypted file…");
                                IO.execute(()->{
                                    String problem=null;
                                    try {
                                        File readBack=File.createTempFile("readback-",".ppbackup",NodeBackupCapture.directory(act));
                                        try {
                                            long expected=encrypted.length();String hash=PoolBackupBundle.digest(encrypted,PoolBackupBundle.MAX_PAYLOAD+64);
                                            try(InputStream in=new FileInputStream(encrypted);OutputStream out=act.getContentResolver().openOutputStream(uri,"w")) {
                                                if(PoolBackupBundle.transfer(in,out,PoolBackupBundle.MAX_PAYLOAD+64)!=expected)throw new IOException("Incomplete export");
                                            }
                                            try(InputStream in=act.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(readBack)) {
                                                if(PoolBackupBundle.transfer(in,out,PoolBackupBundle.MAX_PAYLOAD+64)!=expected)throw new IOException("Saved file size differs");
                                            }
                                            if(!hash.equals(PoolBackupBundle.digest(readBack,PoolBackupBundle.MAX_PAYLOAD+64)))throw new IOException("Saved bytes differ");
                                            String invalid=BackupState.verifyExport(recipes,OwnPoolStore.all(act),0);
                                            if(invalid!=null)throw new IOException("Pool list changed during backup");
                                            if(!BackupState.recordCombinedExport(act,owned))throw new IOException("Could not record verification");
                                        }finally{readBack.delete();}
                                    }catch(Exception failure){problem="BACKUP_NOT_VERIFIED: the saved file could not be read back intact, or verification could not be recorded. Try a different save location and check free space. The backup requirement is still outstanding.";}
                                    finally{encrypted.delete();}
                                    final String error=problem;
                                    act.runOnUiThread(()->{
                                        done.run();if(act.isFinishing()||act.isDestroyed())return;
                                        if(error!=null){status.accept(error);show(act,"Backup still required",error);return;}
                                        status.accept("Encrypted pool + node backup saved and verified.");
                                        show(act,"Complete backup verified","The saved file contains your pool recipes and encrypted node backup, including wallet keys and backup-time signing state. Keep this file and its password safe, preferably off this device.\n\nFuture wallet activity can make signing state in this backup stale. Recovery checks the state it can verify and refreshes reserve proofs when needed.");
                                        act.pools().refresh();
                                    });
                                });
                            });
                        }
                    });
                }
            });
        }, done);
    }
    /** Authenticate a combined file before exposing its recipes; legacy public JSON remains readable. */
    static void inspect(MainActivity act,Uri uri,Consumer<String> recipes,Consumer<String> status){open(act,uri,recipes,status,false);}
    static void open(MainActivity act,Uri uri,Consumer<String> recipes,Consumer<String> status){open(act,uri,recipes,status,true);}
    private static void open(MainActivity act, Uri uri, Consumer<String> recipes, Consumer<String> status,boolean offerActions) {
        status.accept("Reading backup…");
        IO.execute(()->{
            File saved=null;
            try {
                saved=File.createTempFile("selected-",".ppbackup",NodeBackupCapture.directory(act));
                try(InputStream in=act.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(saved)){
                    PoolBackupBundle.transfer(in,out,PoolBackupBundle.MAX_PAYLOAD+64);
                }
                byte[] magic=new byte[4];try(InputStream in=new FileInputStream(saved)){if(in.read(magic)!=4)throw new IOException();}
                final File source=saved;
                if(!Arrays.equals(magic,new byte[]{77,86,76,84})){
                    if(source.length()>4*1024*1024)throw new IOException();
                    String json=new String(java.nio.file.Files.readAllBytes(source.toPath()),java.nio.charset.StandardCharsets.UTF_8);
                    new org.json.JSONObject(json);source.delete();
                    act.runOnUiThread(()->{if(!act.isFinishing()&&!act.isDestroyed())recipes.accept(json);});return;
                }
                act.runOnUiThread(()->{
                    if(act.isFinishing()||act.isDestroyed()){source.delete();return;}
                    password(act,false,pass->IO.execute(()->{
                        PoolBackupBundle.Opened candidate=null;boolean handedOff=false;
                        try {
                            PoolBackupBundle.Opened opened=PoolBackupBundle.open(pass,source,NodeBackupCapture.directory(act));
                            candidate=opened;
                            org.json.JSONArray entries=opened.manifest.getJSONObject("pools").getJSONArray("pools");
                            for(int i=0;i<entries.length();i++)if(!Recovery.validRecipe(entries.getJSONObject(i))){opened.nodeFile.delete();throw new IOException();}
                            act.runOnUiThread(()->{
                                if(offerActions)offerRestore(act,opened,recipes,status);
                                else {opened.nodeFile.delete();if(!act.isFinishing()&&!act.isDestroyed())recipes.accept(opened.poolJson());}
                            });handedOff=true;
                        }catch(Exception failure){act.runOnUiThread(()->status.accept("BACKUP_INVALID: wrong password, damaged file, or invalid pool recipe. Nothing restored."));}
                        finally{source.delete();if(!handedOff&&candidate!=null)candidate.nodeFile.delete();}
                    }),()->{source.delete();status.accept("Restore cancelled.");});
                });
            }catch(Exception failure){if(saved!=null)saved.delete();act.runOnUiThread(()->status.accept("BACKUP_UNREADABLE: select a complete .ppbackup or legacy pool JSON file."));}
        });
    }
    private static void offerRestore(MainActivity act,PoolBackupBundle.Opened opened,Consumer<String> recipes,Consumer<String> status) {
        if(act.isFinishing()||act.isDestroyed()){opened.nodeFile.delete();return;}
        new AlertDialog.Builder(act).setTitle("Encrypted backup verified")
            .setMessage("This file contains pool recipes and a complete node backup.\n\nIf this is still the original wallet, recover the pools on it. Replacing the node with an older backup can roll back signing state; do that only when the original wallet is unavailable. Signing stays paused until current state is established.")
            .setPositiveButton("Recover pools",(d,w)->{
                NodeApi node=act.node();
                if(node==null){opened.nodeFile.delete();status.accept("NODE_UNAVAILABLE: connect to your wallet first.");return;}
                node.cmd("keys",new NodeApi.Cb(){
                    public void onResult(org.json.JSONObject keys){
                        opened.nodeFile.delete();
                        if(!opened.manifest.optString("wallet").equalsIgnoreCase(BlockOwnerRecovery.fingerprint(keys))){status.accept("WALLET_MISMATCH: this node is not the wallet in this backup. Open the file again and choose Node backup to restore on the matching node.");return;}
                        recipes.accept(opened.poolJson());
                    }
                    public void onError(String ignored){opened.nodeFile.delete();status.accept("WALLET_UNREADABLE: could not confirm this wallet. Nothing restored.");}
                });
            })
            .setNeutralButton("Node backup",(d,w)->exportNode(act,opened,status))
            .setNegativeButton("Cancel",(d,w)->opened.nodeFile.delete()).setCancelable(false).show();
    }
    private static void exportNode(MainActivity act,PoolBackupBundle.Opened opened,Consumer<String> status) {
        // A separate node app owns its restart lifecycle. Never issue a destructive command through ordinary IPC.
        act.pickSaveFile("pandapools-node-recovery-"+System.currentTimeMillis()+".bak",uri->{
            if(uri==null){opened.nodeFile.delete();return;}
            IO.execute(()->{
                try {
                    try(InputStream in=new FileInputStream(opened.nodeFile);OutputStream out=act.getContentResolver().openOutputStream(uri,"w")){
                        PoolBackupBundle.transfer(in,out,PoolBackupBundle.MAX_NODE);
                    }
                    File read=File.createTempFile("node-check-",".bak",NodeBackupCapture.directory(act));
                    try {
                        try(InputStream in=act.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(read)){PoolBackupBundle.transfer(in,out,PoolBackupBundle.MAX_NODE);}
                        if(!PoolBackupBundle.digest(read,PoolBackupBundle.MAX_NODE).equals(opened.manifest.optString("nodeSha256")))throw new IOException();
                    }finally{read.delete();}
                    act.runOnUiThread(()->{
                        if(act.isFinishing()||act.isDestroyed())return;
                        TextView text=new TextView(act);text.setTextIsSelectable(true);int pad=Ui.dp(act,20);text.setPadding(pad,pad,pad,pad);
                        text.setText("Import the saved .bak through Minima Core → Restore backup. Use this native backup password:\n\n"+opened.nodePassword()+"\n\nFor an old backup, select MegaMMR resync with eurobuddha.com:9001. This replaces the node wallet; preserve any existing wallet first. After the node is ready, reopen your .ppbackup here and choose Recover pools. Signing remains paused until its current state is confirmed.");
                        AlertDialog dialog=new AlertDialog.Builder(act).setTitle("Node backup saved and checked").setView(text).setPositiveButton("Done",null).create();
                        dialog.setOnDismissListener(d->text.setText(""));dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                        status.accept("Node backup exported. Restore it through the node's backup screen, then recover the pool recipes here.");
                    });
                }catch(Exception failure){act.runOnUiThread(()->status.accept("NODE_EXPORT_FAILED: could not save and verify the native backup. Your combined file is unchanged."));}
                finally{opened.nodeFile.delete();}
            });
        });
    }

    interface PasswordUse { void accept(String password); }
    static void password(MainActivity act,boolean creating,PasswordUse use,Runnable cancelled) {
        LinearLayout body=new LinearLayout(act);body.setOrientation(LinearLayout.VERTICAL);int pad=Ui.dp(act,20);body.setPadding(pad,pad,pad,pad);
        TextView help=new TextView(act);help.setText(creating?"This file will include your wallet keys. Choose a password of at least 12 characters, ideally several random words. You will need it to recover. There is no password reset.":"Enter the password for this combined pool + node backup.");body.addView(help);
        EditText first=new EditText(act);first.setHint("Backup password");protect(first);body.addView(first);
        EditText second=new EditText(act);second.setHint("Repeat password");protect(second);if(creating)body.addView(second);
        boolean wasSecure=(act.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0;
        act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        final boolean[] accepted={false};
        AlertDialog dialog=new AlertDialog.Builder(act).setTitle(creating?"Required encrypted backup":"Open encrypted backup")
                .setView(body).setPositiveButton(creating?"Create backup":"Open backup",null)
                .setNegativeButton("Cancel",(d,w)->{}).setCancelable(false).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String pass=first.getText().toString();
            if(creating&&!PoolBackupBundle.validPassword(pass)){first.setError("Use at least 12 characters (maximum 1024 UTF-8 bytes).");return;}
            if(!creating&&(pass.isEmpty()||pass.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1024)){first.setError("Enter the backup password.");return;}
            if(creating&&!pass.equals(second.getText().toString())){second.setError("Passwords do not match.");return;}
            accepted[0]=true;first.setText("");second.setText("");dialog.dismiss();use.accept(pass);
        }));
        dialog.setOnDismissListener(ignored->{first.setText("");second.setText("");if(!wasSecure)act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);if(!accepted[0])cancelled.run();});
        dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private static void protect(EditText input){
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);input.setSingleLine(true);input.setSaveEnabled(false);
        input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
    }
    static void show(MainActivity act,String title,String message){if(!act.isFinishing()&&!act.isDestroyed())new AlertDialog.Builder(act).setTitle(title).setMessage(message).setPositiveButton("OK",null).show();}
}

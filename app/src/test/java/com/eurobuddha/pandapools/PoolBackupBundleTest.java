// VENDORED VERBATIM from apks/pandapools 0.10.9 app/src/test/java/com/eurobuddha/pandapools/PoolBackupBundleTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
import static org.junit.Assert.*;
public class PoolBackupBundleTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    final String password="🔐 café horse battery staple",wallet="0x"+"12".repeat(32);
    private File bundle() throws Exception {
        File node=folder.newFile();byte[] bytes=new byte[128];Arrays.fill(bytes,(byte)42);Files.write(node.toPath(),bytes);
        File file=folder.newFile();String recipes=new JSONObject().put("pandapools_backup",3).put("pools",new JSONArray().put(new JSONObject().put("addr",wallet))).toString();
        PoolBackupBundle.create(password,recipes,wallet,"block","ab".repeat(32),node,file,folder.getRoot());return file;
    }
    @Test public void completeBundlePreservesBytesAndMetadata() throws Exception {
        PoolBackupBundle.Opened opened=PoolBackupBundle.open(password,bundle(),folder.getRoot());
        assertEquals(128,opened.nodeFile.length());assertEquals(42,Files.readAllBytes(opened.nodeFile.toPath())[0]);
        assertEquals(wallet,opened.manifest.getString("wallet"));assertEquals("ab".repeat(32),opened.nodePassword());opened.nodeFile.delete();
    }
    @Test public void damagedWrongPasswordAndTruncatedFilesNeverExtract() throws Exception {
        File file=bundle();byte[] original=Files.readAllBytes(file.toPath());
        for(int mode=0;mode<4;mode++){
            byte[] changed=original.clone();String pass=password;
            if(mode==0)pass="a different password";
            if(mode==1)changed[changed.length-1]^=1;
            if(mode==2)changed=Arrays.copyOf(changed,changed.length-1);
            if(mode==3)changed[6]=127; // hostile KDF work factor rejected before derivation
            Files.write(file.toPath(),changed);int before=folder.getRoot().list().length;
            try{PoolBackupBundle.open(pass,file,folder.getRoot());fail("accepted damaged backup");}catch(Exception expected){}
            assertEquals("failed authentication must leave no extracted files",before,folder.getRoot().list().length);
        }
    }
    @Test public void emptyNodeAndWeakPasswordsCannotBeExported() throws Exception {
        assertFalse(PoolBackupBundle.validPassword("short"));assertFalse(PoolBackupBundle.validPassword("🔐".repeat(11)));
        assertTrue(PoolBackupBundle.validPassword("🔐".repeat(12)));
        File empty=folder.newFile();
        try{PoolBackupBundle.create(password,"{}",wallet,"block","ab".repeat(32),empty,folder.newFile(),folder.getRoot());fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("NODE_BACKUP_SIZE"));}
    }
    @Test public void metadataTypesAreStrict() throws Exception {
        PoolBackupBundle.Opened opened=PoolBackupBundle.open(password,bundle(),folder.getRoot());opened.nodeFile.delete();
        JSONObject m=opened.manifest;m.put("nodeBytes","128");
        try{PoolBackupBundle.validateManifest(m);fail();}catch(IOException expected){}
        m.put("nodeBytes",128).put("created",1.5);
        try{PoolBackupBundle.validateManifest(m);fail();}catch(IOException expected){}
    }
    @Test public void writerCannotProduceUnsupportedKdfParameters() throws Exception {
        for (int rounds : new int[]{0, 1, BackupVault.DEFAULT_ITERATIONS - 1, Integer.MAX_VALUE}) {
            try { BackupVault.encrypt(password, new byte[32], rounds); fail("unsupported KDF accepted"); }
            catch (java.security.GeneralSecurityException expected) { assertTrue(expected.getMessage().contains("iteration count")); }
        }
    }

}

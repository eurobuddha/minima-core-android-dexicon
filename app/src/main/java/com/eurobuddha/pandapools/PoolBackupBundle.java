// VENDORED VERBATIM from apks/pandapools 0.9.89 app/src/main/java/com/eurobuddha/pandapools/PoolBackupBundle.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Portable encrypted node + pool bundle. Public recipe validation stays in Recovery/BackupState.
 * Inner node backup remains in Minima's native format; its random password is inside the authenticated wrapper.
 * Plain staging contains encrypted node bytes, public recipes and the inner password; keep it app-private.
 */
public final class PoolBackupBundle {
    public static final int VERSION = 1, MAX_MANIFEST = 4 * 1024 * 1024;
    public static final long MAX_NODE = 128L * 1024 * 1024, MAX_PAYLOAD = MAX_NODE + MAX_MANIFEST + 8;
    private static final int MAGIC = 0x50504231; // PPB1, inside authenticated encryption
    public static final class Opened {
        public final JSONObject manifest;
        public final File nodeFile;
        Opened(JSONObject manifest, File nodeFile) { this.manifest = manifest; this.nodeFile = nodeFile; }
        public String poolJson() { return manifest.optJSONObject("pools").toString(); }
        public String nodePassword() { return manifest.optString("nodePassword"); }
    }
    private PoolBackupBundle() {}
    public static boolean validPassword(String password) {
        return password != null && password.codePointCount(0, password.length()) >= 12
                && password.getBytes(StandardCharsets.UTF_8).length <= 1024;
    }
    public static String newNodePassword() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return hex(bytes);
    }
    public static void create(String password, String poolJson, String fingerprint, String keyMode,
                              String nodePassword, File nodeFile, File destination, File workDirectory) throws Exception {
        if (!validPassword(password)) throw new IOException("PASSWORD_REQUIRED: use at least 12 characters.");
        long size = nodeFile.length();
        if (!nodeFile.isFile() || size < 32 || size > MAX_NODE) throw new IOException("NODE_BACKUP_SIZE: node backup is empty, missing or too large.");
        JSONObject manifest = new JSONObject().put("format", "pandapools_full_backup").put("version", VERSION)
                .put("network", "minima-mainnet").put("created", System.currentTimeMillis())
                .put("wallet", fingerprint).put("keyMode", keyMode).put("nodePassword", nodePassword)
                .put("nodeBytes", size).put("nodeSha256", digest(nodeFile, MAX_NODE)).put("pools", new JSONObject(poolJson));
        validateManifest(manifest);
        byte[] json = manifest.toString().getBytes(StandardCharsets.UTF_8);
        if (json.length > MAX_MANIFEST) throw new IOException("BACKUP_MANIFEST_TOO_LARGE");
        File plain = File.createTempFile("pp-bundle-", ".private", workDirectory);
        boolean complete = false;
        try {
            try (DataOutputStream out = new DataOutputStream(new FileOutputStream(plain)); InputStream in = new FileInputStream(nodeFile)) {
                out.writeInt(MAGIC); out.writeInt(json.length); out.write(json);
                if (transfer(in, out, MAX_NODE) != size) throw new IOException("NODE_BACKUP_CHANGED");
            }
            try (InputStream in = new FileInputStream(plain); OutputStream out = new FileOutputStream(destination)) {
                BackupVault.encryptStream(password, in, out, MAX_PAYLOAD);
            }
            // Authenticate and validate the exact bytes before offering this file for export.
            File verifyDir = new File(workDirectory, "verify-" + UUID.randomUUID());
            if (!verifyDir.mkdir()) throw new IOException("BACKUP_STORAGE: cannot create verification directory.");
            try { Opened checked = open(password, destination, verifyDir); checked.nodeFile.delete(); }
            finally { verifyDir.delete(); }
            complete = true;
        } finally { plain.delete(); if (!complete) destination.delete(); }
    }
    public static Opened open(String password, File source, File workDirectory) throws Exception {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 1024) throw new IOException("PASSWORD_INVALID");
        if (!source.isFile() || source.length() > MAX_PAYLOAD + 64) throw new IOException("BACKUP_SIZE_INVALID");
        File plain = File.createTempFile("pp-open-", ".private", workDirectory);
        File node = null; boolean complete = false;
        try {
            try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(plain)) {
                BackupVault.decryptStream(password, in, out, MAX_PAYLOAD);
            }
            // No parsing, file extraction or node command occurs before authenticated decryption finishes.
            try (DataInputStream in = new DataInputStream(new FileInputStream(plain))) {
                if (in.readInt() != MAGIC) throw new IOException("BACKUP_TYPE: not a combined PandaPools backup.");
                int length = in.readInt();
                if (length < 2 || length > MAX_MANIFEST || length > plain.length() - 8) throw new IOException("BACKUP_MANIFEST_INVALID");
                byte[] json = new byte[length]; in.readFully(json);
                JSONObject manifest = new JSONObject(StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(json)).toString()); validateManifest(manifest);
                long expected = manifest.getLong("nodeBytes");
                if (plain.length() - 8 - length != expected) throw new IOException("NODE_BACKUP_SIZE: incomplete node backup.");
                // Never extract a path supplied by a backup. Only a generated file in caller's private directory.
                node = File.createTempFile("pp-node-", ".bak", workDirectory);
                try (OutputStream out = new FileOutputStream(node)) {
                    if (transfer(in, out, MAX_NODE) != expected) throw new IOException("NODE_BACKUP_TRUNCATED");
                }
                if (!MessageDigest.isEqual(manifest.getString("nodeSha256").getBytes(StandardCharsets.US_ASCII), digest(node, MAX_NODE).getBytes(StandardCharsets.US_ASCII)))
                    throw new IOException("NODE_BACKUP_HASH: node backup integrity check failed.");
                complete = true; return new Opened(manifest, node);
            }
        } finally { plain.delete(); if (!complete && node != null) node.delete(); }
    }
    static void validateManifest(JSONObject m) throws Exception {
        if (!"pandapools_full_backup".equals(m.optString("format")) || strictInteger(m, "version") != VERSION
                || !"minima-mainnet".equals(m.optString("network"))) throw new IOException("BACKUP_VERSION_OR_NETWORK: incompatible bundle.");
        if (!m.optString("wallet").matches("0x[0-9a-fA-F]{64}")
                || !("block".equals(m.optString("keyMode")) || "classic".equals(m.optString("keyMode")))) throw new IOException("BACKUP_WALLET_IDENTITY_MISSING");
        if (!m.optString("nodePassword").matches("[a-f0-9]{64}") || !m.optString("nodeSha256").matches("[a-f0-9]{64}")
                || strictInteger(m, "nodeBytes") < 32 || strictInteger(m, "nodeBytes") > MAX_NODE || strictInteger(m, "created") <= 0) throw new IOException("BACKUP_NODE_METADATA_INVALID");
        JSONObject pool = m.optJSONObject("pools"); JSONArray entries = pool == null ? null : pool.optJSONArray("pools");
        if (pool == null || strictInteger(pool, "pandapools_backup") < 1 || strictInteger(pool, "pandapools_backup") > 3
                || entries == null || entries.length() < 1 || entries.length() > 500) throw new IOException("BACKUP_POOL_RECIPES_MISSING");
    }
    private static long strictInteger(JSONObject object, String name) throws IOException {
        Object value=object.opt(name);
        if(!(value instanceof Number))throw new IOException("BACKUP_METADATA_TYPE");
        try {
            long number=new java.math.BigDecimal(value.toString()).longValueExact();
            if(number<0||number>9007199254740991L)throw new IOException("BACKUP_METADATA_RANGE");
            return number;
        }catch(ArithmeticException|NumberFormatException bad){throw new IOException("BACKUP_METADATA_TYPE");}
    }
    public static long transfer(InputStream in, OutputStream out, long maximum) throws IOException {
        if (in == null || out == null) throw new IOException("BACKUP_FILE_UNAVAILABLE");
        long total = 0; byte[] buffer = new byte[65536]; int n;
        while ((n = in.read(buffer)) != -1) { total += n; if (total > maximum) throw new IOException("BACKUP_TOO_LARGE"); out.write(buffer, 0, n); }
        return total;
    }
    public static String digest(File file, long maximum) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file); OutputStream out = new DigestOutputStream(new OutputStream() { public void write(int b) {} public void write(byte[] b,int o,int n) {} }, digest)) {
            transfer(in, out, maximum);
        }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(bytes.length * 2); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b)); return out.toString(); }
}

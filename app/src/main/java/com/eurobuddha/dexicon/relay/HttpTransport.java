package com.eurobuddha.dexicon.relay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** HttpURLConnection transport. Read timeout covers a 25 s long-poll; bodies are capped at 1 MB. */
public final class HttpTransport implements RelayClient.Transport {
    private static final int MAX = 1024 * 1024;
    private final int readTimeoutMs;

    public HttpTransport(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public HttpTransport() { this(40_000); }

    @Override public String get(String url) throws IOException { return req("GET", url, null); }
    @Override public String post(String url, String body) throws IOException { return req("POST", url, body); }

    private String req(String method, String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(readTimeoutMs);
            c.setUseCaches(false);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = c.getOutputStream()) { o.write(b); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("HTTP " + code);
            String text = read(in);
            // The relay answers 4xx/5xx with a JSON {ok:false,error} body; hand it back so the caller can show the reason.
            if (code >= 400 && (text.isEmpty() || !text.trim().startsWith("{"))) throw new IOException("HTTP " + code);
            return text;
        } finally { c.disconnect(); }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream i = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n, total = 0;
            while ((n = i.read(buf)) != -1) { total += n; if (total > MAX) throw new IOException("relay reply too large"); out.write(buf, 0, n); }
            return out.toString("UTF-8");
        }
    }
}

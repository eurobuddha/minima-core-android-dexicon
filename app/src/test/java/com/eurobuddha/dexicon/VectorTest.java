package com.eurobuddha.dexicon;

import static org.junit.Assert.*;

import com.eurobuddha.dexicon.envelope.Envelope;
import com.eurobuddha.dexicon.envelope.Envelopes;
import com.eurobuddha.dexicon.envelope.Json;
import com.eurobuddha.dexicon.identity.DexIdentity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Byte-identity with mds/dexicon/spec/vectors/order-v1-vector.json: same seed → same keys → same canonical → same sig. */
public class VectorTest {

    private static JSONObject vector() throws Exception {
        try (InputStream in = VectorTest.class.getResourceAsStream("/order-v1-vector.json")) {
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private static List<String> list(JSONArray a) { List<String> l = new ArrayList<>(); for (int i = 0; i < a.length(); i++) l.add(a.optString(i)); return l; }

    @Test public void identityAndSignatureMatchTheReferenceVector() throws Exception {
        JSONObject V = vector(), v = V.getJSONObject("envelope");
        DexIdentity me = TestIds.id("11");
        assertEquals("signPk", V.getString("signPk"), me.signPkHex());
        assertEquals("pubId", v.getString("pubId"), me.publicId());
        Map<String, Object> fields = Json.parseObject(v.toString());
        assertEquals("canonical", V.getString("canonical"), Envelopes.canonical(fields));
        String signed = Envelopes.order(me, v.getString("market"), v.getString("side"), v.getString("price"), v.getString("amount"),
                list(v.getJSONArray("coins")), list(v.getJSONArray("scripts")), v.getString("address"), v.getString("orderId"), v.getLong("seq"), v.getLong("expiry"));
        assertEquals("sig", V.getString("sig"), new JSONObject(signed).getString("sig"));
        Envelope e = Envelope.parse(signed, TestIds.NOW);
        assertEquals(Envelope.Kind.ORDER, e.kind);
        assertEquals(V.getString("signPk").toLowerCase() + "|" + v.getString("orderId").toLowerCase(), e.key());
        assertEquals(1, e.scripts.size());
        // the relay-published raw of the vector verifies too
        assertEquals(e.key(), Envelope.parse(v.toString(), TestIds.NOW).key());
    }

    @Test public void sealOpenAndMsgEnvelopeRoundTrip() {
        DexIdentity me = TestIds.id("11"), other = TestIds.id("22"), third = TestIds.id("33");
        String blob = me.seal(other.publicId(), "{\"k\":\"take\"}".getBytes(StandardCharsets.UTF_8));
        String m = Envelopes.msg(me, other.publicId(), blob, TestIds.NOW + 300);
        Envelope mv = Envelope.parse(m, TestIds.NOW);
        assertEquals(Envelope.Kind.MSG, mv.kind);
        DexIdentity.Opened o = other.open(mv.blob);
        assertNotNull(o); assertEquals("{\"k\":\"take\"}", o.text()); assertEquals(me.publicId(), o.fromPublicId);
        assertNull("third party cannot open", third.open(mv.blob));
        try { Envelopes.msg(me, other.publicId(), blob, TestIds.NOW + 601); Envelope.parse(Envelopes.msg(me, other.publicId(), blob, TestIds.NOW + 601), TestIds.NOW); fail(); }
        catch (Envelope.Invalid ex) { assertEquals("expiry too far ahead", ex.getMessage()); }
    }

    @Test public void rejectionsShapeBeforeSignature() throws Exception {
        JSONObject V = vector(), v = V.getJSONObject("envelope");
        String signed = v.toString();
        String sig = V.getString("sig");
        rej(signed.replace(sig, sig.substring(0, sig.length() - 1) + (sig.endsWith("e") ? "f" : "e")), TestIds.NOW, "bad signature");
        rej(signed.replace(v.getString("pubId"), TestIds.hex("00", 64)), TestIds.NOW, "pubId must end with signPk");
        rej(signed, 1800000000L + 1, "expired");
        rej(signed, TestIds.NOW - 2 * 86400, "expiry too far ahead");
        rej(signed.replace("\"side\":\"sell\"", "\"side\":\"short\""), TestIds.NOW, "side must be buy or sell");
        rej(signed.replace("\"price\":\"0.01234567\"", "\"price\":\"0.123456789\""), TestIds.NOW, "bad price");
        rej(signed.replace("\"v\":1", "\"v\":1,\"x\":1"), TestIds.NOW, "keys must be exactly");
        rej(signed.replace("\"seq\":1", "\"seq\":-1"), TestIds.NOW, "seq must be a non-negative integer");
        rej("nope", TestIds.NOW, "bad json");
        rej(signed.replace("RETURN SIGNEDBY(0xF8DA8F32", "RETURN TRUE OR SIGNEDBY(0xF8DA8F32"), TestIds.NOW, "bad script");
    }
    private static void rej(String raw, long now, String want) {
        try { Envelope.parse(raw, now); fail("accepted: " + want); }
        catch (Envelope.Invalid e) { assertTrue(e.getMessage() + " !~ " + want, e.getMessage().startsWith(want)); }
    }

    @Test public void canonicalRefusesTheForbiddenSubset() {
        thr("{\"a\":1.5}"); thr("{\"a\":{\"b\":1}}"); thr("{\"a\":\"x\\\"y\"}"); thr("{\"a\":\"é\"}"); thr("{\"a\":[[1]]}"); thr("{\"a\":1,\"a\":2}");
        assertEquals("{\"a\":[1,\"x\",true,null],\"b\":-7}", Json.canonicalWithout(Json.parseObject(" { \"b\" : -7 , \"a\" : [ 1 , \"x\" , true , null ] } "), null));
    }
    private static void thr(String json) { try { Json.parseObject(json); fail("accepted " + json); } catch (IllegalArgumentException expected) {} }
}

package com.eurobuddha.minimaapi;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import java.time.Duration;
import java.util.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

/** A reply Minima Core 1.8+ sends in pieces reaches the caller exactly once, whole; foreign or broken pieces never do. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33) @LooperMode(LooperMode.Mode.PAUSED)
public class ChunkedReplyTest {
    @BeforeClass public static void trustNodes() { MinimaAPI.trustCheck = (c, p) -> true; }
    @AfterClass public static void restoreTrust() { MinimaAPI.trustCheck = MinimaAPIMessages::trusted; }
    static final String P = MinimaAPIMessages.MINIMA_BASE_CLASS;
    Application app; MinimaAPI api; List<JSONObject> replies;
    @Before public void setup() { app = RuntimeEnvironment.getApplication(); replies = new ArrayList<>(); api = new MinimaAPI(app, null); }
    @After public void cleanup() { api.onDestroy(); idle(); }
    void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    List<Intent> broadcasts() { return Shadows.shadowOf(app).getBroadcastIntents(); }
    void discover() {
        for (Intent r : new ArrayList<>(broadcasts()))
            if (MinimaAPIMessages.MINIMA_API_REGISTER.equals(r.getAction()) && MinimaAPI.PANDAMONIUM_PACKAGE.equals(r.getPackage()))
                api.ResponseReceived(new Intent(MinimaAPIMessages.MINIMA_API_RESPONSE)
                        .putExtra(MinimaAPIMessages.MINIMA_API_REGISTER_MINIMAID, r.getStringExtra(MinimaAPIMessages.MINIMA_API_REGISTER_MINIMAID))
                        .putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_ID, r.getStringExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_ID))
                        .putExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_RESULT, "{\"status\":true,\"enabled\":true}"));
        idle(); Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
    }
    Intent sent() {
        api.Command("coins", replies::add); idle(); discover();
        for (Intent i : broadcasts()) if (MinimaAPIMessages.MINIMA_API_CMD.equals(i.getAction())) return i;
        throw new AssertionError("no command sent");
    }
    Intent piece(Intent req, String minimaid, int size, int pos, String part) {
        return new Intent(MinimaAPIMessages.key(P, "RESPONSE_CHUNKED"))
                .putExtra(MinimaAPIMessages.key(P, "REGISTER_MINIMAID"), minimaid)
                .putExtra(MinimaAPIMessages.key(P, "RESPONSE_ID"), req.getStringExtra(MinimaAPIMessages.MINIMA_API_RESPONSE_ID))
                .putExtra(MinimaAPIMessages.key(P, "RESPONSE_CHUNKED_SIZE"), size)
                .putExtra(MinimaAPIMessages.key(P, "RESPONSE_CHUNKED_POS"), pos)
                .putExtra(MinimaAPIMessages.key(P, "RESPONSE_RESULT"), part);
    }

    @Test public void piecesInAnyOrderArriveAsOneWholeReply() throws Exception {
        Intent req = sent(); String id = req.getStringExtra(MinimaAPIMessages.MINIMA_API_REGISTER_MINIMAID);
        String body = "{\"status\":true,\"response\":[{\"coinid\":\"0x01\"},{\"coinid\":\"0x02\"}]}";
        String a = body.substring(0, 20), b = body.substring(20, 40), c = body.substring(40);
        new MinimaAPIReceive(api).onReceive(app, piece(req, id, 3, 2, c)); idle();
        new MinimaAPIReceive(api).onReceive(app, piece(req, id, 3, 0, a)); idle();
        assertTrue("not delivered before the last piece", replies.isEmpty());
        new MinimaAPIReceive(api).onReceive(app, piece(req, id, 3, 1, b)); idle();
        assertEquals(1, replies.size());
        assertEquals("0x02", replies.get(0).getJSONArray("response").getJSONObject(1).getString("coinid"));
        new MinimaAPIReceive(api).onReceive(app, piece(req, id, 3, 1, b)); idle();
        assertEquals("delivered once", 1, replies.size());
    }

    /** Over 64K the joined reply is parsed on the reply-reader thread, then delivered once, whole. */
    @Test public void aBigReplyIsParsedOffTheMainThreadAndDeliveredWhole() throws Exception {
        Intent req = sent(); String id = req.getStringExtra(MinimaAPIMessages.MINIMA_API_REGISTER_MINIMAID);
        String pad = new String(new char[70_000]).replace('\0', 'a');
        String body = "{\"status\":true,\"response\":\"" + pad + "\"}";
        int half = body.length() / 2;
        new MinimaAPIReceive(api).onReceive(app, piece(req, id, 2, 0, body.substring(0, half))); idle();
        new MinimaAPIReceive(api).onReceive(app, piece(req, id, 2, 1, body.substring(half))); idle();
        for (int i = 0; i < 200 && replies.isEmpty(); i++) { Thread.sleep(10); idle(); }
        assertEquals(1, replies.size());
        assertEquals(70_000, replies.get(0).getString("response").length());
    }

    @Test public void piecesWithTheWrongPairingSecretAreIgnored() {
        Intent req = sent();
        new MinimaAPIReceive(api).onReceive(app, piece(req, "0x" + "AB".repeat(16), 1, 0, "{\"status\":true}")); idle();
        assertTrue(replies.isEmpty());
    }

    @Test public void aBrokenPieceEndsTheRequestWithAFailureNotAGuess() {
        Intent req = sent(); String id = req.getStringExtra(MinimaAPIMessages.MINIMA_API_REGISTER_MINIMAID);
        new MinimaAPIReceive(api).onReceive(app, piece(req, id, 2, 5, "x")); idle();
        assertEquals(1, replies.size());
        assertTrue(replies.get(0).toString().contains("could not be reassembled"));
    }
}

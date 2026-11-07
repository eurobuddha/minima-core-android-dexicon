// VENDORED VERBATIM from apks/pandapools 0.9.87 app/src/test/java/com/eurobuddha/pandapools/SerialQueueTest.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class SerialQueueTest {
    @Test public void replyBurstIsSerializedAndLateCompletionCannotAdvanceAnotherRequest() {
        SerialQueue queue = new SerialQueue();
        List<Integer> started = new ArrayList<>();
        List<Runnable> complete = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            final int id = i;
            queue.submit(finish -> { started.add(id); complete.add(finish); });
        }
        assertEquals(Arrays.asList(0), started);
        for (int i = 0; i < 17; i++) {
            complete.get(i).run(); complete.get(i).run();
            assertEquals(i + 2, started.size());
        }
        complete.get(17).run();
        queue.submit(finish -> { started.add(18); finish.run(); });
        assertEquals(19, started.size());
    }

    @Test public void callbackMayQueueDependentCommandWithoutOverlappingTheReply() {
        SerialQueue queue = new SerialQueue();
        List<String> events = new ArrayList<>();
        queue.submit(finish -> {
            events.add("reply starts");
            queue.submit(next -> { events.add("child dispatched"); next.run(); });
            events.add("reply consumed");
            finish.run();
        });
        assertEquals(Arrays.asList("reply starts", "reply consumed", "child dispatched"), events);
    }
}

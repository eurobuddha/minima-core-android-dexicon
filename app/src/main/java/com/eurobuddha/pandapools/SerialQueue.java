// VENDORED VERBATIM from apks/pandapools 0.10.12 app/src/main/java/com/eurobuddha/pandapools/SerialQueue.java by scripts/vendor-pandapools.sh — do not edit here; re-vendor.
package com.eurobuddha.pandapools;

import java.util.ArrayDeque;

/** Main-thread FIFO, extracted from TxPost's queue and idempotent completion gate.
 * Each operation owns its completion; elapsed time never releases it automatically. */
final class SerialQueue {
    interface Operation { void run(Runnable finish); }
    private final ArrayDeque<Operation> pending = new ArrayDeque<>();
    private boolean busy;

    void submit(Operation operation) {
        pending.add(operation);
        if (!busy) next();
    }

    private void next() {
        Operation operation = pending.poll();
        if (operation == null) { busy = false; return; }
        busy = true;
        operation.run(new Runnable() {
            private boolean finished;
            public void run() {
                if (finished) return;
                finished = true;
                next();
            }
        });
    }
}

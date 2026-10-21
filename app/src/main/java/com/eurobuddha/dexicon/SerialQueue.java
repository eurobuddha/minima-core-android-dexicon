package com.eurobuddha.dexicon;

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

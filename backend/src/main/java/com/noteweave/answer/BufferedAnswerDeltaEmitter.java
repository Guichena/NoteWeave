package com.noteweave.answer;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Coalesces small token callbacks into bounded 30ms/512-char SSE batches. */
public final class BufferedAnswerDeltaEmitter implements AutoCloseable {

    private static final int MAX_BATCH_CHARS = 512;
    private static final long MAX_BATCH_DELAY_MS = 30;

    private final Object monitor = new Object();
    private final ScheduledExecutorService scheduler;
    private final Consumer<String> sink;
    private final StringBuilder pending = new StringBuilder();
    private ScheduledFuture<?> scheduledFlush;
    private RuntimeException failure;
    private boolean closed;

    public BufferedAnswerDeltaEmitter(ScheduledExecutorService scheduler, Consumer<String> sink) {
        this.scheduler = scheduler;
        this.sink = sink;
    }

    public void accept(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        String immediate = null;
        synchronized (monitor) {
            if (failure != null) {
                throw failure;
            }
            if (closed) {
                return;
            }
            pending.append(token);
            if (pending.length() >= MAX_BATCH_CHARS) {
                immediate = drainLocked();
            } else if (scheduledFlush == null) {
                scheduledFlush = scheduler.schedule(this::flushFromScheduler, MAX_BATCH_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        }
        if (immediate != null) {
            sink.accept(immediate);
        }
    }

    public void flush() {
        String batch;
        synchronized (monitor) {
            batch = drainLocked();
        }
        if (batch != null) {
            sink.accept(batch);
        }
        synchronized (monitor) {
            if (failure != null) {
                throw failure;
            }
        }
    }

    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
        }
        flush();
    }

    private String drainLocked() {
        if (scheduledFlush != null) {
            scheduledFlush.cancel(false);
            scheduledFlush = null;
        }
        if (pending.isEmpty()) {
            return null;
        }
        String batch = pending.toString();
        pending.setLength(0);
        return batch;
    }

    private void flushFromScheduler() {
        try {
            flush();
        } catch (RuntimeException ex) {
            synchronized (monitor) {
                failure = ex;
            }
        }
    }
}

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
    private boolean failureObserved;
    private boolean closed;

    public BufferedAnswerDeltaEmitter(ScheduledExecutorService scheduler, Consumer<String> sink) {
        this.scheduler = scheduler;
        this.sink = sink;
    }

    public void accept(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        synchronized (monitor) {
            throwStoredFailure(true);
            if (closed) {
                return;
            }
            pending.append(token);
            if (pending.length() >= MAX_BATCH_CHARS) {
                deliverLocked();
            } else if (scheduledFlush == null) {
                scheduledFlush = scheduler.schedule(this::flushFromScheduler, MAX_BATCH_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    public void flush() {
        synchronized (monitor) {
            throwStoredFailure(true);
            deliverLocked();
        }
    }

    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            if (failure == null) {
                deliverLocked();
            }
            throwStoredFailure(false);
        }
    }

    // 排出和发送共享同一把锁，定时线程不能被后续生产线程越过。
    // 这里只串行化本运行的有界增量，不持有数据库锁或模型调用锁。
    private void deliverLocked() {
        String batch = drainLocked();
        if (batch == null) {
            return;
        }
        try {
            sink.accept(batch);
        } catch (RuntimeException ex) {
            failure = ex;
            failureObserved = true;
            throw ex;
        }
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
                failureObserved = false;
            }
        }
    }

    private void throwStoredFailure(boolean markObserved) {
        synchronized (monitor) {
            if (failure == null || (!markObserved && failureObserved)) {
                return;
            }
            if (markObserved) {
                failureObserved = true;
            }
            throw failure;
        }
    }
}

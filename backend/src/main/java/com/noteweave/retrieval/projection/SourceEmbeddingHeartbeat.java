package com.noteweave.retrieval.projection;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 每个协调器共享有限线程；网络调用不占心跳线程，结束或关闭时取消续租。 */
final class SourceEmbeddingHeartbeat implements AutoCloseable {
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2, runnable -> {
        Thread thread = new Thread(runnable, "source-embedding-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Lease> leases = ConcurrentHashMap.newKeySet();
    private boolean closed;

    SourceEmbeddingHeartbeat() {
        scheduler.setRemoveOnCancelPolicy(true);
    }

    synchronized Lease start(Runnable renew, long intervalMillis) {
        if (closed) throw new IllegalStateException("SOURCE_EMBEDDING_HEARTBEAT_CLOSED");
        Lease lease = new Lease();
        leases.add(lease);
        lease.future = scheduler.scheduleWithFixedDelay(() -> {
            if (lease.lost.get()) return;
            try {
                renew.run();
            } catch (RuntimeException failure) {
                // 不确定是否仍持有租约时保守停止后续发送和提交，不能静默继续。
                lease.lost.set(true);
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return lease;
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (Lease lease : leases) {
            lease.lost.set(true);
            lease.close();
        }
        scheduler.shutdownNow();
    }

    final class Lease implements AutoCloseable {
        private final AtomicBoolean lost = new AtomicBoolean();
        private ScheduledFuture<?> future;

        void check() {
            if (lost.get() || Thread.currentThread().isInterrupted())
                throw new IllegalStateException("SOURCE_EMBEDDING_BATCH_LEASE_LOST");
        }

        @Override
        public void close() {
            if (future != null) future.cancel(false);
            leases.remove(this);
        }
    }
}

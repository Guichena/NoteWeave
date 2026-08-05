package com.noteweave.infra.outbox;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Shared claim and retry policy for durable outbox dispatchers. */
public final class OutboxDispatchPolicy {

    public static final Duration LEASE_DURATION = Duration.ofMinutes(1);
    /** Covers the worker's one-hour execution timeout; progress callbacks renew it. */
    public static final Duration ARTIFACT_LEASE_DURATION = Duration.ofMinutes(70);
    public static final int MAX_ATTEMPTS = 5;

    private OutboxDispatchPolicy() {
    }

    public static long retryDelaySeconds(int attemptNo) {
        long base = Math.min(60L, 1L << Math.min(attemptNo, 6));
        long maxJitter = Math.max(1L, base / 5L);
        return Math.min(60L, base + ThreadLocalRandom.current().nextLong(maxJitter + 1L));
    }

    public static String abbreviate(String message, String fallback) {
        String value = message == null || message.isBlank() ? fallback : message;
        return value.substring(0, Math.min(1000, value.length()));
    }
}

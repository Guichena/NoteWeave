package com.noteweave.common.api;

public final class RequestIdHolder {

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    private RequestIdHolder() {
    }

    public static void set(String requestId) {
        REQUEST_ID.set(requestId);
    }

    public static String get() {
        return REQUEST_ID.get();
    }

    public static void clear() {
        REQUEST_ID.remove();
    }
}

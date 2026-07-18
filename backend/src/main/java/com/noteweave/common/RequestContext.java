package com.noteweave.common;

import org.slf4j.MDC;

public final class RequestContext {

    public static final String REQUEST_ID = "request_id";
    public static final String CORRELATION_ID = "correlation_id";
    public static final String USER_ID = "user_id";
    public static final String WORKSPACE_ID = "workspace_id";
    public static final String TASK_ID = "task_id";
    public static final String SOURCE_ID = "source_id";
    public static final String ANSWER_RUN_ID = "answer_run_id";
    public static final String EVENT_ID = "event_id";

    private RequestContext() {
    }

    public static String currentRequestId() {
        return valueOrNull(REQUEST_ID);
    }

    public static String valueOrNull(String key) {
        String value = MDC.get(key);
        return value == null || value.isBlank() ? null : value;
    }
}

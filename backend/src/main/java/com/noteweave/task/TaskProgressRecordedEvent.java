package com.noteweave.task;

import java.util.Map;

public record TaskProgressRecordedEvent(
        String taskId,
        String phase,
        String message,
        Integer progressPercent,
        Map<String, Object> metrics,
        Map<String, Object> payload
) {
}

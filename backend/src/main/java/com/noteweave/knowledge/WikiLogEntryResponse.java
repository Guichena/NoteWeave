package com.noteweave.knowledge;

import java.time.Instant;

public record WikiLogEntryResponse(String id, String itemId, String eventType, String message, Instant createdAt) {
}

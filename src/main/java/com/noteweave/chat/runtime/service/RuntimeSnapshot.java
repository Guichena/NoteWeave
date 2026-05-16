package com.noteweave.chat.runtime.service;

import java.util.List;

public record RuntimeSnapshot(
        RuntimeState runtimeState,
        ShortTermContext shortTermContext,
        StreamState streamState,
        List<com.noteweave.chat.runtime.protocol.ServerEventEnvelope> events
) {
}

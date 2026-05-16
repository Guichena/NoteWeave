package com.noteweave.chat.runtime.service;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ActiveExecution {

    private final String streamId;
    private final String requestId;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    public void stop() {
        stopRequested.set(true);
    }
}

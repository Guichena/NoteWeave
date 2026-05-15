package com.noteweave.task.worker;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NoopTestTaskInput {
    private long durationMillis = 0L;
    private long stepSleepMillis = 25L;
    private boolean shouldFail;
    private boolean shouldTimeout;
    private String successMessage = "NOOP task completed";
}

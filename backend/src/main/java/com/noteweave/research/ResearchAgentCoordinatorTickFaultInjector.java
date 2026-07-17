package com.noteweave.research;

import org.springframework.stereotype.Component;

/** Test seam for transactional coordinator crash/restart recovery boundaries. */
@Component
public class ResearchAgentCoordinatorTickFaultInjector {
    public void checkpoint(Stage stage) {
        // Production no-op. Tests may inject a fail-stop equivalent before commit.
    }

    public enum Stage { AFTER_INITIAL_TASKIZATION, AFTER_FAILED_WAVE_RECOVERY }
}

package com.noteweave.research;

/**
 * Test-only extension point. Production has no bean and therefore no runtime
 * switch capable of injecting failures.
 */
public interface ResearchAgentCompletionFaultInjector {
    enum Stage {
        AFTER_LOCKS,
        AFTER_EXECUTION_ANCHOR,
        AFTER_COMPLETION_ANCHOR,
        AFTER_NTH_EVIDENCE,
        AFTER_NTH_CANDIDATE,
        AFTER_FIRST_CELL_CAS,
        AFTER_NTH_CELL_EVIDENCE,
        AFTER_BUDGET_FINALIZE,
        AFTER_TASK_TERMINAL_UPDATE,
        AFTER_COMMIT_BEFORE_HTTP_RESPONSE
    }

    void checkpoint(Stage stage, int ordinal);
}

package com.noteweave.research;

import org.springframework.stereotype.Component;

/** Test seam for MA4J transactional fail-stop recovery. */
@Component
public class ResearchAgentIncrementalFinalizationFaultInjector {
    public void checkpoint(Stage stage) { }
    public enum Stage { AFTER_RUN_REPORT_WRITE }
}

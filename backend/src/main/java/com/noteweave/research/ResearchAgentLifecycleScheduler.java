package com.noteweave.research;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Optional MA4B reaper. Disabled until distributed agent execution is explicitly enabled. */
@Component
@ConditionalOnProperty(name = "noteweave.research.agent.lifecycle-reaper-enabled", havingValue = "true")
public class ResearchAgentLifecycleScheduler {

    private final ResearchAgentLifecycleService lifecycleService;

    public ResearchAgentLifecycleScheduler(ResearchAgentLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Scheduled(fixedDelayString = "${noteweave.research.agent.lifecycle-reaper-delay-ms:1000}")
    public void reapExpiredLeases() {
        lifecycleService.reapExpiredLeases();
    }
}

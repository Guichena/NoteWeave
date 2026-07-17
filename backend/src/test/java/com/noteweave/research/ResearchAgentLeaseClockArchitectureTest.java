package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Prevents a caller/JVM clock from returning to the authoritative lease path. */
class ResearchAgentLeaseClockArchitectureTest {

    @Test
    void shouldKeepEveryProductionLeaseDecisionOnTheDatabaseClock() throws IOException {
        String taskService = source("ResearchAgentTaskService.java");
        assertThat(taskService)
                .contains("lease_expires_at = timestampadd(second, ?, current_timestamp)")
                .contains("public int expireLeases()")
                .contains("lease_expires_at <= current_timestamp")
                .doesNotContain("Instant.now()", "expireLeases(Instant", "Timestamp.from(now)");

        String lifecycleService = source("ResearchAgentLifecycleService.java");
        assertThat(lifecycleService)
                .contains("public ReapReceipt reapExpiredLeases()")
                .contains("next_attempt_at = timestampadd(second, ?, current_timestamp)")
                .contains("lease_expires_at <= current_timestamp")
                .doesNotContain("java.time.Instant", "java.sql.Timestamp", "Timestamp.from(now)");

        String controller = source("ResearchAgentTaskInternalController.java");
        assertThat(controller)
                .contains("RESEARCH_AGENT_LEASE_CLOCK_OVERRIDE_FORBIDDEN")
                .contains("taskService.expireLeases()")
                .doesNotContain("ExpireRequest", "java.time.Instant");

        String scheduler = source("ResearchAgentLifecycleScheduler.java");
        assertThat(scheduler)
                .contains("lifecycleService.reapExpiredLeases()")
                .doesNotContain("Instant.now()", "java.time.Instant");
    }

    private String source(String fileName) throws IOException {
        return Files.readString(
                Path.of("src", "main", "java", "com", "noteweave", "research", fileName),
                StandardCharsets.UTF_8);
    }
}

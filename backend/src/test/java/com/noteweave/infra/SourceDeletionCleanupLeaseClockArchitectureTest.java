package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Keeps cleanup scheduling and expiration on one authoritative clock. */
class SourceDeletionCleanupLeaseClockArchitectureTest {

    @Test
    void shouldKeepCleanupLeaseAndRetryDecisionsOnTheDatabaseClock() throws IOException {
        String listener = source("infra", "SourceDeletionCleanupListener.java");
        assertThat(listener)
                .contains("lease_until = timestampadd(second, ?, current_timestamp)")
                .contains("next_attempt_at = timestampadd(second, ?, current_timestamp)")
                .doesNotContain("Instant.now()", "Timestamp.from(", "nextAttemptAt(");

        String policy = source("infra", "outbox", "OutboxDispatchPolicy.java");
        assertThat(policy)
                .doesNotContain(
                        "java.sql.Timestamp",
                        "java.time.Instant",
                        "leaseUntil()",
                        "artifactLeaseUntil()",
                        "nextAttemptAt(");
    }

    private String source(String... path) throws IOException {
        String[] components = new String[path.length + 5];
        components[0] = "src";
        components[1] = "main";
        components[2] = "java";
        components[3] = "com";
        components[4] = "noteweave";
        System.arraycopy(path, 0, components, 5, path.length);
        return Files.readString(Path.of("", components), StandardCharsets.UTF_8);
    }
}

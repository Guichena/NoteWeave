package com.noteweave.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Prevents JVM clock skew from changing answer stream lease ownership. */
class AnswerRunLeaseClockArchitectureTest {

    @Test
    void shouldKeepStreamLeaseDecisionsOnTheDatabaseClock() throws IOException {
        String source = Files.readString(
                Path.of("src", "main", "java", "com", "noteweave", "answer",
                        "AnswerRunService.java"),
                StandardCharsets.UTF_8);

        assertThat(source)
                .contains("stream_lease_until = timestampadd(second, ?, current_timestamp)")
                .contains("stream_lease_until >= current_timestamp")
                .doesNotContain(
                        "Instant.now().plusSeconds(STREAM_LEASE_SECONDS)",
                        "Timestamp.from(Instant.now()");
    }
}

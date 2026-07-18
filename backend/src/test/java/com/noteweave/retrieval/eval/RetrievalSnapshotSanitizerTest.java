package com.noteweave.retrieval.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RetrievalSnapshotSanitizerTest {
    @Test
    void shouldPseudonymizeIdsRedactSensitiveTextAndPreserveReplayConsistency() {
        String sensitive = "Contact alice@example.com at https://example.com/doc from 10.1.2.3 "
                + "phone 13800138000 file D:\\private\\note.txt "
                + "mirror [doc](D:/java-projects/NoteWeave-v2/docs/README.md) "
                + "token=abcdefgh1234 uuid 123e4567-e89b-12d3-a456-426614174000";
        RetrievalGoldSet.Candidate candidate = new RetrievalGoldSet.Candidate(
                "chunk-raw", "source-raw", "Private reference", sensitive, "PDF", List.of("citation-raw"));
        RetrievalGoldSet.GoldCase goldCase = new RetrievalGoldSet.GoldCase(
                "case-raw", "QA", "workspace-raw", sensitive,
                List.of("source-raw"), List.of(candidate), List.of("chunk-raw"),
                List.of("citation-raw"), false, 1);
        RetrievalGoldSet raw = new RetrievalGoldSet(
                "retrieval-gold-v1", "raw-private-snapshot", List.of(goldCase));
        RetrievalSnapshotSanitizer sanitizer = new RetrievalSnapshotSanitizer();

        var first = sanitizer.sanitize(raw, "0123456789abcdef-test-salt");
        var second = sanitizer.sanitize(raw, "0123456789abcdef-test-salt");
        var differentSalt = sanitizer.sanitize(raw, "fedcba9876543210-other-salt");

        assertThat(second).isEqualTo(first);
        assertThat(differentSalt.goldSet().cases().get(0).id())
                .isNotEqualTo(first.goldSet().cases().get(0).id());
        var sanitizedCase = first.goldSet().cases().get(0);
        var sanitizedCandidate = sanitizedCase.candidates().get(0);
        assertThat(first.goldSet().datasetVersion()).isEqualTo("raw-private-snapshot-sanitized");
        assertThat(sanitizedCase.id()).startsWith("case-").doesNotContain("raw");
        assertThat(sanitizedCase.workspaceId()).startsWith("workspace-").doesNotContain("raw");
        assertThat(sanitizedCandidate.evidenceId()).startsWith("evidence-").doesNotContain("raw");
        assertThat(sanitizedCandidate.sourceId()).startsWith("source-").doesNotContain("raw");
        assertThat(sanitizedCandidate.content())
                .contains("[EMAIL]", "[URL]", "[IP]", "[PHONE]", "[PATH]", "[REDACTED]", "[UUID]")
                .contains("[doc]([PATH])")
                .doesNotContain(
                        "alice@example.com", "13800138000", "abcdefgh1234", "10.1.2.3",
                        "D:\\private\\note.txt", "D:/java-projects/NoteWeave-v2/docs/README.md");
        assertThat(first.redactionStats().pathCount()).isEqualTo(4);
        assertThat(first.redactionStats().totalCount()).isEqualTo(16);

        var report = new RetrievalBenchmarkReplay().replay(first.goldSet());
        assertThat(report.overall().macroRecallAtK()).isEqualTo(1.0d);
        assertThat(report.overall().macroCitationCoverage()).isEqualTo(1.0d);
        assertThat(report.overall().scopeViolationCount()).isZero();
    }
}

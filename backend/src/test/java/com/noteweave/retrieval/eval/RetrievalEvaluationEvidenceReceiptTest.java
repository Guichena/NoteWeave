package com.noteweave.retrieval.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetrievalEvaluationEvidenceReceiptTest {
    private static final String PROFILE = "qa-weknora-hybrid-v1";
    private static final Instant GENERATED_AT = Instant.parse("2026-07-16T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteProfiledContentFreeReceiptForStableSnapshots() throws Exception {
        Path gold = resource("stage5-retrieval-gold-v1.json");
        Path shadowOne = profiledShadow("receipt-shadow-1", PROFILE, "sensitive-shadow-one.json");
        Path shadowTwo = profiledShadow("receipt-shadow-2", PROFILE, "sensitive-shadow-two.json");
        Path receiptPath = tempDir.resolve("receipt.json");
        var writer = writer();

        var receipt = writer.createAndWrite(
                gold, List.of(shadowOne, shadowTwo), receiptPath);

        assertThat(receipt.schemaVersion())
                .isEqualTo(RetrievalEvaluationEvidenceReceipt.SCHEMA_VERSION);
        assertThat(receipt.generatedAt()).isEqualTo(GENERATED_AT);
        assertThat(receipt.strategyProfile()).isEqualTo(PROFILE);
        assertThat(receipt.snapshots()).hasSize(2)
                .extracting(item -> item.quality().caseCount())
                .containsOnly(4);
        assertThat(receipt.stability().snapshotCount()).isEqualTo(2);
        assertThat(receipt.stability().distinctRankingSignatureCount()).isEqualTo(1);
        assertThat(receipt.stability().rankingsStable()).isTrue();
        assertThat(receipt.stability().combinedLatencyMicros().min()).isEqualTo(600L);
        assertThat(receipt.stability().combinedLatencyMicros().max()).isEqualTo(1_200L);
        assertThat(receipt.goldArtifact().sha256()).hasSize(64);
        assertThat(receipt.snapshots()).allSatisfy(snapshot -> {
            assertThat(snapshot.artifact().sha256()).hasSize(64);
            assertThat(snapshot.rankingSignature()).hasSize(64);
        });

        String serialized = Files.readString(receiptPath);
        assertThat(serialized)
                .doesNotContain(
                        tempDir.toString(),
                        "sensitive-shadow-one.json",
                        "sensitive-shadow-two.json",
                        "workspace-fixture",
                        "case-qa",
                        "qa-pass-1",
                        "QA workspace answer",
                        "query");
    }

    @Test
    void shouldRejectGenericMixedAndDuplicateProfiledSnapshots() throws Exception {
        Path gold = resource("stage5-retrieval-gold-v1.json");
        Path generic = resource("stage5-shadow-fixture-v1.json");
        Path unsupported = profiledShadow(
                "profile-legacy", "qa-retrieval-legacy-v1", "legacy.json");
        Path v2 = profiledShadow("profile-v2", PROFILE, "v2.json");
        Path duplicate = profiledShadow("profile-v2", PROFILE, "duplicate.json");
        Path unsafeVersion = profiledShadow(
                "D:/workspace-sensitive-id", PROFILE, "unsafe-version.json");
        Path unknownProfile = profiledShadow(
                "unknown-profile", "workspace-sensitive-profile", "unknown-profile.json");
        var writer = writer();

        assertThatThrownBy(() -> writer.create(gold, List.of(generic)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit strategy profile");
        assertThatThrownBy(() -> writer.create(gold, List.of(unsupported, v2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategy profile is unsupported");
        assertThatThrownBy(() -> writer.create(gold, List.of(v2, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snapshot versions must be unique");
        assertThatThrownBy(() -> writer.create(gold, List.of(unsafeVersion)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snapshot version is not a safe identifier");
        assertThatThrownBy(() -> writer.create(gold, List.of(unknownProfile)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategy profile is unsupported");
        assertThatThrownBy(() -> writer.createAndWrite(gold, List.of(v2), v2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot overwrite an input artifact");
        assertThat(writer.create(gold, List.of(v2)).stability().rankingsStable())
                .isFalse();
    }

    private RetrievalEvaluationEvidenceReceipt writer() {
        return new RetrievalEvaluationEvidenceReceipt(
                objectMapper,
                new RetrievalShadowComparator(),
                Clock.fixed(GENERATED_AT, ZoneOffset.UTC)
        );
    }

    private Path profiledShadow(
            String snapshotVersion,
            String strategyProfile,
            String fileName
    ) throws Exception {
        RetrievalShadowSnapshot fixture = objectMapper.readValue(
                resource("stage5-shadow-fixture-v1.json").toFile(),
                RetrievalShadowSnapshot.class);
        Path output = tempDir.resolve(fileName);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                output.toFile(),
                new RetrievalShadowSnapshot(
                        fixture.schemaVersion(),
                        snapshotVersion,
                        strategyProfile,
                        fixture.cases()
                )
        );
        return output;
    }

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getResource("/retrieval/" + name).toURI());
    }
}

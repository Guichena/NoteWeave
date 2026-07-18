package com.noteweave.retrieval.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.eval.DeterministicBm25Baseline.RankedEvidence;
import com.noteweave.retrieval.eval.RetrievalBenchmarkReplay.AggregateMetrics;
import com.noteweave.retrieval.eval.RetrievalShadowComparator.ShadowComparisonReport;
import com.noteweave.retrieval.eval.RetrievalShadowSnapshot.CaseRanking;
import com.noteweave.retrieval.QaRetrievalStrategyProfile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Produces a durable, non-content receipt for sanitized retrieval evaluation artifacts.
 *
 * <p>The receipt deliberately excludes paths, case/evidence/source/citation identifiers, queries,
 * candidate text and HMAC salt. It keeps only artifact digests, versioned cohort identity,
 * aggregate quality/runtime metrics and a digest of each complete admitted ranking.</p>
 */
public final class RetrievalEvaluationEvidenceReceipt {
    public static final String SCHEMA_VERSION = "retrieval-evaluation-evidence-receipt-v1";
    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private final ObjectMapper objectMapper;
    private final RetrievalShadowComparator comparator;
    private final Clock clock;

    public RetrievalEvaluationEvidenceReceipt() {
        this(
                new ObjectMapper().findAndRegisterModules(),
                new RetrievalShadowComparator(),
                Clock.systemUTC()
        );
    }

    RetrievalEvaluationEvidenceReceipt(
            ObjectMapper objectMapper,
            RetrievalShadowComparator comparator,
            Clock clock
    ) {
        this.objectMapper = objectMapper;
        this.comparator = comparator;
        this.clock = clock;
    }

    public Receipt create(Path goldSetPath, List<Path> shadowSnapshotPaths) throws Exception {
        Path goldPath = requireInput(goldSetPath, "gold set");
        List<Path> shadowPaths = requireShadows(shadowSnapshotPaths);
        RetrievalGoldSet goldSet = objectMapper.readValue(goldPath.toFile(), RetrievalGoldSet.class);
        requireSafeVersion("dataset version", goldSet.datasetVersion());

        List<SnapshotReceipt> snapshots = new ArrayList<>();
        Set<String> snapshotVersions = new LinkedHashSet<>();
        Set<String> rankingSignatures = new LinkedHashSet<>();
        List<Long> combinedLatencies = new ArrayList<>();
        String expectedProfile = "";
        for (int index = 0; index < shadowPaths.size(); index++) {
            Path shadowPath = shadowPaths.get(index);
            RetrievalShadowSnapshot shadow = objectMapper.readValue(
                    shadowPath.toFile(), RetrievalShadowSnapshot.class);
            ShadowComparisonReport comparison = comparator.compare(goldSet, shadow);
            String profile = comparison.strategyProfile();
            requireSupportedProfile(profile);
            requireSafeVersion("snapshot version", comparison.shadowSnapshotVersion());
            if (expectedProfile.isEmpty()) {
                expectedProfile = profile;
            } else if (!expectedProfile.equals(profile)) {
                throw new IllegalArgumentException(
                        "Retrieval evidence receipt cannot mix strategy profiles");
            }
            if (!snapshotVersions.add(comparison.shadowSnapshotVersion())) {
                throw new IllegalArgumentException(
                        "Retrieval evidence receipt snapshot versions must be unique");
            }

            String signature = rankingSignature(shadow);
            rankingSignatures.add(signature);
            List<Long> latencies = shadow.cases().stream()
                    .map(CaseRanking::latencyMicros)
                    .sorted()
                    .toList();
            combinedLatencies.addAll(latencies);
            snapshots.add(new SnapshotReceipt(
                    index + 1,
                    comparison.shadowSnapshotVersion(),
                    artifact(shadowPath),
                    comparison.summary().caseCount(),
                    quality(comparison.shadow().overall()),
                    runtime(shadow.cases(), latencies),
                    signature
            ));
        }

        combinedLatencies.sort(Long::compareTo);
        return new Receipt(
                SCHEMA_VERSION,
                clock.instant(),
                goldSet.datasetVersion(),
                expectedProfile,
                artifact(goldPath),
                List.copyOf(snapshots),
                new StabilitySummary(
                        snapshots.size(),
                        rankingSignatures.size(),
                        snapshots.size() >= 2 && rankingSignatures.size() == 1,
                        latency(combinedLatencies)
                )
        );
    }

    public Receipt createAndWrite(
            Path goldSetPath,
            List<Path> shadowSnapshotPaths,
            Path outputPath
    ) throws Exception {
        Path output = requireOutput(outputPath, goldSetPath, shadowSnapshotPaths);
        Receipt receipt = create(goldSetPath, shadowSnapshotPaths);
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), receipt);
        return receipt;
    }

    private List<Path> requireShadows(List<Path> shadowSnapshotPaths) {
        if (shadowSnapshotPaths == null || shadowSnapshotPaths.isEmpty()) {
            throw new IllegalArgumentException("Retrieval evidence receipt requires shadow snapshots");
        }
        return shadowSnapshotPaths.stream()
                .map(path -> requireInput(path, "shadow snapshot"))
                .toList();
    }

    private void requireSupportedProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException(
                    "Retrieval evidence receipt requires an explicit strategy profile");
        }
        boolean supported = java.util.Arrays.stream(QaRetrievalStrategyProfile.values())
                .anyMatch(candidate -> candidate.profileVersion().equals(profile));
        if (!supported) {
            throw new IllegalArgumentException(
                    "Retrieval evidence receipt strategy profile is unsupported");
        }
    }

    private void requireSafeVersion(String label, String value) {
        if (value == null || !SAFE_VERSION.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Retrieval evidence receipt " + label + " is not a safe identifier");
        }
    }

    private Path requireInput(Path path, String label) {
        if (path == null) {
            throw new IllegalArgumentException("Retrieval evidence receipt " + label + " is required");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException(
                    "Retrieval evidence receipt " + label + " must be a regular file");
        }
        return normalized;
    }

    private Path requireOutput(
            Path outputPath,
            Path goldSetPath,
            List<Path> shadowSnapshotPaths
    ) {
        if (outputPath == null) {
            throw new IllegalArgumentException("Retrieval evidence receipt output is required");
        }
        Path output = outputPath.toAbsolutePath().normalize();
        Set<Path> inputs = new LinkedHashSet<>();
        inputs.add(goldSetPath.toAbsolutePath().normalize());
        if (shadowSnapshotPaths != null) {
            shadowSnapshotPaths.forEach(path -> {
                if (path != null) {
                    inputs.add(path.toAbsolutePath().normalize());
                }
            });
        }
        if (inputs.contains(output)) {
            throw new IllegalArgumentException(
                    "Retrieval evidence receipt output cannot overwrite an input artifact");
        }
        return output;
    }

    private ArtifactDigest artifact(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        return new ArtifactDigest(bytes.length, sha256(bytes));
    }

    private QualityMetrics quality(AggregateMetrics metrics) {
        return new QualityMetrics(
                metrics.caseCount(),
                metrics.retrievalCaseCount(),
                metrics.refusalCaseCount(),
                metrics.macroRecallAtK(),
                metrics.macroMrr(),
                metrics.macroNdcgAtK(),
                metrics.macroCitationPrecision(),
                metrics.macroCitationCoverage(),
                metrics.refusalAccuracy(),
                metrics.scopeViolationCount()
        );
    }

    private RuntimeMetrics runtime(List<CaseRanking> cases, List<Long> latencies) {
        int totalCandidates = cases.stream().mapToInt(CaseRanking::candidateCount).sum();
        return new RuntimeMetrics(
                latency(latencies),
                totalCandidates,
                cases.isEmpty() ? 0.0d : totalCandidates / (double) cases.size()
        );
    }

    private LatencySummary latency(List<Long> sortedLatencies) {
        if (sortedLatencies == null || sortedLatencies.isEmpty()) {
            return new LatencySummary(0L, 0L, 0L, 0L);
        }
        return new LatencySummary(
                sortedLatencies.get(0),
                percentile(sortedLatencies, 50),
                percentile(sortedLatencies, 95),
                sortedLatencies.get(sortedLatencies.size() - 1)
        );
    }

    private long percentile(List<Long> sortedValues, int percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile / 100.0d * sortedValues.size()) - 1);
        return sortedValues.get(Math.min(index, sortedValues.size() - 1));
    }

    private String rankingSignature(RetrievalShadowSnapshot shadow) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<CaseRanking> cases = shadow.cases().stream()
                .sorted(Comparator.comparing(CaseRanking::caseId))
                .toList();
        for (CaseRanking item : cases) {
            update(digest, item.caseId());
            for (RankedEvidence evidence : item.rankedEvidence()) {
                update(digest, evidence.evidenceId());
                update(digest, evidence.sourceId());
                update(digest, Double.toHexString(evidence.score()));
                evidence.citationIds().forEach(citation -> update(digest, citation));
            }
        }
        return hex(digest.digest());
    }

    private String sha256(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void update(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }

    public record Receipt(
            String schemaVersion,
            Instant generatedAt,
            String datasetVersion,
            String strategyProfile,
            ArtifactDigest goldArtifact,
            List<SnapshotReceipt> snapshots,
            StabilitySummary stability
    ) {
    }

    public record ArtifactDigest(
            long byteSize,
            String sha256
    ) {
    }

    public record SnapshotReceipt(
            int sequence,
            String snapshotVersion,
            ArtifactDigest artifact,
            int caseCount,
            QualityMetrics quality,
            RuntimeMetrics runtime,
            String rankingSignature
    ) {
    }

    public record QualityMetrics(
            int caseCount,
            int retrievalCaseCount,
            int refusalCaseCount,
            double macroRecallAtK,
            double macroMrr,
            double macroNdcgAtK,
            double macroCitationPrecision,
            double macroCitationCoverage,
            double refusalAccuracy,
            long scopeViolationCount
    ) {
    }

    public record RuntimeMetrics(
            LatencySummary latencyMicros,
            int totalCandidateCount,
            double averageCandidateCount
    ) {
    }

    public record LatencySummary(
            long min,
            long p50,
            long p95,
            long max
    ) {
    }

    public record StabilitySummary(
            int snapshotCount,
            int distinctRankingSignatureCount,
            boolean rankingsStable,
            LatencySummary combinedLatencyMicros
    ) {
    }
}

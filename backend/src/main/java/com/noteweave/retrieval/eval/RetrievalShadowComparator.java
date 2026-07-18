package com.noteweave.retrieval.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.eval.DeterministicBm25Baseline.RankedEvidence;
import com.noteweave.retrieval.eval.RetrievalBenchmarkReplay.BenchmarkReport;
import com.noteweave.retrieval.eval.RetrievalBenchmarkReplay.CaseResult;
import com.noteweave.retrieval.eval.RetrievalShadowSnapshot.CaseRanking;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RetrievalShadowComparator {
    public static final String SHADOW_SCHEMA_VERSION = "retrieval-shadow-v1";

    private final ObjectMapper objectMapper;
    private final RetrievalBenchmarkReplay replay;

    public RetrievalShadowComparator() {
        this(new ObjectMapper().findAndRegisterModules(), new RetrievalBenchmarkReplay());
    }

    RetrievalShadowComparator(ObjectMapper objectMapper, RetrievalBenchmarkReplay replay) {
        this.objectMapper = objectMapper;
        this.replay = replay;
    }

    public ShadowComparisonReport compare(Path goldSetPath, Path shadowSnapshotPath) throws Exception {
        RetrievalGoldSet goldSet = objectMapper.readValue(goldSetPath.toFile(), RetrievalGoldSet.class);
        RetrievalShadowSnapshot shadow = objectMapper.readValue(
                shadowSnapshotPath.toFile(), RetrievalShadowSnapshot.class);
        return compare(goldSet, shadow);
    }

    public ShadowComparisonReport compare(RetrievalGoldSet goldSet, RetrievalShadowSnapshot shadow) {
        validate(goldSet, shadow);
        BenchmarkReport baselineReport = replay.replay(goldSet);
        Map<String, List<RankedEvidence>> rankingsByCaseId = new LinkedHashMap<>();
        shadow.cases().forEach(item -> rankingsByCaseId.put(item.caseId(), item.rankedEvidence()));
        BenchmarkReport shadowReport = replay.evaluateRankings(
                goldSet,
                "shadow:" + shadow.snapshotVersion(),
                rankingsByCaseId
        );

        Map<String, CaseResult> baselineById = byId(baselineReport.cases());
        Map<String, CaseResult> shadowById = byId(shadowReport.cases());
        Map<String, CaseRanking> runtimeById = new HashMap<>();
        shadow.cases().forEach(item -> runtimeById.put(item.caseId(), item));
        List<CaseDrift> cases = goldSet.cases().stream().map(goldCase -> {
            CaseResult baseline = baselineById.get(goldCase.id());
            CaseResult online = shadowById.get(goldCase.id());
            CaseRanking runtime = runtimeById.get(goldCase.id());
            List<String> baselineIds = ids(baseline.rankedEvidence());
            List<String> shadowIds = ids(online.rankedEvidence());
            return new CaseDrift(
                    goldCase.id(),
                    goldCase.mode(),
                    overlap(baselineIds, shadowIds),
                    positionAgreement(baselineIds, shadowIds),
                    first(baselineIds),
                    first(shadowIds),
                    !first(baselineIds).equals(first(shadowIds)),
                    online.recallAtK() - baseline.recallAtK(),
                    online.reciprocalRank() - baseline.reciprocalRank(),
                    online.ndcgAtK() - baseline.ndcgAtK(),
                    online.citationPrecision() - baseline.citationPrecision(),
                    online.citationCoverage() - baseline.citationCoverage(),
                    online.refusalCorrect() != baseline.refusalCorrect(),
                    online.scopeViolationCount(),
                    runtime.latencyMicros(),
                    runtime.candidateCount()
            );
        }).toList();

        return new ShadowComparisonReport(
                goldSet.datasetVersion(),
                shadow.snapshotVersion(),
                shadow.strategyProfile(),
                baselineReport,
                shadowReport,
                cases,
                summarize(baselineReport, shadowReport, cases)
        );
    }

    private ShadowDriftSummary summarize(
            BenchmarkReport baseline,
            BenchmarkReport shadow,
            List<CaseDrift> cases
    ) {
        List<Long> latencies = cases.stream().map(CaseDrift::latencyMicros).sorted().toList();
        return new ShadowDriftSummary(
                cases.size(),
                cases.stream().mapToDouble(CaseDrift::topKOverlap).average().orElse(1.0d),
                cases.stream().mapToDouble(CaseDrift::positionAgreement).average().orElse(1.0d),
                cases.stream().filter(CaseDrift::top1Changed).count(),
                shadow.overall().macroRecallAtK() - baseline.overall().macroRecallAtK(),
                shadow.overall().macroMrr() - baseline.overall().macroMrr(),
                shadow.overall().macroNdcgAtK() - baseline.overall().macroNdcgAtK(),
                shadow.overall().macroCitationPrecision() - baseline.overall().macroCitationPrecision(),
                shadow.overall().macroCitationCoverage() - baseline.overall().macroCitationCoverage(),
                shadow.overall().refusalAccuracy() - baseline.overall().refusalAccuracy(),
                shadow.overall().scopeViolationCount(),
                percentile(latencies, 50),
                percentile(latencies, 95),
                cases.stream().mapToInt(CaseDrift::candidateCount).sum(),
                cases.stream().mapToInt(CaseDrift::candidateCount).average().orElse(0.0d)
        );
    }

    private Map<String, CaseResult> byId(List<CaseResult> cases) {
        Map<String, CaseResult> byId = new HashMap<>();
        cases.forEach(item -> byId.put(item.id(), item));
        return byId;
    }

    private List<String> ids(List<RankedEvidence> rankedEvidence) {
        return rankedEvidence.stream().map(RankedEvidence::evidenceId).toList();
    }

    private double overlap(List<String> left, List<String> right) {
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        if (union.isEmpty()) {
            return 1.0d;
        }
        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(new LinkedHashSet<>(right));
        return (double) intersection.size() / union.size();
    }

    private double positionAgreement(List<String> left, List<String> right) {
        int positions = Math.max(left.size(), right.size());
        if (positions == 0) {
            return 1.0d;
        }
        int matches = 0;
        for (int index = 0; index < positions; index++) {
            String leftId = index < left.size() ? left.get(index) : "";
            String rightId = index < right.size() ? right.get(index) : "";
            if (leftId.equals(rightId)) {
                matches++;
            }
        }
        return (double) matches / positions;
    }

    private String first(List<String> values) {
        return values.isEmpty() ? "" : values.get(0);
    }

    private long percentile(List<Long> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) {
            return 0L;
        }
        int index = Math.max(0, (int) Math.ceil(percentile / 100.0d * sortedValues.size()) - 1);
        return sortedValues.get(Math.min(index, sortedValues.size() - 1));
    }

    private void validate(RetrievalGoldSet goldSet, RetrievalShadowSnapshot shadow) {
        if (!SHADOW_SCHEMA_VERSION.equals(shadow.schemaVersion())) {
            throw new IllegalArgumentException("Unsupported retrieval shadow schema: " + shadow.schemaVersion());
        }
        if (shadow.snapshotVersion().isBlank()) {
            throw new IllegalArgumentException("Retrieval shadow snapshotVersion is required");
        }
        Set<String> goldCaseIds = new LinkedHashSet<>();
        goldSet.cases().forEach(item -> goldCaseIds.add(item.id()));
        Set<String> shadowCaseIds = new LinkedHashSet<>();
        for (CaseRanking item : shadow.cases()) {
            if (item.caseId().isBlank() || !shadowCaseIds.add(item.caseId())) {
                throw new IllegalArgumentException("Shadow case ids must be unique and non-blank");
            }
            if (item.latencyMicros() < 0 || item.candidateCount() < item.rankedEvidence().size()) {
                throw new IllegalArgumentException("Shadow runtime values are invalid: " + item.caseId());
            }
            Set<String> rankedIds = new LinkedHashSet<>();
            for (RankedEvidence evidence : item.rankedEvidence()) {
                if (evidence.evidenceId().isBlank()
                        || evidence.sourceId().isBlank()
                        || !rankedIds.add(evidence.evidenceId())) {
                    throw new IllegalArgumentException(
                            "Shadow ranked evidence ids must be unique and ids non-blank: " + item.caseId());
                }
            }
        }
        if (!shadowCaseIds.equals(goldCaseIds)) {
            throw new IllegalArgumentException("Shadow cases must exactly match gold cases");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: RetrievalShadowComparator <gold-set.json> <shadow-snapshot.json>");
        }
        RetrievalShadowComparator comparator = new RetrievalShadowComparator();
        ShadowComparisonReport report = comparator.compare(Path.of(args[0]), Path.of(args[1]));
        System.out.println(comparator.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    public record ShadowComparisonReport(
            String datasetVersion,
            String shadowSnapshotVersion,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) String strategyProfile,
            BenchmarkReport baseline,
            BenchmarkReport shadow,
            List<CaseDrift> cases,
            ShadowDriftSummary summary
    ) {
        public ShadowComparisonReport {
            strategyProfile = strategyProfile == null ? "" : strategyProfile.trim();
        }

        public ShadowComparisonReport(
                String datasetVersion,
                String shadowSnapshotVersion,
                BenchmarkReport baseline,
                BenchmarkReport shadow,
                List<CaseDrift> cases,
                ShadowDriftSummary summary
        ) {
            this(datasetVersion, shadowSnapshotVersion, "", baseline, shadow, cases, summary);
        }
    }

    public record CaseDrift(
            String caseId,
            String mode,
            double topKOverlap,
            double positionAgreement,
            String baselineTop1EvidenceId,
            String shadowTop1EvidenceId,
            boolean top1Changed,
            double recallDelta,
            double mrrDelta,
            double ndcgDelta,
            double citationPrecisionDelta,
            double citationCoverageDelta,
            boolean refusalCorrectnessChanged,
            long scopeViolationCount,
            long latencyMicros,
            int candidateCount
    ) {
    }

    public record ShadowDriftSummary(
            int caseCount,
            double meanTopKOverlap,
            double meanPositionAgreement,
            long top1ChangedCount,
            double macroRecallDelta,
            double macroMrrDelta,
            double macroNdcgDelta,
            double macroCitationPrecisionDelta,
            double macroCitationCoverageDelta,
            double refusalAccuracyDelta,
            long scopeViolationCount,
            long p50LatencyMicros,
            long p95LatencyMicros,
            int totalCandidateCount,
            double averageCandidateCount
    ) {
    }
}

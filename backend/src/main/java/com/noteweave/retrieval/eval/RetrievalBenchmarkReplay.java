package com.noteweave.retrieval.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.eval.DeterministicBm25Baseline.RankedEvidence;
import com.noteweave.retrieval.eval.RetrievalGoldSet.GoldCase;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class RetrievalBenchmarkReplay {
    public static final String BASELINE_VERSION = "deterministic-bm25-v1";
    public static final String GOLD_SCHEMA_VERSION = "retrieval-gold-v1";

    private final ObjectMapper objectMapper;
    private final DeterministicBm25Baseline baseline;

    public RetrievalBenchmarkReplay() {
        this(new ObjectMapper().findAndRegisterModules(), new DeterministicBm25Baseline());
    }

    RetrievalBenchmarkReplay(ObjectMapper objectMapper, DeterministicBm25Baseline baseline) {
        this.objectMapper = objectMapper;
        this.baseline = baseline;
    }

    public BenchmarkReport replay(Path goldSetPath) throws Exception {
        RetrievalGoldSet goldSet = objectMapper.readValue(goldSetPath.toFile(), RetrievalGoldSet.class);
        return replay(goldSet);
    }

    public BenchmarkReport replay(RetrievalGoldSet goldSet) {
        validate(goldSet);
        return buildReport(goldSet, BASELINE_VERSION, baseline::rank);
    }

    public BenchmarkReport evaluateRankings(
            RetrievalGoldSet goldSet,
            String rankingVersion,
            Map<String, List<RankedEvidence>> rankingsByCaseId
    ) {
        validate(goldSet);
        if (rankingVersion == null || rankingVersion.isBlank()) {
            throw new IllegalArgumentException("rankingVersion is required");
        }
        Map<String, List<RankedEvidence>> rankings = rankingsByCaseId == null
                ? Map.of()
                : rankingsByCaseId;
        return buildReport(
                goldSet,
                rankingVersion,
                goldCase -> rankings.getOrDefault(goldCase.id(), List.of())
        );
    }

    private BenchmarkReport buildReport(
            RetrievalGoldSet goldSet,
            String rankingVersion,
            Function<GoldCase, List<RankedEvidence>> rankingProvider
    ) {
        List<CaseResult> cases = goldSet.cases().stream()
                .map(goldCase -> evaluate(goldCase, rankingProvider.apply(goldCase)))
                .toList();
        Map<String, AggregateMetrics> byMode = new LinkedHashMap<>();
        cases.stream().map(CaseResult::mode).distinct().forEach(mode -> byMode.put(
                mode,
                aggregate(cases.stream().filter(result -> mode.equals(result.mode())).toList())
        ));
        return new BenchmarkReport(
                goldSet.schemaVersion(),
                goldSet.datasetVersion(),
                rankingVersion,
                cases,
                aggregate(cases),
                Collections.unmodifiableMap(new LinkedHashMap<>(byMode))
        );
    }

    private CaseResult evaluate(GoldCase goldCase, List<RankedEvidence> suppliedRanking) {
        Set<String> allowedSources = new LinkedHashSet<>(goldCase.allowedSourceIds());
        List<RankedEvidence> ranked = (suppliedRanking == null ? List.<RankedEvidence>of() : suppliedRanking)
                .stream()
                .limit(goldCase.topK())
                .toList();
        Set<String> relevant = new LinkedHashSet<>(goldCase.relevantEvidenceIds());
        List<String> rankedIds = ranked.stream().map(RankedEvidence::evidenceId).toList();
        long relevantRetrieved = rankedIds.stream().filter(relevant::contains).count();
        double recallAtK = relevant.isEmpty() ? 1.0d : (double) relevantRetrieved / relevant.size();
        double reciprocalRank = reciprocalRank(rankedIds, relevant);
        double ndcgAtK = ndcg(rankedIds, relevant, goldCase.topK());

        Set<String> selectedCitations = new LinkedHashSet<>();
        ranked.forEach(item -> selectedCitations.addAll(item.citationIds()));
        Set<String> expectedCitations = new LinkedHashSet<>(goldCase.expectedCitationIds());
        long correctCitations = selectedCitations.stream().filter(expectedCitations::contains).count();
        double citationPrecision = selectedCitations.isEmpty()
                ? (expectedCitations.isEmpty() ? 1.0d : 0.0d)
                : (double) correctCitations / selectedCitations.size();
        double citationCoverage = expectedCitations.isEmpty()
                ? 1.0d
                : (double) correctCitations / expectedCitations.size();

        long scopeViolationCount = ranked.stream()
                .filter(item -> !allowedSources.isEmpty() && !allowedSources.contains(item.sourceId()))
                .count();
        boolean refusalCorrect = !goldCase.shouldRefuse() || ranked.isEmpty();
        return new CaseResult(
                goldCase.id(),
                goldCase.mode(),
                goldCase.query(),
                goldCase.topK(),
                ranked,
                recallAtK,
                reciprocalRank,
                ndcgAtK,
                citationPrecision,
                citationCoverage,
                expectedCitations.size(),
                scopeViolationCount,
                goldCase.shouldRefuse(),
                refusalCorrect
        );
    }

    private AggregateMetrics aggregate(List<CaseResult> cases) {
        List<CaseResult> retrievalCases = cases.stream()
                .filter(result -> !result.shouldRefuse())
                .toList();
        List<CaseResult> citationCases = cases.stream()
                .filter(result -> result.expectedCitationCount() > 0)
                .toList();
        List<CaseResult> refusalCases = cases.stream().filter(CaseResult::shouldRefuse).toList();
        return new AggregateMetrics(
                cases.size(),
                retrievalCases.size(),
                refusalCases.size(),
                average(retrievalCases, CaseResult::recallAtK),
                average(retrievalCases, CaseResult::reciprocalRank),
                average(retrievalCases, CaseResult::ndcgAtK),
                average(citationCases, CaseResult::citationPrecision),
                average(citationCases, CaseResult::citationCoverage),
                refusalCases.isEmpty() ? 1.0d : refusalCases.stream().filter(CaseResult::refusalCorrect).count()
                        / (double) refusalCases.size(),
                cases.stream().mapToLong(CaseResult::scopeViolationCount).sum()
        );
    }

    private double average(List<CaseResult> cases, java.util.function.ToDoubleFunction<CaseResult> metric) {
        return cases.isEmpty() ? 1.0d : cases.stream().mapToDouble(metric).average().orElse(1.0d);
    }

    private double reciprocalRank(List<String> rankedIds, Set<String> relevant) {
        for (int index = 0; index < rankedIds.size(); index++) {
            if (relevant.contains(rankedIds.get(index))) {
                return 1.0d / (index + 1);
            }
        }
        return relevant.isEmpty() ? 1.0d : 0.0d;
    }

    private double ndcg(List<String> rankedIds, Set<String> relevant, int topK) {
        if (relevant.isEmpty()) {
            return 1.0d;
        }
        double dcg = 0.0d;
        for (int index = 0; index < rankedIds.size(); index++) {
            if (relevant.contains(rankedIds.get(index))) {
                dcg += 1.0d / log2(index + 2.0d);
            }
        }
        double ideal = 0.0d;
        for (int index = 0; index < Math.min(relevant.size(), topK); index++) {
            ideal += 1.0d / log2(index + 2.0d);
        }
        return ideal == 0.0d ? 0.0d : dcg / ideal;
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2.0d);
    }

    private void validate(RetrievalGoldSet goldSet) {
        if (!GOLD_SCHEMA_VERSION.equals(goldSet.schemaVersion())) {
            throw new IllegalArgumentException("Unsupported retrieval gold schema: " + goldSet.schemaVersion());
        }
        if (goldSet.datasetVersion().isBlank()) {
            throw new IllegalArgumentException("Retrieval gold datasetVersion is required");
        }
        if (goldSet.cases().isEmpty()) {
            throw new IllegalArgumentException("Retrieval gold cases are required");
        }
        Set<String> caseIds = new LinkedHashSet<>();
        for (GoldCase goldCase : goldSet.cases()) {
            if (goldCase.id().isBlank() || !caseIds.add(goldCase.id())) {
                throw new IllegalArgumentException("Retrieval gold case ids must be unique and non-blank");
            }
            if (goldCase.mode().isBlank()
                    || goldCase.workspaceId().isBlank()
                    || goldCase.query().isBlank()) {
                throw new IllegalArgumentException(
                        "Retrieval gold case mode/workspace/query is required: " + goldCase.id());
            }
            if (!Set.of("QA", "NOTE", "WIKI").contains(goldCase.mode())) {
                throw new IllegalArgumentException("Unsupported retrieval gold mode: " + goldCase.mode());
            }
            if (goldCase.topK() <= 0) {
                throw new IllegalArgumentException("Retrieval gold topK must be positive: " + goldCase.id());
            }
            Set<String> candidateIds = new LinkedHashSet<>();
            Set<String> candidateCitationIds = new LinkedHashSet<>();
            goldCase.candidates().forEach(candidate -> {
                if (candidate.evidenceId().isBlank()
                        || candidate.sourceId().isBlank()
                        || !candidateIds.add(candidate.evidenceId())) {
                    throw new IllegalArgumentException(
                            "Candidate evidence/source ids must be non-blank and evidence ids unique: "
                                    + goldCase.id());
                }
                candidateCitationIds.addAll(candidate.citationIds());
            });
            if (!candidateIds.containsAll(goldCase.relevantEvidenceIds())) {
                throw new IllegalArgumentException("Relevant evidence is missing from candidates: " + goldCase.id());
            }
            Map<String, String> sourceByEvidence = new LinkedHashMap<>();
            goldCase.candidates().forEach(candidate -> sourceByEvidence.put(
                    candidate.evidenceId(), candidate.sourceId()));
            Set<String> allowedSources = new LinkedHashSet<>(goldCase.allowedSourceIds());
            if (!allowedSources.isEmpty() && goldCase.relevantEvidenceIds().stream()
                    .map(sourceByEvidence::get)
                    .anyMatch(sourceId -> !allowedSources.contains(sourceId))) {
                throw new IllegalArgumentException("Relevant evidence is outside allowed scope: " + goldCase.id());
            }
            if (!candidateCitationIds.containsAll(goldCase.expectedCitationIds())) {
                throw new IllegalArgumentException("Expected citation is missing from candidates: " + goldCase.id());
            }
            Set<String> relevantCitationIds = new LinkedHashSet<>();
            goldCase.candidates().stream()
                    .filter(candidate -> goldCase.relevantEvidenceIds().contains(candidate.evidenceId()))
                    .forEach(candidate -> relevantCitationIds.addAll(candidate.citationIds()));
            if (!relevantCitationIds.containsAll(goldCase.expectedCitationIds())) {
                throw new IllegalArgumentException(
                        "Expected citation must belong to relevant evidence: " + goldCase.id());
            }
            if (goldCase.shouldRefuse() && !goldCase.relevantEvidenceIds().isEmpty()) {
                throw new IllegalArgumentException("Refusal case cannot declare relevant evidence: " + goldCase.id());
            }
            if (!goldCase.shouldRefuse() && goldCase.relevantEvidenceIds().isEmpty()) {
                throw new IllegalArgumentException("Retrieval case must declare relevant evidence: " + goldCase.id());
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: RetrievalBenchmarkReplay <gold-set.json>");
        }
        RetrievalBenchmarkReplay replay = new RetrievalBenchmarkReplay();
        BenchmarkReport report = replay.replay(Path.of(args[0]));
        System.out.println(replay.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    public record BenchmarkReport(
            String schemaVersion,
            String datasetVersion,
            String rankingVersion,
            List<CaseResult> cases,
            AggregateMetrics overall,
            Map<String, AggregateMetrics> byMode
    ) {
    }

    public record CaseResult(
            String id,
            String mode,
            String query,
            int topK,
            List<RankedEvidence> rankedEvidence,
            double recallAtK,
            double reciprocalRank,
            double ndcgAtK,
            double citationPrecision,
            double citationCoverage,
            int expectedCitationCount,
            long scopeViolationCount,
            boolean shouldRefuse,
            boolean refusalCorrect
    ) {
    }

    public record AggregateMetrics(
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
}

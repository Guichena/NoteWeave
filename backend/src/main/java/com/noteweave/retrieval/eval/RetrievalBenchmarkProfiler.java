package com.noteweave.retrieval.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.eval.RetrievalBenchmarkReplay.BenchmarkReport;
import com.noteweave.retrieval.eval.RetrievalGoldSet.Candidate;
import com.noteweave.retrieval.eval.RetrievalGoldSet.GoldCase;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class RetrievalBenchmarkProfiler {
    private final ObjectMapper objectMapper;
    private final RetrievalBenchmarkReplay replay;
    private final DeterministicBm25Baseline baseline;

    public RetrievalBenchmarkProfiler() {
        this(
                new ObjectMapper().findAndRegisterModules(),
                new RetrievalBenchmarkReplay(),
                new DeterministicBm25Baseline()
        );
    }

    RetrievalBenchmarkProfiler(
            ObjectMapper objectMapper,
            RetrievalBenchmarkReplay replay,
            DeterministicBm25Baseline baseline
    ) {
        this.objectMapper = objectMapper;
        this.replay = replay;
        this.baseline = baseline;
    }

    public ProfileReport profile(Path goldSetPath, int warmupIterations, int measuredIterations) throws Exception {
        RetrievalGoldSet goldSet = objectMapper.readValue(goldSetPath.toFile(), RetrievalGoldSet.class);
        return profile(goldSet, warmupIterations, measuredIterations);
    }

    public ProfileReport profile(
            RetrievalGoldSet goldSet,
            int warmupIterations,
            int measuredIterations
    ) {
        if (warmupIterations < 0 || measuredIterations <= 0) {
            throw new IllegalArgumentException("Profiler requires warmup >= 0 and measured iterations > 0");
        }
        for (int iteration = 0; iteration < warmupIterations; iteration++) {
            replay.replay(goldSet);
        }

        List<Long> latencies = new ArrayList<>();
        BenchmarkReport reference = null;
        for (int iteration = 0; iteration < measuredIterations; iteration++) {
            long started = System.nanoTime();
            BenchmarkReport current = replay.replay(goldSet);
            latencies.add(System.nanoTime() - started);
            if (reference == null) {
                reference = current;
            } else if (!reference.equals(current)) {
                throw new IllegalStateException("Retrieval replay report changed between profiler iterations");
            }
        }
        List<Long> sorted = latencies.stream().sorted().toList();
        return new ProfileReport(
                goldSet.datasetVersion(),
                RetrievalBenchmarkReplay.BASELINE_VERSION,
                warmupIterations,
                measuredIterations,
                sorted.get(0),
                percentile(sorted, 50),
                percentile(sorted, 95),
                sorted.get(sorted.size() - 1),
                latencies.stream().mapToLong(Long::longValue).average().orElse(0.0d),
                workload(goldSet, reference)
        );
    }

    private WorkloadCost workload(RetrievalGoldSet goldSet, BenchmarkReport report) {
        int totalCandidates = goldSet.cases().stream().mapToInt(item -> item.candidates().size()).sum();
        int allowedCandidates = 0;
        int queryTokens = 0;
        int candidateTokens = 0;
        int requestedTopK = 0;
        int relevantEvidence = 0;
        int expectedCitations = 0;
        for (GoldCase goldCase : goldSet.cases()) {
            Set<String> allowedSources = new LinkedHashSet<>(goldCase.allowedSourceIds());
            List<Candidate> allowed = goldCase.candidates().stream()
                    .filter(candidate -> allowedSources.isEmpty() || allowedSources.contains(candidate.sourceId()))
                    .toList();
            allowedCandidates += allowed.size();
            queryTokens += baseline.tokens(goldCase.query()).size();
            for (Candidate candidate : allowed) {
                candidateTokens += baseline.tokens(candidate.title()).size();
                candidateTokens += baseline.tokens(candidate.content()).size();
                candidateTokens += baseline.tokens(candidate.sourceType()).size();
            }
            requestedTopK += goldCase.topK();
            relevantEvidence += goldCase.relevantEvidenceIds().size();
            expectedCitations += goldCase.expectedCitationIds().size();
        }
        int returnedEvidence = report.cases().stream()
                .mapToInt(item -> item.rankedEvidence().size())
                .sum();
        return new WorkloadCost(
                goldSet.cases().size(),
                totalCandidates,
                allowedCandidates,
                queryTokens,
                candidateTokens,
                requestedTopK,
                returnedEvidence,
                relevantEvidence,
                expectedCitations
        );
    }

    private long percentile(List<Long> sortedValues, int percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile / 100.0d * sortedValues.size()) - 1);
        return sortedValues.get(Math.min(index, sortedValues.size() - 1));
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3) {
            throw new IllegalArgumentException(
                    "Usage: RetrievalBenchmarkProfiler <gold-set.json> [warmup=5] [iterations=30]");
        }
        int warmup = args.length >= 2 ? Integer.parseInt(args[1]) : 5;
        int iterations = args.length >= 3 ? Integer.parseInt(args[2]) : 30;
        RetrievalBenchmarkProfiler profiler = new RetrievalBenchmarkProfiler();
        ProfileReport report = profiler.profile(Path.of(args[0]), warmup, iterations);
        System.out.println(profiler.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    public record ProfileReport(
            String datasetVersion,
            String baselineVersion,
            int warmupIterations,
            int measuredIterations,
            long minLatencyNanos,
            long p50LatencyNanos,
            long p95LatencyNanos,
            long maxLatencyNanos,
            double averageLatencyNanos,
            WorkloadCost workload
    ) {
    }

    public record WorkloadCost(
            int caseCount,
            int totalCandidateCount,
            int allowedCandidateCount,
            int queryTokenCount,
            int candidateTokenCount,
            int requestedTopK,
            int returnedEvidenceCount,
            int relevantEvidenceCount,
            int expectedCitationCount
    ) {
    }
}

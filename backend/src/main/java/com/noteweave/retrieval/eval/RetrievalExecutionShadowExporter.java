package com.noteweave.retrieval.eval;

import com.noteweave.answer.strategy.EvidenceBundleSnapshot;
import com.noteweave.answer.strategy.RetrievalExecutionTrace;
import com.noteweave.retrieval.eval.DeterministicBm25Baseline.RankedEvidence;
import com.noteweave.retrieval.eval.RetrievalShadowSnapshot.CaseRanking;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Maps persisted online retrieval artifacts into the same schema consumed by shadow comparison. */
@Component
public class RetrievalExecutionShadowExporter {

    private final RetrievalSnapshotSanitizer sanitizer;

    public RetrievalExecutionShadowExporter(RetrievalSnapshotSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    public RetrievalShadowSnapshot export(
            String snapshotVersion,
            List<CaseExecution> executions,
            String salt
    ) {
        return export(snapshotVersion, "", executions, salt);
    }

    public RetrievalShadowSnapshot export(
            String snapshotVersion,
            String strategyProfile,
            List<CaseExecution> executions,
            String salt
    ) {
        if (snapshotVersion == null || snapshotVersion.isBlank()) {
            throw new IllegalArgumentException("Online retrieval shadow snapshotVersion is required");
        }
        if (executions == null || executions.isEmpty()) {
            throw new IllegalArgumentException("Online retrieval shadow requires at least one execution");
        }
        List<CaseRanking> cases = executions.stream()
                .map(execution -> exportCase(execution, salt))
                .toList();
        return new RetrievalShadowSnapshot(
                RetrievalShadowComparator.SHADOW_SCHEMA_VERSION,
                snapshotVersion,
                strategyProfile,
                cases
        );
    }

    private CaseRanking exportCase(CaseExecution execution, String salt) {
        if (execution == null || execution.trace() == null || execution.bundle() == null) {
            throw new IllegalArgumentException("Online retrieval execution requires trace and bundle snapshot");
        }
        Map<String, List<String>> citations = execution.citationIdsByEvidenceIdentity();
        List<RankedEvidence> ranked = execution.bundle().evidence().stream()
                .map(evidence -> {
                    String identity = evidenceIdentity(evidence);
                    String sourceIdentity = sourceIdentity(evidence);
                    List<String> citationIds = citations.getOrDefault(identity, List.of()).stream()
                            .map(citationId -> sanitizer.pseudonym("citation", citationId, salt))
                            .toList();
                    return new RankedEvidence(
                            sanitizer.pseudonym("evidence", identity, salt),
                            sanitizer.pseudonym("source", sourceIdentity, salt),
                            evidence.rerankScore(),
                            citationIds
                    );
                })
                .toList();
        return new CaseRanking(
                sanitizer.pseudonym("case", execution.caseId(), salt),
                execution.trace().totalLatencyMicros(),
                candidateCount(execution.trace()),
                ranked
        );
    }

    private int candidateCount(RetrievalExecutionTrace trace) {
        if (trace.steps().isEmpty()) {
            return trace.rawCandidateCount();
        }
        int total = 0;
        for (RetrievalExecutionTrace.StepTrace step : trace.steps()) {
            total = saturatingAdd(total, stepCandidateCount(step));
        }
        return total;
    }

    private int stepCandidateCount(RetrievalExecutionTrace.StepTrace step) {
        Map<String, Long> measurements = step.measurements();
        if (Long.valueOf(1L).equals(measurements.get("mysql_fallback_used"))
                && measurements.containsKey("mysql_candidate_count")) {
            return boundedCandidateCount(measurements.get("mysql_candidate_count"));
        }
        if (measurements.containsKey("primary_hit_count")) {
            return boundedCandidateCount(measurements.get("primary_hit_count"));
        }
        return step.rawCandidateCount();
    }

    private int boundedCandidateCount(Long value) {
        if (value == null || value <= 0) {
            return 0;
        }
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : value.intValue();
    }

    private int saturatingAdd(int left, int right) {
        return left >= Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
    }

    private String evidenceIdentity(EvidenceBundleSnapshot.EvidenceSnapshot evidence) {
        if ("PASSAGE".equals(evidence.kind()) && !blank(evidence.passageId())) {
            return evidence.passageId();
        }
        if ("KNOWLEDGE_VERSION".equals(evidence.kind()) && !blank(evidence.knowledgeVersionId())) {
            return evidence.knowledgeVersionId();
        }
        return evidence.evidenceId();
    }

    private String sourceIdentity(EvidenceBundleSnapshot.EvidenceSnapshot evidence) {
        if (!blank(evidence.sourceId())) {
            return evidence.sourceId();
        }
        if (!blank(evidence.knowledgeItemId())) {
            return evidence.knowledgeItemId();
        }
        return evidence.evidenceId();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record CaseExecution(
            String caseId,
            RetrievalExecutionTrace trace,
            EvidenceBundleSnapshot bundle,
            Map<String, List<String>> citationIdsByEvidenceIdentity
    ) {
        public CaseExecution {
            caseId = caseId == null ? "" : caseId;
            citationIdsByEvidenceIdentity = citationIdsByEvidenceIdentity == null
                    ? Map.of() : Map.copyOf(citationIdsByEvidenceIdentity);
        }
    }
}

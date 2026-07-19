package com.noteweave.chat;

import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.EvidenceRetrievalResult;
import com.noteweave.answer.strategy.EvidenceRetriever;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.retrieval.QaRetrievalStrategyProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class QaPassageEvidenceRetriever implements EvidenceRetriever {

    public static final String CHANNEL = "QA_PASSAGE";

    private final QaPassageRetriever passageRetriever;

    public QaPassageEvidenceRetriever(QaPassageRetriever passageRetriever) {
        this.passageRetriever = passageRetriever;
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public EvidenceRetrievalResult retrieve(
            AnswerContext context,
            RetrievalPlan plan,
            RetrievalPlan.Step step
    ) {
        QaRetrievalStrategyProfile profile = QaRetrievalStrategyProfile.fromPlan(plan, step);
        QaPassageRetriever.RetrievalResult retrieval = passageRetriever.retrieveWithDiagnostics(
                context.workspaceId(), context.query(), context.sourceScope(), profile);
        List<EvidenceBundle.Evidence> evidence = retrieval.chunks().stream()
                .limit(Math.max(0, step.candidateLimit()))
                .map(chunk -> toEvidence(chunk, step.weight()))
                .toList();
        return new EvidenceRetrievalResult(
                evidence,
                Map.of(
                        "strategy_profile", profile.profileVersion(),
                        "relevance_policy", profile.relevancePolicyVersion(),
                        "selection_policy", profile.selectionPolicyVersion()),
                retrieval.degraded(),
                retrieval.degradationReasons(),
                retrieval.measurements()
        );
    }

    private EvidenceBundle.Evidence toEvidence(
            QaPassageRetriever.RetrievedChunk chunk,
            double weight
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source_type", text(chunk.sourceType()));
        metadata.put("generated_by", text(chunk.generatedBy()));
        metadata.put("generated_ref_id", text(chunk.generatedRefId()));
        metadata.put("chunk_no", Integer.toString(chunk.chunkNo()));
        metadata.put("match_reason", text(chunk.matchReason()));
        metadata.put("raw_title", text(chunk.title()));
        metadata.put("freshness_status", "CURRENT_AT_RETRIEVAL");
        double fusedScore = chunk.fusedScore() * weight;
        return new EvidenceBundle.Evidence(
                "passage:" + chunk.chunkId(),
                "PASSAGE",
                chunk.sourceId(),
                chunk.sourceSnapshotId(),
                chunk.chunkId(),
                "",
                "",
                displayTitle(chunk.title(), chunk.generatedBy(), chunk.generatedRefId()),
                chunk.content(),
                chunk.locationInfo(),
                chunk.rawScore(),
                fusedScore,
                chunk.rerankScore(),
                contextScope(chunk.sourceId()),
                java.time.Instant.now(),
                chunk.matchReason(),
                chunk.content() == null ? 0 : chunk.content().length(),
                metadata
        );
    }

    private String displayTitle(String title, String generatedBy, String generatedRefId) {
        if ("research_agent".equals(generatedBy)) {
            return title + " · Research Report(" + (
                    generatedRefId == null || generatedRefId.isBlank() ? "unknown run" : generatedRefId) + ")";
        }
        return title;
    }

    private String contextScope(String sourceId) {
        return "workspace-source:" + sourceId;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}

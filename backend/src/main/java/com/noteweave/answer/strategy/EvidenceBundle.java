package com.noteweave.answer.strategy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record EvidenceBundle(
        String bundleId,
        String retrievalPlanVersion,
        List<Evidence> evidence,
        boolean degraded,
        List<String> degradationReasons,
        Instant createdAt,
        Map<String, String> metadata,
        RetrievalExecutionTrace trace
) {
    public EvidenceBundle {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        trace = trace == null
                ? RetrievalExecutionTrace.empty(retrievalPlanVersion)
                : trace;
    }

    public EvidenceBundle(
            String bundleId,
            String retrievalPlanVersion,
            List<Evidence> evidence,
            boolean degraded,
            List<String> degradationReasons,
            Instant createdAt,
            Map<String, String> metadata
    ) {
        this(bundleId, retrievalPlanVersion, evidence, degraded, degradationReasons,
                createdAt, metadata, RetrievalExecutionTrace.empty(retrievalPlanVersion));
    }

    public EvidenceBundle(
            String bundleId,
            String retrievalPlanVersion,
            List<Evidence> evidence,
            boolean degraded,
            List<String> degradationReasons,
            Instant createdAt
    ) {
        this(bundleId, retrievalPlanVersion, evidence, degraded, degradationReasons,
                createdAt, Map.of(), RetrievalExecutionTrace.empty(retrievalPlanVersion));
    }

    public record Evidence(
            String evidenceId,
            String kind,
            String sourceId,
            String sourceSnapshotId,
            String passageId,
            String knowledgeItemId,
            String knowledgeVersionId,
            String title,
            String excerpt,
            String location,
            double rawScore,
            double fusedScore,
            double rerankScore,
            String accessScope,
            Instant freshAt,
            String selectionReason,
            int characterCost,
            Map<String, String> metadata,
            ScoreInfo scoreInfo
    ) {
        public Evidence {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            scoreInfo = scoreInfo == null ? ScoreInfo.unknown() : scoreInfo;
        }

        /**
         * 兼容历史调用：没有显式声明评分来源时保留历史未知。
         * 三条检索链路和会话证据通道都显式传入 {@link ScoreInfo}，这条路径只留给测试与历史数据。
         */
        public Evidence(
                String evidenceId,
                String kind,
                String sourceId,
                String sourceSnapshotId,
                String passageId,
                String knowledgeItemId,
                String knowledgeVersionId,
                String title,
                String excerpt,
                String location,
                double rawScore,
                double fusedScore,
                double rerankScore,
                String accessScope,
                Instant freshAt,
                String selectionReason,
                int characterCost,
                Map<String, String> metadata
        ) {
            this(evidenceId, kind, sourceId, sourceSnapshotId, passageId, knowledgeItemId,
                    knowledgeVersionId, title, excerpt, location, rawScore, fusedScore, rerankScore,
                    accessScope, freshAt, selectionReason, characterCost, metadata, ScoreInfo.unknown());
        }
    }
}

package com.noteweave.answer.strategy;

import java.time.Instant;
import java.util.List;

/**
 * Persistable audit view of an EvidenceBundle. Text and opaque retriever metadata are excluded so
 * AnswerRun can retain exact evidence identities and ranking decisions without duplicating source
 * content into operational tables.
 */
public record EvidenceBundleSnapshot(
        String schemaVersion,
        String bundleId,
        String retrievalPlanVersion,
        boolean degraded,
        List<String> degradationReasons,
        Instant createdAt,
        List<EvidenceSnapshot> evidence
) {
    public static final String SCHEMA_VERSION = "evidence-bundle-snapshot-v1";

    public EvidenceBundleSnapshot {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion;
        degradationReasons = degradationReasons == null
                ? List.of() : List.copyOf(degradationReasons);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static EvidenceBundleSnapshot from(EvidenceBundle bundle) {
        if (bundle == null) {
            return new EvidenceBundleSnapshot(
                    SCHEMA_VERSION, "", "", false, List.of(), null, List.of());
        }
        List<EvidenceSnapshot> evidence = java.util.stream.IntStream
                .range(0, bundle.evidence().size())
                .mapToObj(index -> EvidenceSnapshot.from(index + 1, bundle.evidence().get(index)))
                .toList();
        return new EvidenceBundleSnapshot(
                SCHEMA_VERSION,
                bundle.bundleId(),
                bundle.retrievalPlanVersion(),
                bundle.degraded(),
                bundle.degradationReasons(),
                bundle.createdAt(),
                evidence
        );
    }

    public record EvidenceSnapshot(
            int rank,
            String evidenceId,
            String kind,
            String sourceId,
            String sourceSnapshotId,
            String passageId,
            String knowledgeItemId,
            String knowledgeVersionId,
            double rawScore,
            double fusedScore,
            double rerankScore,
            String accessScope,
            Instant freshAt,
            String selectionReason,
            int characterCost
    ) {
        public EvidenceSnapshot {
            rank = Math.max(1, rank);
            characterCost = Math.max(0, characterCost);
        }

        static EvidenceSnapshot from(int rank, EvidenceBundle.Evidence evidence) {
            return new EvidenceSnapshot(
                    rank,
                    evidence.evidenceId(),
                    evidence.kind(),
                    evidence.sourceId(),
                    evidence.sourceSnapshotId(),
                    evidence.passageId(),
                    evidence.knowledgeItemId(),
                    evidence.knowledgeVersionId(),
                    evidence.rawScore(),
                    evidence.fusedScore(),
                    evidence.rerankScore(),
                    evidence.accessScope(),
                    evidence.freshAt(),
                    evidence.selectionReason(),
                    evidence.characterCost()
            );
        }
    }
}

package com.noteweave.answer.strategy;

import java.util.List;
import java.util.Set;

/** Read-side port that proves evidence identities belong to the current Workspace and version. */
public interface EvidenceOwnershipPort {

    Set<EvidenceIdentity> findCurrent(String workspaceId, List<EvidenceIdentity> identities);

    /** 研究证据的 passageId 存证据键，researchRunId 存所属研究运行；其他类型的 researchRunId 为空串。 */
    record EvidenceIdentity(
            String kind,
            String sourceId,
            String sourceSnapshotId,
            String passageId,
            String knowledgeItemId,
            String knowledgeVersionId,
            String researchRunId
    ) {
        public EvidenceIdentity(
                String kind,
                String sourceId,
                String sourceSnapshotId,
                String passageId,
                String knowledgeItemId,
                String knowledgeVersionId
        ) {
            this(kind, sourceId, sourceSnapshotId, passageId, knowledgeItemId, knowledgeVersionId, "");
        }

        public static EvidenceIdentity from(EvidenceBundle.Evidence evidence) {
            return new EvidenceIdentity(
                    evidence.kind(),
                    text(evidence.sourceId()),
                    text(evidence.sourceSnapshotId()),
                    text(evidence.passageId()),
                    text(evidence.knowledgeItemId()),
                    text(evidence.knowledgeVersionId()),
                    ResearchReportEvidence.researchRunId(evidence)
            );
        }

        private static String text(String value) {
            return value == null ? "" : value;
        }
    }
}

package com.noteweave.answer.strategy;

import java.util.List;
import java.util.Set;

/** Read-side port that proves evidence identities belong to the current Workspace and version. */
public interface EvidenceOwnershipPort {

    Set<EvidenceIdentity> findCurrent(String workspaceId, List<EvidenceIdentity> identities);

    record EvidenceIdentity(
            String kind,
            String sourceId,
            String sourceSnapshotId,
            String passageId,
            String knowledgeItemId,
            String knowledgeVersionId
    ) {
        public static EvidenceIdentity from(EvidenceBundle.Evidence evidence) {
            return new EvidenceIdentity(
                    evidence.kind(),
                    text(evidence.sourceId()),
                    text(evidence.sourceSnapshotId()),
                    text(evidence.passageId()),
                    text(evidence.knowledgeItemId()),
                    text(evidence.knowledgeVersionId())
            );
        }

        private static String text(String value) {
            return value == null ? "" : value;
        }
    }
}

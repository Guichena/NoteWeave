package com.noteweave.answer.strategy;

import com.noteweave.answer.strategy.EvidenceOwnershipPort.EvidenceIdentity;
import com.noteweave.common.BusinessException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class EvidenceOwnershipGuard {

    private final EvidenceOwnershipPort ownershipPort;

    public EvidenceOwnershipGuard(EvidenceOwnershipPort ownershipPort) {
        this.ownershipPort = ownershipPort;
    }

    public void requireCurrent(
            AnswerContext context,
            List<EvidenceBundle.Evidence> evidence
    ) {
        if (context == null || evidence == null) {
            throw violation();
        }
        List<EvidenceIdentity> identities = evidence.stream()
                .map(EvidenceIdentity::from)
                .distinct()
                .toList();
        if (identities.isEmpty()) {
            return;
        }
        Set<EvidenceIdentity> current = ownershipPort.findCurrent(
                context.workspaceId(), identities);
        if (current == null || !current.containsAll(new LinkedHashSet<>(identities))) {
            throw violation();
        }
    }

    static EvidenceOwnershipGuard trustValidatedRetriever() {
        return new EvidenceOwnershipGuard((workspaceId, identities) -> Set.copyOf(identities));
    }

    private BusinessException violation() {
        return new BusinessException(
                "EVIDENCE_SCOPE_VIOLATION",
                "Evidence does not belong to the current Workspace or active version",
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }
}

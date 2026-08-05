package com.noteweave.memory;

import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryPromotionService {

    private final MemoryCandidateService memoryCandidateService;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final CanonicalMemoryReviewService canonicalMemoryReviewService;

    public MemoryPromotionService(
            MemoryCandidateService memoryCandidateService,
            WorkspaceAccessGuard workspaceAccessGuard,
            CanonicalMemoryReviewService canonicalMemoryReviewService
    ) {
        this.memoryCandidateService = memoryCandidateService;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.canonicalMemoryReviewService = canonicalMemoryReviewService;
    }

    @Transactional
    public MemoryPromotionResponse promoteSignals(String workspaceId, List<String> signalIds) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.MEMORY_REVIEW);
        List<MemoryCandidateResponse> candidates = memoryCandidateService.buildCandidates(workspaceId, signalIds);
        List<MemoryObjectResponse> activeItems = new ArrayList<>();
        for (MemoryCandidateResponse response : candidates) {
            MemoryCandidateService.CandidateRow candidate =
                    memoryCandidateService.findCandidate(workspaceId, response.candidateId());
            MemoryObjectResponse projected = canonicalMemoryReviewService.projectCandidate(candidate);
            if ("READY".equals(candidate.reviewStatus())) {
                activeItems.add(projected);
            }
        }
        return new MemoryPromotionResponse(candidates, List.copyOf(activeItems));
    }
}

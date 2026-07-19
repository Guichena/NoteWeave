package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.util.LinkedHashSet;
import java.util.List;

public record ResearchAcquisitionPolicy(
        ResearchRetrievalMode mode,
        List<String> seedSourceIds,
        boolean allowExternalSearch,
        boolean allowExternalFetch
) {
    static ResearchAcquisitionPolicy compile(
            CreateResearchRunRequest request,
            ResearchAgentExternalEvidencePolicy externalEvidencePolicy
    ) {
        List<String> legacyIds = normalized(request.sourceScopeSourceIds());
        List<String> explicitSeedIds = normalized(request.seedSourceIds());
        if (!legacyIds.isEmpty() && !explicitSeedIds.isEmpty() && !legacyIds.equals(explicitSeedIds)) {
            throw new BusinessException(
                    "RESEARCH_SEED_SCOPE_CONFLICT", "Legacy source scope conflicts with seed source ids");
        }
        List<String> seedIds = explicitSeedIds.isEmpty() ? legacyIds : explicitSeedIds;
        ResearchRetrievalMode mode = ResearchRetrievalMode.resolve(request.retrievalMode(), legacyIds);
        if (mode == ResearchRetrievalMode.WEB_ONLY && !seedIds.isEmpty()) {
            throw new BusinessException(
                    "RESEARCH_WEB_ONLY_SEEDS_FORBIDDEN", "WEB_ONLY does not accept seed sources");
        }
        if (mode.usesSeeds() && seedIds.isEmpty()) {
            throw new BusinessException(
                    "RESEARCH_SEED_SCOPE_REQUIRED", mode + " requires at least one seed source");
        }
        if (mode.usesWeb() && !externalEvidencePolicy.enabled()) {
            throw new BusinessException(
                    "RESEARCH_WEB_DISABLED", "External Web research is disabled by server policy");
        }
        boolean allowExternal = mode.usesWeb();
        return new ResearchAcquisitionPolicy(mode, seedIds, allowExternal, allowExternal);
    }

    private static List<String> normalized(List<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(sourceIds));
    }
}

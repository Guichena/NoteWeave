package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.util.List;

public enum ResearchRetrievalMode {
    WEB_ONLY,
    WEB_PLUS_SEEDS,
    SOURCES_ONLY;

    static ResearchRetrievalMode resolve(String requestedMode, List<String> legacySourceIds) {
        if (requestedMode == null || requestedMode.isBlank()) {
            return legacySourceIds == null || legacySourceIds.isEmpty() ? WEB_ONLY : SOURCES_ONLY;
        }
        try {
            return valueOf(requestedMode.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    "RESEARCH_RETRIEVAL_MODE_INVALID", "Unsupported Research retrieval mode");
        }
    }

    boolean usesWeb() {
        return this != SOURCES_ONLY;
    }

    boolean usesSeeds() {
        return this != WEB_ONLY;
    }
}

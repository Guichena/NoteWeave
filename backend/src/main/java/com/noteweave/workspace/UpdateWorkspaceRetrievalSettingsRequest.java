package com.noteweave.workspace;

import jakarta.validation.constraints.NotNull;

public record UpdateWorkspaceRetrievalSettingsRequest(
        @NotNull Boolean retrievalStrategyV2Enabled
) {
}

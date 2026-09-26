package com.noteweave.research;

/**
 * Command response for a Research Run.
 *
 * <p>{@code resumeMode} / {@code resumeModeReason} are populated only by
 * {@link ResearchRunCommandService#resumeFromCheckpoint}. They make an {@code AUTO} request that
 * could not hydrate observably distinguishable from one that did, instead of silently degrading
 * to {@code CONTEXT_RESTART}.
 */
public record ResearchRunResponse(
        String researchRunId,
        String taskId,
        String status,
        String resumeMode,
        String resumeModeReason
) {
    public ResearchRunResponse(String researchRunId, String taskId, String status) {
        this(researchRunId, taskId, status, null, null);
    }
}

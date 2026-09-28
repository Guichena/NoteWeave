package com.noteweave.artifact;

import java.util.List;

/** Explicit parent selection; no child Artifact Job is created until material is READY. */
public record CreateVideoLearningBundleRequest(
        String clientRequestId,
        String videoUrl,
        int part,
        String language,
        String frameDensity,
        String asrFallback,
        String templateVersion,
        String userRequirement,
        List<String> selectedSkills
) {
    VideoLearningRequestDraft draft() {
        return new VideoLearningRequestDraft(videoUrl, part, language, frameDensity,
                asrFallback, templateVersion, userRequirement, selectedSkills);
    }
}

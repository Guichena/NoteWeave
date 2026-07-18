package com.noteweave.artifact;

public record ArtifactVersionComparisonResponse(
        int fromVersionNo,
        int toVersionNo,
        boolean titleChanged,
        int addedLines,
        int removedLines,
        int unchangedLines,
        String summary
) {
}

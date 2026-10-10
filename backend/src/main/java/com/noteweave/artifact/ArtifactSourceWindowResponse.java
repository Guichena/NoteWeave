package com.noteweave.artifact;

public record ArtifactSourceWindowResponse(
        String windowId, int chunkNo, int windowNo, String heading,
        String locationInfo, String content, String checksumSha256) { }

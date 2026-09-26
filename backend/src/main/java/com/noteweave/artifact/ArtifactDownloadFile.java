package com.noteweave.artifact;

public record ArtifactDownloadFile(String fileName, String mediaType, byte[] content) { }

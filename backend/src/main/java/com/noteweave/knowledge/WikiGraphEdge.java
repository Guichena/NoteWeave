package com.noteweave.knowledge;

public record WikiGraphEdge(String sourceItemId, String targetItemId, String targetTitle, String relationType, String relationStatus) {
}

package com.noteweave.personal.compiler.dto;

public record ConceptRelationDraft(
        String sourceName,
        String targetName,
        String relationType,
        String description
) {
}

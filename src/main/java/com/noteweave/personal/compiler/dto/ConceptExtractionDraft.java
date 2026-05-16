package com.noteweave.personal.compiler.dto;

import java.util.List;

public record ConceptExtractionDraft(
        List<ConceptDraft> concepts,
        List<ConceptRelationDraft> relations
) {
}

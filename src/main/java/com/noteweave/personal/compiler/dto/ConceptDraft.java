package com.noteweave.personal.compiler.dto;

import java.util.List;

public record ConceptDraft(
        String name,
        List<String> aliases,
        String definition,
        String explanation,
        List<String> useCases,
        List<String> commonMisunderstandings,
        EvidenceQuoteDraft evidence,
        double confidence
) {
}

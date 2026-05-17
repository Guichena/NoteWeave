package com.noteweave.personal.generation.service;

import com.noteweave.citation.model.Citation;
import com.noteweave.personal.source.model.Source;

public record PersonalEvidenceItem(
        Citation citation,
        Source source
) {
}

package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WikiConceptExtractorTest {

    @Test
    void shouldPreferTagConceptsAndThenFillFromTitle() {
        assertThat(WikiConceptExtractor.extract(
                "Retrieval Design.md", "[\"RAG\",\"Evidence\"]"))
                .containsExactly("RAG", "Evidence", "Retrieval", "Design");
    }

    @Test
    void shouldRemoveFormattingTermsAndCaseInsensitiveDuplicates() {
        assertThat(WikiConceptExtractor.extract(
                "RAG-notes.pdf", "[\"rag\",\"PDF\",\"RAG\"]"))
                .containsExactly("rag", "notes");
    }

    @Test
    void shouldBoundConceptCountAndIgnoreInvalidValues() {
        assertThat(WikiConceptExtractor.extract(
                "one two three four five six seven", "[\"a\",\"valid\"]"))
                .containsExactly("valid", "one", "two", "three", "four", "five");
    }

    @Test
    void shouldTreatMissingInputsAsNoConcepts() {
        assertThat(WikiConceptExtractor.extract(null, null)).isEmpty();
    }
}

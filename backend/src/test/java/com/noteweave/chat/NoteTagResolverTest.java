package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.noteweave.source.SourceTagCodec;
import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NoteTagResolverTest {
    @Test
    void resolvesFacetTagsAndLeavesUnknownTermsForTextFallback() {
        CandidateSource source = new CandidateSource(
                "source-1", "Architecture", "MARKDOWN", 1, 1, "", "", "",
                "[{\"name\":\"RAG\",\"facet\":\"topic\"}]", "{}", "", 0, "",
                List.of(), List.of(), 0, 0, "", "");

        var result = new NoteTagResolver(new SourceTagCodec(new ObjectMapper(), new SimpleMeterRegistry())).resolve(
                Set.of("rag", "unknown"), List.of(source));

        assertThat(result.resolvedTags()).containsExactly("topic:RAG");
        assertThat(result.unresolvedTerms()).containsExactly("unknown");
        assertThat(result.sourceScores()).containsEntry("source-1", 12);
    }
}

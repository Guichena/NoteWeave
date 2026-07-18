package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NoteRelationGraphTest {
    @Test
    void shouldBuildWeightedGraphAndPropagateAwayFromAnchor() {
        NoteRelationGraph graph = new NoteRelationGraph();
        CandidateSource a = source("a", "Alpha", "[\"shared\"]");
        CandidateSource b = source("b", "Alpha neighbor", "[\"shared\"]");
        var weights = graph.build(List.of(a, b), Map.of("note", Set.of("a", "b")), Map.of());
        assertThat(weights.get("a").get("b")).isPositive();
        assertThat(graph.propagate(weights, Set.of("a"), 4, 0.2)).containsKey("b");
        assertThat(graph.coOccurrence(Set.of("a"), Map.of("turn", Set.of("a", "b"))))
                .containsEntry("b", 1);
    }

    private CandidateSource source(String id, String title, String tags) {
        return new CandidateSource(id, title, "MARKDOWN", 1, 1, "", "", "summary", tags,
                "{}", "", 0, "", List.of(), List.of(), 0, 0, "", "");
    }
}

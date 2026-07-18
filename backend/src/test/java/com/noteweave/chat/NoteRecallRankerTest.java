package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.chat.NoteRecallRanker.ScoredCandidate;
import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NoteRecallRankerTest {
    @Test
    void shouldPreserveCoverageAwareMetadataScoring() {
        NoteRecallRanker ranker = new NoteRecallRanker();
        CandidateSource source = new CandidateSource(
                "source", "Spring Spring Boot 3", "PDF", 1, 1, "", "",
                "Boot 3", "[\"spring\"]", "{\"framework\":\"boot\"}", "Spring",
                0, "", List.of(), List.of(), 0, 0, "", ""
        );

        Map<String, NoteRecallRanker.MetadataRank> ranks = ranker.rankMetadata(
                List.of(source), new LinkedHashSet<>(List.of("spring", "boot", "3")));

        NoteRecallRanker.MetadataRank metadataRank = ranks.get("source");
        assertThat(metadataRank.score()).isEqualTo(183);
        assertThat(metadataRank.matchedFields())
                .containsExactly("title", "tags", "sample_text", "summary", "metadata");
        assertThat(metadataRank.coverageTerms()).containsExactly("spring", "boot", "3");
        assertThat(metadataRank.coveredQueryTerms()).isEqualTo(3);
        assertThat(metadataRank.totalQueryTerms()).isEqualTo(3);
    }

    @Test
    void shouldPreserveJournalMetadataRelationAndVerifyQuotas() {
        NoteRecallRanker ranker = new NoteRecallRanker();
        var selection = ranker.select(List.of(
                scored("journal", "PDF", 30, 0, 5, 0),
                scored("metadata", "MARKDOWN", 25, 8, 0, 0),
                scored("relation", "WEB", 20, 0, 0, 6),
                scored("other", "DOCX", 15, 2, 0, 0),
                scored("expansion", "HTML", 10, 0, 0, 5)));
        assertThat(selection.candidates()).extracting(ScoredCandidate::sourceId)
                .containsExactly("journal", "metadata", "other", "relation");
        assertThat(selection.candidates()).extracting(ScoredCandidate::selectionReason)
                .containsExactly("journal-quota", "metadata-quota", "metadata-quota", "relation-quota");
        assertThat(selection.expansions()).extracting(ScoredCandidate::sourceId)
                .containsExactly("expansion");
        assertThat(selection.verifySources()).extracting(CandidateSource::sourceId)
                .containsExactly("journal", "metadata", "other", "relation", "expansion");
        assertThat(selection.verifySources()).extracting(CandidateSource::verifyAdmissionReason)
                .containsExactly(
                        "candidate:journal-quota",
                        "candidate:metadata-quota",
                        "candidate:metadata-quota",
                        "candidate:relation-quota",
                        "relation-expansion:source-type-quota"
                );
    }

    private ScoredCandidate scored(String id, String type, int score, int metadata, int note, int relation) {
        CandidateSource source = new CandidateSource(id, id, type, 1, 1, "", "", "", "[]", "{}", "",
                score, "", List.of(), List.of(), 0, 0, "", "");
        return new ScoredCandidate(source, metadata, note, relation, 1);
    }
}

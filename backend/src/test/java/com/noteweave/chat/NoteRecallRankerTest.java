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
        assertThat(metadataRank.score()).isPositive();
        assertThat(metadataRank.matchedFields())
                .containsExactly("title", "summary", "tags", "metadata", "sample_text");
        assertThat(metadataRank.coverageTerms()).containsExactly("spring", "boot", "3");
        assertThat(metadataRank.coveredQueryTerms()).isEqualTo(3);
        assertThat(metadataRank.totalQueryTerms()).isEqualTo(3);
    }

    @Test
    void titleMatchShouldOutrankSampleOnlyMatch() {
        NoteRecallRanker ranker = new NoteRecallRanker();
        CandidateSource title = new CandidateSource(
                "title", "AnchorCoverage.md", "MARKDOWN", 1, 1, "", "",
                "Spec2026 architecture review", "[]", "{}", "other",
                0, "", List.of(), List.of(), 0, 0, "", "");
        CandidateSource sample = new CandidateSource(
                "sample", "sample-window.md", "MARKDOWN", 1, 1, "", "",
                "正文里没有这两个词", "[]", "{}", "AnchorCoverage Spec2026",
                0, "", List.of(), List.of(), 0, 0, "", "");

        Map<String, NoteRecallRanker.MetadataRank> ranks = ranker.rankMetadata(
                List.of(sample, title), new LinkedHashSet<>(List.of("AnchorCoverage", "Spec2026")));

        assertThat(ranks.get("title").score()).isGreaterThan(ranks.get("sample").score());
        assertThat(ranks.get("title").matchedFields()).contains("title");
        assertThat(ranks.get("title").coveredQueryTerms()).isEqualTo(2);
    }

    @Test
    void windowlessCandidateDoesNotEnterVerifyBatch() {
        NoteRecallRanker ranker = new NoteRecallRanker();
        CandidateSource windowless = new CandidateSource(
                "windowless", "windowless", "PDF", 1, 0, "", "", "", "[]", "{}", "",
                30, "", List.of(), List.of(), 0, 0, "", "");
        CandidateSource ready = new CandidateSource(
                "ready", "ready", "MARKDOWN", 1, 1, "", "", "", "[]", "{}", "",
                20, "", List.of(), List.of(), 0, 0, "", "");
        var selection = ranker.select(List.of(
                new ScoredCandidate(windowless, 8, 0, 0, 0),
                new ScoredCandidate(ready, 6, 0, 0, 0)));

        assertThat(selection.verifySources()).extracting(CandidateSource::sourceId)
                .containsExactly("ready");
        assertThat(selection.verifySources()).extracting(CandidateSource::verifyAdmissionReason)
                .noneMatch(reason -> reason.contains("window-fallback"));
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

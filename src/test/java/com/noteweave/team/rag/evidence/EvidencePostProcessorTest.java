package com.noteweave.team.rag.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.team.rag.retriever.RetrievedChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidencePostProcessorTest {

    private final EvidencePostProcessor processor = new EvidencePostProcessor();

    @Test
    void shouldMergeAdjacentChunksAndKeepPerDocumentLimit() {
        List<RetrievedChunk> chunks = List.of(
                retrievedChunk(101L, 11L, 5L, 1L, 1, 0, "Prepare the blue green environment first.", 0.98, 1, 0, 12),
                retrievedChunk(102L, 11L, 5L, 1L, 1, 1, "Rollback rehearsal must finish before release.", 0.95, 1, 13, 24),
                retrievedChunk(103L, 11L, 5L, 1L, 1, 4, "Observe alerts after deployment.", 0.81, 1, 25, 36),
                retrievedChunk(201L, 22L, 5L, 1L, 1, 0, "Another document also mentions deployment checks.", 0.88, 2, 0, 14)
        );

        EvidenceOptions options = EvidenceOptions.builder()
                .maxEvidencePerDocument(1)
                .mergeAdjacentChunks(true)
                .maxMergedChars(200)
                .finalTopK(5)
                .maxContextChars(1000)
                .minScore(0.0d)
                .build();

        List<EvidenceItem> items = processor.process(chunks, options);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).citationIndex()).isEqualTo(1);
        assertThat(items.get(0).documentId()).isEqualTo(11L);
        assertThat(items.get(0).content())
                .contains("blue green environment")
                .contains("Rollback rehearsal");
        assertThat(items.get(0).sources())
                .extracting(EvidenceSource::chunkId)
                .containsExactly(101L, 102L);
        assertThat(items.get(1).citationIndex()).isEqualTo(2);
        assertThat(items.get(1).documentId()).isEqualTo(22L);
    }

    @Test
    void shouldTrimByMaxContextCharsAndDropDuplicateChunks() {
        RetrievedChunk duplicate = retrievedChunk(301L, 33L, 6L, 1L, 2, 0, "Duplicate evidence", 0.91, 3, 0, 4);
        List<RetrievedChunk> chunks = List.of(
                duplicate,
                duplicate,
                retrievedChunk(302L, 33L, 6L, 1L, 2, 2, "This is a long supplemental paragraph used to verify context truncation behavior.", 0.90, 3, 5, 30)
        );

        EvidenceOptions options = EvidenceOptions.builder()
                .maxEvidencePerDocument(3)
                .mergeAdjacentChunks(false)
                .maxMergedChars(200)
                .finalTopK(5)
                .maxContextChars(20)
                .minScore(0.0d)
                .build();

        List<EvidenceItem> items = processor.process(chunks, options);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).content()).isEqualTo("Duplicate evidence");
        assertThat(items.get(1).content().length()).isLessThanOrEqualTo(20 - "Duplicate evidence".length());
        assertThat(items.get(0).sources()).hasSize(1);
    }

    @Test
    void shouldFilterLowScoreEvidenceBeforeApplyingTopK() {
        List<RetrievedChunk> chunks = List.of(
                retrievedChunk(401L, 44L, 6L, 1L, 2, 0, "High confidence evidence", 0.93, 1, 0, 4),
                retrievedChunk(402L, 45L, 6L, 1L, 2, 0, "Low confidence evidence", 0.09, 1, 0, 4),
                retrievedChunk(403L, 46L, 6L, 1L, 2, 0, "Medium confidence evidence", 0.62, 1, 0, 4)
        );

        EvidenceOptions options = EvidenceOptions.builder()
                .maxEvidencePerDocument(3)
                .mergeAdjacentChunks(false)
                .maxMergedChars(200)
                .finalTopK(5)
                .maxContextChars(1000)
                .minScore(0.2d)
                .build();

        List<EvidenceItem> items = processor.process(chunks, options);

        assertThat(items).hasSize(2);
        assertThat(items)
                .extracting(EvidenceItem::documentId)
                .containsExactly(44L, 46L);
    }

    @Test
    void shouldNotMergeAdjacentChunksWhenIndexVersionIsMissing() {
        List<RetrievedChunk> chunks = List.of(
                retrievedChunk(501L, 55L, 6L, 1L, null, 0, "First chunk", 0.93, 1, 0, 4),
                retrievedChunk(502L, 55L, 6L, 1L, null, 1, "Second chunk", 0.91, 1, 5, 9)
        );

        EvidenceOptions options = EvidenceOptions.builder()
                .maxEvidencePerDocument(3)
                .mergeAdjacentChunks(true)
                .maxMergedChars(200)
                .finalTopK(5)
                .maxContextChars(1000)
                .minScore(0.0d)
                .build();

        List<EvidenceItem> items = processor.process(chunks, options);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).content()).isEqualTo("First chunk");
        assertThat(items.get(1).content()).isEqualTo("Second chunk");
    }

    private RetrievedChunk retrievedChunk(
            Long chunkId,
            Long documentId,
            Long knowledgeBaseId,
            Long spaceId,
            Integer indexVersion,
            Integer chunkIndex,
            String content,
            Double score,
            Integer pageNo,
            Integer startOffset,
            Integer endOffset
    ) {
        return new RetrievedChunk(
                chunkId,
                documentId,
                knowledgeBaseId,
                spaceId,
                "DOCUMENT",
                documentId,
                indexVersion,
                chunkIndex,
                "Deployment Handbook-" + documentId,
                content,
                score,
                pageNo,
                startOffset,
                endOffset,
                "v" + indexVersion
        );
    }
}

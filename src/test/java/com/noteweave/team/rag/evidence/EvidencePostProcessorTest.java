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
                retrievedChunk(101L, 11L, 5L, 1L, 1, 0, "部署前先准备蓝绿环境。", 0.98, 1, 0, 12),
                retrievedChunk(102L, 11L, 5L, 1L, 1, 1, "回滚前需要完成演练。", 0.95, 1, 13, 24),
                retrievedChunk(103L, 11L, 5L, 1L, 1, 4, "发布后要观察告警。", 0.81, 1, 25, 36),
                retrievedChunk(201L, 22L, 5L, 1L, 1, 0, "另一个文档也提到部署检查。", 0.88, 2, 0, 14)
        );

        EvidenceOptions options = EvidenceOptions.builder()
                .maxEvidencePerDocument(1)
                .mergeAdjacentChunks(true)
                .maxMergedChars(200)
                .finalTopK(5)
                .maxContextChars(1000)
                .build();

        List<EvidenceItem> items = processor.process(chunks, options);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).citationIndex()).isEqualTo(1);
        assertThat(items.get(0).documentId()).isEqualTo(11L);
        assertThat(items.get(0).content()).contains("蓝绿环境").contains("回滚前");
        assertThat(items.get(0).sources())
                .extracting(EvidenceSource::chunkId)
                .containsExactly(101L, 102L);
        assertThat(items.get(1).citationIndex()).isEqualTo(2);
        assertThat(items.get(1).documentId()).isEqualTo(22L);
    }

    @Test
    void shouldTrimByMaxContextCharsAndDropDuplicateChunks() {
        RetrievedChunk duplicate = retrievedChunk(301L, 33L, 6L, 1L, 2, 0, "重复证据", 0.91, 3, 0, 4);
        List<RetrievedChunk> chunks = List.of(
                duplicate,
                duplicate,
                retrievedChunk(302L, 33L, 6L, 1L, 2, 2, "这是一段很长的补充说明，用于验证上下文截断逻辑。", 0.90, 3, 5, 30)
        );

        EvidenceOptions options = EvidenceOptions.builder()
                .maxEvidencePerDocument(3)
                .mergeAdjacentChunks(false)
                .maxMergedChars(200)
                .finalTopK(5)
                .maxContextChars(12)
                .build();

        List<EvidenceItem> items = processor.process(chunks, options);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).content()).isEqualTo("重复证据");
        assertThat(items.get(1).content().length()).isLessThanOrEqualTo(12);
        assertThat(items.get(0).sources()).hasSize(1);
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
                indexVersion,
                chunkIndex,
                "部署手册-" + documentId,
                content,
                score,
                pageNo,
                startOffset,
                endOffset,
                "v" + indexVersion
        );
    }
}

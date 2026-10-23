package com.noteweave.support;

import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.retrieval.provider.EmbeddingClient;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Phase3 契约测试专用向量客户端：字符二元组哈希落桶，相同文本得到相同向量。
 * 共享的 DeterministicEmbeddingTestConfiguration 使用常量向量以保持语义边界中性，
 * 但 Phase3 的 metadata coverage 排序需要可区分的向量，避免正文关键词重复压过标题与元数据。
 */
@TestConfiguration(proxyBeanMethods = false)
public class Phase3HashedEmbeddingTestConfiguration {

    @Bean
    @Primary
    EmbeddingClient phase3HashedEmbeddingClient(NoteWeaveProperties properties) {
        return new HashedEmbeddingClient(properties.embedding().dimensions());
    }

    static final class HashedEmbeddingClient implements EmbeddingClient {

        private final int dimensions;

        HashedEmbeddingClient(int dimensions) {
            this.dimensions = dimensions;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public EmbeddingResult embedQuery(String text) {
            return embedDocuments(List.of(text));
        }

        @Override
        public EmbeddingResult embedDocuments(List<String> texts) {
            List<List<Float>> vectors = new ArrayList<>(texts.size());
            long totalTokens = 0;
            for (String text : texts) {
                vectors.add(vector(text));
                totalTokens += Math.max(1, text == null ? 0 : text.length() / 4);
            }
            return new EmbeddingResult(vectors, "stub-embedding", dimensions, totalTokens);
        }

        @Override
        public EmbeddingResult embedDocumentsWithBudget(List<String> texts, Runnable beforeRequest) {
            if (beforeRequest != null) {
                beforeRequest.run();
            }
            return embedDocuments(texts);
        }

        private List<Float> vector(String text) {
            float[] values = new float[dimensions];
            String normalized = text == null ? "" : text;
            for (int index = 0; index < normalized.length(); index++) {
                int hash = 31 * normalized.charAt(index) + index + 1;
                values[Math.floorMod(hash, dimensions)] += 1.0F;
            }
            double norm = 0;
            for (float value : values) {
                norm += value * value;
            }
            norm = Math.sqrt(norm);
            if (norm == 0) {
                values[0] = 1.0F;
                norm = 1.0;
            }
            List<Float> vector = new ArrayList<>(dimensions);
            for (float value : values) {
                vector.add((float) (value / norm));
            }
            return vector;
        }
    }
}

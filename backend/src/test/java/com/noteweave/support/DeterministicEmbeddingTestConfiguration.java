package com.noteweave.support;

import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.retrieval.provider.EmbeddingClient;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试用的确定性向量客户端：不访问外部模型服务，所有文本返回同一个单位向量。
 * 向量维度取配置中的 dimensions，与检索索引 mapping 保持一致。
 * 使用常量向量是为了让语义边界和向量排序保持中性，契约测试仍以关键词检索和业务状态为主。
 */
@TestConfiguration(proxyBeanMethods = false)
public class DeterministicEmbeddingTestConfiguration {

    @Bean
    @Primary
    EmbeddingClient deterministicEmbeddingClient(NoteWeaveProperties properties) {
        return new DeterministicEmbeddingClient(properties.embedding().dimensions());
    }

    static final class DeterministicEmbeddingClient implements EmbeddingClient {

        private final int dimensions;

        DeterministicEmbeddingClient(int dimensions) {
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
            float value = (float) (1.0d / Math.sqrt(dimensions));
            List<Float> vector = new ArrayList<>(dimensions);
            for (int index = 0; index < dimensions; index++) {
                vector.add(value);
            }
            return vector;
        }
    }
}

package com.noteweave.conversation;

import com.noteweave.retrieval.provider.EmbeddingClient;
import java.util.ArrayList;
import java.util.List;

/**
 * 把用户消息换成向量。切分口径固定为用户原话 message.text()：检索改写出的 standalone query 只服务检索，
 * 不进入话题切分，避免同一句话在检索与切分里被算成两种语义。嵌入不可用或条数对不上时保持原消息，
 * 交给切分器走词项降级。
 */
final class TopicSegmentationVectors {
    private TopicSegmentationVectors() {
    }

    static List<TopicSegmenterV2.Message> attach(List<TopicSegmenterV2.Message> messages,
                                                 EmbeddingClient client) {
        return attach(messages, client, null);
    }

    /**
     * @param cache 可为空。命中缓存的消息不再请求嵌入，只把没见过的文本发给嵌入服务；
     *              话题切分每轮都会看全部用户消息，不缓存时对话越长每轮嵌入越多。
     */
    static List<TopicSegmenterV2.Message> attach(List<TopicSegmenterV2.Message> messages,
                                                 EmbeddingClient client, Cache cache) {
        if (client == null || !client.isEnabled() || messages.isEmpty()) {
            return messages;
        }
        List<Integer> indexes = new ArrayList<>();
        List<List<Float>> vectors = new ArrayList<>();
        List<String> missingTexts = new ArrayList<>();
        List<Integer> missingSlots = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            TopicSegmenterV2.Message message = messages.get(index);
            if (!"USER".equals(message.role()) || message.text() == null || message.text().isBlank()) {
                continue;
            }
            indexes.add(index);
            List<Float> cached = cache == null ? null : cache.get(message.text());
            vectors.add(cached);
            if (cached == null) {
                missingTexts.add(message.text());
                missingSlots.add(vectors.size() - 1);
            }
        }
        if (indexes.isEmpty()) {
            return messages;
        }
        if (!missingTexts.isEmpty()) {
            EmbeddingClient.EmbeddingResult result;
            try {
                result = client.embedDocuments(missingTexts);
            } catch (RuntimeException ex) {
                return messages;
            }
            if (result == null || result.vectors().size() != missingTexts.size()) {
                return messages;
            }
            for (int index = 0; index < missingSlots.size(); index++) {
                List<Float> vector = result.vectors().get(index);
                if (vector == null || vector.isEmpty()) {
                    return messages;
                }
                vectors.set(missingSlots.get(index), vector);
                if (cache != null) {
                    cache.put(missingTexts.get(index), vector);
                }
            }
        }
        List<TopicSegmenterV2.Message> copy = new ArrayList<>(messages);
        for (int index = 0; index < indexes.size(); index++) {
            TopicSegmenterV2.Message message = copy.get(indexes.get(index));
            copy.set(indexes.get(index), new TopicSegmenterV2.Message(
                    message.messageId(), message.seq(), message.role(), message.text(), vectors.get(index)));
        }
        return List.copyOf(copy);
    }

    /** 按原文摘要缓存切分向量的进程内 LRU；嵌入模型更换需要重启服务，缓存随之清空。 */
    static final class Cache {
        private final int capacity;
        private final java.util.LinkedHashMap<String, float[]> entries;

        Cache(int capacity) {
            this.capacity = Math.max(1, capacity);
            this.entries = new java.util.LinkedHashMap<>(16, 0.75f, true);
        }

        synchronized List<Float> get(String text) {
            float[] vector = entries.get(key(text));
            if (vector == null) {
                return null;
            }
            List<Float> boxed = new ArrayList<>(vector.length);
            for (float value : vector) {
                boxed.add(value);
            }
            return boxed;
        }

        synchronized void put(String text, List<Float> vector) {
            float[] packed = new float[vector.size()];
            for (int index = 0; index < packed.length; index++) {
                packed[index] = vector.get(index);
            }
            entries.put(key(text), packed);
            while (entries.size() > capacity) {
                entries.remove(entries.keySet().iterator().next());
            }
        }

        private static String key(String text) {
            try {
                return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 is unavailable", ex);
            }
        }
    }
}

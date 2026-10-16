package com.noteweave.conversation;

import com.noteweave.retrieval.provider.EmbeddingClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 把用户消息换成向量。嵌入不可用或条数对不上时保持原消息，交给切分器走词项降级。 */
final class TopicSegmentationVectors {
    private TopicSegmentationVectors() {
    }

    static List<TopicSegmenterV2.Message> attach(List<TopicSegmenterV2.Message> messages,
                                                  Map<String, String> standaloneByMessageId,
                                                  EmbeddingClient client) {
        if (client == null || !client.isEnabled() || messages.isEmpty()) {
            return messages;
        }
        List<String> texts = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            TopicSegmenterV2.Message message = messages.get(index);
            if (!"USER".equals(message.role()) || message.text() == null || message.text().isBlank()) {
                continue;
            }
            String standalone = standaloneByMessageId == null ? null : standaloneByMessageId.get(message.messageId());
            texts.add(standalone == null || standalone.isBlank() ? message.text() : standalone);
            indexes.add(index);
        }
        if (texts.isEmpty()) {
            return messages;
        }
        EmbeddingClient.EmbeddingResult result;
        try {
            result = client.embedDocuments(texts);
        } catch (RuntimeException ex) {
            return messages;
        }
        if (result == null || result.vectors().size() != texts.size()) {
            return messages;
        }
        List<TopicSegmenterV2.Message> copy = new ArrayList<>(messages);
        for (int index = 0; index < indexes.size(); index++) {
            List<Float> vector = result.vectors().get(index);
            if (vector == null || vector.isEmpty()) {
                return messages;
            }
            TopicSegmenterV2.Message message = copy.get(indexes.get(index));
            copy.set(indexes.get(index), new TopicSegmenterV2.Message(
                    message.messageId(), message.seq(), message.role(), message.text(), vector));
        }
        return List.copyOf(copy);
    }
}

package com.noteweave.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class WikiRetrievalSnapshotCodec {

    public static final String METADATA_KEY = "wiki_retrieval_snapshot";

    private final ObjectMapper objectMapper;

    public WikiRetrievalSnapshotCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(WikiRetrievalSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(
                    "WIKI_RETRIEVAL_SNAPSHOT_INVALID",
                    "Failed to encode Wiki retrieval snapshot"
            );
        }
    }

    public WikiRetrievalSnapshot decode(String value) {
        try {
            return objectMapper.readValue(value, WikiRetrievalSnapshot.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new BusinessException(
                    "WIKI_RETRIEVAL_SNAPSHOT_INVALID",
                    "Failed to decode Wiki retrieval snapshot"
            );
        }
    }
}

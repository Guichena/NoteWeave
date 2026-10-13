package com.noteweave.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.List;
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

    public String encodeGraphPath(List<String> titles) {
        if (titles == null || titles.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(titles);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(
                    "WIKI_RETRIEVAL_SNAPSHOT_INVALID",
                    "Failed to encode Wiki graph path"
            );
        }
    }

    public List<String> decodeGraphPath(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            List<String> path = objectMapper.readValue(value, new TypeReference<List<String>>() {
            });
            return path == null ? List.of() : List.copyOf(path);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            return List.of();
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

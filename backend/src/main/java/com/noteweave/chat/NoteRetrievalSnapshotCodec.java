package com.noteweave.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class NoteRetrievalSnapshotCodec {

    public static final String METADATA_KEY = "note_retrieval_snapshot";

    private final ObjectMapper objectMapper;

    public NoteRetrievalSnapshotCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(NoteRetrievalSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(
                    "NOTE_RETRIEVAL_SNAPSHOT_INVALID",
                    "Failed to encode Note retrieval snapshot"
            );
        }
    }

    public NoteRetrievalSnapshot decode(String value) {
        try {
            return objectMapper.readValue(value, NoteRetrievalSnapshot.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new BusinessException(
                    "NOTE_RETRIEVAL_SNAPSHOT_INVALID",
                    "Failed to decode Note retrieval snapshot"
            );
        }
    }
}

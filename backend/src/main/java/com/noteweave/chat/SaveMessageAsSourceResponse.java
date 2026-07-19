package com.noteweave.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SaveMessageAsSourceResponse(
        @JsonProperty("source_id") String sourceId,
        @JsonProperty("message_id") String messageId,
        String title,
        String status,
        @JsonProperty("parse_status") String parseStatus,
        @JsonProperty("index_status") String indexStatus,
        @JsonProperty("generated_by") String generatedBy,
        @JsonProperty("generated_ref_id") String generatedRefId
) {
}

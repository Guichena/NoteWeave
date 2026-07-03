package com.noteweave.source;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DeleteSourceResponse(
        @JsonProperty("source_id") String sourceId,
        String status,
        @JsonProperty("wiki_retract_task_id") String wikiRetractTaskId
) {
}

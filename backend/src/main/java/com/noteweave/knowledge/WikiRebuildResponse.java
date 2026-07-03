package com.noteweave.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record WikiRebuildResponse(
        @JsonProperty("workspace_id") String workspaceId,
        @JsonProperty("source_count") int sourceCount,
        @JsonProperty("task_count") int taskCount,
        @JsonProperty("task_ids") List<String> taskIds
) {
}

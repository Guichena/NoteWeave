package com.noteweave.artifact;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 产物版本编辑结果；created=false 表示无变化或幂等重放，返回既有版本。 */
public record ArtifactVersionEditResponse(
        @JsonProperty("version_id") String versionId,
        @JsonProperty("version_no") int versionNo,
        @JsonProperty("base_version_no") int baseVersionNo,
        boolean created,
        @JsonProperty("file_preparation_status") String filePreparationStatus,
        @JsonProperty("memory_feedback_status") String memoryFeedbackStatus,
        @JsonProperty("edit_event_id") String editEventId
) {
}

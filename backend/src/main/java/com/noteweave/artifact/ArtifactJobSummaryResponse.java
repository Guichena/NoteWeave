package com.noteweave.artifact;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.noteweave.task.WaitContextResponse;
import java.time.Instant;

public record ArtifactJobSummaryResponse(
        String artifactJobId,
        String workspaceId,
        String taskId,
        String skillKey,
        String status,
        String taskStatus,
        String progressPhase,
        String progressMessage,
        String resultTitle,
        WaitContextResponse waitContext,
        int latestVersionNo,
        Instant createdAt,
        Instant updatedAt,
        /** 失败时的原始报错，progressMessage 已改写成面向用户的说法；没有改写时为空。 */
        @JsonInclude(JsonInclude.Include.NON_NULL) String failureDetail,
        /** 排队中时前面还有几个产物任务（含自己，从 1 开始）；不在排队时为空。 */
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer queuePosition
) {
}

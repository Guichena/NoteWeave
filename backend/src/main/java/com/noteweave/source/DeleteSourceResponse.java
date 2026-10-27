package com.noteweave.source;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 删除回执。
 *
 * <p>transition 与 reason 是新增的附加字段：重复删除返回 ALREADY_DELETED 与既有原因，
 * 升级返回 UPGRADE_TO_COMPLIANCE；旧调用方只读 source_id/status/wiki_retract_task_id 不受影响。</p>
 */
public record DeleteSourceResponse(
        @JsonProperty("source_id") String sourceId,
        String status,
        @JsonProperty("wiki_retract_task_id") String wikiRetractTaskId,
        String transition,
        String reason
) {
    public DeleteSourceResponse(
            @JsonProperty("source_id") String sourceId,
            String status,
            @JsonProperty("wiki_retract_task_id") String wikiRetractTaskId
    ) {
        this(sourceId, status, wikiRetractTaskId,
                DeleteTransitionDecision.Kind.FIRST_DELETE.name(), SourceService.DELETE_REASON_USER);
    }
}

package com.noteweave.source;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record SourceResponse(
        @JsonProperty("source_id") String sourceId,
        String title,
        @JsonProperty("source_type") String sourceType,
        String status,
        @JsonProperty("parse_status") String parseStatus,
        @JsonProperty("index_status") String indexStatus,
        @JsonProperty("generated_by") String generatedBy,
        @JsonProperty("generated_ref_id") String generatedRefId,
        @JsonProperty("updated_at") Instant updatedAt,
        // 解析完成后写入 metadata_json 的切片数与页数；尚未解析时为空
        @JsonProperty("chunk_count") Integer chunkCount,
        @JsonProperty("page_count") Integer pageCount,
        // 最近一次资料解析任务，用于查看处理进度与失败原因
        @JsonProperty("task_id") String taskId,
        // 最新快照所处（或失败时停在）的处理阶段：EXTRACTING / CHUNKING / EMBEDDING / INDEXING / READY
        @JsonProperty("processing_stage") String processingStage,
        // 检索索引已自动重试的次数，以及下次自动重试的时间；不会再自动重试时为空
        @JsonProperty("index_attempt_count") Integer indexAttemptCount,
        @JsonProperty("next_index_retry_at") Instant nextIndexRetryAt
) {
    public SourceResponse(String sourceId, String title, String sourceType, String status, String parseStatus,
                          String indexStatus, String generatedBy, String generatedRefId, Instant updatedAt,
                          Integer chunkCount, Integer pageCount, String taskId) {
        this(sourceId, title, sourceType, status, parseStatus, indexStatus, generatedBy, generatedRefId,
                updatedAt, chunkCount, pageCount, taskId, null, null, null);
    }
}

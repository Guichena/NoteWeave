package com.noteweave.artifact;

import com.noteweave.common.BusinessException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.retrieval.qa.ScopedPassageSearch;
import com.noteweave.worker.WorkerSourceScopeItemResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 索引只提供候选排序；资料权限、快照与正文以宿主数据库为准。 */
@Service
public class ArtifactTaskRetrievalService {
    private final ArtifactJobService jobs;
    private final ScopedPassageSearch search;
    private final JdbcTemplate jdbc;

    public ArtifactTaskRetrievalService(ArtifactJobService jobs, ScopedPassageSearch search, JdbcTemplate jdbc) {
        this.jobs = jobs;
        this.search = search;
        this.jdbc = jdbc;
    }

    public Response retrieve(String taskId, String query, int limit) {
        if (query == null || query.isBlank() || query.length() > 2000 || limit < 1 || limit > 20) {
            throw new BusinessException("ARTIFACT_RETRIEVAL_REQUEST_INVALID", "检索查询或条数超出允许范围", HttpStatus.BAD_REQUEST);
        }
        ArtifactWorkerInputResponse input = jobs.getWorkerInput(taskId);
        if (!"FULL".equals(input.replayAvailability())) {
            throw new BusinessException("ARTIFACT_RETRIEVAL_REPLAY_DENIED", "任务冻结输入不可完整重放", HttpStatus.CONFLICT);
        }
        Map<String, WorkerSourceScopeItemResponse> scope = new LinkedHashMap<>();
        input.sourceScope().forEach(source -> scope.put(source.sourceId(), source));
        if (scope.isEmpty()) return response(input, List.of(), List.of());
        ScopedPassageSearch.Result found;
        try {
            found = search.search(input.workspaceId(), scope.keySet(), query.trim(), Math.min(80, limit * 4));
        } catch (RuntimeException ex) {
            // Provider 异常不返回连接信息，冻结输入资格错误在上面的宿主检查中直接拒绝。
            jobs.getWorkerInput(taskId);
            return response(input, List.of(), List.of("ARTIFACT_RETRIEVAL_PROVIDER_UNAVAILABLE"));
        }
        LinkedHashSet<String> reasons = new LinkedHashSet<>(found.degradationReasons());
        if (found.degraded() && reasons.isEmpty()) reasons.add("ARTIFACT_RETRIEVAL_PROVIDER_DEGRADED");
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Passage> passages = new ArrayList<>();
        int remaining = 65_536;
        for (ScopedPassageSearch.Passage candidate : found.passages()) {
            WorkerSourceScopeItemResponse source = scope.get(candidate.sourceId());
            if (source == null || !source.sourceSnapshotId().equals(candidate.sourceSnapshotId())) {
                reasons.add("ARTIFACT_RETRIEVAL_FROZEN_SCOPE_MISMATCH");
                continue;
            }
            if (!seen.add(candidate.chunkId())) continue;
            List<Passage> rows = jdbc.query("""
                    select sc.id, sc.chunk_no, coalesce(sc.location_info, '') as location_info, sc.content, sc.part, sc.start_ms, sc.end_ms, sc.frame_ids_json, sc.source_url
                    from source_chunk sc
                    join source s on s.id = sc.source_id
                    join source_snapshot ss on ss.id = sc.source_snapshot_id and ss.source_id = s.id
                    where sc.id = ? and sc.workspace_id = ? and s.workspace_id = ?
                      and sc.source_id = ? and sc.source_snapshot_id = ? and s.status = 'READY'
                      and ss.parse_status = 'PARSED' and ss.index_status in ('INDEXED', 'DISABLED')
                    """, (rs, index) -> new Passage(rs.getString("id"), source.sourceId(),
                    source.sourceSnapshotId(), rs.getInt("chunk_no"), source.title(),
                    rs.getString("location_info"), rs.getString("content"),
                    Double.isFinite(candidate.score()) ? candidate.score() : 0,
                    (Integer) rs.getObject("part"),
                    rs.getObject("start_ms", Long.class),
                    rs.getObject("end_ms", Long.class),
                    frameIds(rs.getString("frame_ids_json")),
                    rs.getString("source_url")),
                    candidate.chunkId(), input.workspaceId(), input.workspaceId(), source.sourceId(), source.sourceSnapshotId());
            if (rows.isEmpty()) {
                reasons.add("ARTIFACT_RETRIEVAL_CHUNK_UNAVAILABLE");
                continue;
            }
            Passage passage = rows.get(0);
            int size = passage.content().getBytes(StandardCharsets.UTF_8).length;
            if (passage.content().isBlank()) continue;
            if (size > remaining) {
                reasons.add("ARTIFACT_RETRIEVAL_BYTE_BUDGET_REACHED");
                continue;
            }
            remaining -= size;
            passages.add(passage);
            if (passages.size() == limit) break;
        }
        // 检索期间资料可能被撤权，返回前再次检查完整冻结输入。
        jobs.getWorkerInput(taskId);
        return response(input, passages, List.copyOf(reasons));
    }

    private Response response(ArtifactWorkerInputResponse input, List<Passage> passages,
                              List<String> reasons) {
        return new Response(input.taskId(), input.inputSnapshotId(), List.copyOf(passages), !reasons.isEmpty(), reasons);
    }

    private static List<String> frameIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            var node = new ObjectMapper().readTree(json);
            if (!node.isArray()) throw new IllegalArgumentException("关键帧索引必须是数组");
            List<String> ids = new ArrayList<>();
            for (var item : node) {
                if (!item.isTextual() || item.asText().isBlank()) throw new IllegalArgumentException("关键帧编号无效");
                ids.add(item.asText());
            }
            return List.copyOf(ids);
        } catch (java.io.IOException ex) { throw new IllegalStateException("关键帧索引不可读取", ex); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Passage(String chunkId, String sourceId, String sourceSnapshotId, int chunkNo,
                          String title, String location, String content, double score,
                          Integer part, Long startMs, Long endMs, List<String> frameIds, String sourceUrl) { }

    public record Response(String taskId, String inputSnapshotId, List<Passage> passages,
                           boolean degraded, List<String> degradationReasons) { }
}

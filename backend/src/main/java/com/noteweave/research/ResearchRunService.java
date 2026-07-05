package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.infra.LocalObjectStorage;
import com.noteweave.knowledge.WikiIngestService;
import com.noteweave.memory.MemoryCompilerService;
import com.noteweave.memory.MemoryControlPackResponse;
import com.noteweave.source.SourceParseService;
import com.noteweave.task.TaskService;
import com.noteweave.worker.WorkerContextSnapshotResponse;
import com.noteweave.worker.WorkerFailRequest;
import com.noteweave.worker.WorkerSourceScopeItemResponse;
import com.noteweave.worker.WorkerTaskCallbackService.CompletionOutcome;
import com.noteweave.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResearchRunService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkspaceService workspaceService;
    private final TaskService taskService;
    private final MemoryCompilerService memoryCompilerService;
    private final LocalObjectStorage storage;
    private final SourceParseService sourceParseService;
    private final WikiIngestService wikiIngestService;

    public ResearchRunService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkspaceService workspaceService,
            TaskService taskService,
            MemoryCompilerService memoryCompilerService,
            LocalObjectStorage storage,
            SourceParseService sourceParseService,
            WikiIngestService wikiIngestService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.workspaceService = workspaceService;
        this.taskService = taskService;
        this.memoryCompilerService = memoryCompilerService;
        this.storage = storage;
        this.sourceParseService = sourceParseService;
        this.wikiIngestService = wikiIngestService;
    }

    @Transactional
    public ResearchRunResponse createRun(String workspaceId, CreateResearchRunRequest request) {
        requireWorkspace(workspaceId);
        String researchRunId = Ids.newId();
        String profileKey = normalizeToken(request.profile());
        String taskId = taskService.createTask(
                workspaceId,
                "RESEARCH_RUN",
                "RESEARCH_RUN",
                researchRunId,
                "QUEUED",
                "Deep Research 任务已创建"
        );
        MemoryControlPackResponse controlPack = memoryCompilerService.compileResearchControlPack(workspaceId, profileKey);
        List<String> sourceScopeIds = loadReadySourceScope(workspaceId).stream()
                .map(WorkerSourceScopeItemResponse::sourceId)
                .toList();
        jdbcTemplate.update("""
                insert into research_run(
                    id, workspace_id, task_id, question, profile_key, context_snapshot_id,
                    source_scope_json, control_pack_json, status
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED')
                """,
                researchRunId,
                workspaceId,
                taskId,
                request.question().trim(),
                profileKey,
                blankToNull(request.contextSnapshotId()),
                Json.write(objectMapper, sourceScopeIds),
                Json.write(objectMapper, controlPack)
        );
        jdbcTemplate.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                values (?, ?, 'noteweave.research.run', ?, ?, 'READY')
                """,
                Ids.newId(),
                taskId,
                researchRunId,
                Json.write(objectMapper, Map.of(
                        "task_id", taskId,
                        "task_type", "RESEARCH_RUN",
                        "workspace_id", workspaceId,
                        "target_type", "RESEARCH_RUN",
                        "target_id", researchRunId,
                        "payload_version", "v1",
                        "trace_id", researchRunId,
                        "created_at", System.currentTimeMillis()
                )));
        insertTrace(researchRunId, "RUN_CREATED", "Research run 已入队", Map.of(
                "question", request.question().trim(),
                "profile_key", profileKey
        ));
        return new ResearchRunResponse(researchRunId, taskId, "QUEUED");
    }

    public ResearchWorkerInputResponse getWorkerInput(String taskId) {
        RunRow row = findByTaskId(taskId);
        return new ResearchWorkerInputResponse(
                row.taskId(),
                row.workspaceId(),
                row.researchRunId(),
                loadSourceScopeSnapshot(row.workspaceId(), row.sourceScopeJson()),
                new WorkerContextSnapshotResponse(row.contextSnapshotId() == null ? "" : row.contextSnapshotId()),
                readControlPack(row.controlPackJson()),
                new ResearchWorkerInputPayload(row.question(), row.profileKey(), blankIfNull(row.contextSnapshotId()))
        );
    }

    public ResearchRunDetailResponse getRunDetail(String workspaceId, String researchRunId) {
        requireWorkspace(workspaceId);
        DetailRow row = jdbcTemplate.query("""
                select id, workspace_id, task_id, question, profile_key, context_snapshot_id,
                       source_scope_json, control_pack_json, status, final_report_title,
                       final_report_markdown, trace_summary, created_at, updated_at
                from research_run
                where workspace_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "研究任务不存在");
            }
            return new DetailRow(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("task_id"),
                    rs.getString("question"),
                    rs.getString("profile_key"),
                    rs.getString("context_snapshot_id"),
                    rs.getString("source_scope_json"),
                    rs.getString("control_pack_json"),
                    rs.getString("status"),
                    rs.getString("final_report_title"),
                    rs.getString("final_report_markdown"),
                    rs.getString("trace_summary"),
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("updated_at"))
            );
        }, workspaceId, researchRunId);

        return new ResearchRunDetailResponse(
                row.researchRunId(),
                row.workspaceId(),
                row.taskId(),
                row.question(),
                row.profileKey(),
                blankIfNull(row.contextSnapshotId()),
                row.status(),
                blankIfNull(row.finalReportTitle()),
                blankIfNull(row.finalReportMarkdown()),
                blankIfNull(row.traceSummary()),
                loadSourceScopeSnapshot(row.workspaceId(), row.sourceScopeJson()),
                readControlPack(row.controlPackJson()),
                loadTraces(row.researchRunId()),
                row.createdAt(),
                row.updatedAt()
        );
    }

    @Transactional
    public SaveResearchReportSourceResponse saveReportAsSource(String workspaceId, String researchRunId) {
        requireWorkspace(workspaceId);
        SaveReportRow row = loadSaveReportRow(workspaceId, researchRunId);
        if (row.reportSourceId() != null && !row.reportSourceId().isBlank()) {
            return loadSavedReportSource(workspaceId, row.reportSourceId());
        }
        if (!"COMPLETED".equals(row.status()) || row.finalReportMarkdown() == null || row.finalReportMarkdown().isBlank()) {
            throw new BusinessException("RESEARCH_REPORT_NOT_READY", "研究报告尚未完成，不能写入资料池");
        }

        byte[] reportBytes = row.finalReportMarkdown().getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256(reportBytes);
        FileObjectRef fileObject = getOrCreateGeneratedFileObject(
                workspaceId,
                researchRunId,
                row.finalReportTitle(),
                sha256,
                reportBytes
        );
        String sourceId = Ids.newId();
        String snapshotId = Ids.newId();
        String objectKey = "workspace/%s/research/%s/report/final.md".formatted(workspaceId, researchRunId);
        storage.write(objectKey, reportBytes);
        jdbcTemplate.update("""
                insert into source(
                    id, workspace_id, file_object_id, title, source_type, status,
                    parse_status, index_status, generated_by, generated_ref_id
                )
                values (?, ?, ?, ?, 'GENERATED_RESEARCH_REPORT', 'PROCESSING', 'PENDING', 'PENDING', 'research_agent', ?)
                """,
                sourceId,
                workspaceId,
                fileObject.id(),
                reportTitle(row),
                researchRunId
        );
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status)
                values (?, ?, ?, 1, ?, ?, 'PENDING', 'PENDING')
                """, snapshotId, sourceId, fileObject.id(), objectKey, sha256);

        sourceParseService.parseAndIndex(workspaceId, sourceId, snapshotId, reportBytes);
        wikiIngestService.enqueueAndRunSourceIngestIfEnabled(workspaceId, sourceId);
        jdbcTemplate.update("""
                update research_run
                set report_source_id = ?, updated_at = current_timestamp
                where id = ?
                """, sourceId, researchRunId);
        insertTrace(researchRunId, "REPORT_SAVED_AS_SOURCE", reportTitle(row), Map.of(
                "source_id", sourceId,
                "generated_by", "research_agent"
        ));
        return loadSavedReportSource(workspaceId, sourceId);
    }

    @Transactional
    public void markRunning(String taskId, String phase, String message, Map<String, Object> metrics) {
        RunRow row = findByTaskId(taskId);
        jdbcTemplate.update("""
                update research_run
                set status = 'RUNNING', updated_at = current_timestamp
                where task_id = ?
                """, taskId);
        insertTrace(row.researchRunId(), "PROGRESS", message, Map.of(
                "phase", phase == null ? "" : phase,
                "metrics", metrics == null ? Map.of() : metrics
        ));
    }

    @Transactional
    public CompletionOutcome completeFromWorker(String taskId, com.noteweave.worker.WorkerCompleteRequest request) {
        RunRow row = findByTaskId(taskId);
        String reportMarkdown = extractReport(request.resultPayload());
        jdbcTemplate.update("""
                update research_run
                set status = 'COMPLETED',
                    final_report_title = ?,
                    final_report_markdown = ?,
                    trace_summary = ?,
                    updated_at = current_timestamp
                where id = ?
                """,
                request.resultTitle(),
                reportMarkdown,
                blankToNull(request.traceSummary()),
                row.researchRunId()
        );
        insertTrace(row.researchRunId(), "FINAL_REPORT", request.resultTitle(), Map.of(
                "result_type", request.resultType(),
                "result_payload", request.resultPayload() == null ? Map.of() : request.resultPayload(),
                "trace_summary", request.traceSummary() == null ? "" : request.traceSummary(),
                "citations", request.citations() == null ? List.of() : request.citations()
        ));
        return new CompletionOutcome("RESEARCH_REPORTED", "研究报告已生成：" + request.resultTitle(), row.researchRunId());
    }

    @Transactional
    public void markFailed(String taskId, WorkerFailRequest request) {
        RunRow row = findByTaskId(taskId);
        jdbcTemplate.update("""
                update research_run
                set status = 'FAILED', updated_at = current_timestamp
                where task_id = ?
                """, taskId);
        insertTrace(row.researchRunId(), "FAILED", request.errorMessage(), Map.of(
                "phase", request.phase(),
                "error_code", request.errorCode(),
                "retryable", request.retryable()
        ));
    }

    private SaveReportRow loadSaveReportRow(String workspaceId, String researchRunId) {
        return jdbcTemplate.query("""
                select id, workspace_id, status, final_report_title, final_report_markdown, report_source_id
                from research_run
                where workspace_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "研究任务不存在");
            }
            return new SaveReportRow(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("status"),
                    rs.getString("final_report_title"),
                    rs.getString("final_report_markdown"),
                    rs.getString("report_source_id")
            );
        }, workspaceId, researchRunId);
    }

    private SaveResearchReportSourceResponse loadSavedReportSource(String workspaceId, String sourceId) {
        return jdbcTemplate.query("""
                select id, title, source_type, status, parse_status, index_status,
                       coalesce(generated_by, '') as generated_by,
                       coalesce(generated_ref_id, '') as generated_ref_id
                from source
                where workspace_id = ? and id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("SOURCE_NOT_FOUND", "资料不存在");
            }
            return new SaveResearchReportSourceResponse(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("source_type"),
                    rs.getString("status"),
                    rs.getString("parse_status"),
                    rs.getString("index_status"),
                    rs.getString("generated_by"),
                    rs.getString("generated_ref_id")
            );
        }, workspaceId, sourceId);
    }

    private RunRow findByTaskId(String taskId) {
        return jdbcTemplate.query("""
                select id, workspace_id, task_id, question, profile_key, context_snapshot_id,
                       source_scope_json, control_pack_json
                from research_run
                where task_id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "研究任务不存在");
            }
            return new RunRow(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("task_id"),
                    rs.getString("question"),
                    rs.getString("profile_key"),
                    rs.getString("context_snapshot_id"),
                    rs.getString("source_scope_json"),
                    rs.getString("control_pack_json")
            );
        }, taskId);
    }

    private void insertTrace(String researchRunId, String type, String message, Map<String, Object> payload) {
        jdbcTemplate.update("""
                insert into research_trace(id, research_run_id, trace_type, trace_message, payload_json)
                values (?, ?, ?, ?, ?)
                """, Ids.newId(), researchRunId, type, message, Json.write(objectMapper, payload));
    }

    private List<ResearchTraceResponse> loadTraces(String researchRunId) {
        return jdbcTemplate.query("""
                select id, trace_type, trace_message, payload_json, created_at
                from research_trace
                where research_run_id = ?
                order by created_at asc, id asc
                """, (rs, rowNum) -> new ResearchTraceResponse(
                rs.getString("id"),
                rs.getString("trace_type"),
                rs.getString("trace_message"),
                readPayloadMap(rs.getString("payload_json")),
                toInstant(rs.getTimestamp("created_at"))
        ), researchRunId);
    }

    private List<WorkerSourceScopeItemResponse> loadReadySourceScope(String workspaceId) {
        return jdbcTemplate.query("""
                select s.id, s.title, coalesce(s.summary, '') as summary,
                       coalesce((
                           select sw.content
                           from source_chunk sc
                           join source_window sw on sw.source_chunk_id = sc.id
                           where sc.source_id = s.id
                           order by sc.chunk_no asc, sw.window_no asc
                           limit 1
                       ), '') as sample_text
                from source s
                where s.workspace_id = ? and s.status = 'READY'
                order by s.updated_at desc, s.id desc
                limit 20
                """, (rs, rowNum) -> new WorkerSourceScopeItemResponse(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("sample_text")
        ), workspaceId);
    }

    private List<WorkerSourceScopeItemResponse> loadSourceScopeSnapshot(String workspaceId, String sourceScopeJson) {
        List<String> sourceIds = readSourceScopeIds(sourceScopeJson);
        if (sourceIds.isEmpty()) {
            return List.of();
        }
        return sourceIds.stream()
                .map(sourceId -> loadSourceScopeItem(workspaceId, sourceId))
                .flatMap(List::stream)
                .toList();
    }

    private List<WorkerSourceScopeItemResponse> loadSourceScopeItem(String workspaceId, String sourceId) {
        return jdbcTemplate.query("""
                select s.id, s.title, coalesce(s.summary, '') as summary,
                       coalesce((
                           select sw.content
                           from source_chunk sc
                           join source_window sw on sw.source_chunk_id = sc.id
                           where sc.source_id = s.id
                           order by sc.chunk_no asc, sw.window_no asc
                           limit 1
                       ), '') as sample_text
                from source s
                where s.workspace_id = ? and s.id = ? and s.status = 'READY'
                """, (rs, rowNum) -> new WorkerSourceScopeItemResponse(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("sample_text")
        ), workspaceId, sourceId);
    }

    private List<String> readSourceScopeIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("RESEARCH_SOURCE_SCOPE_PARSE_FAILED", "研究资料范围解析失败");
        }
    }

    private MemoryControlPackResponse readControlPack(String json) {
        try {
            return objectMapper.readValue(json, MemoryControlPackResponse.class);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("RESEARCH_CONTROL_PACK_PARSE_FAILED", "研究控制包解析失败");
        }
    }

    private Map<String, Object> readPayloadMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("RESEARCH_TRACE_PAYLOAD_PARSE_FAILED", "研究轨迹载荷解析失败");
        }
    }

    private void requireWorkspace(String workspaceId) {
        if (!workspaceService.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
    }

    private String normalizeToken(String value) {
        return value == null ? "" : value.trim().toUpperCase().replace('-', '_').replace(' ', '_');
    }

    private String extractReport(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        Object reportMarkdown = payload.get("report_markdown");
        if (reportMarkdown instanceof String text && !text.isBlank()) {
            return text;
        }
        Object markdown = payload.get("markdown");
        if (markdown instanceof String text && !text.isBlank()) {
            return text;
        }
        return Json.write(objectMapper, payload);
    }

    private FileObjectRef getOrCreateGeneratedFileObject(
            String workspaceId,
            String researchRunId,
            String title,
            String sha256,
            byte[] bytes
    ) {
        List<FileObjectRef> existing = jdbcTemplate.query("""
                select id, object_key from file_object where workspace_id = ? and sha256 = ?
                """, (rs, rowNum) -> new FileObjectRef(rs.getString("id"), rs.getString("object_key")), workspaceId, sha256);
        if (!existing.isEmpty()) {
            FileObjectRef ref = existing.get(0);
            if (!storage.exists(ref.objectKey())) {
                storage.write(ref.objectKey(), bytes);
            }
            jdbcTemplate.update("update file_object set ref_count = ref_count + 1 where id = ?", ref.id());
            return ref;
        }
        String fileObjectId = Ids.newId();
        String objectKey = "workspace/%s/file_object/%s-%s.md".formatted(workspaceId, sha256, sanitize(title));
        storage.write(objectKey, bytes);
        jdbcTemplate.update("""
                insert into file_object(id, workspace_id, object_key, sha256, file_size, mime_type, ref_count)
                values (?, ?, ?, ?, ?, 'text/markdown', 1)
                """, fileObjectId, workspaceId, objectKey, sha256, bytes.length);
        return new FileObjectRef(fileObjectId, objectKey);
    }

    private String reportTitle(SaveReportRow row) {
        if (row.finalReportTitle() != null && !row.finalReportTitle().isBlank()) {
            return row.finalReportTitle();
        }
        return "Research Report " + row.researchRunId();
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private String sanitize(String value) {
        String normalized = value == null ? "research-report" : value;
        return normalized.replaceAll("[^\\p{IsHan}a-zA-Z0-9._-]+", "-");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record RunRow(
            String researchRunId,
            String workspaceId,
            String taskId,
            String question,
            String profileKey,
            String contextSnapshotId,
            String sourceScopeJson,
            String controlPackJson
    ) {
    }

    private record DetailRow(
            String researchRunId,
            String workspaceId,
            String taskId,
            String question,
            String profileKey,
            String contextSnapshotId,
            String sourceScopeJson,
            String controlPackJson,
            String status,
            String finalReportTitle,
            String finalReportMarkdown,
            String traceSummary,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    private record SaveReportRow(
            String researchRunId,
            String workspaceId,
            String status,
            String finalReportTitle,
            String finalReportMarkdown,
            String reportSourceId
    ) {
    }

    private record FileObjectRef(String id, String objectKey) {
    }
}

package com.noteweave.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.task.TaskCommandPort;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SourceLinkService {
    public static final String LINK_MIME = "application/vnd.noteweave.bilibili-link+json";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ObjectStorage storage;
    private final WorkspaceAccessGuard access;
    private final AuditActorProvider actor;
    private final TaskCommandPort tasks;
    private final SourceCatalogVersionService catalog;
    private final SourceTranscriptionSubmissionService submissions;
    private final SourceLinkWorkerClient worker;
    private final TransactionTemplate transactions;

    public SourceLinkService(JdbcTemplate jdbc, ObjectMapper mapper, ObjectStorage storage, WorkspaceAccessGuard access,
            AuditActorProvider actor, TaskCommandPort tasks, SourceCatalogVersionService catalog,
            SourceTranscriptionSubmissionService submissions, SourceLinkWorkerClient worker, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.mapper = mapper; this.storage = storage; this.access = access; this.actor = actor;
        this.tasks = tasks; this.catalog = catalog; this.submissions = submissions; this.worker = worker;
        transactions = new TransactionTemplate(manager);
    }

    public SourceLinkWorkerClient.VideoInfo preview(String workspaceId, String url) {
        access.requirePermission(workspaceId, WorkspacePermission.SOURCE_WRITE);
        return worker.preview(normalize(url));
    }

    public Map<String, String> create(String workspaceId, LinkRequest request) {
        access.requirePermission(workspaceId, WorkspacePermission.SOURCE_WRITE);
        String url = normalize(request.sourceUrl());
        if (request.parts() == null || request.parts().isEmpty() || request.parts().size() > 1000
                || request.parts().stream().anyMatch(part -> part == null || part < 1 || part > 1000)
                || request.parts().stream().distinct().count() != request.parts().size()) {
            throw new BusinessException("SOURCE_LINK_PARTS_INVALID", "请选择有效且不重复的视频分 P");
        }
        // 网络预览在事务外完成；提交时重新检查分 P，避免完全信任前端预览。
        var info = worker.preview(url);
        if (request.parts().stream().anyMatch(part -> info.parts().stream().noneMatch(item -> item.part() == part))) {
            throw new BusinessException("SOURCE_LINK_PARTS_INVALID", "所选视频分 P 不存在");
        }
        return transactions.execute(ignored -> {
            String sourceId = Ids.newId(), snapshotId = Ids.newId(), fileId = Ids.newId();
            String objectKey = "workspace/%s/source/%s/video-link.json".formatted(workspaceId, sourceId);
            String who = actor.currentOrSystem("SOURCE_LINK");
            byte[] content = json(Map.of("source_id", sourceId, "source_url", url, "parts", request.parts())).getBytes(StandardCharsets.UTF_8);
            String sha;
            try { sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
            catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
            // 对象存储只保存链接清单，远程原视频始终只在 media-mcp 临时目录存在。
            storage.write("noteweave-source", objectKey, content);
            jdbc.update("""
                    insert into file_object(id, workspace_id, object_key, sha256, file_size, mime_type)
                    values (?, ?, ?, ?, ?, ?)
                    """, fileId, workspaceId, objectKey, sha, content.length, LINK_MIME);
            jdbc.update("""
                    insert into source(id, workspace_id, file_object_id, title, source_type, status, parse_status,
                        index_status, created_by, updated_by) values (?, ?, ?, ?, 'VIDEO', 'PROCESSING', 'PENDING', 'PENDING', ?, ?)
                    """, sourceId, workspaceId, fileId, info.title(), who, who);
            jdbc.update("""
                    insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256,
                        parse_status, index_status, processing_stage, source_url, selected_parts_json)
                    values (?, ?, ?, 1, ?, ?, 'PENDING', 'PENDING', 'TRANSCRIBING', ?, ?)
                    """, snapshotId, sourceId, fileId, objectKey, sha, url, json(request.parts()));
            String taskId = tasks.createTask(workspaceId, "SOURCE_PARSE", "SOURCE", sourceId,
                    SourcePipelineStages.STAGE_TRANSCRIBING, "解析视频链接并生成图文资料");
            tasks.startTask(taskId);
            submissions.enqueue(new SourceTranscriptionPort.Request(workspaceId, sourceId, snapshotId, info.title(), LINK_MIME, taskId), objectKey, sha);
            catalog.bump(workspaceId);
            return Map.of("source_id", sourceId, "snapshot_id", snapshotId, "task_id", taskId);
        });
    }

    static String normalize(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String host = uri.getHost();
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || host == null || !List.of("bilibili.com", "www.bilibili.com", "m.bilibili.com").contains(host.toLowerCase(java.util.Locale.ROOT))
                    || uri.getUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443
                    || !uri.getPath().matches("/video/(BV[0-9A-Za-z]{10}|av[0-9]+)/?")) {
                throw new IllegalArgumentException();
            }
            return "https://www.bilibili.com" + uri.getPath().replaceAll("/$", "");
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("SOURCE_LINK_INVALID", "只支持 bilibili.com 的标准视频链接");
        }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (java.io.IOException ex) { throw new IllegalStateException("视频链接清单编码失败", ex); }
    }

    public record LinkRequest(@com.fasterxml.jackson.annotation.JsonProperty("source_url") String sourceUrl, List<Integer> parts) { }
}

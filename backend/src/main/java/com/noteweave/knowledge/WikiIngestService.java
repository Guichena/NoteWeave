package com.noteweave.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.task.TaskService;
import com.noteweave.workspace.WorkspaceService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WikiIngestService {

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceService workspaceService;
    private final KnowledgeService knowledgeService;
    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    public WikiIngestService(
            JdbcTemplate jdbcTemplate,
            WorkspaceService workspaceService,
            KnowledgeService knowledgeService,
            TaskService taskService,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceService = workspaceService;
        this.knowledgeService = knowledgeService;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public String enqueueAndRunSourceIngestIfEnabled(String workspaceId, String sourceId) {
        if (!workspaceService.isWikiEnabled(workspaceId)) {
            return "";
        }
        return enqueueAndRunSourceIngest(workspaceId, sourceId, "ingest", "Wiki 构建已开启，资料变更已进入 Wiki ingest 队列");
    }

    @Transactional
    public WikiRebuildResponse enqueueAndRunWorkspaceIngestIfEnabled(String workspaceId, String trigger) {
        if (!workspaceService.isWikiEnabled(workspaceId)) {
            return new WikiRebuildResponse(workspaceId, 0, 0, List.of());
        }
        List<String> sourceIds = readySourceIds(workspaceId);
        List<String> taskIds = sourceIds.stream()
                .map(sourceId -> enqueueAndRunSourceIngest(workspaceId, sourceId, trigger, "Wiki 工作台级回补/重建已入队"))
                .toList();
        return new WikiRebuildResponse(workspaceId, sourceIds.size(), taskIds.size(), taskIds);
    }

    private String enqueueAndRunSourceIngest(String workspaceId, String sourceId, String operation, String message) {
        String taskId = taskService.createTask(workspaceId, "WIKI_INGEST", "SOURCE", sourceId, "QUEUED", message);
        jdbcTemplate.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                values (?, ?, 'noteweave.wiki.ingest', ?, ?, 'READY')
                """, Ids.newId(), taskId, sourceId, Json.write(objectMapper, Map.of(
                "taskId", taskId,
                "sourceId", sourceId,
                "workspaceId", workspaceId,
                "operation", operation
        )));
        KnowledgeItemResponse page = ingestSource(workspaceId, sourceId);
        jdbcTemplate.update("update task_outbox set status = 'SENT', sent_at = current_timestamp where task_id = ?", taskId);
        taskService.completeTask(taskId, "WIKI_INDEXED", "Wiki ingest 已生成或更新工作台级页面", page.itemId());
        return taskId;
    }

    private KnowledgeItemResponse ingestSource(String workspaceId, String sourceId) {
        SourceForWiki source = loadSource(workspaceId, sourceId);
        List<ChunkForWiki> chunks = loadChunks(workspaceId, sourceId);
        List<String> citationIds = createCitations(workspaceId, chunks);
        String content = buildWikiContent(source, chunks);
        String existingItemId = findWikiItemIdByTitle(workspaceId, source.title());
        if (existingItemId == null) {
            return knowledgeService.createItemWithVersion(workspaceId, "WIKI", source.title(), content, null, citationIds);
        }
        return knowledgeService.appendVersion(existingItemId, new AppendKnowledgeVersionRequest(content, null, citationIds));
    }

    private List<String> readySourceIds(String workspaceId) {
        return jdbcTemplate.queryForList("""
                select id from source
                where workspace_id = ? and status = 'READY'
                order by updated_at asc, id asc
                """, String.class, workspaceId);
    }

    private SourceForWiki loadSource(String workspaceId, String sourceId) {
        return jdbcTemplate.query("""
                select id, title, source_type, coalesce(summary, '') as summary, coalesce(tags_json, '[]') as tags_json
                from source
                where workspace_id = ? and id = ? and status = 'READY'
                """, rs -> {
            if (!rs.next()) {
                throw new IllegalStateException("source is not ready for wiki ingest: " + sourceId);
            }
            return new SourceForWiki(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("source_type"),
                    rs.getString("summary"),
                    rs.getString("tags_json")
            );
        }, workspaceId, sourceId);
    }

    private List<ChunkForWiki> loadChunks(String workspaceId, String sourceId) {
        return jdbcTemplate.query("""
                select c.id, c.source_id, c.source_snapshot_id, c.heading, c.content, c.location_info
                from source_chunk c
                where c.workspace_id = ? and c.source_id = ?
                order by c.chunk_no asc
                limit 5
                """, (rs, rowNum) -> new ChunkForWiki(
                rs.getString("id"),
                rs.getString("source_id"),
                rs.getString("source_snapshot_id"),
                rs.getString("heading"),
                rs.getString("content"),
                rs.getString("location_info")
        ), workspaceId, sourceId);
    }

    private List<String> createCitations(String workspaceId, List<ChunkForWiki> chunks) {
        return chunks.stream().limit(3).map(chunk -> {
            String citationId = Ids.newId();
            jdbcTemplate.update("""
                    insert into citation(id, workspace_id, source_id, source_snapshot_id, source_chunk_id, title, quote_text, page_no, location_info)
                    values (?, ?, ?, ?, ?, ?, ?, null, ?)
                    """, citationId, workspaceId, chunk.sourceId(), chunk.sourceSnapshotId(), chunk.chunkId(), chunk.heading(),
                    trim(chunk.content(), 360), chunk.locationInfo());
            return citationId;
        }).toList();
    }

    private String buildWikiContent(SourceForWiki source, List<ChunkForWiki> chunks) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(source.title()).append("\n\n");
        builder.append("## 摘要\n\n");
        builder.append(source.summary().isBlank() ? "该页面由 Wiki ingest 根据资料内容生成，可通过 Wiki 工作台继续维护。" : source.summary()).append("\n\n");
        List<String> concepts = wikiConcepts(source);
        if (!concepts.isEmpty()) {
            builder.append("## 关联概念\n\n");
            for (String concept : concepts) {
                builder.append("- [[").append(concept).append("]]\n");
            }
            builder.append("\n");
        }
        builder.append("## 关键内容\n\n");
        for (ChunkForWiki chunk : chunks) {
            builder.append("- ").append(trim(chunk.content(), 220))
                    .append("（").append(chunk.locationInfo()).append("）\n");
        }
        builder.append("\n## 来源\n\n");
        builder.append("- source_id: `").append(source.sourceId()).append("`\n");
        builder.append("- source_type: `").append(source.sourceType()).append("`\n");
        builder.append("- tags: `").append(source.tagsJson()).append("`\n\n");
        builder.append("## 构建说明\n\n");
        builder.append("该页面由工作台级 Wiki ingest 根据资料变化生成，不绑定单次会话。\n");
        return builder.toString();
    }

    private List<String> wikiConcepts(SourceForWiki source) {
        List<String> concepts = new ArrayList<>();
        addConcept(concepts, source.sourceType());
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"([^\"]{2,40})\"").matcher(source.tagsJson());
        while (matcher.find() && concepts.size() < 6) {
            addConcept(concepts, matcher.group(1));
        }
        for (String part : source.title().replaceAll("\\.[a-zA-Z0-9]{1,8}$", "").split("[\\s_\\-]+")) {
            if (concepts.size() >= 6) {
                break;
            }
            addConcept(concepts, part);
        }
        return concepts;
    }

    private void addConcept(List<String> concepts, String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < 2 || normalized.length() > 40) {
            return;
        }
        boolean exists = concepts.stream().anyMatch(item -> item.equalsIgnoreCase(normalized));
        if (!exists) {
            concepts.add(normalized);
        }
    }

    private String findWikiItemIdByTitle(String workspaceId, String title) {
        List<String> ids = jdbcTemplate.queryForList("""
                select id from knowledge_item
                where workspace_id = ? and item_type = 'WIKI' and lower(title) = lower(?) and status = 'ACTIVE'
                limit 1
                """, String.class, workspaceId, title);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String trim(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, max - 1)) + "...";
    }

    private record SourceForWiki(String sourceId, String title, String sourceType, String summary, String tagsJson) {
    }

    private record ChunkForWiki(String chunkId, String sourceId, String sourceSnapshotId, String heading, String content, String locationInfo) {
    }
}

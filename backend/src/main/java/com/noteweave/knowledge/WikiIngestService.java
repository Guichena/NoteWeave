package com.noteweave.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.task.TaskService;
import com.noteweave.workspace.WorkspaceService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    @Transactional
    public String enqueueAndRunSourceRetractIfEnabled(String workspaceId, String sourceId, String sourceTitle) {
        if (!workspaceService.isWikiEnabled(workspaceId)) {
            return "";
        }
        String taskId = taskService.createTask(
                workspaceId,
                "WIKI_RETRACT",
                "SOURCE",
                sourceId,
                "QUEUED",
                "资料已删除，相关 Wiki 页面进入 retract 清理"
        );
        jdbcTemplate.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                values (?, ?, 'noteweave.wiki.retract', ?, ?, 'READY')
                """, Ids.newId(), taskId, sourceId, Json.write(objectMapper, Map.of(
                "taskId", taskId,
                "sourceId", sourceId,
                "workspaceId", workspaceId,
                "operation", "retract"
        )));
        List<String> itemIds = knowledgeService.findSourceBackedWikiItemIds(workspaceId, sourceId);
        for (String itemId : itemIds) {
            knowledgeService.deleteItem(itemId);
        }
        List<String> remainingSourceIds = readySourceIds(workspaceId);
        if (!remainingSourceIds.isEmpty()) {
            for (String remainingSourceId : remainingSourceIds) {
                ingestSource(workspaceId, remainingSourceId);
            }
            refreshWorkspaceIndexPage(workspaceId, "source_retract");
        }
        jdbcTemplate.update("update task_outbox set status = 'SENT', sent_at = current_timestamp where task_id = ?", taskId);
        taskService.completeTask(taskId, "WIKI_RETRACTED", "Wiki retract 已清理资料删除影响的页面：" + itemIds.size() + " 个", sourceId);
        return taskId;
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
        List<String> citationIds = createCitations(workspaceId, chunks, 3);
        KnowledgeItemResponse sourcePage = knowledgeService.upsertWikiPage(
                workspaceId,
                source.title(),
                buildSourcePageContent(source, chunks),
                citationIds
        );
        for (String concept : wikiConcepts(source)) {
            List<SourceForWiki> relatedSources = loadRelatedSourcesForConcept(workspaceId, concept, 4);
            if (relatedSources.isEmpty()) {
                relatedSources = List.of(source);
            }
            List<ChunkForWiki> relatedChunks = loadChunksForSources(
                    workspaceId,
                    relatedSources.stream().map(SourceForWiki::sourceId).toList(),
                    6
            );
            List<String> conceptCitationIds = createCitations(workspaceId, relatedChunks, 4);
            knowledgeService.upsertWikiPage(
                    workspaceId,
                    concept,
                    buildConceptPageContent(concept, relatedSources, relatedChunks, source),
                    conceptCitationIds
            );
        }
        refreshWorkspaceIndexPage(workspaceId, source.title());
        return sourcePage;
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
        return loadChunksForSources(workspaceId, List.of(sourceId), 5);
    }

    private List<ChunkForWiki> loadChunksForSources(String workspaceId, List<String> sourceIds, int limit) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", sourceIds.stream().map(ignored -> "?").toList());
        List<Object> params = new ArrayList<>();
        params.add(workspaceId);
        params.addAll(sourceIds);
        params.add(limit);
        return jdbcTemplate.query("""
                select c.id, c.source_id, c.source_snapshot_id, c.heading, c.content, c.location_info
                from source_chunk c
                where c.workspace_id = ? and c.source_id in (%s)
                order by c.chunk_no asc
                limit ?
                """.formatted(placeholders), (rs, rowNum) -> new ChunkForWiki(
                rs.getString("id"),
                rs.getString("source_id"),
                rs.getString("source_snapshot_id"),
                rs.getString("heading"),
                rs.getString("content"),
                rs.getString("location_info")
        ), params.toArray());
    }

    private List<SourceForWiki> loadRelatedSourcesForConcept(String workspaceId, String concept, int limit) {
        String keyword = "%" + concept.toLowerCase(Locale.ROOT) + "%";
        return jdbcTemplate.query("""
                select id, title, source_type, coalesce(summary, '') as summary, coalesce(tags_json, '[]') as tags_json
                from source
                where workspace_id = ?
                  and status = 'READY'
                  and (
                      lower(title) like ?
                      or lower(coalesce(summary, '')) like ?
                      or lower(coalesce(tags_json, '[]')) like ?
                  )
                order by updated_at desc, id desc
                limit ?
                """, (rs, rowNum) -> new SourceForWiki(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("source_type"),
                rs.getString("summary"),
                rs.getString("tags_json")
        ), workspaceId, keyword, keyword, keyword, limit);
    }

    private List<String> createCitations(String workspaceId, List<ChunkForWiki> chunks, int limit) {
        return chunks.stream().limit(limit).map(chunk -> {
            String citationId = Ids.newId();
            jdbcTemplate.update("""
                    insert into citation(id, workspace_id, source_id, source_snapshot_id, source_chunk_id, title, quote_text, page_no, location_info)
                    values (?, ?, ?, ?, ?, ?, ?, null, ?)
                    """, citationId, workspaceId, chunk.sourceId(), chunk.sourceSnapshotId(), chunk.chunkId(), chunk.heading(),
                    trim(chunk.content(), 360), chunk.locationInfo());
            return citationId;
        }).toList();
    }

    private String buildSourcePageContent(SourceForWiki source, List<ChunkForWiki> chunks) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(source.title()).append("\n\n");
        builder.append("## 资料摘要\n\n");
        builder.append(source.summary().isBlank() ? "该页面由 Wiki ingest 根据资料内容生成，可通过 Wiki 工作台继续维护。" : source.summary()).append("\n\n");
        List<String> concepts = wikiConcepts(source);
        builder.append("## 页面导航\n\n");
        builder.append("- [[Wiki Index]]\n");
        if (!concepts.isEmpty()) {
            builder.append("\n## 关联概念\n\n");
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

    private String buildConceptPageContent(
            String concept,
            List<SourceForWiki> relatedSources,
            List<ChunkForWiki> relatedChunks,
            SourceForWiki triggerSource
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(concept).append("\n\n");
        builder.append("## 概念摘要\n\n");
        builder.append("该概念页由工作台级 Wiki ingest 自动汇聚，与 `").append(concept).append("` 相关的资料页和片段会持续回流到这里。\n\n");
        builder.append("## 相关资料\n\n");
        builder.append("- [[Wiki Index]]\n");
        for (SourceForWiki related : relatedSources) {
            builder.append("- [[").append(related.title()).append("]]");
            if (!related.summary().isBlank()) {
                builder.append("：").append(trim(related.summary(), 80));
            }
            builder.append("\n");
        }
        builder.append("\n## 关键依据\n\n");
        if (relatedChunks.isEmpty()) {
            builder.append("- 当前还没有足够的原文片段，后续资料 ingest 会继续补齐。\n");
        } else {
            for (ChunkForWiki chunk : relatedChunks) {
                builder.append("- 《").append(titleOfChunkSource(relatedSources, chunk.sourceId())).append("》：")
                        .append(trim(chunk.content(), 180))
                        .append("（").append(chunk.locationInfo()).append("）\n");
            }
        }
        builder.append("\n## 相邻概念\n\n");
        for (String sibling : wikiConcepts(triggerSource)) {
            if (!sibling.equalsIgnoreCase(concept)) {
                builder.append("- [[").append(sibling).append("]]\n");
            }
        }
        builder.append("\n## 构建说明\n\n");
        builder.append("该页面由工作台级 Wiki ingest 自动维护，会随着相关资料的上传、重建和删除持续更新。\n");
        return builder.toString();
    }

    private void refreshWorkspaceIndexPage(String workspaceId, String triggerTitle) {
        List<SimpleWikiPage> pages = loadActiveWikiPages(workspaceId);
        List<ChunkForWiki> overviewChunks = loadRecentWorkspaceChunks(workspaceId, 4);
        List<String> citationIds = createCitations(workspaceId, overviewChunks, 4);
        String content = buildWorkspaceIndexPageContent(workspaceId, triggerTitle, pages);
        knowledgeService.upsertWikiPage(workspaceId, "Wiki Index", content, citationIds);
    }

    private String buildWorkspaceIndexPageContent(String workspaceId, String triggerTitle, List<SimpleWikiPage> pages) {
        List<SimpleWikiPage> visiblePages = pages.stream()
                .filter(page -> !"Wiki Index".equalsIgnoreCase(page.title()))
                .toList();
        WikiStatsResponse stats = knowledgeService.getWikiStats(workspaceId);
        List<WikiIssueResponse> topIssues = knowledgeService.listWikiIssues(workspaceId, null, null, null, null).stream()
                .limit(4)
                .toList();
        StringBuilder builder = new StringBuilder();
        builder.append("# Wiki Index\n\n");
        builder.append("## 工作台概览\n\n");
        builder.append("- workspace_id: `").append(workspaceId).append("`\n");
        builder.append("- READY 资料数: ").append(readySourceIds(workspaceId).size()).append("\n");
        builder.append("- 活跃 Wiki 页面数: ").append(visiblePages.size()).append("\n");
        if (triggerTitle != null && !triggerTitle.isBlank()) {
            builder.append("- 最近触发资料: [[").append(triggerTitle).append("]]\n");
        }
        builder.append("\n## 治理概览\n\n");
        builder.append("- 页面总数: ").append(stats.pageCount()).append("\n");
        builder.append("- 链接总数: ").append(stats.linkCount()).append("\n");
        builder.append("- 已解析链接: ").append(stats.resolvedLinkCount()).append("\n");
        builder.append("- 断链数: ").append(stats.unresolvedLinkCount()).append("\n");
        builder.append("- 问题总数: ").append(stats.issueCount()).append("\n");
        builder.append("- 可自动修复: ").append(stats.autoFixableIssueCount()).append("\n");
        builder.append("- 需人工确认: ").append(stats.manualReviewIssueCount()).append("\n");
        builder.append("- 待处理任务: ").append(stats.pendingTaskCount()).append("\n");
        builder.append("\n## 页面分布\n\n");
        appendPageKindSummary(builder, stats.pagesByKind());
        builder.append("\n## 页面目录\n\n");
        appendPageGroup(builder, "总览页", visiblePages, "OVERVIEW");
        appendPageGroup(builder, "概念页", visiblePages, "CONCEPT");
        appendPageGroup(builder, "主题页", visiblePages, "TOPIC");
        appendPageGroup(builder, "对比页", visiblePages, "COMPARISON");
        if (!topIssues.isEmpty()) {
            builder.append("\n## 优先治理问题\n\n");
            for (WikiIssueResponse issue : topIssues) {
                builder.append("- ").append(issue.severity()).append(" / ").append(issue.issueType())
                        .append("：").append(issue.title());
                if (issue.autoFixable()) {
                    builder.append("（可自动修复）");
                }
                builder.append("\n");
            }
        }
        builder.append("\n## 最近资料变化\n\n");
        for (String title : loadRecentSourceTitles(workspaceId, 6)) {
            builder.append("- [[").append(title).append("]]\n");
        }
        builder.append("\n## 构建说明\n\n");
        builder.append("该索引页由工作台级 Wiki ingest 自动维护，用于承接目录、概览和页面导航，而不是一次会话里的临时草稿。\n");
        return builder.toString();
    }

    private void appendPageGroup(StringBuilder builder, String label, List<SimpleWikiPage> pages, String pageKind) {
        List<SimpleWikiPage> group = pages.stream()
                .filter(page -> pageKind.equalsIgnoreCase(page.pageKind()))
                .limit(12)
                .toList();
        if (group.isEmpty()) {
            return;
        }
        builder.append("### ").append(label).append("\n\n");
        for (SimpleWikiPage page : group) {
            builder.append("- [[").append(page.title()).append("]]：")
                    .append(trim(page.summary(), 60))
                    .append("\n");
        }
        builder.append("\n");
    }

    private void appendPageKindSummary(StringBuilder builder, Map<String, Integer> pagesByKind) {
        if (pagesByKind == null || pagesByKind.isEmpty()) {
            builder.append("- 暂无已沉淀页面\n");
            return;
        }
        List<String> orderedKinds = List.of("OVERVIEW", "CONCEPT", "TOPIC", "COMPARISON");
        for (String pageKind : orderedKinds) {
            Integer count = pagesByKind.get(pageKind);
            if (count != null && count > 0) {
                builder.append("- ").append(pageKind).append(": ").append(count).append("\n");
            }
        }
        pagesByKind.entrySet().stream()
                .filter(entry -> !orderedKinds.contains(entry.getKey()) && entry.getValue() != null && entry.getValue() > 0)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n"));
    }

    private List<String> wikiConcepts(SourceForWiki source) {
        List<String> concepts = new ArrayList<>();
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
        String lowered = normalized.toLowerCase(Locale.ROOT);
        if (Set.of("markdown", "pdf", "txt", "text", "doc", "docx", "md").contains(lowered)) {
            return;
        }
        boolean exists = concepts.stream().anyMatch(item -> item.equalsIgnoreCase(normalized));
        if (!exists) {
            concepts.add(normalized);
        }
    }

    private List<SimpleWikiPage> loadActiveWikiPages(String workspaceId) {
        return jdbcTemplate.query("""
                select i.title, coalesce(i.page_kind, 'TOPIC') as page_kind, coalesce(v.summary, '') as summary
                from knowledge_item i
                left join knowledge_version v on v.id = i.latest_version_id
                where i.workspace_id = ? and i.item_type = 'WIKI' and i.status = 'ACTIVE'
                order by i.updated_at desc, i.title asc
                """, (rs, rowNum) -> new SimpleWikiPage(
                rs.getString("title"),
                rs.getString("page_kind"),
                rs.getString("summary")
        ), workspaceId);
    }

    private List<ChunkForWiki> loadRecentWorkspaceChunks(String workspaceId, int limit) {
        return jdbcTemplate.query("""
                select c.id, c.source_id, c.source_snapshot_id, c.heading, c.content, c.location_info
                from source_chunk c
                join source s on s.id = c.source_id
                where c.workspace_id = ? and s.status = 'READY'
                order by s.updated_at desc, c.chunk_no asc
                limit ?
                """, (rs, rowNum) -> new ChunkForWiki(
                rs.getString("id"),
                rs.getString("source_id"),
                rs.getString("source_snapshot_id"),
                rs.getString("heading"),
                rs.getString("content"),
                rs.getString("location_info")
        ), workspaceId, limit);
    }

    private List<String> loadRecentSourceTitles(String workspaceId, int limit) {
        return jdbcTemplate.queryForList("""
                select title
                from source
                where workspace_id = ? and status = 'READY'
                order by updated_at desc, id desc
                limit ?
                """, String.class, workspaceId, limit);
    }

    private String titleOfChunkSource(List<SourceForWiki> sources, String sourceId) {
        for (SourceForWiki source : sources) {
            if (source.sourceId().equals(sourceId)) {
                return source.title();
            }
        }
        return sourceId;
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

    private record SimpleWikiPage(String title, String pageKind, String summary) {
    }
}

package com.noteweave.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.source.SourceMessagingMode;
import com.noteweave.task.TaskCommandPort;
import com.noteweave.workspace.WorkspaceQueryPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class WikiIngestService {

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceQueryPort workspaceQueryPort;
    private final KnowledgeQueryService knowledgeQueryService;
    private final KnowledgeCommandService knowledgeCommandService;
    private final KnowledgeGovernanceService knowledgeGovernanceService;
    private final TaskCommandPort taskCommandPort;
    private final ObjectMapper objectMapper;
    private final SourceMessagingMode messagingMode;
    private final WikiIngestTransactionExecutor transactionExecutor;

    private static final Duration ORPHANED_PENDING_TASK_AGE = Duration.ofMinutes(10);
    /** 自动概念页正文里的固定说明，用来区分自动生成的概念页和用户手工创建的页面。 */
    static final String CONCEPT_PAGE_MARKER = "该概念页由工作台级 Wiki ingest 自动汇聚";
    /** 资料页构建说明里的固定文字，用来识别自动生成的资料页。 */
    static final String SOURCE_PAGE_MARKER = "该页面由工作台级 Wiki ingest 根据资料变化生成";
    /** 资料页来源小节里记录概念指纹的前缀。 */
    static final String CONCEPT_FINGERPRINT_LABEL = "- concept_fingerprint: `";
    /** 资料页列出当前选中概念的小节标题。 */
    static final String SELECTED_CONCEPTS_HEADING = "## 关联概念";
    private static final Logger log = LoggerFactory.getLogger(WikiIngestService.class);
    private WikiConceptSuggester conceptSuggester = WikiConceptSuggester.ruleBasedOnly();

    /** 概念页的选题：有大模型时由模型挑选核心术语，否则按规则抽取。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setConceptSuggester(WikiConceptSuggester conceptSuggester) {
        if (conceptSuggester != null) this.conceptSuggester = conceptSuggester;
    }

    public WikiIngestService(
            JdbcTemplate jdbcTemplate,
            WorkspaceQueryPort workspaceQueryPort,
            KnowledgeQueryService knowledgeQueryService,
            KnowledgeCommandService knowledgeCommandService,
            KnowledgeGovernanceService knowledgeGovernanceService,
            TaskCommandPort taskCommandPort,
            ObjectMapper objectMapper,
            SourceMessagingMode messagingMode,
            WikiIngestTransactionExecutor transactionExecutor
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceQueryPort = workspaceQueryPort;
        this.knowledgeQueryService = knowledgeQueryService;
        this.knowledgeCommandService = knowledgeCommandService;
        this.knowledgeGovernanceService = knowledgeGovernanceService;
        this.taskCommandPort = taskCommandPort;
        this.objectMapper = objectMapper;
        this.messagingMode = messagingMode;
        this.transactionExecutor = transactionExecutor;
    }

    public String enqueueAndRunSourceIngestIfEnabled(String workspaceId, String sourceId) {
        if (!workspaceQueryPort.isWikiEnabled(workspaceId)) {
            return "";
        }
        return enqueueAndRunSourceIngest(workspaceId, sourceId, "ingest", "Wiki 构建已开启，资料变更已进入 Wiki ingest 队列");
    }

    /**
     * Kafka 消费者回调入口：直接执行 source ingest。
     */
    public void runSourceIngestNow(String taskId, String workspaceId, String sourceId) {
        if (!isWikiEnabledForInternalExecution(workspaceId)) {
            taskCommandPort.cancelTask(taskId, "WIKI_DISABLED", "Wiki 构建已关闭，跳过资料 ingest", sourceId);
            return;
        }
        cancelSupersededPendingTasks(
                taskId, workspaceId, sourceId, "WIKI_INGEST", "ingest");
        taskCommandPort.startTask(taskId);
        try {
            transactionExecutor.execute(() -> {
                KnowledgeItemResponse page = ingestSource(workspaceId, sourceId);
                taskCommandPort.completeTask(taskId, "WIKI_INDEXED",
                        "Wiki ingest 已生成或更新工作台级页面", page.itemId());
                return null;
            });
        } catch (RuntimeException ex) {
            taskCommandPort.failTask(taskId, "WIKI_INDEXED_FAILED",
                    "Wiki ingest 失败：" + failureMessage(ex), "WIKI_INGEST_FAILED", true);
            // Do not rethrow: task is already FAILED; Kafka redelivery would only create noise.
        }
    }

    /**
     * Kafka 消费者回调入口：直接执行 source retract。
     */
    public void runSourceRetractNow(String taskId, String workspaceId, String sourceId) {
        if (!isWikiEnabledForInternalExecution(workspaceId)) {
            taskCommandPort.cancelTask(taskId, "WIKI_DISABLED", "Wiki 构建已关闭，跳过资料 retract", sourceId);
            return;
        }
        cancelSupersededPendingTasks(
                taskId, workspaceId, sourceId, "WIKI_RETRACT", "retract");
        taskCommandPort.startTask(taskId);
        try {
            transactionExecutor.execute(() -> {
                List<String> itemIds = knowledgeQueryService.findSourceBackedWikiItemIds(
                        workspaceId, sourceId);
                for (String itemId : itemIds) {
                    knowledgeCommandService.deleteItem(itemId);
                }
                List<String> remainingSourceIds = readySourceIds(workspaceId);
                if (!remainingSourceIds.isEmpty()) {
                    for (String remainingSourceId : remainingSourceIds) {
                        ingestSource(workspaceId, remainingSourceId);
                    }
                    refreshWorkspaceIndexPage(workspaceId, "source_retract");
                }
                taskCommandPort.completeTask(taskId, "WIKI_RETRACTED",
                        "Wiki retract 已清理资料删除影响的页面：" + itemIds.size() + " 个", sourceId);
                return null;
            });
        } catch (RuntimeException ex) {
            taskCommandPort.failTask(taskId, "WIKI_RETRACTED_FAILED",
                    "Wiki retract 失败：" + failureMessage(ex), "WIKI_RETRACT_FAILED", true);
            // Do not rethrow: task is already FAILED; Kafka redelivery would only create noise.
        }
    }

    public WikiRebuildResponse enqueueAndRunWorkspaceIngestIfEnabled(String workspaceId, String trigger) {
        if (!workspaceQueryPort.isWikiEnabled(workspaceId)) {
            return new WikiRebuildResponse(workspaceId, 0, 0, List.of());
        }
        List<String> sourceIds = readySourceIds(workspaceId);
        List<String> taskIds = sourceIds.stream()
                .map(sourceId -> enqueueAndRunSourceIngest(workspaceId, sourceId, trigger, "Wiki 工作台级回补/重建已入队"))
                .toList();
        return new WikiRebuildResponse(workspaceId, sourceIds.size(), taskIds.size(), taskIds);
    }

    @Scheduled(
            fixedDelayString = "${noteweave.wiki.task-reconcile-ms:60000}",
            initialDelayString = "${noteweave.wiki.task-reconcile-initial-delay-ms:5000}"
    )
    public int reconcileOrphanedPendingWikiTasks() {
        Timestamp cutoff = Timestamp.from(
                Instant.now().minus(ORPHANED_PENDING_TASK_AGE));
        List<String> orphanedTaskIds = jdbcTemplate.queryForList("""
                select distinct t.id
                from task t
                join task_outbox o on o.task_id = t.id
                where t.task_type in ('WIKI_INGEST', 'WIKI_RETRACT')
                  and t.task_status = 'PENDING'
                  and o.status = 'SENT'
                  and t.updated_at < ?
                order by t.id asc
                limit 100
                """, String.class, cutoff);
        for (String taskId : orphanedTaskIds) {
            taskCommandPort.cancelTask(
                    taskId,
                    "WIKI_DELIVERY_ORPHANED",
                    "Wiki 消息已发送但消费者长时间未接管，任务已安全取消，可通过资料重建重新执行",
                    "");
        }
        return orphanedTaskIds.size();
    }

    public String enqueueAndRunSourceRetractIfEnabled(String workspaceId, String sourceId, String sourceTitle) {
        if (!workspaceQueryPort.isWikiEnabled(workspaceId)) {
            return "";
        }
        String taskId = transactionExecutor.execute(() -> enqueueSourceRetract(
                workspaceId, sourceId));
        if (messagingMode.isAsyncEnabled()) {
            return taskId;
        }
        runSourceRetractNow(taskId, workspaceId, sourceId);
        markOutboxSent(taskId);
        return taskId;
    }

    private String enqueueAndRunSourceIngest(String workspaceId, String sourceId, String operation, String message) {
        String taskId = transactionExecutor.execute(() -> enqueueSourceIngest(
                workspaceId, sourceId, operation, message));
        if (!messagingMode.isAsyncEnabled()) {
            runSourceIngestNow(taskId, workspaceId, sourceId);
            markOutboxSent(taskId);
        }
        return taskId;
    }

    private String enqueueSourceIngest(String workspaceId, String sourceId, String operation, String message) {
        String taskId = taskCommandPort.createTask(workspaceId, "WIKI_INGEST", "SOURCE", sourceId, "QUEUED", message);
        Map<String, Object> payload = Map.of(
                "taskId", taskId,
                "sourceId", sourceId,
                "workspaceId", workspaceId,
                "operation", operation
        );
        jdbcTemplate.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                values (?, ?, 'noteweave.wiki.ingest', ?, ?, 'READY')
                """, Ids.newId(), taskId, sourceId, Json.write(objectMapper, payload));
        return taskId;
    }

    private String enqueueSourceRetract(String workspaceId, String sourceId) {
        String taskId = taskCommandPort.createTask(
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
        return taskId;
    }

    private void markOutboxSent(String taskId) {
        transactionExecutor.execute(() -> {
            jdbcTemplate.update(
                    "update task_outbox set status = 'SENT', sent_at = current_timestamp where task_id = ?",
                    taskId
            );
            return null;
        });
    }

    /**
     * Kafka callbacks have no servlet request identity. Their task payload is already scoped to a
     * workspace, so the worker only needs the persisted workspace state instead of the
     * user-authorized {@link WorkspaceQueryPort} used by request-facing enqueue operations.
     */
    private boolean isWikiEnabledForInternalExecution(String workspaceId) {
        Integer enabledWorkspaceCount = jdbcTemplate.queryForObject("""
                select count(*) from workspace
                where id = ? and status = 'ACTIVE' and wiki_enabled = true
                """, Integer.class, workspaceId);
        return enabledWorkspaceCount != null && enabledWorkspaceCount > 0;
    }

    private void cancelSupersededPendingTasks(
            String currentTaskId,
            String workspaceId,
            String sourceId,
            String taskType,
            String operation
    ) {
        List<String> staleTaskIds = jdbcTemplate.queryForList("""
                select id from task
                where id <> ? and workspace_id = ? and target_id = ?
                  and task_type = ? and task_status = 'PENDING'
                order by created_at asc, id asc
                """, String.class, currentTaskId, workspaceId, sourceId, taskType);
        for (String staleTaskId : staleTaskIds) {
            taskCommandPort.cancelTask(
                    staleTaskId,
                    "WIKI_SUPERSEDED",
                    "较新的 Wiki " + operation + " 任务已接管同一资料，取消旧的待处理任务",
                    sourceId);
        }
    }

    private String failureMessage(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
    }

    private KnowledgeItemResponse ingestSource(String workspaceId, String sourceId) {
        SourceForWiki source = loadSource(workspaceId, sourceId);
        List<ChunkForWiki> chunks = loadChunks(workspaceId, sourceId);
        String leadingText = String.join("\n", chunks.stream().map(ChunkForWiki::content).toList());
        String fingerprint = conceptFingerprint(source, leadingText);
        // 先选定概念，再写入页面和引用，缩短事务内持有写锁的时间。
        // 资料内容没变时沿用上次选出的概念，避免每次重建都让模型重新挑选、概念页来回变动
        List<String> concepts = reusableConcepts(workspaceId, source.title(), fingerprint);
        if (concepts.isEmpty()) {
            concepts = conceptSuggester.suggest(source.title(), source.summary(), source.tagsJson(), leadingText,
                    loadAutoConceptTitles(workspaceId));
        }
        // 在写入资料页之前清理旧概念页，写入时的自动链接就不会再指向即将删除的页面
        pruneOrphanedConceptPages(workspaceId, source.title(), concepts);
        List<String> citationIds = createCitations(workspaceId, chunks, 3);
        KnowledgeItemResponse sourcePage = knowledgeCommandService.upsertWikiPageForInternalExecution(
                workspaceId,
                source.title(),
                buildSourcePageContent(source, chunks, concepts, fingerprint),
                citationIds
        );
        for (String concept : concepts) {
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
            knowledgeCommandService.upsertWikiPageForInternalExecution(
                    workspaceId,
                    concept,
                    buildConceptPageContent(concept, relatedSources, relatedChunks, concepts),
                    conceptCitationIds
            );
        }
        refreshWorkspaceIndexPage(workspaceId, source.title());
        return sourcePage;
    }

    /**
     * 清理不再被任何资料页选中的自动概念页。
     * <p>
     * 资料页的关联概念小节列出它当前选出的概念；概念选择变化后（例如规则抽取换成模型挑选），
     * 旧概念页不会再被覆盖，只会残留在目录里。这里不能看链接表：自动链接会把正文里出现的页面标题
     * 也标成链接，旧概念页会因此一直被引用。只删除从未被用户编辑过的自动概念页，
     * 用户改过或手工创建的页面一律保留。
     */
    private int pruneOrphanedConceptPages(String workspaceId, String currentSourceTitle, List<String> currentConcepts) {
        // 当前资料的资料页还没写入，它的选中概念以本次结果为准；其他资料以各自资料页为准
        java.util.Set<String> selected = new java.util.HashSet<>(currentConcepts);
        jdbcTemplate.queryForList("""
                select v.content
                from knowledge_item page
                join knowledge_version v on v.id = page.latest_version_id
                join source s on s.workspace_id = page.workspace_id and s.title = page.title
                where page.workspace_id = ? and page.item_type = 'WIKI' and page.status = 'ACTIVE'
                  and s.status = 'READY' and page.title <> ?
                """, String.class, workspaceId, currentSourceTitle)
                .forEach(content -> selected.addAll(selectedConcepts(content)));
        List<String> orphanIds = new ArrayList<>();
        jdbcTemplate.query("""
                select i.id, i.title
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.workspace_id = ?
                  and i.item_type = 'WIKI'
                  and i.status = 'ACTIVE'
                  and i.updated_by like 'SYSTEM:%'
                  and v.content like ?
                """, rs -> {
            if (!selected.contains(rs.getString("title"))) orphanIds.add(rs.getString("id"));
        }, workspaceId, "%" + CONCEPT_PAGE_MARKER + "%");
        for (String itemId : orphanIds) {
            knowledgeCommandService.deleteItem(itemId);
        }
        if (!orphanIds.isEmpty()) {
            log.info("Pruned {} orphaned Wiki concept pages in workspace {}", orphanIds.size(), workspaceId);
        }
        return orphanIds.size();
    }

    /** 资料内容指纹：标题、摘要和正文开头都没变时，概念选择可以直接沿用。 */
    static String conceptFingerprint(SourceForWiki source, String leadingText) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                    (source.title() + "\n" + source.summary() + "\n" + leadingText)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** 现有资料页的指纹与本次一致时，返回它关联概念小节里的概念；否则返回空列表。 */
    private List<String> reusableConcepts(String workspaceId, String sourceTitle, String fingerprint) {
        List<String> contents = jdbcTemplate.queryForList("""
                select v.content
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.workspace_id = ? and i.item_type = 'WIKI' and i.status = 'ACTIVE' and i.title = ?
                """, String.class, workspaceId, sourceTitle);
        if (contents.isEmpty() || !contents.get(0).contains(CONCEPT_FINGERPRINT_LABEL + fingerprint + "`")) {
            return List.of();
        }
        return selectedConcepts(contents.get(0));
    }

    /** 工作台里已有的自动概念页标题，提示模型沿用，让不同资料能汇聚到同一个概念页。 */
    private List<String> loadAutoConceptTitles(String workspaceId) {
        return jdbcTemplate.queryForList("""
                select i.title
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.workspace_id = ? and i.item_type = 'WIKI' and i.status = 'ACTIVE' and v.content like ?
                order by i.updated_at desc
                limit 40
                """, String.class, workspaceId, "%" + CONCEPT_PAGE_MARKER + "%");
    }

    /** 读取资料页关联概念小节里的 [[概念]] 列表。 */
    public static List<String> selectedConcepts(String sourcePageContent) {
        if (sourcePageContent == null) return List.of();
        int start = sourcePageContent.indexOf(SELECTED_CONCEPTS_HEADING);
        if (start < 0) return List.of();
        int bodyStart = start + SELECTED_CONCEPTS_HEADING.length();
        int end = sourcePageContent.indexOf("\n## ", bodyStart);
        String section = sourcePageContent.substring(bodyStart, end < 0 ? sourcePageContent.length() : end);
        List<String> concepts = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[\\[([^\\]]+)]]").matcher(section);
        while (matcher.find()) concepts.add(matcher.group(1).trim());
        return concepts;
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

    private String buildSourcePageContent(SourceForWiki source, List<ChunkForWiki> chunks, List<String> concepts,
                                          String fingerprint) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(source.title()).append("\n\n");
        builder.append("## 资料摘要\n\n");
        builder.append(source.summary().isBlank() ? "该页面由 Wiki ingest 根据资料内容生成，可通过 Wiki 工作台继续维护。" : source.summary()).append("\n\n");
        builder.append("## 页面导航\n\n");
        builder.append("- [[Wiki Index]]\n");
        if (!concepts.isEmpty()) {
            builder.append("\n").append(SELECTED_CONCEPTS_HEADING).append("\n\n");
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
        builder.append("- tags: `").append(source.tagsJson()).append("`\n");
        builder.append(CONCEPT_FINGERPRINT_LABEL).append(fingerprint).append("`\n\n");
        builder.append("## 构建说明\n\n");
        builder.append(SOURCE_PAGE_MARKER).append("，不绑定单次会话。\n");
        return builder.toString();
    }

    private String buildConceptPageContent(
            String concept,
            List<SourceForWiki> relatedSources,
            List<ChunkForWiki> relatedChunks,
            List<String> siblingConcepts
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(concept).append("\n\n");
        builder.append("## 概念摘要\n\n");
        builder.append(CONCEPT_PAGE_MARKER).append("，与 `").append(concept).append("` 相关的资料页和片段会持续回流到这里。\n\n");
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
        for (String sibling : siblingConcepts) {
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
        knowledgeCommandService.upsertWikiPageForInternalExecution(
                workspaceId, "Wiki Index", content, citationIds);
    }

    private String buildWorkspaceIndexPageContent(String workspaceId, String triggerTitle, List<SimpleWikiPage> pages) {
        List<SimpleWikiPage> visiblePages = pages.stream()
                .filter(page -> !"Wiki Index".equalsIgnoreCase(page.title()))
                .toList();
        WikiStatsResponse stats = knowledgeGovernanceService
                .getWikiStatsForInternalExecution(workspaceId);
        List<WikiIssueResponse> topIssues = knowledgeGovernanceService
                .listWikiIssues(workspaceId, null, null, null, null).stream()
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

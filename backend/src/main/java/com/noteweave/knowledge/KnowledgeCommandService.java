package com.noteweave.knowledge;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.security.SystemActor;
import com.noteweave.workspace.WorkspaceQueryPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeCommandService {

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceQueryPort workspaceQueryPort;
    private final AuditActorProvider auditActorProvider;
    private final KnowledgeVersionService knowledgeVersionService;
    private final KnowledgeWikiMutationService wikiMutationService;
    private final KnowledgeCitationReadGate citationReadGate;
    private final WikiIndexOutbox wikiIndexOutbox;

    /** 资源授权纵深防御（NR01）：删除等破坏性操作不单靠拦截器，服务层自校验归属。 */
    private com.noteweave.security.WorkspaceAccessGuard workspaceAccessGuard;

    @Autowired(required = false)
    void setWorkspaceAccessGuard(com.noteweave.security.WorkspaceAccessGuard workspaceAccessGuard) {
        this.workspaceAccessGuard = workspaceAccessGuard;
    }

    public KnowledgeCommandService(
            JdbcTemplate jdbcTemplate,
            WorkspaceQueryPort workspaceQueryPort,
            AuditActorProvider auditActorProvider,
            KnowledgeVersionService knowledgeVersionService,
            KnowledgeWikiMutationService wikiMutationService,
            KnowledgeCitationReadGate citationReadGate
    ) {
        this(jdbcTemplate, workspaceQueryPort, auditActorProvider, knowledgeVersionService,
                wikiMutationService, citationReadGate, null);
    }

    @Autowired
    public KnowledgeCommandService(
            JdbcTemplate jdbcTemplate,
            WorkspaceQueryPort workspaceQueryPort,
            AuditActorProvider auditActorProvider,
            KnowledgeVersionService knowledgeVersionService,
            KnowledgeWikiMutationService wikiMutationService,
            KnowledgeCitationReadGate citationReadGate,
            WikiIndexOutbox wikiIndexOutbox
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceQueryPort = workspaceQueryPort;
        this.auditActorProvider = auditActorProvider;
        this.knowledgeVersionService = knowledgeVersionService;
        this.wikiMutationService = wikiMutationService;
        this.citationReadGate = citationReadGate;
        this.wikiIndexOutbox = wikiIndexOutbox;
    }

    /**
     * 内部执行的审计 actor（WIKI-05）：后台 ingest、链接刷新这类自动写入必须落 SYSTEM 身份，
     * 不能继承请求线程上的当前用户，否则自动内容会被记成人工编辑并挡住后续自动更新。
     *
     * <p>这里刻意不走 {@link AuditActorProvider#currentOrSystem(String)}：只要线程上碰巧挂着
     * 一个用户身份（请求线程内联触发编译、本地免登录回退），内部写入就会被记成用户操作。</p>
     */
    private String internalActor() {
        return SystemActor.of("KNOWLEDGE");
    }

    @Transactional
    public KnowledgeItemResponse createItem(
            String workspaceId,
            KnowledgeItemRequest request
    ) {
        requireWorkspace(workspaceId);
        List<String> citationIds = citationIdsFromRequest(
                workspaceId, request.sourceMessageId(), List.of());
        if ("WIKI".equals(request.itemType())) {
            String existingItemId = wikiMutationService.findWikiItemIdByTitle(
                    workspaceId, request.title());
            if (existingItemId != null) {
                return knowledgeVersionService.appendVersion(
                        existingItemId,
                        new AppendKnowledgeVersionRequest(
                                request.content(), request.sourceMessageId(), citationIds));
            }
        }
        return createItemWithVersion(
                workspaceId,
                request.itemType(),
                request.title(),
                request.content(),
                request.sourceMessageId(),
                citationIds);
    }

    @Transactional
    public KnowledgeItemResponse renameItem(
            String itemId,
            RenameKnowledgeItemRequest request
    ) {
        KnowledgeItemRef item = loadKnowledgeItem(itemId);
        String nextTitle = request.title().trim();
        if (nextTitle.isBlank()) {
            throw new BusinessException(
                    "KNOWLEDGE_TITLE_REQUIRED", "知识标题不能为空");
        }
        String currentContent = latestContent(itemId);
        String pageKind = "WIKI".equals(item.itemType())
                ? wikiMutationService.inferWikiPageKind(nextTitle, currentContent)
                : null;
        String actor = auditActorProvider.currentOrSystem("KNOWLEDGE");
        jdbcTemplate.update("""
                update knowledge_item
                set title = ?, page_kind = ?, updated_by = ?,
                    updated_at = current_timestamp
                where id = ?
                """, nextTitle, pageKind, actor, itemId);
        if ("WIKI".equals(item.itemType())) {
            List<String> related = relatedWikiItemIds(item.workspaceId(), itemId, item.title());
            refreshWikiLinksAfterPageTitleChange(
                    item.workspaceId(), itemId, item.title(), nextTitle, currentContent);
            wikiMutationService.logWiki(
                    item.workspaceId(),
                    itemId,
                    "RENAME_PAGE",
                    "重命名 Wiki 页面：" + item.title() + " -> " + nextTitle);
            enqueueUpsert(item.workspaceId(), itemId);
            for (String relatedId : related) {
                enqueueUpsert(item.workspaceId(), relatedId);
            }
        }
        return itemResponse(itemId);
    }

    @Transactional
    public void deleteItem(String itemId) {
        KnowledgeItemRef item = loadKnowledgeItem(itemId);
        // NR01 纵深防御：即使上游拦截器被等价路径绕过，删除也必须通过资源归属校验
        if (workspaceAccessGuard != null) {
            workspaceAccessGuard.requireKnowledgeItemPermission(
                    itemId, com.noteweave.security.WorkspacePermission.KNOWLEDGE_WRITE);
        }
        deleteItemRow(item);
    }

    /**
     * 后台任务的删除入口：Kafka 消费者线程没有 HTTP 用户会话，
     * {@code requireKnowledgeItemPermission} 会直接抛 AUTHENTICATION_REQUIRED，
     * 导致资料删除后的 Wiki retract 永久失败。
     *
     * <p>该入口不做 HTTP 身份校验，改为校验条目归属指定工作台；审计 actor 固定为 SYSTEM
     * （WIKI-05：后台删除不能被记成当前用户的操作）。</p>
     */
    @Transactional
    public void deleteItemForInternalExecution(String workspaceId, String itemId) {
        KnowledgeItemRef item = loadKnowledgeItem(itemId);
        if (!item.workspaceId().equals(workspaceId)) {
            throw new BusinessException(
                    "KNOWLEDGE_ITEM_WORKSPACE_MISMATCH", "知识条目不属于指定工作台");
        }
        deleteItemRow(item, internalActor());
    }

    private void deleteItemRow(KnowledgeItemRef item) {
        deleteItemRow(item, auditActorProvider.currentOrSystem("KNOWLEDGE"));
    }

    private void deleteItemRow(KnowledgeItemRef item, String actor) {
        String itemId = item.itemId();
        jdbcTemplate.update("""
                update knowledge_item
                set status = 'DELETED', updated_by = ?,
                    updated_at = current_timestamp
                where id = ?
                """, actor, itemId);
        if ("WIKI".equals(item.itemType())) {
            List<String> related = relatedWikiItemIds(item.workspaceId(), itemId, item.title());
            jdbcTemplate.update(
                    "delete from knowledge_item_link where source_item_id = ?", itemId);
            jdbcTemplate.update("""
                    update knowledge_item_link
                    set target_item_id = null, relation_status = 'UNRESOLVED',
                        updated_at = current_timestamp
                    where workspace_id = ? and target_item_id = ?
                    """, item.workspaceId(), itemId);
            wikiMutationService.logWiki(
                    item.workspaceId(), itemId, "DELETE_PAGE",
                    "删除 Wiki 页面：" + item.title());
            enqueueDelete(item.workspaceId(), itemId);
            for (String relatedId : related) {
                enqueueUpsert(item.workspaceId(), relatedId);
            }
        }
    }

    @Transactional
    public KnowledgeItemResponse upsertWikiPage(
            String workspaceId,
            String title,
            String content,
            List<String> citationIds
    ) {
        requireWorkspace(workspaceId);
        return upsertWikiPageForInternalExecution(
                workspaceId, title, content, citationIds);
    }

    /**
     * Background Wiki tasks are already bound to a persisted workspace and do not have an HTTP
     * user session. Request-facing callers must continue to use {@link #upsertWikiPage}.
     */
    KnowledgeItemResponse upsertWikiPageForInternalExecution(
            String workspaceId,
            String title,
            String content,
            List<String> citationIds
    ) {
        return upsertWikiSourcePageForInternalExecution(
                workspaceId, title, content, citationIds, null);
    }

    /**
     * NR19：自动资料页按 origin_source_id 定位——同名不同来源各自建页，
     * 同一来源重处理更新原页；不再以标题吞并人工页或其他来源的资料页。
     */
    KnowledgeItemResponse upsertWikiSourcePageForInternalExecution(
            String workspaceId,
            String title,
            String content,
            List<String> citationIds,
            String originSourceId
    ) {
        List<String> normalizedCitationIds = citationIds == null
                ? List.of() : List.copyOf(citationIds);
        String existingItemId = originSourceId == null
                ? wikiMutationService.findWikiItemIdByTitle(workspaceId, title)
                : wikiMutationService.findWikiItemIdBySource(workspaceId, originSourceId);
        if (existingItemId == null) {
            return createItemWithVersion(
                    workspaceId, "WIKI", title, content, null, normalizedCitationIds,
                    originSourceId, null, internalActor());
        }
        // WIKI-03：人工编辑过的页面不能被自动 ingest 静默覆盖。
        // 只标记待确认并保留用户内容，用户再次编辑后状态回到 MANUAL。
        WikiPageState state = wikiPageState(existingItemId);
        if (!state.autoUpdatable()) {
            markWikiPageNeedsReview(workspaceId, existingItemId, title);
            return itemResponse(existingItemId);
        }
        KnowledgeItemResponse updated = knowledgeVersionService.appendVersion(
                existingItemId,
                new AppendKnowledgeVersionRequest(
                        content, null, normalizedCitationIds),
                internalActor());
        markWikiPageState(existingItemId, WikiPageState.AUTO);
        return updated;
    }

    private WikiPageState wikiPageState(String itemId) {
        return jdbcTemplate.query("""
                select wiki_page_state, updated_by
                from knowledge_item
                where id = ?
                """, rs -> {
            if (!rs.next()) {
                return WikiPageState.AUTO;
            }
            return WikiPageState.fromStored(rs.getString(1), rs.getString(2));
        }, itemId);
    }

    private void markWikiPageState(String itemId, WikiPageState state) {
        jdbcTemplate.update(
                "update knowledge_item set wiki_page_state = ? where id = ?",
                state.name(), itemId);
    }

    private void markWikiPageNeedsReview(String workspaceId, String itemId, String title) {
        if (wikiPageState(itemId) == WikiPageState.NEEDS_REVIEW) {
            return;
        }
        markWikiPageState(itemId, WikiPageState.NEEDS_REVIEW);
        wikiMutationService.logWiki(
                workspaceId, itemId, "SKIP_MANUAL_EDIT",
                "页面已被人工编辑，跳过本次自动更新：" + title);
    }

    @Transactional
    KnowledgeItemResponse createItemWithVersion(
            String workspaceId,
            String itemType,
            String title,
            String content,
            String sourceMessageId,
            List<String> citationIds
    ) {
        return createItemWithVersion(workspaceId, itemType, title, content,
                sourceMessageId, citationIds, null, null);
    }

    /** NR19：originSourceId 绑定自动资料页的来源身份（人工/概念页为 null）。 */
    KnowledgeItemResponse createItemWithVersion(
            String workspaceId,
            String itemType,
            String title,
            String content,
            String sourceMessageId,
            List<String> citationIds,
            String originSourceId
    ) {
        return createItemWithVersion(workspaceId, itemType, title, content,
                sourceMessageId, citationIds, originSourceId, null);
    }

    /**
     * KB-01：产物写回知识库。先按冻结来源建立引用行，再建知识对象并绑定，
     * 让写回内容与来源同生命周期。
     *
     * <p>产物确实有来源却一条都绑不上时直接失败，不留下一条不可读的知识对象；
     * 产物本身没有来源依赖时按普通知识对象写入，只记录来源性质。</p>
     */
    @Transactional
    public KnowledgeItemResponse createArtifactItem(
            String workspaceId,
            String itemType,
            String title,
            String content,
            List<ArtifactSourceCitation> citations
    ) {
        requireWorkspace(workspaceId);
        List<String> citationIds = insertArtifactCitations(workspaceId, citations);
        if (citationIds.isEmpty() && citations != null && !citations.isEmpty()) {
            throw new BusinessException("ARTIFACT_WRITEBACK_LINEAGE_LOST",
                    "产物的来源已不可追溯，本次写回已取消", HttpStatus.CONFLICT);
        }
        String origin = citationIds.isEmpty() ? "ARTIFACT_UNSOURCED" : "ARTIFACT_SOURCED";
        return createItemWithVersion(workspaceId, itemType, title, content,
                null, citationIds, null, origin);
    }

    /**
     * 为产物的每个冻结来源建一条引用行。快照取产物生成时冻结的那个；
     * 缺失时回退到来源最新快照。来源不属于当前工作台或没有快照时跳过该条。
     */
    private List<String> insertArtifactCitations(
            String workspaceId,
            List<ArtifactSourceCitation> citations
    ) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }
        List<String> citationIds = new ArrayList<>();
        Set<String> seenSourceIds = new LinkedHashSet<>();
        for (ArtifactSourceCitation citation : citations) {
            if (citation == null || citation.sourceId() == null
                    || citation.sourceId().isBlank()
                    || !seenSourceIds.add(citation.sourceId())) {
                continue;
            }
            String snapshotId = resolveCitationSnapshot(
                    workspaceId, citation.sourceId(), citation.sourceSnapshotId());
            if (snapshotId == null) {
                continue;
            }
            String citationId = Ids.newId();
            jdbcTemplate.update("""
                    insert into citation(
                        id, workspace_id, source_id, source_snapshot_id, source_chunk_id,
                        title, quote_text, page_no, location_info
                    ) values (?, ?, ?, ?, null, ?, ?, null, ?)
                    """, citationId, workspaceId, citation.sourceId(), snapshotId,
                    citationTitle(workspaceId, citation),
                    citation.quoteText() == null ? "" : trim(citation.quoteText(), 360),
                    trim(citation.location(), 300));
            citationIds.add(citationId);
        }
        return List.copyOf(citationIds);
    }

    private String resolveCitationSnapshot(
            String workspaceId,
            String sourceId,
            String preferredSnapshotId
    ) {
        List<String> snapshots = jdbcTemplate.queryForList("""
                select s.id from source_snapshot s
                join source src on src.id = s.source_id
                where s.source_id = ? and src.workspace_id = ?
                order by case when s.id = ? then 0 else 1 end, s.version_no desc
                limit 1
                """, String.class, sourceId, workspaceId, preferredSnapshotId == null ? "" : preferredSnapshotId);
        return snapshots.isEmpty() ? null : snapshots.get(0);
    }

    private String citationTitle(String workspaceId, ArtifactSourceCitation citation) {
        if (citation.title() != null && !citation.title().isBlank()) {
            return trim(citation.title(), 300);
        }
        List<String> titles = jdbcTemplate.queryForList("""
                select title from source where id = ? and workspace_id = ?
                """, String.class, citation.sourceId(), workspaceId);
        String title = titles.isEmpty() ? "" : titles.get(0);
        return title == null || title.isBlank() ? "来源" : trim(title, 300);
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private KnowledgeItemResponse createItemWithVersion(
            String workspaceId,
            String itemType,
            String title,
            String content,
            String sourceMessageId,
            List<String> citationIds,
            String originSourceId,
            String origin
    ) {
        return createItemWithVersion(workspaceId, itemType, title, content, sourceMessageId,
                citationIds, originSourceId, origin, auditActorProvider.currentOrSystem("KNOWLEDGE"));
    }

    /** 指定写入者的建条目入口（WIKI-05）：内部执行传 SYSTEM 身份，人工入口用当前用户。 */
    private KnowledgeItemResponse createItemWithVersion(
            String workspaceId,
            String itemType,
            String title,
            String content,
            String sourceMessageId,
            List<String> citationIds,
            String originSourceId,
            String origin,
            String actor
    ) {
        List<String> normalizedCitationIds = citationIds == null
                ? List.of() : List.copyOf(citationIds);
        citationReadGate.requireCitationIdsReadable(workspaceId, normalizedCitationIds);
        String normalizedContent = content;
        String pageKind = null;
        if ("WIKI".equals(itemType)) {
            KnowledgeWikiMutationService.PreparedWikiContent prepared =
                    wikiMutationService.prepareContent(workspaceId, title, content);
            normalizedContent = prepared.content();
            pageKind = prepared.pageKind();
        }
        String itemId = Ids.newId();
        String versionId = Ids.newId();
        String summary = summarize(normalizedContent);
        // WIKI-03：新建页面时按写入者标注来源状态，人工创建的页面后续不会被自动 ingest 覆盖
        String pageState = "WIKI".equals(itemType)
                ? (actor.startsWith("SYSTEM:") ? WikiPageState.AUTO.name() : WikiPageState.MANUAL.name())
                : null;
        jdbcTemplate.update("""
                insert into knowledge_item(
                    id, workspace_id, item_type, page_kind, title, status,
                    latest_version_id, created_by, updated_by, origin_source_id,
                    wiki_page_state, origin
                ) values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?)
                """, itemId, workspaceId, itemType, pageKind, title,
                versionId, actor, actor, originSourceId, pageState, origin);
        jdbcTemplate.update("""
                insert into knowledge_version(
                    id, item_id, version_no, content, summary, source_message_id
                ) values (?, ?, 1, ?, ?, ?)
                """, versionId, itemId, normalizedContent, summary, sourceMessageId);
        bindVersionCitations(versionId, normalizedCitationIds);
        if ("WIKI".equals(itemType)) {
            wikiMutationService.upsertWikiLinks(
                    workspaceId, itemId, title, normalizedContent);
            wikiMutationService.resolveIncomingLinksForCreatedWikiPage(
                    workspaceId, itemId, title);
            wikiMutationService.logWiki(
                    workspaceId, itemId, "CREATE_PAGE", "创建 Wiki 页面：" + title);
        }
        return new KnowledgeItemResponse(
                itemId, itemType, pageKind, title, "ACTIVE", versionId, 1,
                summary, Instant.now(), 0, 0, normalizedCitationIds.size(), 0, pageState);
    }

    private void refreshWikiLinksAfterPageTitleChange(
            String workspaceId,
            String itemId,
            String oldTitle,
            String nextTitle,
            String currentContent
    ) {
        jdbcTemplate.update("""
                update knowledge_item_link
                set target_title = ?, target_item_id = ?, relation_status = 'RESOLVED',
                    updated_at = current_timestamp
                where workspace_id = ? and lower(target_title) = lower(?)
                """, nextTitle, itemId, workspaceId, oldTitle);
        jdbcTemplate.update("""
                update knowledge_item_link
                set target_item_id = ?, relation_status = 'RESOLVED',
                    updated_at = current_timestamp
                where workspace_id = ? and lower(target_title) = lower(?)
                """, itemId, workspaceId, nextTitle);
        rewriteIncomingWikiPageContentAfterTitleChange(
                workspaceId, itemId, oldTitle, nextTitle);
        jdbcTemplate.update(
                "delete from knowledge_item_link where source_item_id = ?", itemId);
        wikiMutationService.upsertWikiLinks(
                workspaceId, itemId, nextTitle, currentContent);
    }

    private void rewriteIncomingWikiPageContentAfterTitleChange(
            String workspaceId,
            String renamedItemId,
            String oldTitle,
            String nextTitle
    ) {
        if (oldTitle == null || oldTitle.isBlank()
                || nextTitle == null || nextTitle.isBlank()) {
            return;
        }
        List<IncomingWikiPageRef> incomingPages = jdbcTemplate.query("""
                select distinct i.id, i.title, v.id as version_id, v.content
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                join knowledge_item_link l on l.source_item_id = i.id
                where i.workspace_id = ?
                  and i.item_type = 'WIKI'
                  and i.status = 'ACTIVE'
                  and i.id <> ?
                  and (
                      l.target_item_id = ?
                      or lower(l.target_title) = lower(?)
                  )
                """, (rs, rowNum) -> new IncomingWikiPageRef(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("version_id"),
                rs.getString("content")
        ), workspaceId, renamedItemId, renamedItemId, oldTitle);
        for (IncomingWikiPageRef page : incomingPages) {
            String rewritten = rewriteExplicitWikiLinkTitle(
                    page.content(), oldTitle, nextTitle);
            if (rewritten.equals(page.content())) {
                continue;
            }
            knowledgeVersionService.appendVersion(
                    page.itemId(),
                    new AppendKnowledgeVersionRequest(
                            rewritten,
                            null,
                            knowledgeVersionService.citationIdsForVersion(
                                    page.versionId())),
                    // 链接重写是系统维护动作，不能把引用页记成人工编辑
                    internalActor());
            wikiMutationService.logWiki(
                    workspaceId,
                    page.itemId(),
                    "REFRESH_LINK_CONTENT",
                    "页面重命名后已刷新引用页正文：" + page.title() + " -> " + nextTitle);
        }
    }

    private String rewriteExplicitWikiLinkTitle(
            String content,
            String oldTitle,
            String nextTitle
    ) {
        if (content == null || content.isBlank()) {
            return content == null ? "" : content;
        }
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\[\\[([^\\]]{1,120})]]");
        java.util.regex.Matcher matcher = pattern.matcher(content);
        StringBuffer buffer = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String raw = matcher.group(1).trim();
            String targetTitle = raw.contains("|")
                    ? raw.substring(0, raw.indexOf('|')).trim() : raw;
            if (!targetTitle.equalsIgnoreCase(oldTitle)) {
                matcher.appendReplacement(
                        buffer,
                        java.util.regex.Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String alias = raw.contains("|")
                    ? raw.substring(raw.indexOf('|') + 1).trim() : "";
            String replacement = alias.isBlank()
                    ? "[[" + nextTitle + "]]"
                    : "[[" + nextTitle + "|" + alias + "]]";
            matcher.appendReplacement(
                    buffer, java.util.regex.Matcher.quoteReplacement(replacement));
            changed = true;
        }
        matcher.appendTail(buffer);
        return changed ? buffer.toString() : content;
    }

    private String latestContent(String itemId) {
        return jdbcTemplate.query("""
                select v.content
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException(
                        "KNOWLEDGE_ITEM_NOT_FOUND", "知识对象不存在");
            }
            return rs.getString("content");
        }, itemId);
    }

    private KnowledgeItemResponse itemResponse(String itemId) {
        return jdbcTemplate.query("""
                select i.id, i.item_type, coalesce(i.page_kind, '') as page_kind,
                       i.title, i.status, i.latest_version_id, i.updated_at,
                       coalesce(v.version_no, 0) as version_no,
                       coalesce(v.summary, '') as summary,
                       i.wiki_page_state
                from knowledge_item i
                left join knowledge_version v on v.id = i.latest_version_id
                where i.id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException(
                        "KNOWLEDGE_ITEM_NOT_FOUND", "知识对象不存在");
            }
            return new KnowledgeItemResponse(
                    rs.getString("id"),
                    rs.getString("item_type"),
                    rs.getString("page_kind"),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getString("latest_version_id"),
                    rs.getInt("version_no"),
                    rs.getString("summary"),
                    toInstant(rs.getTimestamp("updated_at")),
                    0, 0, 0, 0,
                    rs.getString("wiki_page_state"));
        }, itemId);
    }

    private List<String> relatedWikiItemIds(String workspaceId, String itemId, String title) {
        if (wikiIndexOutbox == null) {
            return List.of();
        }
        List<String> related = new ArrayList<>();
        related.addAll(jdbcTemplate.queryForList("""
                select distinct source_item_id from knowledge_item_link
                where workspace_id = ?
                  and source_item_id <> ?
                  and (target_item_id = ? or lower(target_title) = lower(?))
                """, String.class, workspaceId, itemId, itemId, title == null ? "" : title));
        related.addAll(jdbcTemplate.queryForList("""
                select distinct target_item_id from knowledge_item_link
                where workspace_id = ?
                  and source_item_id = ?
                  and target_item_id is not null
                  and target_item_id <> ?
                """, String.class, workspaceId, itemId, itemId));
        return related.stream().distinct().toList();
    }

    private void enqueueUpsert(String workspaceId, String itemId) {
        if (wikiIndexOutbox != null && itemId != null) {
            wikiIndexOutbox.enqueueUpsert(workspaceId, itemId);
        }
    }

    private void enqueueDelete(String workspaceId, String itemId) {
        if (wikiIndexOutbox != null && itemId != null) {
            wikiIndexOutbox.enqueueDelete(workspaceId, itemId);
        }
    }

    private KnowledgeItemRef loadKnowledgeItem(String itemId) {
        return jdbcTemplate.query("""
                select id, workspace_id, item_type, title, status
                from knowledge_item
                where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException(
                        "KNOWLEDGE_ITEM_NOT_FOUND", "知识对象不存在");
            }
            if (!"ACTIVE".equals(rs.getString("status"))) {
                throw new BusinessException(
                        "KNOWLEDGE_ITEM_INACTIVE", "知识对象不可更新");
            }
            return new KnowledgeItemRef(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("item_type"),
                    rs.getString("title"));
        }, itemId);
    }

    private MessageSnapshot loadAssistantMessage(String messageId) {
        return jdbcTemplate.query("""
                select id, workspace_id, role, content
                from conversation_message
                where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("MESSAGE_NOT_FOUND", "消息不存在");
            }
            if (!"ASSISTANT".equals(rs.getString("role"))) {
                throw new BusinessException(
                        "MESSAGE_NOT_ASSISTANT", "只能保存助手回答为 Note");
            }
            return new MessageSnapshot(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("content"));
        }, messageId);
    }

    private List<String> citationIdsFromRequest(
            String workspaceId,
            String sourceMessageId,
            List<String> directCitationIds
    ) {
        if (sourceMessageId != null && !sourceMessageId.isBlank()) {
            MessageSnapshot message = loadAssistantMessage(sourceMessageId);
            if (!workspaceId.equals(message.workspaceId())) {
                throw new BusinessException(
                        "MESSAGE_WORKSPACE_MISMATCH", "消息不属于当前工作台");
            }
            return citationIdsForMessage(sourceMessageId);
        }
        return directCitationIds == null ? List.of() : List.copyOf(directCitationIds);
    }

    private List<String> citationIdsForMessage(String messageId) {
        return jdbcTemplate.queryForList("""
                select citation_id
                from message_citation
                where message_id = ?
                order by sort_order asc
                """, String.class, messageId);
    }

    private void bindVersionCitations(String versionId, List<String> citationIds) {
        for (int index = 0; index < citationIds.size(); index++) {
            jdbcTemplate.update("""
                    insert into knowledge_version_citation(
                        id, knowledge_version_id, citation_id, sort_order
                    ) values (?, ?, ?, ?)
                    """, Ids.newId(), versionId, citationIds.get(index), index);
        }
    }

    private void requireWorkspace(String workspaceId) {
        if (!workspaceQueryPort.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
    }

    private String summarize(String content) {
        String normalized = content == null
                ? "" : content.replace("\r", "").replace("\n", " ").trim();
        if (normalized.length() <= 220) {
            return normalized;
        }
        return normalized.substring(0, 219) + "...";
    }

    private Instant toInstant(Timestamp value) {
        return value == null ? Instant.now() : value.toInstant();
    }

    private record KnowledgeItemRef(
            String itemId,
            String workspaceId,
            String itemType,
            String title
    ) {
    }

    private record MessageSnapshot(
            String messageId,
            String workspaceId,
            String content
    ) {
    }

    private record IncomingWikiPageRef(
            String itemId,
            String title,
            String versionId,
            String content
    ) {
    }
}

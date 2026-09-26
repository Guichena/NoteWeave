package com.noteweave.knowledge;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.workspace.WorkspaceQueryPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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

    public KnowledgeCommandService(
            JdbcTemplate jdbcTemplate,
            WorkspaceQueryPort workspaceQueryPort,
            AuditActorProvider auditActorProvider,
            KnowledgeVersionService knowledgeVersionService,
            KnowledgeWikiMutationService wikiMutationService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceQueryPort = workspaceQueryPort;
        this.auditActorProvider = auditActorProvider;
        this.knowledgeVersionService = knowledgeVersionService;
        this.wikiMutationService = wikiMutationService;
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
            refreshWikiLinksAfterPageTitleChange(
                    item.workspaceId(), itemId, item.title(), nextTitle, currentContent);
            wikiMutationService.logWiki(
                    item.workspaceId(),
                    itemId,
                    "RENAME_PAGE",
                    "重命名 Wiki 页面：" + item.title() + " -> " + nextTitle);
        }
        return itemResponse(itemId);
    }

    @Transactional
    public void deleteItem(String itemId) {
        KnowledgeItemRef item = loadKnowledgeItem(itemId);
        String actor = auditActorProvider.currentOrSystem("KNOWLEDGE");
        jdbcTemplate.update("""
                update knowledge_item
                set status = 'DELETED', updated_by = ?,
                    updated_at = current_timestamp
                where id = ?
                """, actor, itemId);
        if ("WIKI".equals(item.itemType())) {
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
        List<String> normalizedCitationIds = citationIds == null
                ? List.of() : List.copyOf(citationIds);
        String existingItemId = wikiMutationService.findWikiItemIdByTitle(
                workspaceId, title);
        if (existingItemId == null) {
            return createItemWithVersion(
                    workspaceId, "WIKI", title, content, null, normalizedCitationIds);
        }
        return knowledgeVersionService.appendVersion(
                existingItemId,
                new AppendKnowledgeVersionRequest(
                        content, null, normalizedCitationIds));
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
        List<String> normalizedCitationIds = citationIds == null
                ? List.of() : List.copyOf(citationIds);
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
        String actor = auditActorProvider.currentOrSystem("KNOWLEDGE");
        jdbcTemplate.update("""
                insert into knowledge_item(
                    id, workspace_id, item_type, page_kind, title, status,
                    latest_version_id, created_by, updated_by
                ) values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, itemId, workspaceId, itemType, pageKind, title,
                versionId, actor, actor);
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
                summary, Instant.now(), 0, 0, normalizedCitationIds.size(), 0);
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
                                    page.versionId())));
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
                       coalesce(v.summary, '') as summary
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
                    0, 0, 0, 0);
        }, itemId);
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

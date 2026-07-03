package com.noteweave.knowledge;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.workspace.WorkspaceService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeService {

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceService workspaceService;

    public KnowledgeService(JdbcTemplate jdbcTemplate, WorkspaceService workspaceService) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceService = workspaceService;
    }

    @Transactional
    public KnowledgeItemResponse createItem(String workspaceId, KnowledgeItemRequest request) {
        if (!workspaceService.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
        List<String> citationIds = citationIdsFromRequest(workspaceId, request.sourceMessageId(), List.of());
        return createItemWithVersion(workspaceId, request.itemType(), request.title(), request.content(), request.sourceMessageId(), citationIds);
    }

    @Transactional
    public KnowledgeItemResponse saveMessageAsNote(String messageId, SaveNoteRequest request) {
        MessageSnapshot message = loadAssistantMessage(messageId);
        List<String> citationIds = citationIdsForMessage(messageId);
        return createItemWithVersion(message.workspaceId(), "NOTE", request.title(), message.content(), messageId, citationIds);
    }

    @Transactional
    public KnowledgeItemResponse appendVersion(String itemId, AppendKnowledgeVersionRequest request) {
        KnowledgeItemRef item = loadKnowledgeItem(itemId);
        String content = request.content() == null ? "" : request.content().trim();
        if (content.isBlank()) {
            throw new BusinessException("KNOWLEDGE_CONTENT_REQUIRED", "知识版本内容不能为空");
        }
        List<String> citationIds = citationIdsFromRequest(item.workspaceId(), request.sourceMessageId(), request.citationIds());
        Integer currentVersion = jdbcTemplate.queryForObject("""
                select coalesce(max(version_no), 0) from knowledge_version where item_id = ?
                """, Integer.class, itemId);
        int nextVersionNo = (currentVersion == null ? 0 : currentVersion) + 1;
        String versionId = Ids.newId();
        String summary = summarize(content);
        jdbcTemplate.update("""
                insert into knowledge_version(id, item_id, version_no, content, summary, source_message_id)
                values (?, ?, ?, ?, ?, ?)
                """, versionId, itemId, nextVersionNo, content, summary, request.sourceMessageId());
        bindVersionCitations(versionId, citationIds);
        jdbcTemplate.update("""
                update knowledge_item
                set latest_version_id = ?, updated_at = current_timestamp
                where id = ?
                """, versionId, itemId);
        if ("WIKI".equals(item.itemType())) {
            jdbcTemplate.update("delete from knowledge_item_link where source_item_id = ?", itemId);
            upsertWikiLinks(item.workspaceId(), itemId, item.title(), content);
            logWiki(item.workspaceId(), itemId, "UPDATE_PAGE", "追加 Wiki 页面版本：" + item.title());
        }
        return new KnowledgeItemResponse(itemId, item.itemType(), item.title(), "ACTIVE", versionId, nextVersionNo, summary, Instant.now());
    }

    @Transactional
    public KnowledgeItemResponse renameItem(String itemId, RenameKnowledgeItemRequest request) {
        KnowledgeItemRef item = loadKnowledgeItem(itemId);
        String nextTitle = request.title().trim();
        if (nextTitle.isBlank()) {
            throw new BusinessException("KNOWLEDGE_TITLE_REQUIRED", "知识标题不能为空");
        }
        jdbcTemplate.update("""
                update knowledge_item
                set title = ?, updated_at = current_timestamp
                where id = ?
                """, nextTitle, itemId);
        if ("WIKI".equals(item.itemType())) {
            refreshWikiLinksAfterPageTitleChange(item.workspaceId(), itemId, item.title(), nextTitle);
            logWiki(item.workspaceId(), itemId, "RENAME_PAGE", "重命名 Wiki 页面：" + item.title() + " -> " + nextTitle);
        }
        return itemResponse(itemId);
    }

    @Transactional
    public void deleteItem(String itemId) {
        KnowledgeItemRef item = loadKnowledgeItem(itemId);
        jdbcTemplate.update("""
                update knowledge_item
                set status = 'DELETED', updated_at = current_timestamp
                where id = ?
                """, itemId);
        if ("WIKI".equals(item.itemType())) {
            jdbcTemplate.update("delete from knowledge_item_link where source_item_id = ?", itemId);
            jdbcTemplate.update("""
                    update knowledge_item_link
                    set target_item_id = null, relation_status = 'UNRESOLVED', updated_at = current_timestamp
                    where workspace_id = ? and target_item_id = ?
                    """, item.workspaceId(), itemId);
            logWiki(item.workspaceId(), itemId, "DELETE_PAGE", "删除 Wiki 页面：" + item.title());
        }
    }

    private List<String> citationIdsFromRequest(String workspaceId, String sourceMessageId, List<String> directCitationIds) {
        if (sourceMessageId != null && !sourceMessageId.isBlank()) {
            MessageSnapshot message = loadAssistantMessage(sourceMessageId);
            if (!workspaceId.equals(message.workspaceId())) {
                throw new BusinessException("MESSAGE_WORKSPACE_MISMATCH", "消息不属于当前工作台");
            }
            return citationIdsForMessage(sourceMessageId);
        }
        return directCitationIds == null ? List.of() : directCitationIds;
    }

    private List<String> citationIdsForMessage(String messageId) {
        return jdbcTemplate.queryForList("""
                select citation_id from message_citation where message_id = ? order by sort_order asc
                """, String.class, messageId);
    }

    public WikiHomeResponse getWikiHome(String workspaceId) {
        if (!workspaceService.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
        return new WikiHomeResponse(
                workspaceId,
                "/workspaces/" + workspaceId + "/wiki",
                listItems(workspaceId, "WIKI"),
                listWikiLinks(workspaceId)
        );
    }

    public List<WikiLinkResponse> listWikiLinks(String workspaceId) {
        return jdbcTemplate.query("""
                select source_item_id, target_item_id, target_title, relation_type, relation_status, mention_count
                from knowledge_item_link
                where workspace_id = ?
                order by updated_at desc
                """, (rs, rowNum) -> new WikiLinkResponse(
                rs.getString("source_item_id"),
                rs.getString("target_item_id"),
                rs.getString("target_title"),
                rs.getString("relation_type"),
                rs.getString("relation_status"),
                rs.getInt("mention_count")
        ), workspaceId);
    }

    public List<KnowledgeItemResponse> searchWikiPages(String workspaceId, String query) {
        Set<String> terms = extractTerms(query);
        return listItems(workspaceId, "WIKI").stream()
                .filter(item -> terms.isEmpty() || score(item.title() + "\n" + item.summary(), terms) > 0)
                .sorted(Comparator.comparingInt((KnowledgeItemResponse item) -> score(item.title() + "\n" + item.summary(), terms)).reversed())
                .toList();
    }

    public WikiGraphResponse getWikiGraph(String workspaceId) {
        List<WikiGraphNode> nodes = jdbcTemplate.query("""
                select i.id, i.title, coalesce(i.page_kind, '') as page_kind, coalesce(v.version_no, 0) as version_no
                from knowledge_item i
                left join knowledge_version v on v.id = i.latest_version_id
                where i.workspace_id = ? and i.item_type = 'WIKI' and i.status = 'ACTIVE'
                order by i.updated_at desc
                """, (rs, rowNum) -> new WikiGraphNode(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("page_kind"),
                rs.getInt("version_no")
        ), workspaceId);
        List<WikiGraphEdge> edges = jdbcTemplate.query("""
                select source_item_id, target_item_id, target_title, relation_type, relation_status
                from knowledge_item_link
                where workspace_id = ?
                order by updated_at desc
                """, (rs, rowNum) -> new WikiGraphEdge(
                rs.getString("source_item_id"),
                rs.getString("target_item_id"),
                rs.getString("target_title"),
                rs.getString("relation_type"),
                rs.getString("relation_status")
        ), workspaceId);
        return new WikiGraphResponse(workspaceId, nodes, edges);
    }

    public WikiStatsResponse getWikiStats(String workspaceId) {
        int pageCount = count("select count(*) from knowledge_item where workspace_id = ? and item_type = 'WIKI' and status = 'ACTIVE'", workspaceId);
        int linkCount = count("select count(*) from knowledge_item_link where workspace_id = ?", workspaceId);
        int resolvedLinkCount = count("select count(*) from knowledge_item_link where workspace_id = ? and relation_status = 'RESOLVED'", workspaceId);
        int unresolvedLinkCount = count("select count(*) from knowledge_item_link where workspace_id = ? and relation_status = 'UNRESOLVED'", workspaceId);
        int citationCount = count("""
                select count(*)
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                join knowledge_version_citation c on c.knowledge_version_id = v.id
                where i.workspace_id = ? and i.item_type = 'WIKI' and i.status = 'ACTIVE'
                """, workspaceId);
        return new WikiStatsResponse(workspaceId, pageCount, linkCount, resolvedLinkCount, unresolvedLinkCount, citationCount, lintWiki(workspaceId).size());
    }

    public List<WikiIssueResponse> lintWiki(String workspaceId) {
        List<WikiIssueResponse> issues = new ArrayList<>();
        issues.addAll(jdbcTemplate.query("""
                select source_item_id, target_title
                from knowledge_item_link
                where workspace_id = ? and relation_status = 'UNRESOLVED'
                order by updated_at desc
                """, (rs, rowNum) -> new WikiIssueResponse(
                "BROKEN_LINK",
                "HIGH",
                rs.getString("source_item_id"),
                rs.getString("target_title"),
                "页面引用了尚不存在的 Wiki 页面：" + rs.getString("target_title"),
                "创建缺失页面或修改链接标题"
        ), workspaceId));
        issues.addAll(jdbcTemplate.query("""
                select i.id, i.title
                from knowledge_item i
                left join knowledge_item_link outgoing on outgoing.source_item_id = i.id
                left join knowledge_item_link incoming on incoming.target_item_id = i.id
                where i.workspace_id = ? and i.item_type = 'WIKI' and i.status = 'ACTIVE'
                group by i.id, i.title
                having count(outgoing.id) = 0 and count(incoming.id) = 0
                """, (rs, rowNum) -> new WikiIssueResponse(
                "ORPHAN_PAGE",
                "MEDIUM",
                rs.getString("id"),
                rs.getString("title"),
                "页面没有出链或入链，可能没有进入 Wiki 网络。",
                "补充页面链接或在相关页面中引用它"
        ), workspaceId));
        issues.addAll(jdbcTemplate.query("""
                select i.id, i.title
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                left join knowledge_version_citation c on c.knowledge_version_id = v.id
                where i.workspace_id = ? and i.item_type = 'WIKI' and i.status = 'ACTIVE'
                group by i.id, i.title
                having count(c.id) = 0
                """, (rs, rowNum) -> new WikiIssueResponse(
                "MISSING_SOURCE",
                "MEDIUM",
                rs.getString("id"),
                rs.getString("title"),
                "页面缺少来源引用。",
                "补充来源引用或从资料 ingest 重新生成"
        ), workspaceId));
        return issues;
    }

    public List<WikiLogEntryResponse> listWikiLog(String workspaceId) {
        return jdbcTemplate.query("""
                select id, item_id, event_type, message, created_at
                from wiki_log_entry
                where workspace_id = ?
                order by created_at desc, id desc
                limit 80
                """, (rs, rowNum) -> new WikiLogEntryResponse(
                rs.getString("id"),
                rs.getString("item_id"),
                rs.getString("event_type"),
                rs.getString("message"),
                toInstant(rs.getTimestamp("created_at"))
        ), workspaceId);
    }

    @Transactional
    public WikiStatsResponse rebuildWikiLinks(String workspaceId) {
        List<WikiPageSnapshot> pages = loadWikiPageSnapshots(workspaceId);
        jdbcTemplate.update("delete from knowledge_item_link where workspace_id = ?", workspaceId);
        for (WikiPageSnapshot page : pages) {
            upsertWikiLinks(workspaceId, page.itemId(), page.title(), page.content());
        }
        logWiki(workspaceId, null, "REBUILD_LINKS", "已重建 Wiki 页面链接关系");
        return getWikiStats(workspaceId);
    }

    @Transactional
    public WikiAutoFixResponse autoFixWiki(String workspaceId) {
        List<String> missingTitles = jdbcTemplate.queryForList("""
                select distinct target_title
                from knowledge_item_link
                where workspace_id = ? and relation_status = 'UNRESOLVED'
                """, String.class, workspaceId);
        int created = 0;
        for (String title : missingTitles) {
            if (findWikiItemIdByTitle(workspaceId, title) == null) {
                createItemWithVersion(workspaceId, "WIKI", title,
                        "# " + title + "\n\n## 待补充\n\n该页面由 Wiki auto-fix 根据断链自动创建，需要人工补充内容和来源。\n",
                        null,
                        List.of());
                created++;
            }
        }
        WikiStatsResponse stats = rebuildWikiLinks(workspaceId);
        logWiki(workspaceId, null, "AUTO_FIX", "已自动创建缺失页面 " + created + " 个，并重建链接");
        return new WikiAutoFixResponse(workspaceId, created, stats.linkCount(), stats.issueCount());
    }

    public List<KnowledgeItemResponse> listItems(String workspaceId, String itemType) {
        return jdbcTemplate.query("""
                select i.id, i.item_type, i.title, i.status, i.latest_version_id, i.updated_at,
                       coalesce(v.version_no, 0) as version_no,
                       coalesce(v.summary, '') as summary
                from knowledge_item i
                left join knowledge_version v on v.id = i.latest_version_id
                where i.workspace_id = ? and i.item_type = ? and i.status = 'ACTIVE'
                order by i.updated_at desc
                """, (rs, rowNum) -> new KnowledgeItemResponse(
                rs.getString("id"),
                rs.getString("item_type"),
                rs.getString("title"),
                rs.getString("status"),
                rs.getString("latest_version_id"),
                rs.getInt("version_no"),
                rs.getString("summary"),
                toInstant(rs.getTimestamp("updated_at"))
        ), workspaceId, itemType);
    }

    private KnowledgeItemResponse itemResponse(String itemId) {
        return jdbcTemplate.query("""
                select i.id, i.item_type, i.title, i.status, i.latest_version_id, i.updated_at,
                       coalesce(v.version_no, 0) as version_no,
                       coalesce(v.summary, '') as summary
                from knowledge_item i
                left join knowledge_version v on v.id = i.latest_version_id
                where i.id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("KNOWLEDGE_ITEM_NOT_FOUND", "知识对象不存在");
            }
            return new KnowledgeItemResponse(
                    rs.getString("id"),
                    rs.getString("item_type"),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getString("latest_version_id"),
                    rs.getInt("version_no"),
                    rs.getString("summary"),
                    toInstant(rs.getTimestamp("updated_at"))
            );
        }, itemId);
    }

    public KnowledgeItemDetailResponse getItemDetail(String itemId) {
        return jdbcTemplate.query("""
                select i.id, i.item_type, i.title, i.status, i.latest_version_id, i.updated_at,
                       v.id as version_id, v.version_no, v.content, coalesce(v.summary, '') as summary
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("KNOWLEDGE_ITEM_NOT_FOUND", "知识对象不存在");
            }
            if (!"ACTIVE".equals(rs.getString("status"))) {
                throw new BusinessException("KNOWLEDGE_ITEM_INACTIVE", "知识对象不可读取");
            }
            String versionId = rs.getString("version_id");
            return new KnowledgeItemDetailResponse(
                    rs.getString("id"),
                    rs.getString("item_type"),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getString("latest_version_id"),
                    rs.getInt("version_no"),
                    rs.getString("content"),
                    rs.getString("summary"),
                    citationsForVersion(versionId),
                    toInstant(rs.getTimestamp("updated_at"))
            );
        }, itemId);
    }

    public List<KnowledgePageHit> findRelevantWikiPages(String workspaceId, String query) {
        List<KnowledgePageHit> pages = jdbcTemplate.query("""
                select i.id, i.title, v.id as version_id, v.version_no, v.content, coalesce(v.summary, '') as summary, i.updated_at
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.workspace_id = ? and i.item_type = 'WIKI' and i.status = 'ACTIVE'
                order by i.updated_at desc
                limit 80
                """, (rs, rowNum) -> new KnowledgePageHit(
                rs.getString("id"),
                rs.getString("version_id"),
                rs.getInt("version_no"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("summary"),
                0
        ), workspaceId);
        Set<String> terms = extractTerms(query);
        List<KnowledgePageHit> scored = pages.stream()
                .map(page -> page.withScore(score(page.title() + "\n" + page.summary() + "\n" + page.content(), terms)))
                .filter(page -> page.score() > 0 || terms.isEmpty())
                .sorted(Comparator.comparingInt(KnowledgePageHit::score).reversed())
                .limit(5)
                .toList();
        if (!scored.isEmpty()) {
            return scored;
        }
        return pages.stream().limit(5).toList();
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
        String itemId = Ids.newId();
        String versionId = Ids.newId();
        String summary = summarize(content);
        jdbcTemplate.update("""
                insert into knowledge_item(id, workspace_id, item_type, page_kind, title, status, latest_version_id)
                values (?, ?, ?, ?, ?, 'ACTIVE', ?)
                """, itemId, workspaceId, itemType, defaultPageKind(itemType), title, versionId);
        jdbcTemplate.update("""
                insert into knowledge_version(id, item_id, version_no, content, summary, source_message_id)
                values (?, ?, 1, ?, ?, ?)
                """, versionId, itemId, content, summary, sourceMessageId);
        bindVersionCitations(versionId, citationIds);
        if ("WIKI".equals(itemType)) {
            upsertWikiLinks(workspaceId, itemId, title, content);
            logWiki(workspaceId, itemId, "CREATE_PAGE", "创建 Wiki 页面：" + title);
        }
        return new KnowledgeItemResponse(itemId, itemType, title, "ACTIVE", versionId, 1, summary, Instant.now());
    }

    public List<String> citationIdsForWikiPages(List<KnowledgePageHit> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }
        List<String> versionIds = pages.stream().map(KnowledgePageHit::versionId).toList();
        String placeholders = String.join(",", versionIds.stream().map(v -> "?").toList());
        return jdbcTemplate.queryForList("""
                select citation_id from knowledge_version_citation
                where knowledge_version_id in (%s)
                order by sort_order asc
                """.formatted(placeholders), String.class, versionIds.toArray());
    }

    public List<String> findSourceBackedWikiItemIds(String workspaceId, String sourceId, String sourceTitle) {
        return jdbcTemplate.queryForList("""
                select i.id
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                join knowledge_version_citation kvc on kvc.knowledge_version_id = v.id
                join citation c on c.id = kvc.citation_id
                where i.workspace_id = ?
                  and i.item_type = 'WIKI'
                  and i.status = 'ACTIVE'
                  and lower(i.title) = lower(?)
                group by i.id
                having sum(case when c.source_id = ? then 1 else 0 end) > 0
                   and sum(case when c.source_id <> ? then 1 else 0 end) = 0
                """, String.class, workspaceId, sourceTitle, sourceId, sourceId);
    }

    private List<KnowledgeCitationResponse> citationsForVersion(String versionId) {
        return jdbcTemplate.query("""
                select c.id, c.source_id, c.title, c.quote_text, c.page_no, c.location_info
                from knowledge_version_citation kvc
                join citation c on c.id = kvc.citation_id
                where kvc.knowledge_version_id = ?
                order by kvc.sort_order asc
                """, (rs, rowNum) -> new KnowledgeCitationResponse(
                rs.getString("id"),
                rs.getString("source_id"),
                rs.getString("title"),
                rs.getString("quote_text"),
                (Integer) rs.getObject("page_no"),
                rs.getString("location_info")
        ), versionId);
    }

    private void bindVersionCitations(String versionId, List<String> citationIds) {
        for (int i = 0; i < citationIds.size(); i++) {
            jdbcTemplate.update("""
                    insert into knowledge_version_citation(id, knowledge_version_id, citation_id, sort_order)
                    values (?, ?, ?, ?)
                    """, Ids.newId(), versionId, citationIds.get(i), i);
        }
    }

    private void upsertWikiLinks(String workspaceId, String sourceItemId, String sourceTitle, String content) {
        List<String> linkTitles = extractWikiLinks(content);
        for (String targetTitle : linkTitles) {
            if (targetTitle.equalsIgnoreCase(sourceTitle)) {
                continue;
            }
            String targetItemId = findWikiItemIdByTitle(workspaceId, targetTitle);
            jdbcTemplate.update("""
                    insert into knowledge_item_link(id, workspace_id, source_item_id, target_item_id, target_title, relation_type, relation_status, mention_count)
                    values (?, ?, ?, ?, ?, 'WIKI_LINK', ?, 1)
                    """, Ids.newId(), workspaceId, sourceItemId, targetItemId, targetTitle,
                    targetItemId == null ? "UNRESOLVED" : "RESOLVED");
        }
    }

    private void refreshWikiLinksAfterPageTitleChange(String workspaceId, String itemId, String oldTitle, String nextTitle) {
        jdbcTemplate.update("""
                update knowledge_item_link
                set target_title = ?, target_item_id = ?, relation_status = 'RESOLVED', updated_at = current_timestamp
                where workspace_id = ? and lower(target_title) = lower(?)
                """, nextTitle, itemId, workspaceId, oldTitle);
        jdbcTemplate.update("""
                update knowledge_item_link
                set target_item_id = ?, relation_status = 'RESOLVED', updated_at = current_timestamp
                where workspace_id = ? and lower(target_title) = lower(?)
                """, itemId, workspaceId, nextTitle);
        jdbcTemplate.update("delete from knowledge_item_link where source_item_id = ?", itemId);
        KnowledgeItemDetailResponse detail = getItemDetail(itemId);
        upsertWikiLinks(workspaceId, itemId, nextTitle, detail.content());
    }

    private List<String> extractWikiLinks(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> links = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[\\[([^\\]]{1,120})]]").matcher(content);
        while (matcher.find()) {
            String title = matcher.group(1).trim();
            if (!title.isBlank() && links.stream().noneMatch(existing -> existing.equalsIgnoreCase(title))) {
                links.add(title);
            }
        }
        return links;
    }

    private String findWikiItemIdByTitle(String workspaceId, String title) {
        List<String> ids = jdbcTemplate.queryForList("""
                select id from knowledge_item
                where workspace_id = ? and item_type = 'WIKI' and lower(title) = lower(?) and status = 'ACTIVE'
                limit 1
                """, String.class, workspaceId, title);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private List<WikiPageSnapshot> loadWikiPageSnapshots(String workspaceId) {
        return jdbcTemplate.query("""
                select i.id, i.title, v.content
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.workspace_id = ? and i.item_type = 'WIKI' and i.status = 'ACTIVE'
                """, (rs, rowNum) -> new WikiPageSnapshot(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("content")
        ), workspaceId);
    }

    private void logWiki(String workspaceId, String itemId, String eventType, String message) {
        jdbcTemplate.update("""
                insert into wiki_log_entry(id, workspace_id, item_id, event_type, message)
                values (?, ?, ?, ?, ?)
                """, Ids.newId(), workspaceId, itemId, eventType, message);
    }

    private int count(String sql, String workspaceId) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, workspaceId);
        return value == null ? 0 : value;
    }

    private KnowledgeItemRef loadKnowledgeItem(String itemId) {
        return jdbcTemplate.query("""
                select id, workspace_id, item_type, title, status from knowledge_item where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("KNOWLEDGE_ITEM_NOT_FOUND", "知识对象不存在");
            }
            if (!"ACTIVE".equals(rs.getString("status"))) {
                throw new BusinessException("KNOWLEDGE_ITEM_INACTIVE", "知识对象不可更新");
            }
            return new KnowledgeItemRef(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("item_type"),
                    rs.getString("title")
            );
        }, itemId);
    }

    private String defaultPageKind(String itemType) {
        return "WIKI".equals(itemType) ? "TOPIC" : null;
    }

    private MessageSnapshot loadAssistantMessage(String messageId) {
        return jdbcTemplate.query("""
                select id, workspace_id, role, content from conversation_message where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("MESSAGE_NOT_FOUND", "消息不存在");
            }
            if (!"ASSISTANT".equals(rs.getString("role"))) {
                throw new BusinessException("MESSAGE_NOT_ASSISTANT", "只能保存助手回答为 Note");
            }
            return new MessageSnapshot(rs.getString("id"), rs.getString("workspace_id"), rs.getString("content"));
        }, messageId);
    }

    private Set<String> extractTerms(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        String[] parts = normalized.split("[^\\p{IsHan}a-zA-Z0-9]+");
        Set<String> terms = new LinkedHashSet<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                terms.add(part);
            }
        }
        if (terms.isEmpty() && normalized.length() >= 2) {
            terms.add(normalized);
        }
        return terms;
    }

    private int score(String content, Set<String> terms) {
        if (terms.isEmpty()) {
            return 1;
        }
        String lower = content == null ? "" : content.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (lower.contains(term)) {
                score += Math.max(1, term.length());
            }
        }
        return score;
    }

    private String summarize(String content) {
        String normalized = content == null ? "" : content.replace("\r", "").replace("\n", " ").trim();
        if (normalized.length() <= 220) {
            return normalized;
        }
        return normalized.substring(0, 219) + "...";
    }

    private Instant toInstant(Timestamp value) {
        return value == null ? Instant.now() : value.toInstant();
    }

    private record MessageSnapshot(String messageId, String workspaceId, String content) {
    }

    private record KnowledgeItemRef(String itemId, String workspaceId, String itemType, String title) {
    }

    private record WikiPageSnapshot(String itemId, String title, String content) {
    }

    public record KnowledgePageHit(
            String itemId,
            String versionId,
            int versionNo,
            String title,
            String content,
            String summary,
            int score
    ) {
        KnowledgePageHit withScore(int nextScore) {
            return new KnowledgePageHit(itemId, versionId, versionNo, title, content, summary, nextScore);
        }
    }
}

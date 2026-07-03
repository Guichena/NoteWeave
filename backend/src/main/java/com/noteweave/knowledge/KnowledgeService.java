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
        }
        return new KnowledgeItemResponse(itemId, item.itemType(), item.title(), "ACTIVE", versionId, nextVersionNo, summary, Instant.now());
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

package com.noteweave.knowledge;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.security.AuditActorProvider;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeVersionService {

    private final JdbcTemplate jdbcTemplate;
    private final AuditActorProvider auditActorProvider;
    private final KnowledgeWikiMutationService wikiMutationService;
    private final KnowledgeCitationReadGate citationReadGate;

    public KnowledgeVersionService(
            JdbcTemplate jdbcTemplate,
            AuditActorProvider auditActorProvider,
            KnowledgeWikiMutationService wikiMutationService,
            KnowledgeCitationReadGate citationReadGate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditActorProvider = auditActorProvider;
        this.wikiMutationService = wikiMutationService;
        this.citationReadGate = citationReadGate;
    }

    @Transactional
    public KnowledgeItemResponse appendVersion(
            String itemId,
            AppendKnowledgeVersionRequest request
    ) {
        KnowledgeItemRef item = loadKnowledgeItem(itemId, true);
        String content = request.content() == null ? "" : request.content().trim();
        if (content.isBlank()) {
            throw new BusinessException(
                    "KNOWLEDGE_CONTENT_REQUIRED", "知识版本内容不能为空");
        }
        String normalizedContent = content;
        String pageKind = null;
        if ("WIKI".equals(item.itemType())) {
            KnowledgeWikiMutationService.PreparedWikiContent prepared =
                    wikiMutationService.prepareContent(
                            item.workspaceId(), item.title(), content);
            normalizedContent = prepared.content();
            pageKind = prepared.pageKind();
        }
        List<String> citationIds = citationIdsFromRequest(
                item.workspaceId(), request.sourceMessageId(), request.citationIds());
        citationReadGate.requireCitationIdsReadable(item.workspaceId(), citationIds);
        Integer currentVersion = jdbcTemplate.queryForObject("""
                select coalesce(max(version_no), 0)
                from knowledge_version
                where item_id = ?
                """, Integer.class, itemId);
        int nextVersionNo = (currentVersion == null ? 0 : currentVersion) + 1;
        String versionId = Ids.newId();
        String summary = summarize(normalizedContent);
        String actor = auditActorProvider.currentOrSystem("KNOWLEDGE");
        jdbcTemplate.update("""
                insert into knowledge_version(
                    id, item_id, version_no, content, summary, source_message_id
                ) values (?, ?, ?, ?, ?, ?)
                """, versionId, itemId, nextVersionNo,
                normalizedContent, summary, request.sourceMessageId());
        bindVersionCitations(versionId, citationIds);
        jdbcTemplate.update("""
                update knowledge_item
                set latest_version_id = ?, page_kind = ?, updated_by = ?,
                    updated_at = current_timestamp
                where id = ?
                """, versionId, pageKind, actor, itemId);
        if ("WIKI".equals(item.itemType())) {
            wikiMutationService.replaceOutgoingLinks(
                    item.workspaceId(), itemId, item.title(), normalizedContent);
            wikiMutationService.logWiki(
                    item.workspaceId(), itemId, "UPDATE_PAGE",
                    "追加 Wiki 页面版本：" + item.title());
        }
        return new KnowledgeItemResponse(
                itemId, item.itemType(), pageKind, item.title(), "ACTIVE",
                versionId, nextVersionNo, summary, Instant.now(),
                0, 0, citationIds.size(), 0);
    }

    public List<KnowledgeVersionSummaryResponse> listItemVersions(String itemId) {
        KnowledgeItemRef item = loadKnowledgeItem(itemId, false);
        List<KnowledgeVersionSummaryResponse> versions = jdbcTemplate.query("""
                select v.id, v.version_no, coalesce(v.summary, '') as summary,
                       v.source_message_id, v.created_at, count(kvc.id) as citation_count
                from knowledge_version v
                left join knowledge_version_citation kvc on kvc.knowledge_version_id = v.id
                where v.item_id = ?
                group by v.id, v.version_no, v.summary, v.source_message_id, v.created_at
                order by v.version_no desc, v.created_at desc
                """, (rs, rowNum) -> new KnowledgeVersionSummaryResponse(
                rs.getString("id"),
                rs.getInt("version_no"),
                rs.getString("summary"),
                rs.getString("source_message_id"),
                rs.getInt("citation_count"),
                toInstant(rs.getTimestamp("created_at"))
        ), item.itemId());
        var readable = citationReadGate.readableVersionIds(item.workspaceId(),
                versions.stream().map(KnowledgeVersionSummaryResponse::versionId).toList());
        return versions.stream().filter(version -> readable.contains(version.versionId())).toList();
    }

    public KnowledgeVersionDetailResponse getItemVersionDetail(
            String itemId,
            int versionNo
    ) {
        KnowledgeItemRef item = loadKnowledgeItem(itemId, false);
        return jdbcTemplate.query("""
                select v.id, v.item_id, v.version_no, v.content,
                       coalesce(v.summary, '') as summary,
                       v.source_message_id, v.created_at
                from knowledge_version v
                where v.item_id = ? and v.version_no = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException(
                        "KNOWLEDGE_VERSION_NOT_FOUND", "知识版本不存在");
            }
            String versionId = rs.getString("id");
            citationReadGate.requireReadable(item.workspaceId(), versionId);
            return new KnowledgeVersionDetailResponse(
                    versionId,
                    item.itemId(),
                    rs.getInt("version_no"),
                    rs.getString("content"),
                    rs.getString("summary"),
                    rs.getString("source_message_id"),
                    citationsForVersion(versionId),
                    toInstant(rs.getTimestamp("created_at"))
            );
        }, item.itemId(), versionNo);
    }

    public List<String> citationIdsForVersion(String versionId) {
        List<String> workspaces = jdbcTemplate.queryForList("""
                select i.workspace_id from knowledge_version v
                join knowledge_item i on i.id = v.item_id
                where v.id = ? and i.status = 'ACTIVE'
                """, String.class, versionId);
        if (workspaces.size() != 1) {
            throw new BusinessException("KNOWLEDGE_VERSION_NOT_FOUND", "知识版本不存在");
        }
        citationReadGate.requireReadable(workspaces.get(0), versionId);
        return jdbcTemplate.queryForList("""
                select citation_id
                from knowledge_version_citation
                where knowledge_version_id = ?
                order by sort_order asc
                """, String.class, versionId);
    }

    private KnowledgeItemRef loadKnowledgeItem(String itemId, boolean forUpdate) {
        String lockClause = forUpdate ? " for update" : "";
        return jdbcTemplate.query("""
                select id, workspace_id, item_type, title, status
                from knowledge_item
                where id = ?%s
                """.formatted(lockClause), rs -> {
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
                    rs.getString("title")
            );
        }, itemId);
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
            return jdbcTemplate.queryForList("""
                    select citation_id
                    from message_citation
                    where message_id = ?
                    order by sort_order asc
                    """, String.class, sourceMessageId);
        }
        return directCitationIds == null ? List.of() : List.copyOf(directCitationIds);
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

    private void bindVersionCitations(String versionId, List<String> citationIds) {
        for (int i = 0; i < citationIds.size(); i++) {
            jdbcTemplate.update("""
                    insert into knowledge_version_citation(
                        id, knowledge_version_id, citation_id, sort_order
                    ) values (?, ?, ?, ?)
                    """, Ids.newId(), versionId, citationIds.get(i), i);
        }
    }

    private List<KnowledgeCitationResponse> citationsForVersion(String versionId) {
        return jdbcTemplate.query("""
                select c.id, c.source_id, c.title, c.quote_text, c.page_no,
                       c.location_info,
                       coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id
                from knowledge_version_citation kvc
                join citation c on c.id = kvc.citation_id
                left join source s on s.id = c.source_id
                where kvc.knowledge_version_id = ?
                order by kvc.sort_order asc
                """, (rs, rowNum) -> new KnowledgeCitationResponse(
                rs.getString("id"),
                rs.getString("source_id"),
                rs.getString("title"),
                rs.getString("quote_text"),
                (Integer) rs.getObject("page_no"),
                rs.getString("location_info"),
                rs.getString("generated_by"),
                rs.getString("generated_ref_id")
        ), versionId);
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
}

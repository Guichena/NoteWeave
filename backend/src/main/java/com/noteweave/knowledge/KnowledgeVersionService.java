package com.noteweave.knowledge;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.source.eligibility.AccessPurpose;
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
        return appendVersion(itemId, request, auditActorProvider.currentOrSystem("KNOWLEDGE"));
    }

    /**
     * 指定写入者的追加版本入口（WIKI-05）。后台 ingest 与链接刷新必须传入 SYSTEM 身份，
     * 否则自动内容会被记成人工编辑，后续自动更新被 WIKI-03 的保护逻辑挡住。
     */
    @Transactional
    public KnowledgeItemResponse appendVersion(
            String itemId,
            AppendKnowledgeVersionRequest request,
            String actor
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
        jdbcTemplate.update("""
                insert into knowledge_version(
                    id, item_id, version_no, content, summary, source_message_id
                ) values (?, ?, ?, ?, ?, ?)
                """, versionId, itemId, nextVersionNo,
                normalizedContent, summary, request.sourceMessageId());
        bindVersionCitations(versionId, citationIds);
        // WIKI-03：人工追加版本即视为人工页面，后续自动 ingest 让路；
        // 系统追加的版本保持自动页面，可以继续随资料更新。
        String pageState = "WIKI".equals(item.itemType())
                ? (actor.startsWith("SYSTEM:") ? WikiPageState.AUTO.name() : WikiPageState.MANUAL.name())
                : null;
        jdbcTemplate.update("""
                update knowledge_item
                set latest_version_id = ?, page_kind = ?, updated_by = ?,
                    updated_at = current_timestamp, wiki_page_state = ?
                where id = ?
                """, versionId, pageKind, actor, pageState, itemId);
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
                0, 0, citationIds.size(), 0, pageState);
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
        // 版本列表是元数据读取：Note 按历史政策保留（USER 删除/暂不可用仍列出），Wiki 仍要求当前依赖
        AccessPurpose listPurpose = purposeForItem(item.itemType());
        var readable = citationReadGate.readableVersionIds(item.workspaceId(),
                versions.stream().map(KnowledgeVersionSummaryResponse::versionId).toList(), listPurpose);
        return versions.stream().filter(version -> readable.contains(version.versionId())).toList();
    }

    /** Note 历史正文按历史政策展示；Wiki 正文与引用要求当前来源可用。 */
    private AccessPurpose purposeForItem(String itemType) {
        return "WIKI".equals(itemType)
                ? AccessPurpose.CURRENT_EVIDENCE
                : AccessPurpose.HISTORY_DISPLAY;
    }

    /** V02：版本引用来源的可用性说明（与详情响应同构，不泄露正文）。 */
    private List<KnowledgeSourceAvailabilityResponse> sourceAvailabilityFor(String workspaceId, String versionId) {
        return citationReadGate.versionSourceAvailability(workspaceId, List.of(versionId))
                .getOrDefault(versionId, List.of()).stream()
                .map(decision -> new KnowledgeSourceAvailabilityResponse(
                        decision.originKey().startsWith("source:") ? decision.originKey().substring(7) : decision.originKey(),
                        decision.status().name(),
                        decision.reasonCode(),
                        decision.historyReadable(),
                        decision.evidenceEligible(),
                        decision.republishAllowed(),
                        decision.dependencySummary()))
                .toList();
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
            // 旧版本正文读取同样按用途门控；COMPLIANCE 撤销不能经历史版本绕过
            citationReadGate.requireReadable(item.workspaceId(), versionId, purposeForItem(item.itemType()));
            // V02：历史版本同样返回逐项来源可用性（不泄露正文，仅状态与原因），
            // 前端历史视图此前读取的字段在响应合同中不存在
            return new KnowledgeVersionDetailResponse(
                    versionId,
                    item.itemId(),
                    rs.getInt("version_no"),
                    rs.getString("content"),
                    rs.getString("summary"),
                    rs.getString("source_message_id"),
                    citationsForVersion(versionId),
                    toInstant(rs.getTimestamp("created_at")),
                    sourceAvailabilityFor(item.workspaceId(), versionId)
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

package com.noteweave.knowledge;

import com.noteweave.common.BusinessException;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import com.noteweave.workspace.WorkspaceQueryPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeQueryService implements WikiRetrievalQueryPort {

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeWikiSearchEngine wikiSearchEngine;
    private final WorkspaceQueryPort workspaceQueryPort;
    private final KnowledgeGovernanceService knowledgeGovernanceService;
    private final WikiPageVersionCache wikiPageVersionCache;
    private final MeterRegistry meterRegistry;
    private final KnowledgeCitationReadGate citationReadGate;
    private final ResearchGeneratedSourceReadGate generatedSourceGate;

    public KnowledgeQueryService(
            JdbcTemplate jdbcTemplate,
            KnowledgeWikiSearchEngine wikiSearchEngine,
            WorkspaceQueryPort workspaceQueryPort,
            KnowledgeGovernanceService knowledgeGovernanceService,
            WikiPageVersionCache wikiPageVersionCache,
            MeterRegistry meterRegistry,
            KnowledgeCitationReadGate citationReadGate,
            ResearchGeneratedSourceReadGate generatedSourceGate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.wikiSearchEngine = wikiSearchEngine;
        this.workspaceQueryPort = workspaceQueryPort;
        this.knowledgeGovernanceService = knowledgeGovernanceService;
        this.wikiPageVersionCache = wikiPageVersionCache;
        this.meterRegistry = meterRegistry;
        this.citationReadGate = citationReadGate;
        this.generatedSourceGate = generatedSourceGate;
    }

    public WikiHomeResponse getWikiHome(String workspaceId) {
        requireWorkspace(workspaceId);
        return new WikiHomeResponse(
                workspaceId,
                "/workspaces/" + workspaceId + "/wiki",
                listItems(workspaceId, "WIKI"),
                listWikiLinks(workspaceId));
    }

    public WikiIndexResponse getWikiIndex(String workspaceId) {
        requireWorkspace(workspaceId);
        WikiStatsResponse stats = knowledgeGovernanceService.getWikiStats(workspaceId);
        int readySourceCount = count("""
                select count(*) from source
                where workspace_id = ? and status = 'READY'
                """, workspaceId);
        int sourceBackedPageCount = count("""
                select count(*)
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.workspace_id = ?
                  and i.item_type = 'WIKI'
                  and i.status = 'ACTIVE'
                  and exists (
                      select 1 from knowledge_version_citation kvc
                      where kvc.knowledge_version_id = v.id
                  )
                """, workspaceId);
        int manualPageCount = Math.max(0, stats.pageCount() - sourceBackedPageCount);
        List<RecentSource> sourceRows = jdbcTemplate.query("""
                select id, title, status, index_status, updated_at,
                       coalesce(generated_by, ''), coalesce(generated_ref_id, '')
                from source
                where workspace_id = ? and status <> 'DELETED'
                order by updated_at desc, id desc
                limit 100
                """, (rs, rowNum) -> new RecentSource(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                toInstant(rs.getTimestamp(5)), rs.getString(6), rs.getString(7)), workspaceId);
        List<WikiIndexSourceResponse> recentSources = sourceRows.stream()
                .filter(source -> generatedSourceGate.visible(
                        workspaceId, source.generatedBy(), source.generatedRefId()))
                .limit(5).map(source -> {
            List<WikiTaskRelatedPageResponse> relatedPages =
                    knowledgeGovernanceService.relatedWikiPagesForSource(
                            workspaceId, source.id(), 3);
            Set<String> readablePages = citationReadGate.readableWikiItemIds(workspaceId,
                    relatedPages.stream().map(WikiTaskRelatedPageResponse::itemId).toList());
            relatedPages = relatedPages.stream()
                    .filter(page -> readablePages.contains(page.itemId())).toList();
            String recommendedAction;
            String focusItemId = "";
            String focusTitle = "";
            if (!relatedPages.isEmpty()) {
                recommendedAction = "OPEN_WIKI_PAGE";
                focusItemId = relatedPages.get(0).itemId();
                focusTitle = relatedPages.get(0).title();
            } else if (stats.wikiEnabled()) {
                recommendedAction = "REBUILD_WIKI";
            } else {
                recommendedAction = "ENABLE_WIKI";
            }
            return new WikiIndexSourceResponse(
                    source.id(),
                    source.title(),
                    source.status(),
                    source.indexStatus(),
                    relatedPages,
                    recommendedAction,
                    focusItemId,
                    focusTitle,
                    source.updatedAt());
        }).toList();
        Set<String> readableRecentPages = citationReadGate.readableWikiItemIds(workspaceId,
                stats.recentUpdates().stream().map(KnowledgeItemResponse::itemId).toList());
        List<KnowledgeItemResponse> recentUpdates = stats.recentUpdates().stream()
                .filter(page -> readableRecentPages.contains(page.itemId())).toList();
        List<WikiTaskSummaryResponse> recentTasks = stats.recentTasks().stream()
                .filter(task -> wikiTaskReadable(workspaceId, task)).toList();
        List<WikiIssueResponse> issues = knowledgeGovernanceService.lintWiki(workspaceId);
        Set<String> readableIssuePages = citationReadGate.readableWikiItemIds(workspaceId,
                issues.stream().map(WikiIssueResponse::itemId).toList());
        return new WikiIndexResponse(
                workspaceId,
                stats.wikiEnabled(),
                readySourceCount,
                stats.pageCount(),
                sourceBackedPageCount,
                manualPageCount,
                stats.linkCount(),
                stats.resolvedLinkCount(),
                stats.unresolvedLinkCount(),
                stats.citationCount(),
                stats.issueCount(),
                stats.autoFixableIssueCount(),
                stats.manualReviewIssueCount(),
                stats.pendingTaskCount(),
                stats.pagesByKind(),
                recentUpdates,
                recentTasks,
                recentSources,
                issues.stream().filter(issue -> readableIssuePages.contains(issue.itemId()))
                        .limit(5).toList());
    }

    public List<WikiLinkResponse> listWikiLinks(String workspaceId) {
        List<WikiLinkResponse> links = jdbcTemplate.query("""
                select source_item_id, target_item_id, target_title,
                       relation_type, relation_status, mention_count
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
        return readableLinks(workspaceId, links);
    }

    public List<KnowledgeItemResponse> listItems(String workspaceId, String itemType) {
        if ("WIKI".equalsIgnoreCase(itemType)) {
            return readableWikiRows(workspaceId, wikiSearchEngine.loadRows(workspaceId)).stream()
                    .sorted(Comparator.comparing(WikiSearchRow::updatedAt).reversed())
                    .map(WikiSearchRow::toItemResponse)
                    .toList();
        }
        return jdbcTemplate.query("""
                select i.id, i.item_type, coalesce(i.page_kind, '') as page_kind,
                       i.title, i.status, i.latest_version_id, i.updated_at,
                       coalesce(v.version_no, 0) as version_no,
                       coalesce(v.summary, '') as summary
                from knowledge_item i
                left join knowledge_version v on v.id = i.latest_version_id
                where i.workspace_id = ? and i.item_type = ? and i.status = 'ACTIVE'
                order by i.updated_at desc
                """, (rs, rowNum) -> new KnowledgeItemResponse(
                rs.getString("id"),
                rs.getString("item_type"),
                rs.getString("page_kind"),
                rs.getString("title"),
                rs.getString("status"),
                rs.getString("latest_version_id"),
                rs.getInt("version_no"),
                rs.getString("summary"),
                toInstant(rs.getTimestamp("updated_at")),
                0, 0, 0, 0
        ), workspaceId, itemType);
    }

    public KnowledgeItemDetailResponse getItemDetail(String itemId) {
        ItemState item = loadItemState(itemId);
        if (!"ACTIVE".equals(item.status())) {
            throw new BusinessException(
                    "KNOWLEDGE_ITEM_INACTIVE", "知识对象不可读取");
        }
        boolean wiki = "WIKI".equals(item.itemType());
        KnowledgePageVersionSnapshot snapshot = wiki
                ? wikiPageVersionCache.get(
                        item.workspaceId(), item.itemId(), item.latestVersionId())
                        .orElseGet(() -> loadVersionSnapshot(item, true))
                : loadVersionSnapshot(item, false);
        citationReadGate.requireReadable(item.workspaceId(), snapshot.versionId());
        return new KnowledgeItemDetailResponse(
                item.itemId(),
                item.itemType(),
                item.pageKind(),
                item.title(),
                item.status(),
                snapshot.versionId(),
                snapshot.versionNo(),
                snapshot.content(),
                snapshot.summary(),
                snapshot.sourceMessageId(),
                snapshot.citations(),
                wiki ? listOutgoingLinks(item.workspaceId(), item.itemId()) : List.of(),
                wiki ? listBacklinks(item.workspaceId(), item.itemId()) : List.of(),
                snapshot.createdAt(),
                item.updatedAt());
    }

    private ItemState loadItemState(String itemId) {
        return jdbcTemplate.query("""
                select id, workspace_id, item_type, coalesce(page_kind, '') as page_kind,
                       title, status, latest_version_id, updated_at
                from knowledge_item
                where id = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException(
                        "KNOWLEDGE_ITEM_NOT_FOUND", "知识对象不存在");
            }
            return new ItemState(
                    rs.getString("id"),
                    rs.getString("workspace_id"),
                    rs.getString("item_type"),
                    rs.getString("page_kind"),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getString("latest_version_id"),
                    toInstant(rs.getTimestamp("updated_at")));
        }, itemId);
    }

    private KnowledgePageVersionSnapshot loadVersionSnapshot(
            ItemState item,
            boolean cacheable
    ) {
        long startedAt = System.nanoTime();
        try {
            KnowledgePageVersionSnapshot snapshot = jdbcTemplate.query("""
                    select id, version_no, content, coalesce(summary, '') as summary,
                           source_message_id, created_at
                    from knowledge_version
                    where id = ? and item_id = ?
                    """, rs -> {
                if (!rs.next()) {
                    throw new BusinessException(
                            "KNOWLEDGE_VERSION_NOT_FOUND", "知识版本不存在");
                }
                return new KnowledgePageVersionSnapshot(
                        item.itemId(),
                        rs.getString("id"),
                        rs.getInt("version_no"),
                        rs.getString("content"),
                        rs.getString("summary"),
                        rs.getString("source_message_id"),
                        citationsForVersion(rs.getString("id")),
                        toInstant(rs.getTimestamp("created_at")));
            }, item.latestVersionId(), item.itemId());
            recordWikiPageLoad("success", startedAt);
            if (cacheable) {
                wikiPageVersionCache.put(item.workspaceId(), snapshot);
            }
            return snapshot;
        } catch (RuntimeException exception) {
            recordWikiPageLoad("error", startedAt);
            throw exception;
        }
    }

    private void recordWikiPageLoad(String result, long startedAt) {
        meterRegistry.timer("noteweave.knowledge.wiki.page.db.load", "result", result)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
    }

    public List<String> findSourceBackedWikiItemIds(
            String workspaceId,
            String sourceId
    ) {
        return jdbcTemplate.queryForList("""
                select i.id
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                join knowledge_version_citation kvc on kvc.knowledge_version_id = v.id
                join citation c on c.id = kvc.citation_id
                where i.workspace_id = ?
                  and i.item_type = 'WIKI'
                  and i.status = 'ACTIVE'
                group by i.id
                having sum(case when c.source_id = ? then 1 else 0 end) > 0
                   and sum(case when c.source_id <> ? then 1 else 0 end) = 0
                """, String.class, workspaceId, sourceId, sourceId);
    }

    public List<KnowledgeItemResponse> searchWikiPages(String workspaceId, String query) {
        return readableWikiRows(workspaceId, wikiSearchEngine.search(workspaceId, query)).stream()
                .map(WikiSearchRow::toItemResponse)
                .toList();
    }

    public List<KnowledgePageHit> findRelevantWikiPages(String workspaceId, String query) {
        List<KnowledgePageHit> pages = wikiSearchEngine.findRelevantPages(workspaceId, query);
        Set<String> readable = citationReadGate.readableVersionIds(workspaceId,
                pages.stream().map(KnowledgePageHit::versionId).toList());
        return pages.stream().filter(page -> readable.contains(page.versionId()))
                .limit(5).toList();
    }

    @Override
    public List<WikiPageContext> findRelevantWikiPageContexts(String workspaceId, String query) {
        return findRelevantWikiPages(workspaceId, query).stream()
                .map(page -> new WikiPageContext(
                        page,
                        listOutgoingLinks(workspaceId, page.itemId()).stream().limit(5).toList(),
                        listBacklinks(workspaceId, page.itemId()).stream().limit(5).toList(),
                        citationsForVersion(page.versionId()).stream().limit(5).toList()
                ))
                .toList();
    }

    @Override
    public List<String> citationIdsForWikiPages(String workspaceId, List<KnowledgePageHit> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }
        List<String> versionIds = pages.stream().map(KnowledgePageHit::versionId).distinct().toList();
        if (citationReadGate.readableVersionIds(workspaceId, versionIds).size() != versionIds.size()) {
            throw new BusinessException("KNOWLEDGE_SOURCE_REVOKED",
                    "Wiki 引用的资料或版本已撤销", HttpStatus.CONFLICT);
        }
        String placeholders = String.join(",", versionIds.stream().map(v -> "?").toList());
        return jdbcTemplate.queryForList("""
                select citation_id from knowledge_version_citation
                where knowledge_version_id in (%s)
                order by sort_order asc
                """.formatted(placeholders), String.class, versionIds.toArray());
    }

    private List<WikiSearchRow> readableWikiRows(String workspaceId, List<WikiSearchRow> rows) {
        Set<String> readable = citationReadGate.readableVersionIds(workspaceId,
                rows.stream().map(WikiSearchRow::versionId).toList());
        return rows.stream().filter(row -> readable.contains(row.versionId())).toList();
    }

    private List<KnowledgeCitationResponse> citationsForVersion(String versionId) {
        return jdbcTemplate.query("""
                select c.id, c.source_id, c.title, c.quote_text, c.page_no, c.location_info,
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

    private List<WikiLinkResponse> listOutgoingLinks(String workspaceId, String itemId) {
        List<WikiLinkResponse> links = jdbcTemplate.query("""
                select source_item_id, target_item_id, target_title, relation_type, relation_status, mention_count
                from knowledge_item_link
                where source_item_id = ?
                order by relation_status asc, mention_count desc, updated_at desc
                """, (rs, rowNum) -> new WikiLinkResponse(
                rs.getString("source_item_id"),
                rs.getString("target_item_id"),
                rs.getString("target_title"),
                rs.getString("relation_type"),
                rs.getString("relation_status"),
                rs.getInt("mention_count")
        ), itemId);
        return readableLinks(workspaceId, links);
    }

    private List<WikiLinkResponse> listBacklinks(String workspaceId, String itemId) {
        List<WikiLinkResponse> links = jdbcTemplate.query("""
                select l.source_item_id, l.target_item_id, coalesce(s.title, l.target_title) as source_title,
                       l.relation_type, l.relation_status, l.mention_count
                from knowledge_item_link l
                left join knowledge_item s on s.id = l.source_item_id
                where l.target_item_id = ?
                order by l.mention_count desc, l.updated_at desc
                """, (rs, rowNum) -> new WikiLinkResponse(
                rs.getString("source_item_id"),
                rs.getString("target_item_id"),
                rs.getString("source_title"),
                rs.getString("relation_type"),
                rs.getString("relation_status"),
                rs.getInt("mention_count")
        ), itemId);
        return readableLinks(workspaceId, links);
    }

    private List<WikiLinkResponse> readableLinks(String workspaceId, List<WikiLinkResponse> links) {
        List<String> itemIds = links.stream()
                .flatMap(link -> java.util.stream.Stream.of(link.sourceItemId(), link.targetItemId()))
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        Set<String> readable = citationReadGate.readableWikiItemIds(workspaceId, itemIds);
        return links.stream().filter(link -> readable.contains(link.sourceItemId())
                        && (link.targetItemId() == null || readable.contains(link.targetItemId())))
                .toList();
    }

    private boolean wikiTaskReadable(String workspaceId, WikiTaskSummaryResponse task) {
        String targetType = task.targetType() == null ? "" : task.targetType();
        if ("SOURCE".equalsIgnoreCase(targetType) && !sourceReadable(workspaceId, task.targetId())) {
            return false;
        }
        List<String> pageIds = new java.util.ArrayList<>();
        if ("WIKI".equalsIgnoreCase(targetType)) pageIds.add(task.targetId());
        pageIds.addAll(task.relatedPages().stream().map(WikiTaskRelatedPageResponse::itemId).toList());
        return citationReadGate.readableWikiItemIds(workspaceId, pageIds).size()
                == pageIds.stream().filter(id -> id != null && !id.isBlank()).distinct().count();
    }

    private boolean sourceReadable(String workspaceId, String sourceId) {
        if (sourceId == null || sourceId.isBlank()) return false;
        List<RecentSource> rows = jdbcTemplate.query("""
                select id, title, status, index_status, updated_at,
                       coalesce(generated_by, ''), coalesce(generated_ref_id, '')
                from source where workspace_id = ? and id = ? and status <> 'DELETED'
                """, (rs, rowNum) -> new RecentSource(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                toInstant(rs.getTimestamp(5)), rs.getString(6), rs.getString(7)),
                workspaceId, sourceId);
        return rows.size() == 1 && generatedSourceGate.visible(workspaceId,
                rows.get(0).generatedBy(), rows.get(0).generatedRefId());
    }

    private int count(String sql, String workspaceId) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, workspaceId);
        return value == null ? 0 : value;
    }

    private void requireWorkspace(String workspaceId) {
        if (!workspaceQueryPort.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
    }

    private Instant toInstant(Timestamp value) {
        return value == null ? Instant.now() : value.toInstant();
    }

    private record ItemState(
            String itemId,
            String workspaceId,
            String itemType,
            String pageKind,
            String title,
            String status,
            String latestVersionId,
            Instant updatedAt
    ) {
    }

    private record RecentSource(String id, String title, String status, String indexStatus,
                                Instant updatedAt, String generatedBy, String generatedRefId) {}
}

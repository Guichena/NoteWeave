package com.noteweave.knowledge;

import com.noteweave.common.BusinessException;
import com.noteweave.workspace.WorkspaceQueryPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeGovernanceService {

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceQueryPort workspaceQueryPort;
    private final KnowledgeWikiSearchEngine wikiSearchEngine;
    private final KnowledgeVersionService knowledgeVersionService;
    private final KnowledgeWikiMutationService wikiMutationService;
    private final KnowledgeCommandService knowledgeCommandService;
    private final KnowledgeCitationReadGate citationReadGate;

    public KnowledgeGovernanceService(
            JdbcTemplate jdbcTemplate,
            WorkspaceQueryPort workspaceQueryPort,
            KnowledgeWikiSearchEngine wikiSearchEngine,
            KnowledgeVersionService knowledgeVersionService,
            KnowledgeWikiMutationService wikiMutationService,
            KnowledgeCommandService knowledgeCommandService,
            KnowledgeCitationReadGate citationReadGate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceQueryPort = workspaceQueryPort;
        this.wikiSearchEngine = wikiSearchEngine;
        this.knowledgeVersionService = knowledgeVersionService;
        this.wikiMutationService = wikiMutationService;
        this.knowledgeCommandService = knowledgeCommandService;
        this.citationReadGate = citationReadGate;
    }

    public WikiStatsResponse getWikiStats(String workspaceId) {
        return assembleWikiStats(
                workspaceId, workspaceQueryPort.isWikiEnabled(workspaceId));
    }

    WikiStatsResponse getWikiStatsForInternalExecution(String workspaceId) {
        Integer enabledWorkspaceCount = jdbcTemplate.queryForObject("""
                select count(*) from workspace
                where id = ? and status = 'ACTIVE' and wiki_enabled = true
                """, Integer.class, workspaceId);
        return assembleWikiStats(
                workspaceId,
                enabledWorkspaceCount != null && enabledWorkspaceCount > 0);
    }

    private WikiStatsResponse assembleWikiStats(
            String workspaceId,
            boolean wikiEnabled
    ) {
        List<WikiSearchRow> allPages = wikiSearchEngine.loadRows(workspaceId);
        Set<String> readableVersions = citationReadGate.readableVersionIds(workspaceId,
                allPages.stream().map(WikiSearchRow::versionId).toList());
        List<WikiSearchRow> pages = allPages.stream()
                .filter(row -> readableVersions.contains(row.versionId())).toList();
        Set<String> readablePageIds = pages.stream().map(WikiSearchRow::itemId)
                .collect(java.util.stream.Collectors.toSet());
        List<WikiIssueResponse> issues = lintWiki(workspaceId).stream()
                .filter(issue -> readablePageIds.contains(issue.itemId())).toList();
        int pageCount = pages.size();
        List<String[]> links = jdbcTemplate.query("""
                select source_item_id, target_item_id, relation_status
                from knowledge_item_link where workspace_id = ?
                """, (rs, rowNum) -> new String[] {
                rs.getString(1), rs.getString(2), rs.getString(3)
        }, workspaceId).stream().filter(link -> readablePageIds.contains(link[0])
                && (link[1] == null || readablePageIds.contains(link[1]))).toList();
        int linkCount = links.size();
        int unresolvedLinkCount = (int) links.stream()
                .filter(link -> "UNRESOLVED".equals(link[2])).count();
        int resolvedLinkCount = (int) links.stream()
                .filter(link -> "RESOLVED".equals(link[2])).count();
        int citationCount = pages.stream().mapToInt(WikiSearchRow::citationCount).sum();
        Map<String, Integer> pagesByKind = new HashMap<>();
        pages.forEach(page -> pagesByKind.merge(
                page.pageKind() == null ? "TOPIC" : page.pageKind(), 1, Integer::sum));
        List<KnowledgeItemResponse> recentUpdates = pages.stream()
                .sorted(Comparator.comparing(WikiSearchRow::updatedAt).reversed())
                .limit(5)
                .map(WikiSearchRow::toItemResponse)
                .toList();
        List<WikiTaskSummaryResponse> recentTasks = listRecentWikiTasks(workspaceId);
        int pendingTaskCount = count("""
                select count(*)
                from task
                where workspace_id = ?
                  and task_type in ('WIKI_INGEST', 'WIKI_RETRACT')
                  and task_status in ('PENDING', 'RUNNING')
                """, workspaceId);
        int autoFixableIssueCount = (int) issues.stream()
                .filter(WikiIssueResponse::autoFixable)
                .count();
        int manualReviewIssueCount = issues.size() - autoFixableIssueCount;
        return new WikiStatsResponse(
                workspaceId,
                pageCount,
                linkCount,
                resolvedLinkCount,
                unresolvedLinkCount,
                citationCount,
                issues.size(),
                autoFixableIssueCount,
                manualReviewIssueCount,
                pagesByKind,
                recentUpdates,
                recentTasks,
                pendingTaskCount,
                wikiEnabled);
    }

    public WikiRebuildAdviceResponse getWikiRebuildAdvice(String workspaceId) {
        requireWorkspace(workspaceId);
        boolean wikiEnabled = workspaceQueryPort.isWikiEnabled(workspaceId);
        int readySourceCount = count("""
                select count(*) from source
                where workspace_id = ? and status = 'READY'
                """, workspaceId);
        int activeWikiPageCount = count("""
                select count(*) from knowledge_item
                where workspace_id = ? and item_type = 'WIKI' and status = 'ACTIVE'
                """, workspaceId);
        String message;
        String recommendedAction;
        String recommendedIssueType = "";
        List<WikiIssueResponse> issues = lintWiki(workspaceId);
        long staleCount = issueCount(issues, "CONTENT_STALE");
        long brokenLinkCount = issueCount(issues, "BROKEN_LINK");
        long placeholderCount = issueCount(issues, "PLACEHOLDER_CONTENT");
        long missingSourceCount = issueCount(issues, "MISSING_SOURCE");
        long orphanPageCount = issueCount(issues, "ORPHAN_PAGE");
        if (!wikiEnabled && readySourceCount > 0) {
            if (staleCount > 0) {
                message = "当前工作台已有 " + readySourceCount
                        + " 份可用资料，且存在 " + staleCount
                        + " 张页面落后于资料；建议先开启 Wiki 构建，再让系统自动回补和刷新页面网络。";
                recommendedIssueType = "CONTENT_STALE";
            } else {
                message = "当前工作台已有 " + readySourceCount
                        + " 份可用资料，建议先开启 Wiki 构建，让系统自动生成和维护页面网络。";
            }
            recommendedAction = "ENABLE_WIKI";
        } else if (wikiEnabled && activeWikiPageCount == 0 && readySourceCount > 0) {
            message = "Wiki 构建已开启，但当前还没有稳定页面；可以先按当前资料重建 Wiki，或继续上传资料触发自动 ingest。";
            recommendedAction = "REBUILD_WIKI";
        } else if (wikiEnabled && staleCount > 0) {
            message = "检测到 " + staleCount
                    + " 张 Wiki 页面落后于来源资料，建议先按当前资料重建 Wiki，让页面版本追上资料最新状态。";
            recommendedAction = "REBUILD_WIKI";
            recommendedIssueType = "CONTENT_STALE";
        } else if (brokenLinkCount > 0) {
            message = "检测到 " + brokenLinkCount
                    + " 条断链，建议先执行 Auto Fix 创建补缺页并重建链接，再继续人工修正。";
            recommendedAction = "AUTO_FIX_WIKI";
            recommendedIssueType = "BROKEN_LINK";
        } else if (placeholderCount > 0) {
            message = "检测到 " + placeholderCount
                    + " 张占位补缺页仍待人工补正文，建议进入人工修补区继续完善内容、来源和页面关系。";
            recommendedAction = "FOCUS_MANUAL_REPAIR";
            recommendedIssueType = "PLACEHOLDER_CONTENT";
        } else if (missingSourceCount > 0) {
            message = "检测到 " + missingSourceCount
                    + " 张页面缺少来源引用，建议进入人工修正模式补齐证据和引用。";
            recommendedAction = "FOCUS_MANUAL_REPAIR";
            recommendedIssueType = "MISSING_SOURCE";
        } else if (orphanPageCount > 0) {
            message = "检测到 " + orphanPageCount
                    + " 张页面仍然孤立在 Wiki 网络之外，建议进入人工修正模式补齐页面关系。";
            recommendedAction = "FOCUS_MANUAL_REPAIR";
            recommendedIssueType = "ORPHAN_PAGE";
        } else if (wikiEnabled) {
            message = "当前工作台已进入 Wiki 持续演化状态，后续资料上传、重解析和删除都会同步刷新页面网络。";
            recommendedAction = "OPEN_WIKI_HOME";
        } else {
            message = "当前工作台尚未开启 Wiki 构建；如需长期知识网络，可在准备好资料后再开启。";
            recommendedAction = "OPEN_WIKI_HOME";
        }
        String focusIssueType = recommendedIssueType;
        WikiIssueResponse focusIssue = focusIssueType.isBlank()
                ? null
                : issues.stream()
                        .filter(issue -> focusIssueType.equals(issue.issueType()))
                        .findFirst()
                        .orElse(null);
        return new WikiRebuildAdviceResponse(
                !wikiEnabled && readySourceCount > 0,
                readySourceCount,
                activeWikiPageCount,
                message,
                recommendedAction,
                recommendedIssueType,
                focusIssue == null || focusIssue.itemId() == null
                        ? "" : focusIssue.itemId(),
                focusIssue == null || focusIssue.title() == null
                        ? "" : focusIssue.title());
    }

    public List<WikiIssueResponse> lintWiki(String workspaceId) {
        List<WikiIssueResponse> issues = new ArrayList<>();
        issues.addAll(jdbcTemplate.query("""
                select source_item_id, target_title
                from knowledge_item_link
                where workspace_id = ? and relation_status = 'UNRESOLVED'
                order by updated_at desc
                """, (rs, rowNum) -> new WikiIssueResponse(
                "BROKEN_LINK", "HIGH",
                rs.getString("source_item_id"), rs.getString("target_title"),
                "页面引用了尚不存在的 Wiki 页面：" + rs.getString("target_title"),
                "创建缺失页面或修改链接标题", true, "PREFILL_MISSING_PAGE"
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
                "ORPHAN_PAGE", "MEDIUM", rs.getString("id"), rs.getString("title"),
                "页面没有出链或入链，可能没有进入 Wiki 网络。",
                "补充页面链接或在相关页面中引用它",
                false, "FOCUS_MANUAL_REPAIR"
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
                "MISSING_SOURCE", "MEDIUM", rs.getString("id"), rs.getString("title"),
                "页面缺少来源引用。", "补充来源引用或从资料 ingest 重新生成",
                false, "FOCUS_MANUAL_REPAIR"
        ), workspaceId));
        issues.addAll(jdbcTemplate.query("""
                select i.id, i.title
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                join knowledge_version_citation kvc on kvc.knowledge_version_id = v.id
                join citation c on c.id = kvc.citation_id
                join source s on s.id = c.source_id
                where i.workspace_id = ? and i.item_type = 'WIKI' and i.status = 'ACTIVE'
                group by i.id, i.title, v.created_at
                having max(s.updated_at) > v.created_at
                """, (rs, rowNum) -> new WikiIssueResponse(
                "CONTENT_STALE", "MEDIUM", rs.getString("id"), rs.getString("title"),
                "页面绑定的来源资料已经更新，当前 Wiki 正文可能落后于资料最新状态。",
                "按当前资料重建 Wiki，或进入修正模式追加新版本",
                false, "REBUILD_WIKI"
        ), workspaceId));
        issues.addAll(jdbcTemplate.query("""
                select i.id, i.title
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.workspace_id = ? and i.item_type = 'WIKI' and i.status = 'ACTIVE'
                  and (
                      v.content like '%## 待补充%'
                      or v.content like '%需要人工补充内容和来源%'
                  )
                """, (rs, rowNum) -> new WikiIssueResponse(
                "PLACEHOLDER_CONTENT", "MEDIUM",
                rs.getString("id"), rs.getString("title"),
                "页面目前仍是 Auto Fix 生成的占位补缺页，需要人工补正文、来源和页面关系。",
                "进入修正模式，补齐正文、来源和关联页面",
                false, "FOCUS_MANUAL_REPAIR"
        ), workspaceId));
        return sortWikiIssues(issues);
    }

    public List<WikiIssueResponse> listWikiIssues(
            String workspaceId,
            String issueType,
            String severity,
            Boolean autoFixable,
            String itemId
    ) {
        String normalizedIssueType = normalizeFilter(issueType);
        String normalizedSeverity = normalizeFilter(severity);
        String normalizedItemId = itemId == null ? "" : itemId.trim();
        return lintWiki(workspaceId).stream()
                .filter(issue -> normalizedIssueType.isEmpty()
                        || issue.issueType().equalsIgnoreCase(normalizedIssueType))
                .filter(issue -> normalizedSeverity.isEmpty()
                        || issue.severity().equalsIgnoreCase(normalizedSeverity))
                .filter(issue -> autoFixable == null
                        || issue.autoFixable() == autoFixable.booleanValue())
                .filter(issue -> normalizedItemId.isEmpty()
                        || normalizedItemId.equals(issue.itemId()))
                .toList();
    }

    public List<WikiLogEntryResponse> listWikiLog(String workspaceId, String itemId) {
        String normalizedItemId = itemId == null ? "" : itemId.trim();
        String sql = normalizedItemId.isBlank()
                ? """
                select id, item_id, event_type, message, created_at
                from wiki_log_entry
                where workspace_id = ?
                order by created_at desc, id desc
                limit 80
                """
                : """
                select id, item_id, event_type, message, created_at
                from wiki_log_entry
                where workspace_id = ? and item_id = ?
                order by created_at desc, id desc
                limit 40
                """;
        Object[] params = normalizedItemId.isBlank()
                ? new Object[] { workspaceId }
                : new Object[] { workspaceId, normalizedItemId };
        return jdbcTemplate.query(sql, (rs, rowNum) -> new WikiLogEntryResponse(
                rs.getString("id"),
                rs.getString("item_id"),
                rs.getString("event_type"),
                rs.getString("message"),
                toInstant(rs.getTimestamp("created_at"))
        ), params);
    }

    public List<WikiTaskSummaryResponse> listRecentWikiTasks(String workspaceId) {
        return jdbcTemplate.query("""
                select id, task_type, task_status, progress_phase, progress_message,
                       target_type, target_id, updated_at
                from task
                where workspace_id = ?
                  and task_type in ('WIKI_INGEST', 'WIKI_RETRACT')
                order by updated_at desc, id desc
                limit 6
                """, (rs, rowNum) -> {
            String targetType = rs.getString("target_type");
            String targetId = rs.getString("target_id");
            return new WikiTaskSummaryResponse(
                    rs.getString("id"),
                    rs.getString("task_type"),
                    rs.getString("task_status"),
                    rs.getString("progress_phase"),
                    rs.getString("progress_message"),
                    targetType,
                    targetId,
                    resolveWikiTaskTargetTitle(workspaceId, targetType, targetId),
                    loadWikiTaskRelatedPages(workspaceId, targetType, targetId),
                    toInstant(rs.getTimestamp("updated_at")));
        }, workspaceId);
    }

    @Transactional
    public WikiStatsResponse rebuildWikiLinks(String workspaceId) {
        List<WikiPageSnapshot> pages = loadWikiPageSnapshots(workspaceId);
        jdbcTemplate.update(
                "delete from knowledge_item_link where workspace_id = ?", workspaceId);
        for (WikiPageSnapshot page : pages) {
            String normalizedContent = wikiMutationService.prepareContent(
                    workspaceId, page.title(), page.content()).content();
            if (!normalizedContent.equals(page.content())) {
                knowledgeVersionService.appendVersion(
                        page.itemId(),
                        new AppendKnowledgeVersionRequest(
                                normalizedContent,
                                null,
                                knowledgeVersionService.citationIdsForVersion(
                                        page.versionId())));
                continue;
            }
            wikiMutationService.upsertWikiLinks(
                    workspaceId, page.itemId(), page.title(), normalizedContent);
        }
        wikiMutationService.logWiki(
                workspaceId,
                null,
                "REBUILD_LINKS",
                "已重建 Wiki 页面链接关系，并刷新轻量 Auto Link 正文互链");
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
            if (wikiMutationService.findWikiItemIdByTitle(workspaceId, title) == null) {
                knowledgeCommandService.createItemWithVersion(
                        workspaceId,
                        "WIKI",
                        title,
                        "# " + title
                                + "\n\n## 待补充\n\n"
                                + "该页面由 Wiki auto-fix 根据断链自动创建，需要人工补充内容和来源。\n",
                        null,
                        List.of());
                created++;
            }
        }
        WikiStatsResponse stats = rebuildWikiLinks(workspaceId);
        wikiMutationService.logWiki(
                workspaceId,
                null,
                "AUTO_FIX",
                "已自动创建缺失页面 " + created + " 个，并重建链接");
        return new WikiAutoFixResponse(
                workspaceId, created, stats.linkCount(), stats.issueCount());
    }

    List<WikiTaskRelatedPageResponse> relatedWikiPagesForSource(
            String workspaceId,
            String sourceId,
            int limit
    ) {
        List<String> itemIds = jdbcTemplate.queryForList("""
                select i.id
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                join knowledge_version_citation kvc on kvc.knowledge_version_id = v.id
                join citation c on c.id = kvc.citation_id
                where i.workspace_id = ?
                  and i.item_type = 'WIKI'
                  and i.status = 'ACTIVE'
                  and c.source_id = ?
                group by i.id
                order by max(i.updated_at) desc
                limit ?
                """, String.class, workspaceId, sourceId, limit);
        if (itemIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(
                ",", itemIds.stream().map(ignored -> "?").toList());
        List<Object> params = new ArrayList<>();
        params.add(workspaceId);
        params.addAll(itemIds);
        return jdbcTemplate.query("""
                select id, title, coalesce(page_kind, 'TOPIC') as page_kind
                from knowledge_item
                where workspace_id = ?
                  and id in (%s)
                  and item_type = 'WIKI'
                  and status = 'ACTIVE'
                order by updated_at desc, title asc
                limit 3
                """.formatted(placeholders), (rs, rowNum) ->
                new WikiTaskRelatedPageResponse(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getString("page_kind")), params.toArray());
    }

    private String resolveWikiTaskTargetTitle(
            String workspaceId,
            String targetType,
            String targetId
    ) {
        String normalizedTargetType = targetType == null
                ? "" : targetType.trim().toUpperCase(Locale.ROOT);
        String normalizedTargetId = targetId == null ? "" : targetId.trim();
        if (normalizedTargetId.isBlank()) {
            return "当前工作台";
        }
        if ("SOURCE".equals(normalizedTargetType)) {
            String sourceTitle = jdbcTemplate.query("""
                    select title from source
                    where workspace_id = ? and id = ?
                    """, rs -> rs.next() ? rs.getString("title") : null,
                    workspaceId, normalizedTargetId);
            return sourceTitle == null || sourceTitle.isBlank()
                    ? normalizedTargetId : sourceTitle;
        }
        if ("WIKI".equals(normalizedTargetType)) {
            String pageTitle = jdbcTemplate.query("""
                    select title from knowledge_item
                    where workspace_id = ? and id = ?
                    """, rs -> rs.next() ? rs.getString("title") : null,
                    workspaceId, normalizedTargetId);
            return pageTitle == null || pageTitle.isBlank()
                    ? normalizedTargetId : pageTitle;
        }
        return normalizedTargetId;
    }

    private List<WikiTaskRelatedPageResponse> loadWikiTaskRelatedPages(
            String workspaceId,
            String targetType,
            String targetId
    ) {
        String normalizedTargetType = targetType == null
                ? "" : targetType.trim().toUpperCase(Locale.ROOT);
        String normalizedTargetId = targetId == null ? "" : targetId.trim();
        if (normalizedTargetId.isBlank()) {
            return List.of();
        }
        if ("WIKI".equals(normalizedTargetType)) {
            return jdbcTemplate.query("""
                    select id, title, coalesce(page_kind, 'TOPIC') as page_kind
                    from knowledge_item
                    where workspace_id = ? and id = ?
                      and item_type = 'WIKI' and status = 'ACTIVE'
                    """, (rs, rowNum) -> new WikiTaskRelatedPageResponse(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("page_kind")
            ), workspaceId, normalizedTargetId);
        }
        if (!"SOURCE".equals(normalizedTargetType)) {
            return List.of();
        }
        return relatedWikiPagesForSource(workspaceId, normalizedTargetId, 3);
    }

    private List<WikiPageSnapshot> loadWikiPageSnapshots(String workspaceId) {
        return jdbcTemplate.query("""
                select i.id, i.title, v.id as version_id, v.content
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.workspace_id = ? and i.item_type = 'WIKI'
                  and i.status = 'ACTIVE'
                """, (rs, rowNum) -> new WikiPageSnapshot(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("version_id"),
                rs.getString("content")
        ), workspaceId);
    }

    private long issueCount(List<WikiIssueResponse> issues, String issueType) {
        return issues.stream()
                .filter(issue -> issueType.equals(issue.issueType()))
                .count();
    }

    private int count(String sql, String workspaceId) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, workspaceId);
        return value == null ? 0 : value;
    }

    private List<WikiIssueResponse> sortWikiIssues(List<WikiIssueResponse> issues) {
        return issues.stream()
                .sorted(Comparator
                        .comparingInt((WikiIssueResponse issue) ->
                                severityRank(issue.severity()))
                        .thenComparing(issue -> issue.autoFixable() ? 0 : 1)
                        .thenComparing(issue -> issue.issueType() == null
                                ? "" : issue.issueType())
                        .thenComparing(issue -> issue.title() == null
                                ? "" : issue.title()))
                .toList();
    }

    private int severityRank(String severity) {
        if (severity == null) {
            return 9;
        }
        return switch (severity.trim().toUpperCase(Locale.ROOT)) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            case "LOW" -> 2;
            default -> 9;
        };
    }

    private String normalizeFilter(String value) {
        return value == null ? "" : value.trim();
    }

    private void requireWorkspace(String workspaceId) {
        if (!workspaceQueryPort.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
    }

    private Instant toInstant(Timestamp value) {
        return value == null ? Instant.now() : value.toInstant();
    }

    private record WikiPageSnapshot(
            String itemId,
            String title,
            String versionId,
            String content
    ) {
    }
}

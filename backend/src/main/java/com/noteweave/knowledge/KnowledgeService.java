package com.noteweave.knowledge;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.workspace.WorkspaceService;
import java.util.ArrayDeque;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        if ("WIKI".equals(request.itemType())) {
            String existingItemId = findWikiItemIdByTitle(workspaceId, request.title());
            if (existingItemId != null) {
                return appendVersion(existingItemId, new AppendKnowledgeVersionRequest(request.content(), request.sourceMessageId(), citationIds));
            }
        }
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
        String normalizedContent = "WIKI".equals(item.itemType()) ? normalizeWikiContent(item.workspaceId(), item.title(), content) : content;
        List<String> citationIds = citationIdsFromRequest(item.workspaceId(), request.sourceMessageId(), request.citationIds());
        Integer currentVersion = jdbcTemplate.queryForObject("""
                select coalesce(max(version_no), 0) from knowledge_version where item_id = ?
                """, Integer.class, itemId);
        int nextVersionNo = (currentVersion == null ? 0 : currentVersion) + 1;
        String versionId = Ids.newId();
        String summary = summarize(normalizedContent);
        String pageKind = "WIKI".equals(item.itemType()) ? inferWikiPageKind(item.title(), normalizedContent) : null;
        jdbcTemplate.update("""
                insert into knowledge_version(id, item_id, version_no, content, summary, source_message_id)
                values (?, ?, ?, ?, ?, ?)
                """, versionId, itemId, nextVersionNo, normalizedContent, summary, request.sourceMessageId());
        bindVersionCitations(versionId, citationIds);
        jdbcTemplate.update("""
                update knowledge_item
                set latest_version_id = ?, page_kind = ?, updated_at = current_timestamp
                where id = ?
                """, versionId, pageKind, itemId);
        if ("WIKI".equals(item.itemType())) {
            jdbcTemplate.update("delete from knowledge_item_link where source_item_id = ?", itemId);
            upsertWikiLinks(item.workspaceId(), itemId, item.title(), normalizedContent);
            logWiki(item.workspaceId(), itemId, "UPDATE_PAGE", "追加 Wiki 页面版本：" + item.title());
        }
        return new KnowledgeItemResponse(itemId, item.itemType(), pageKind, item.title(), "ACTIVE", versionId, nextVersionNo, summary, Instant.now(), 0, 0, citationIds.size(), 0);
    }

    @Transactional
    public KnowledgeItemResponse renameItem(String itemId, RenameKnowledgeItemRequest request) {
        KnowledgeItemRef item = loadKnowledgeItem(itemId);
        String nextTitle = request.title().trim();
        if (nextTitle.isBlank()) {
            throw new BusinessException("KNOWLEDGE_TITLE_REQUIRED", "知识标题不能为空");
        }
        String pageKind = "WIKI".equals(item.itemType())
                ? inferWikiPageKind(nextTitle, getItemDetail(itemId).content())
                : null;
        jdbcTemplate.update("""
                update knowledge_item
                set title = ?, page_kind = ?, updated_at = current_timestamp
                where id = ?
                """, nextTitle, pageKind, itemId);
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

    public WikiIndexResponse getWikiIndex(String workspaceId) {
        if (!workspaceService.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
        WikiStatsResponse stats = getWikiStats(workspaceId);
        int readySourceCount = count("""
                select count(*)
                from source
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
                      select 1
                      from knowledge_version_citation kvc
                      where kvc.knowledge_version_id = v.id
                  )
                """, workspaceId);
        int manualPageCount = Math.max(0, stats.pageCount() - sourceBackedPageCount);
        List<WikiIndexSourceResponse> recentSources = jdbcTemplate.query("""
                select id, title, status, index_status, updated_at
                from source
                where workspace_id = ? and status <> 'DELETED'
                order by updated_at desc, id desc
                limit 5
                """, (rs, rowNum) -> {
            List<WikiTaskRelatedPageResponse> relatedPages = loadRelatedWikiPagesForSource(workspaceId, rs.getString("id"), 3);
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
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getString("index_status"),
                    relatedPages,
                    recommendedAction,
                    focusItemId,
                    focusTitle,
                    toInstant(rs.getTimestamp("updated_at"))
            );
        }, workspaceId);
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
                stats.recentUpdates(),
                stats.recentTasks(),
                recentSources,
                lintWiki(workspaceId).stream().limit(5).toList()
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
        return loadWikiSearchRows(workspaceId).stream()
                .map(row -> new ScoredWikiRow(row, scoreWikiRow(row, terms)))
                .filter(row -> row.score() > 0 || terms.isEmpty())
                .sorted(Comparator.comparingInt(ScoredWikiRow::score).reversed()
                        .thenComparing(Comparator.comparingInt((ScoredWikiRow row) -> row.row().citationCount()).reversed())
                        .thenComparing(Comparator.comparingInt((ScoredWikiRow row) -> row.row().backlinkCount()).reversed())
                        .thenComparing(Comparator.comparing((ScoredWikiRow row) -> row.row().updatedAt()).reversed()))
                .map(row -> row.row().toItemResponse())
                .toList();
    }

    public WikiGraphResponse getWikiGraph(
            String workspaceId,
            String mode,
            String centerItemId,
            int depth,
            int limit,
            List<String> pageKinds
    ) {
        if (!workspaceService.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
        List<WikiSearchRow> rows = loadWikiSearchRows(workspaceId);
        List<WikiGraphNode> allNodes = rows.stream().map(this::toGraphNode).toList();
        List<WikiGraphEdge> allEdges = loadWikiGraphEdges(workspaceId);
        String normalizedMode = "ego".equalsIgnoreCase(mode) ? "ego" : "overview";
        int normalizedDepth = Math.max(1, Math.min(depth, 3));
        int normalizedLimit = Math.max(4, Math.min(limit, 120));
        Set<String> normalizedKinds = normalizeGraphKinds(pageKinds);
        List<WikiGraphNode> filteredNodes = filterGraphNodes(allNodes, normalizedMode, centerItemId, normalizedKinds);
        Set<String> filteredNodeIds = filteredNodes.stream()
                .map(WikiGraphNode::itemId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<WikiGraphEdge> filteredEdges = allEdges.stream()
                .filter(edge -> filteredNodeIds.contains(edge.sourceItemId())
                        && (edge.targetItemId() == null || filteredNodeIds.contains(edge.targetItemId())))
                .toList();

        List<String> selectedIds = "ego".equals(normalizedMode)
                ? selectEgoGraphNodeIds(filteredNodes, filteredEdges, centerItemId, normalizedDepth, normalizedLimit)
                : selectOverviewGraphNodeIds(filteredNodes, normalizedLimit);

        if (selectedIds.isEmpty() && !filteredNodes.isEmpty()) {
            selectedIds = selectOverviewGraphNodeIds(filteredNodes, normalizedLimit);
            normalizedMode = "overview";
            centerItemId = "";
            normalizedDepth = 1;
        }

        Set<String> selectedIdSet = new LinkedHashSet<>(selectedIds);
        List<WikiGraphNode> selectedNodes = selectedIds.stream()
                .map(id -> filteredNodes.stream().filter(node -> node.itemId().equals(id)).findFirst().orElse(null))
                .filter(node -> node != null)
                .toList();
        List<WikiGraphEdge> selectedEdges = filteredEdges.stream()
                .filter(edge -> selectedIdSet.contains(edge.sourceItemId())
                        && (edge.targetItemId() == null || selectedIdSet.contains(edge.targetItemId())))
                .toList();
        boolean truncated = selectedNodes.size() < filteredNodes.size();
        return new WikiGraphResponse(
                workspaceId,
                selectedNodes,
                selectedEdges,
                new WikiGraphMetaResponse(
                        normalizedMode,
                        centerItemId == null ? "" : centerItemId,
                        normalizedDepth,
                        filteredNodes.size(),
                        selectedNodes.size(),
                        truncated
                )
        );
    }

    public WikiStatsResponse getWikiStats(String workspaceId) {
        List<WikiIssueResponse> issues = lintWiki(workspaceId);
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
        Map<String, Integer> pagesByKind = new HashMap<>();
        jdbcTemplate.query("""
                select coalesce(page_kind, 'TOPIC') as page_kind, count(*) as total
                from knowledge_item
                where workspace_id = ? and item_type = 'WIKI' and status = 'ACTIVE'
                group by page_kind
                """, (rs, rowNum) -> {
            pagesByKind.put(rs.getString("page_kind"), rs.getInt("total"));
            return null;
        }, workspaceId);
        if (pagesByKind.isEmpty() && pageCount > 0) {
            pagesByKind.put("TOPIC", pageCount);
        }
        List<KnowledgeItemResponse> recentUpdates = listItems(workspaceId, "WIKI").stream().limit(5).toList();
        List<WikiTaskSummaryResponse> recentTasks = listRecentWikiTasks(workspaceId);
        int pendingTaskCount = count("""
                select count(*)
                from task
                where workspace_id = ?
                  and task_type in ('WIKI_INGEST', 'WIKI_RETRACT')
                  and task_status <> 'COMPLETED'
                """, workspaceId);
        int autoFixableIssueCount = (int) issues.stream().filter(WikiIssueResponse::autoFixable).count();
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
                workspaceService.isWikiEnabled(workspaceId)
        );
    }

    public WikiRebuildAdviceResponse getWikiRebuildAdvice(String workspaceId) {
        if (!workspaceService.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
        boolean wikiEnabled = workspaceService.isWikiEnabled(workspaceId);
        int readySourceCount = count("""
                select count(*)
                from source
                where workspace_id = ? and status = 'READY'
                """, workspaceId);
        int activeWikiPageCount = count("""
                select count(*)
                from knowledge_item
                where workspace_id = ? and item_type = 'WIKI' and status = 'ACTIVE'
                """, workspaceId);
        String message;
        String recommendedAction;
        String recommendedIssueType = "";
        List<WikiIssueResponse> issues = lintWiki(workspaceId);
        long staleCount = issues.stream().filter(issue -> "CONTENT_STALE".equals(issue.issueType())).count();
        long brokenLinkCount = issues.stream().filter(issue -> "BROKEN_LINK".equals(issue.issueType())).count();
        long placeholderCount = issues.stream().filter(issue -> "PLACEHOLDER_CONTENT".equals(issue.issueType())).count();
        long missingSourceCount = issues.stream().filter(issue -> "MISSING_SOURCE".equals(issue.issueType())).count();
        long orphanPageCount = issues.stream().filter(issue -> "ORPHAN_PAGE".equals(issue.issueType())).count();
        if (!wikiEnabled && readySourceCount > 0) {
            if (staleCount > 0) {
                message = "当前工作台已有 " + readySourceCount + " 份可用资料，且存在 " + staleCount + " 张页面落后于资料；建议先开启 Wiki 构建，再让系统自动回补和刷新页面网络。";
                recommendedIssueType = "CONTENT_STALE";
            } else {
                message = "当前工作台已有 " + readySourceCount + " 份可用资料，建议先开启 Wiki 构建，让系统自动生成和维护页面网络。";
            }
            recommendedAction = "ENABLE_WIKI";
        } else if (wikiEnabled && activeWikiPageCount == 0 && readySourceCount > 0) {
            message = "Wiki 构建已开启，但当前还没有稳定页面；可以先按当前资料重建 Wiki，或继续上传资料触发自动 ingest。";
            recommendedAction = "REBUILD_WIKI";
        } else if (wikiEnabled && staleCount > 0) {
            message = "检测到 " + staleCount + " 张 Wiki 页面落后于来源资料，建议先按当前资料重建 Wiki，让页面版本追上资料最新状态。";
            recommendedAction = "REBUILD_WIKI";
            recommendedIssueType = "CONTENT_STALE";
        } else if (brokenLinkCount > 0) {
            message = "检测到 " + brokenLinkCount + " 条断链，建议先执行 Auto Fix 创建补缺页并重建链接，再继续人工修正。";
            recommendedAction = "AUTO_FIX_WIKI";
            recommendedIssueType = "BROKEN_LINK";
        } else if (placeholderCount > 0) {
            message = "检测到 " + placeholderCount + " 张占位补缺页仍待人工补正文，建议进入人工修补区继续完善内容、来源和页面关系。";
            recommendedAction = "FOCUS_MANUAL_REPAIR";
            recommendedIssueType = "PLACEHOLDER_CONTENT";
        } else if (missingSourceCount > 0) {
            message = "检测到 " + missingSourceCount + " 张页面缺少来源引用，建议进入人工修正模式补齐证据和引用。";
            recommendedAction = "FOCUS_MANUAL_REPAIR";
            recommendedIssueType = "MISSING_SOURCE";
        } else if (orphanPageCount > 0) {
            message = "检测到 " + orphanPageCount + " 张页面仍然孤立在 Wiki 网络之外，建议进入人工修正模式补齐页面关系。";
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
                focusIssue == null || focusIssue.itemId() == null ? "" : focusIssue.itemId(),
                focusIssue == null || focusIssue.title() == null ? "" : focusIssue.title()
        );
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
                "创建缺失页面或修改链接标题",
                true,
                "PREFILL_MISSING_PAGE"
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
                "补充页面链接或在相关页面中引用它",
                false,
                "FOCUS_MANUAL_REPAIR"
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
                "补充来源引用或从资料 ingest 重新生成",
                false,
                "FOCUS_MANUAL_REPAIR"
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
                "CONTENT_STALE",
                "MEDIUM",
                rs.getString("id"),
                rs.getString("title"),
                "页面绑定的来源资料已经更新，当前 Wiki 正文可能落后于资料最新状态。",
                "按当前资料重建 Wiki，或进入修正模式追加新版本",
                false,
                "REBUILD_WIKI"
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
                "PLACEHOLDER_CONTENT",
                "MEDIUM",
                rs.getString("id"),
                rs.getString("title"),
                "页面目前仍是 Auto Fix 生成的占位补缺页，需要人工补正文、来源和页面关系。",
                "进入修正模式，补齐正文、来源和关联页面",
                false,
                "FOCUS_MANUAL_REPAIR"
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
                .filter(issue -> normalizedIssueType.isEmpty() || issue.issueType().equalsIgnoreCase(normalizedIssueType))
                .filter(issue -> normalizedSeverity.isEmpty() || issue.severity().equalsIgnoreCase(normalizedSeverity))
                .filter(issue -> autoFixable == null || issue.autoFixable() == autoFixable.booleanValue())
                .filter(issue -> normalizedItemId.isEmpty() || normalizedItemId.equals(issue.itemId()))
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
                select id, task_type, task_status, progress_phase, progress_message, target_type, target_id, updated_at
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
                    toInstant(rs.getTimestamp("updated_at"))
            );
        }, workspaceId);
    }

    @Transactional
    public WikiStatsResponse rebuildWikiLinks(String workspaceId) {
        List<WikiPageSnapshot> pages = loadWikiPageSnapshots(workspaceId);
        jdbcTemplate.update("delete from knowledge_item_link where workspace_id = ?", workspaceId);
        for (WikiPageSnapshot page : pages) {
            String normalizedContent = normalizeWikiContent(workspaceId, page.title(), page.content());
            if (!normalizedContent.equals(page.content())) {
                appendVersion(page.itemId(), new AppendKnowledgeVersionRequest(
                        normalizedContent,
                        null,
                        citationIdsForVersionId(page.versionId())
                ));
                continue;
            }
            upsertWikiLinks(workspaceId, page.itemId(), page.title(), normalizedContent);
        }
        logWiki(workspaceId, null, "REBUILD_LINKS", "已重建 Wiki 页面链接关系，并刷新轻量 Auto Link 正文互链");
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
        if ("WIKI".equalsIgnoreCase(itemType)) {
            return loadWikiSearchRows(workspaceId).stream()
                    .sorted(Comparator.comparing(WikiSearchRow::updatedAt).reversed())
                    .map(row -> row.toItemResponse())
                    .toList();
        }
        return jdbcTemplate.query("""
                select i.id, i.item_type, coalesce(i.page_kind, '') as page_kind, i.title, i.status, i.latest_version_id, i.updated_at,
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
                0,
                0,
                0,
                0
        ), workspaceId, itemType);
    }

    private KnowledgeItemResponse itemResponse(String itemId) {
        return jdbcTemplate.query("""
                select i.id, i.item_type, coalesce(i.page_kind, '') as page_kind, i.title, i.status, i.latest_version_id, i.updated_at,
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
                    rs.getString("page_kind"),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getString("latest_version_id"),
                    rs.getInt("version_no"),
                    rs.getString("summary"),
                    toInstant(rs.getTimestamp("updated_at")),
                    0,
                    0,
                    0,
                    0
            );
        }, itemId);
    }

    public KnowledgeItemDetailResponse getItemDetail(String itemId) {
        return jdbcTemplate.query("""
                select i.id, i.item_type, coalesce(i.page_kind, '') as page_kind, i.title, i.status, i.latest_version_id, i.updated_at,
                       v.id as version_id, v.version_no, v.content, coalesce(v.summary, '') as summary,
                       v.source_message_id, v.created_at
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
                    rs.getString("page_kind"),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getString("latest_version_id"),
                    rs.getInt("version_no"),
                    rs.getString("content"),
                    rs.getString("summary"),
                    rs.getString("source_message_id"),
                    citationsForVersion(versionId),
                    "WIKI".equals(rs.getString("item_type")) ? listOutgoingLinks(rs.getString("id")) : List.of(),
                    "WIKI".equals(rs.getString("item_type")) ? listBacklinks(rs.getString("id")) : List.of(),
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("updated_at"))
            );
        }, itemId);
    }

    public List<KnowledgeVersionSummaryResponse> listItemVersions(String itemId) {
        KnowledgeItemRef item = loadKnowledgeItem(itemId);
        return jdbcTemplate.query("""
                select v.id, v.version_no, coalesce(v.summary, '') as summary, v.source_message_id, v.created_at,
                       count(kvc.id) as citation_count
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
    }

    public KnowledgeVersionDetailResponse getItemVersionDetail(String itemId, int versionNo) {
        KnowledgeItemRef item = loadKnowledgeItem(itemId);
        return jdbcTemplate.query("""
                select v.id, v.item_id, v.version_no, v.content, coalesce(v.summary, '') as summary,
                       v.source_message_id, v.created_at
                from knowledge_version v
                where v.item_id = ? and v.version_no = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("KNOWLEDGE_VERSION_NOT_FOUND", "知识版本不存在");
            }
            String versionId = rs.getString("id");
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

    public List<KnowledgePageHit> findRelevantWikiPages(String workspaceId, String query) {
        Set<String> terms = extractTerms(query);
        List<ScoredWikiRow> scored = loadWikiSearchRows(workspaceId).stream()
                .map(row -> new ScoredWikiRow(row, scoreWikiRow(row, terms)))
                .filter(row -> row.score() > 0 || terms.isEmpty())
                .sorted(Comparator.comparingInt(ScoredWikiRow::score).reversed()
                        .thenComparing(Comparator.comparingInt((ScoredWikiRow row) -> row.row().citationCount()).reversed())
                        .thenComparing(Comparator.comparingInt((ScoredWikiRow row) -> row.row().backlinkCount()).reversed())
                        .thenComparing(Comparator.comparing((ScoredWikiRow row) -> row.row().updatedAt()).reversed()))
                .limit(5)
                .toList();
        if (!scored.isEmpty()) {
            return scored.stream().map(row -> row.row().toPageHit(row.score())).toList();
        }
        return loadWikiSearchRows(workspaceId).stream()
                .limit(5)
                .map(row -> row.toPageHit(0))
                .toList();
    }

    @Transactional
    public KnowledgeItemResponse upsertWikiPage(String workspaceId, String title, String content, List<String> citationIds) {
        if (!workspaceService.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
        String existingItemId = findWikiItemIdByTitle(workspaceId, title);
        if (existingItemId == null) {
            return createItemWithVersion(workspaceId, "WIKI", title, content, null, citationIds == null ? List.of() : citationIds);
        }
        return appendVersion(existingItemId, new AppendKnowledgeVersionRequest(content, null, citationIds == null ? List.of() : citationIds));
    }

    public List<WikiPageContext> findRelevantWikiPageContexts(String workspaceId, String query) {
        return findRelevantWikiPages(workspaceId, query).stream()
                .map(page -> new WikiPageContext(
                        page,
                        listOutgoingLinks(page.itemId()).stream().limit(5).toList(),
                        listBacklinks(page.itemId()).stream().limit(5).toList(),
                        citationsForVersion(page.versionId()).stream().limit(5).toList()
                ))
                .toList();
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
        String normalizedContent = "WIKI".equals(itemType) ? normalizeWikiContent(workspaceId, title, content) : content;
        String itemId = Ids.newId();
        String versionId = Ids.newId();
        String summary = summarize(normalizedContent);
        String pageKind = "WIKI".equals(itemType) ? inferWikiPageKind(title, normalizedContent) : null;
        jdbcTemplate.update("""
                insert into knowledge_item(id, workspace_id, item_type, page_kind, title, status, latest_version_id)
                values (?, ?, ?, ?, ?, 'ACTIVE', ?)
                """, itemId, workspaceId, itemType, pageKind, title, versionId);
        jdbcTemplate.update("""
                insert into knowledge_version(id, item_id, version_no, content, summary, source_message_id)
                values (?, ?, 1, ?, ?, ?)
                """, versionId, itemId, normalizedContent, summary, sourceMessageId);
        bindVersionCitations(versionId, citationIds);
        if ("WIKI".equals(itemType)) {
            upsertWikiLinks(workspaceId, itemId, title, normalizedContent);
            resolveIncomingLinksForCreatedWikiPage(workspaceId, itemId, title);
            logWiki(workspaceId, itemId, "CREATE_PAGE", "创建 Wiki 页面：" + title);
        }
        return new KnowledgeItemResponse(itemId, itemType, pageKind, title, "ACTIVE", versionId, 1, summary, Instant.now(), 0, 0, citationIds.size(), 0);
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

    public List<String> findSourceBackedWikiItemIds(String workspaceId, String sourceId) {
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

    private String resolveWikiTaskTargetTitle(String workspaceId, String targetType, String targetId) {
        String normalizedTargetType = targetType == null ? "" : targetType.trim().toUpperCase(Locale.ROOT);
        String normalizedTargetId = targetId == null ? "" : targetId.trim();
        if (normalizedTargetId.isBlank()) {
            return "当前工作台";
        }
        if ("SOURCE".equals(normalizedTargetType)) {
            String sourceTitle = jdbcTemplate.query("""
                    select title
                    from source
                    where workspace_id = ? and id = ?
                    """, rs -> rs.next() ? rs.getString("title") : null, workspaceId, normalizedTargetId);
            return sourceTitle == null || sourceTitle.isBlank() ? normalizedTargetId : sourceTitle;
        }
        if ("WIKI".equals(normalizedTargetType)) {
            String pageTitle = jdbcTemplate.query("""
                    select title
                    from knowledge_item
                    where workspace_id = ? and id = ?
                    """, rs -> rs.next() ? rs.getString("title") : null, workspaceId, normalizedTargetId);
            return pageTitle == null || pageTitle.isBlank() ? normalizedTargetId : pageTitle;
        }
        return normalizedTargetId;
    }

    private List<WikiTaskRelatedPageResponse> loadWikiTaskRelatedPages(String workspaceId, String targetType, String targetId) {
        String normalizedTargetType = targetType == null ? "" : targetType.trim().toUpperCase(Locale.ROOT);
        String normalizedTargetId = targetId == null ? "" : targetId.trim();
        if (normalizedTargetId.isBlank()) {
            return List.of();
        }
        if ("WIKI".equals(normalizedTargetType)) {
            return jdbcTemplate.query("""
                    select id, title, coalesce(page_kind, 'TOPIC') as page_kind
                    from knowledge_item
                    where workspace_id = ? and id = ? and item_type = 'WIKI' and status = 'ACTIVE'
                    """, (rs, rowNum) -> new WikiTaskRelatedPageResponse(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("page_kind")
            ), workspaceId, normalizedTargetId);
        }
        if (!"SOURCE".equals(normalizedTargetType)) {
            return List.of();
        }
        return loadRelatedWikiPagesForSource(workspaceId, normalizedTargetId, 3);
    }

    private List<WikiTaskRelatedPageResponse> loadRelatedWikiPagesForSource(String workspaceId, String sourceId, int limit) {
        List<String> itemIds = jdbcTemplate.queryForList("""
                select distinct i.id
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                join knowledge_version_citation kvc on kvc.knowledge_version_id = v.id
                join citation c on c.id = kvc.citation_id
                where i.workspace_id = ?
                  and i.item_type = 'WIKI'
                  and i.status = 'ACTIVE'
                  and c.source_id = ?
                order by i.updated_at desc
                limit ?
                """, String.class, workspaceId, sourceId, limit);
        if (itemIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", itemIds.stream().map(ignored -> "?").toList());
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
                """.formatted(placeholders), (rs, rowNum) -> new WikiTaskRelatedPageResponse(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("page_kind")
        ), params.toArray());
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

    private List<String> citationIdsForVersionId(String versionId) {
        return jdbcTemplate.queryForList("""
                select citation_id
                from knowledge_version_citation
                where knowledge_version_id = ?
                order by sort_order asc
                """, String.class, versionId);
    }

    private WikiGraphNode toGraphNode(WikiSearchRow row) {
        int degree = row.outgoingCount() + row.backlinkCount();
        return new WikiGraphNode(
                row.itemId(),
                row.title(),
                row.pageKind(),
                row.versionNo(),
                degree,
                row.outgoingCount(),
                row.backlinkCount(),
                row.citationCount(),
                row.unresolvedCount()
        );
    }

    private List<WikiGraphEdge> loadWikiGraphEdges(String workspaceId) {
        return jdbcTemplate.query("""
                select l.source_item_id,
                       coalesce(s.title, l.source_item_id) as source_title,
                       l.target_item_id,
                       l.target_title,
                       l.relation_type,
                       l.relation_status,
                       l.mention_count
                from knowledge_item_link l
                left join knowledge_item s on s.id = l.source_item_id
                where l.workspace_id = ?
                order by l.mention_count desc, l.updated_at desc
                """, (rs, rowNum) -> new WikiGraphEdge(
                rs.getString("source_item_id"),
                rs.getString("source_title"),
                rs.getString("target_item_id"),
                rs.getString("target_title"),
                rs.getString("relation_type"),
                rs.getString("relation_status"),
                rs.getInt("mention_count")
        ), workspaceId);
    }

    private Set<String> normalizeGraphKinds(List<String> pageKinds) {
        if (pageKinds == null || pageKinds.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String pageKind : pageKinds) {
            if (pageKind == null) {
                continue;
            }
            String value = pageKind.trim().toUpperCase(Locale.ROOT);
            if (!value.isBlank()) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    private List<WikiGraphNode> filterGraphNodes(
            List<WikiGraphNode> allNodes,
            String mode,
            String centerItemId,
            Set<String> normalizedKinds
    ) {
        if (normalizedKinds.isEmpty()) {
            return allNodes;
        }
        LinkedHashSet<String> allowedIds = new LinkedHashSet<>();
        for (WikiGraphNode node : allNodes) {
            if (normalizedKinds.contains(normalizePageKind(node.pageKind()))) {
                allowedIds.add(node.itemId());
            }
        }
        if ("ego".equals(mode) && centerItemId != null && !centerItemId.isBlank()) {
            allowedIds.add(centerItemId);
        }
        return allNodes.stream()
                .filter(node -> allowedIds.contains(node.itemId()))
                .toList();
    }

    private List<String> selectOverviewGraphNodeIds(List<WikiGraphNode> nodes, int limit) {
        return nodes.stream()
                .sorted(Comparator.comparingInt(WikiGraphNode::degree).reversed()
                        .thenComparingInt(WikiGraphNode::citationCount).reversed()
                        .thenComparingInt(WikiGraphNode::versionNo).reversed()
                        .thenComparing(WikiGraphNode::title))
                .limit(limit)
                .map(WikiGraphNode::itemId)
                .toList();
    }

    private List<String> selectEgoGraphNodeIds(
            List<WikiGraphNode> nodes,
            List<WikiGraphEdge> edges,
            String centerItemId,
            int depth,
            int limit
    ) {
        if (centerItemId == null || centerItemId.isBlank()) {
            return List.of();
        }
        Map<String, WikiGraphNode> nodeById = new HashMap<>();
        for (WikiGraphNode node : nodes) {
            nodeById.put(node.itemId(), node);
        }
        if (!nodeById.containsKey(centerItemId)) {
            return List.of();
        }
        Map<String, List<String>> adjacency = new HashMap<>();
        for (WikiGraphEdge edge : edges) {
            adjacency.computeIfAbsent(edge.sourceItemId(), ignored -> new ArrayList<>());
            if (edge.targetItemId() != null && !edge.targetItemId().isBlank()) {
                adjacency.computeIfAbsent(edge.sourceItemId(), ignored -> new ArrayList<>()).add(edge.targetItemId());
                adjacency.computeIfAbsent(edge.targetItemId(), ignored -> new ArrayList<>()).add(edge.sourceItemId());
            }
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        ArrayDeque<GraphHop> queue = new ArrayDeque<>();
        selected.add(centerItemId);
        queue.add(new GraphHop(centerItemId, 0));
        while (!queue.isEmpty()) {
            GraphHop hop = queue.poll();
            if (hop.depth() >= depth) {
                continue;
            }
            List<String> neighbors = adjacency.getOrDefault(hop.itemId(), List.of()).stream()
                    .distinct()
                    .sorted(Comparator.comparingInt((String itemId) -> nodeById.getOrDefault(itemId, nodeById.get(centerItemId)).degree()).reversed()
                            .thenComparing(itemId -> nodeById.get(itemId).title()))
                    .toList();
            for (String neighborId : neighbors) {
                if (selected.size() >= limit) {
                    return new ArrayList<>(selected);
                }
                if (selected.add(neighborId)) {
                    queue.add(new GraphHop(neighborId, hop.depth() + 1));
                }
            }
        }
        return new ArrayList<>(selected);
    }

    private List<WikiLinkResponse> listOutgoingLinks(String itemId) {
        return jdbcTemplate.query("""
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
    }

    private List<WikiLinkResponse> listBacklinks(String itemId) {
        return jdbcTemplate.query("""
                select l.source_item_id, l.target_item_id, coalesce(s.title, l.target_title) as source_title,
                       l.relation_type, l.relation_status, l.mention_count
                from knowledge_item_link l
                left join knowledge_item s on s.id = l.source_item_id
                where l.target_item_id = ?
                order by l.mention_count desc, l.updated_at desc
                """, (rs, rowNum) -> {
            return new WikiLinkResponse(
                    rs.getString("source_item_id"),
                    rs.getString("target_item_id"),
                    rs.getString("source_title"),
                    rs.getString("relation_type"),
                    rs.getString("relation_status"),
                    rs.getInt("mention_count")
            );
        }, itemId);
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
        Map<String, LinkDraft> linkDrafts = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : extractWikiLinks(content).entrySet()) {
            linkDrafts.put(entry.getKey(), new LinkDraft(entry.getKey(), entry.getValue(), "WIKI_LINK"));
        }
        for (Map.Entry<String, Integer> entry : inferWikiTitleMentions(workspaceId, sourceTitle, content, linkDrafts.keySet()).entrySet()) {
            LinkDraft existing = findLinkDraft(linkDrafts, entry.getKey());
            if (existing == null) {
                linkDrafts.put(entry.getKey(), new LinkDraft(entry.getKey(), entry.getValue(), "AUTO_LINK"));
                continue;
            }
            String relationType = "WIKI_LINK".equals(existing.relationType()) ? "HYBRID_LINK" : existing.relationType();
            linkDrafts.put(existing.title(), new LinkDraft(existing.title(), existing.mentionCount() + entry.getValue(), relationType));
        }
        for (LinkDraft draft : linkDrafts.values()) {
            String targetTitle = draft.title();
            if (targetTitle.equalsIgnoreCase(sourceTitle)) {
                continue;
            }
            String targetItemId = findWikiItemIdByTitle(workspaceId, targetTitle);
            jdbcTemplate.update("""
                    insert into knowledge_item_link(id, workspace_id, source_item_id, target_item_id, target_title, relation_type, relation_status, mention_count)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), workspaceId, sourceItemId, targetItemId, targetTitle,
                    draft.relationType(),
                    targetItemId == null ? "UNRESOLVED" : "RESOLVED",
                    draft.mentionCount());
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
        rewriteIncomingWikiPageContentAfterTitleChange(workspaceId, itemId, oldTitle, nextTitle);
        jdbcTemplate.update("delete from knowledge_item_link where source_item_id = ?", itemId);
        KnowledgeItemDetailResponse detail = getItemDetail(itemId);
        upsertWikiLinks(workspaceId, itemId, nextTitle, detail.content());
    }

    private void resolveIncomingLinksForCreatedWikiPage(String workspaceId, String itemId, String title) {
        jdbcTemplate.update("""
                update knowledge_item_link
                set target_item_id = ?, target_title = ?, relation_status = 'RESOLVED', updated_at = current_timestamp
                where workspace_id = ?
                  and relation_status = 'UNRESOLVED'
                  and lower(target_title) = lower(?)
                """, itemId, title, workspaceId, title);
    }

    private LinkDraft findLinkDraft(Map<String, LinkDraft> linkDrafts, String title) {
        return linkDrafts.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(title))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private void rewriteIncomingWikiPageContentAfterTitleChange(
            String workspaceId,
            String renamedItemId,
            String oldTitle,
            String nextTitle
    ) {
        if (oldTitle == null || oldTitle.isBlank() || nextTitle == null || nextTitle.isBlank()) {
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
            String rewritten = rewriteExplicitWikiLinkTitle(page.content(), oldTitle, nextTitle);
            if (rewritten.equals(page.content())) {
                continue;
            }
            appendVersion(page.itemId(), new AppendKnowledgeVersionRequest(rewritten, null, citationIdsForVersionId(page.versionId())));
            logWiki(workspaceId, page.itemId(), "REFRESH_LINK_CONTENT",
                    "页面重命名后已刷新引用页正文：" + page.title() + " -> " + nextTitle);
        }
    }

    private Map<String, Integer> extractWikiLinks(String content) {
        if (content == null || content.isBlank()) {
            return Map.of();
        }
        Map<String, Integer> links = new HashMap<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[\\[([^\\]]{1,120})]]").matcher(content);
        while (matcher.find()) {
            String raw = matcher.group(1).trim();
            String title = raw.contains("|") ? raw.substring(0, raw.indexOf('|')).trim() : raw;
            if (!title.isBlank()) {
                String canonical = links.keySet().stream()
                        .filter(existing -> existing.equalsIgnoreCase(title))
                        .findFirst()
                        .orElse(title);
                links.merge(canonical, 1, Integer::sum);
            }
        }
        return links;
    }

    private String rewriteExplicitWikiLinkTitle(String content, String oldTitle, String nextTitle) {
        if (content == null || content.isBlank()) {
            return content == null ? "" : content;
        }
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[\\[([^\\]]{1,120})]]");
        java.util.regex.Matcher matcher = pattern.matcher(content);
        StringBuffer buffer = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String raw = matcher.group(1).trim();
            String targetTitle = raw.contains("|") ? raw.substring(0, raw.indexOf('|')).trim() : raw;
            if (!targetTitle.equalsIgnoreCase(oldTitle)) {
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String alias = raw.contains("|") ? raw.substring(raw.indexOf('|') + 1).trim() : "";
            String replacement = alias.isBlank() ? "[[" + nextTitle + "]]" : "[[" + nextTitle + "|" + alias + "]]";
            matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(replacement));
            changed = true;
        }
        matcher.appendTail(buffer);
        return changed ? buffer.toString() : content;
    }

    private Map<String, Integer> inferWikiTitleMentions(
            String workspaceId,
            String sourceTitle,
            String content,
            Set<String> existingTitles
    ) {
        if (content == null || content.isBlank()) {
            return Map.of();
        }
        String plainContent = content.replaceAll("\\[\\[[^\\]]+]]", " ");
        String lowerContent = plainContent.toLowerCase(Locale.ROOT);
        Map<String, Integer> inferred = new HashMap<>();
        jdbcTemplate.query("""
                select title, coalesce(page_kind, 'TOPIC') as page_kind
                from knowledge_item
                where workspace_id = ? and item_type = 'WIKI' and status = 'ACTIVE'
                """, rs -> {
            while (rs.next()) {
                String candidateTitle = rs.getString("title");
                String pageKind = rs.getString("page_kind");
                if (candidateTitle == null || candidateTitle.isBlank()) {
                    continue;
                }
                if (candidateTitle.equalsIgnoreCase(sourceTitle)) {
                    continue;
                }
                if ("OVERVIEW".equalsIgnoreCase(pageKind) && !"Wiki Index".equalsIgnoreCase(candidateTitle)) {
                    continue;
                }
                if (!isGoodAutoLinkCandidate(candidateTitle)) {
                    continue;
                }
                int count = countOccurrences(lowerContent, candidateTitle.toLowerCase(Locale.ROOT));
                if (count > 0) {
                    inferred.put(candidateTitle, count);
                }
            }
            return null;
        }, workspaceId);
        return inferred;
    }

    private boolean isGoodAutoLinkCandidate(String title) {
        String normalized = title.trim();
        if (normalized.length() < 2) {
            return false;
        }
        String compact = normalized.replaceAll("\\s+", "");
        if (compact.length() < 2) {
            return false;
        }
        if (compact.length() <= 3 && compact.chars().allMatch(ch -> ch < 128 && Character.isLetterOrDigit(ch))) {
            return false;
        }
        return true;
    }

    private String normalizeWikiContent(String workspaceId, String sourceTitle, String content) {
        if (content == null || content.isBlank()) {
            return content == null ? "" : content;
        }
        String normalized = content;
        for (String candidateTitle : loadAutoLinkCandidateTitles(workspaceId, sourceTitle)) {
            normalized = injectAutoLink(normalized, candidateTitle);
        }
        return normalized;
    }

    private List<String> loadAutoLinkCandidateTitles(String workspaceId, String sourceTitle) {
        return jdbcTemplate.query("""
                select title, coalesce(page_kind, 'TOPIC') as page_kind
                from knowledge_item
                where workspace_id = ? and item_type = 'WIKI' and status = 'ACTIVE'
                """, rs -> {
            List<String> titles = new ArrayList<>();
            while (rs.next()) {
                String candidateTitle = rs.getString("title");
                String pageKind = rs.getString("page_kind");
                if (candidateTitle == null || candidateTitle.isBlank()) {
                    continue;
                }
                if (candidateTitle.equalsIgnoreCase(sourceTitle)) {
                    continue;
                }
                if ("OVERVIEW".equalsIgnoreCase(pageKind) && !"Wiki Index".equalsIgnoreCase(candidateTitle)) {
                    continue;
                }
                if (!isGoodAutoLinkCandidate(candidateTitle)) {
                    continue;
                }
                titles.add(candidateTitle);
            }
            return titles.stream()
                    .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareToIgnoreCase))
                    .toList();
        }, workspaceId);
    }

    private String injectAutoLink(String content, String candidateTitle) {
        if (content == null || content.isBlank() || candidateTitle == null || candidateTitle.isBlank()) {
            return content == null ? "" : content;
        }
        if (extractWikiLinks(content).keySet().stream().anyMatch(title -> title.equalsIgnoreCase(candidateTitle))) {
            return content;
        }
        List<TextSpan> forbidden = computeForbiddenSpans(content);
        int matchAt = findFirstSafeMention(content, candidateTitle, forbidden);
        if (matchAt < 0) {
            return content;
        }
        int matchEnd = matchAt + candidateTitle.length();
        return content.substring(0, matchAt) + "[[" + content.substring(matchAt, matchEnd) + "]]" + content.substring(matchEnd);
    }

    private int findFirstSafeMention(String content, String candidateTitle, List<TextSpan> forbidden) {
        String lowerContent = content.toLowerCase(Locale.ROOT);
        String lowerCandidate = candidateTitle.toLowerCase(Locale.ROOT);
        int from = 0;
        while (from < lowerContent.length()) {
            int next = lowerContent.indexOf(lowerCandidate, from);
            if (next < 0) {
                return -1;
            }
            int end = next + candidateTitle.length();
            if (!overlapsForbidden(forbidden, next, end) && (!needsAsciiBoundary(candidateTitle) || hasAsciiBoundary(content, next, end))) {
                return next;
            }
            from = next + 1;
        }
        return -1;
    }

    private boolean overlapsForbidden(List<TextSpan> spans, int start, int end) {
        for (TextSpan span : spans) {
            if (start < span.end() && end > span.start()) {
                return true;
            }
        }
        return false;
    }

    private boolean needsAsciiBoundary(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        char first = text.charAt(0);
        char last = text.charAt(text.length() - 1);
        return isAsciiWord(first) || isAsciiWord(last);
    }

    private boolean hasAsciiBoundary(String content, int start, int end) {
        if (start > 0 && isAsciiWord(content.charAt(start - 1))) {
            return false;
        }
        if (end < content.length() && isAsciiWord(content.charAt(end))) {
            return false;
        }
        return true;
    }

    private boolean isAsciiWord(char ch) {
        return ch < 128 && (Character.isLetterOrDigit(ch) || ch == '_');
    }

    private List<TextSpan> computeForbiddenSpans(String content) {
        List<TextSpan> spans = new ArrayList<>();
        int index = 0;
        while (index < content.length()) {
            if (content.startsWith("```", index) || content.startsWith("~~~", index)) {
                String fence = content.substring(index, index + 3);
                int end = content.indexOf(fence, index + 3);
                if (end < 0) {
                    spans.add(new TextSpan(index, content.length()));
                    break;
                }
                spans.add(new TextSpan(index, Math.min(content.length(), end + 3)));
                index = end + 3;
                continue;
            }
            if (content.startsWith("[[", index)) {
                int end = content.indexOf("]]", index + 2);
                if (end < 0) {
                    spans.add(new TextSpan(index, content.length()));
                    break;
                }
                spans.add(new TextSpan(index, Math.min(content.length(), end + 2)));
                index = end + 2;
                continue;
            }
            if (content.charAt(index) == '`') {
                int end = content.indexOf('`', index + 1);
                if (end < 0) {
                    spans.add(new TextSpan(index, content.length()));
                    break;
                }
                spans.add(new TextSpan(index, end + 1));
                index = end + 1;
                continue;
            }
            int markdownStart = content.startsWith("![", index) ? index + 1 : index;
            if (content.charAt(index) == '[' || content.startsWith("![", index)) {
                int labelEnd = content.indexOf(']', markdownStart + 1);
                if (labelEnd > markdownStart && labelEnd + 1 < content.length() && content.charAt(labelEnd + 1) == '(') {
                    int linkEnd = content.indexOf(')', labelEnd + 2);
                    if (linkEnd > labelEnd) {
                        spans.add(new TextSpan(index, linkEnd + 1));
                        index = linkEnd + 1;
                        continue;
                    }
                }
            }
            index++;
        }
        return spans;
    }

    private int countOccurrences(String content, String keyword) {
        if (content.isBlank() || keyword.isBlank()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (from >= 0 && from < content.length()) {
            int next = content.indexOf(keyword, from);
            if (next < 0) {
                break;
            }
            count++;
            from = next + keyword.length();
        }
        return count;
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
                select i.id, i.title, v.id as version_id, v.content
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.workspace_id = ? and i.item_type = 'WIKI' and i.status = 'ACTIVE'
                """, (rs, rowNum) -> new WikiPageSnapshot(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("version_id"),
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

    private List<WikiIssueResponse> sortWikiIssues(List<WikiIssueResponse> issues) {
        return issues.stream()
                .sorted(Comparator.comparingInt((WikiIssueResponse issue) -> severityRank(issue.severity()))
                        .thenComparing((WikiIssueResponse issue) -> issue.autoFixable() ? 0 : 1)
                        .thenComparing(issue -> issue.issueType() == null ? "" : issue.issueType())
                        .thenComparing(issue -> issue.title() == null ? "" : issue.title()))
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

    private String inferWikiPageKind(String title, String content) {
        String normalized = ((title == null ? "" : title) + "\n" + (content == null ? "" : content)).toLowerCase(Locale.ROOT);
        if (normalized.contains("对比") || normalized.contains("比较") || normalized.contains("vs")) {
            return "COMPARISON";
        }
        if (normalized.contains("总览") || normalized.contains("概览") || normalized.contains("overview") || normalized.contains("索引")) {
            return "OVERVIEW";
        }
        if (normalized.contains("概念") || normalized.contains("定义") || normalized.contains("原理") || normalized.contains("concept")) {
            return "CONCEPT";
        }
        return "TOPIC";
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

    private List<WikiSearchRow> loadWikiSearchRows(String workspaceId) {
        return jdbcTemplate.query("""
                select i.id,
                       i.title,
                       coalesce(i.page_kind, 'TOPIC') as page_kind,
                       v.id as version_id,
                       v.version_no,
                       v.content,
                       coalesce(v.summary, '') as summary,
                       i.updated_at,
                       (select count(*) from knowledge_item_link l where l.source_item_id = i.id) as outgoing_count,
                       (select count(*) from knowledge_item_link l where l.target_item_id = i.id) as backlink_count,
                       (select count(*) from knowledge_version_citation c where c.knowledge_version_id = v.id) as citation_count,
                       (select count(*) from knowledge_item_link l where l.source_item_id = i.id and l.relation_status = 'UNRESOLVED') as unresolved_count
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.workspace_id = ? and i.item_type = 'WIKI' and i.status = 'ACTIVE'
                order by i.updated_at desc
                limit 120
                """, (rs, rowNum) -> new WikiSearchRow(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("page_kind"),
                rs.getString("version_id"),
                rs.getInt("version_no"),
                rs.getString("content"),
                rs.getString("summary"),
                toInstant(rs.getTimestamp("updated_at")),
                rs.getInt("outgoing_count"),
                rs.getInt("backlink_count"),
                rs.getInt("citation_count"),
                rs.getInt("unresolved_count")
        ), workspaceId);
    }

    private int scoreWikiRow(WikiSearchRow row, Set<String> terms) {
        int titleScore = score(row.title(), terms) * 6;
        int summaryScore = score(row.summary(), terms) * 3;
        int contentScore = score(row.content(), terms) * 2;
        if (!terms.isEmpty() && containsAllTerms(row.title(), terms)) {
            titleScore += 24;
        }
        if (!terms.isEmpty() && containsAllTerms(row.summary(), terms)) {
            summaryScore += 6;
        }
        if (!terms.isEmpty() && titleScore + summaryScore + contentScore == 0) {
            return 0;
        }
        int graphScore = Math.min(10, row.outgoingCount() + row.backlinkCount());
        int citationScore = Math.min(8, row.citationCount() * 2);
        int versionScore = Math.min(4, row.versionNo());
        int pageKindBonus = switch ((row.pageKind() == null ? "" : row.pageKind()).toUpperCase(Locale.ROOT)) {
            case "OVERVIEW" -> 4;
            case "COMPARISON" -> 3;
            case "CONCEPT" -> 2;
            default -> 1;
        };
        int penalty = Math.min(4, row.unresolvedCount());
        return titleScore + summaryScore + contentScore + graphScore + citationScore + versionScore + pageKindBonus - penalty;
    }

    private boolean containsAllTerms(String content, Set<String> terms) {
        if (terms.isEmpty()) {
            return false;
        }
        String lower = content == null ? "" : content.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (!lower.contains(term)) {
                return false;
            }
        }
        return true;
    }

    private String normalizePageKind(String pageKind) {
        return (pageKind == null || pageKind.isBlank())
                ? "TOPIC"
                : pageKind.trim().toUpperCase(Locale.ROOT);
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

    private record GraphHop(String itemId, int depth) {
    }

    private record WikiPageSnapshot(String itemId, String title, String versionId, String content) {
    }

    private record TextSpan(int start, int end) {
    }

    private record LinkDraft(String title, int mentionCount, String relationType) {
    }

    private record IncomingWikiPageRef(String itemId, String title, String versionId, String content) {
    }

    private record WikiSearchRow(
            String itemId,
            String title,
            String pageKind,
            String versionId,
            int versionNo,
            String content,
            String summary,
            Instant updatedAt,
            int outgoingCount,
            int backlinkCount,
            int citationCount,
            int unresolvedCount
    ) {
        KnowledgeItemResponse toItemResponse() {
            return new KnowledgeItemResponse(
                    itemId,
                    "WIKI",
                    pageKind,
                    title,
                    "ACTIVE",
                    versionId,
                    versionNo,
                    summary,
                    updatedAt,
                    outgoingCount,
                    backlinkCount,
                    citationCount,
                    unresolvedCount
            );
        }

        KnowledgePageHit toPageHit(int score) {
            return new KnowledgePageHit(itemId, versionId, versionNo, title, content, summary, score);
        }
    }

    private record ScoredWikiRow(WikiSearchRow row, int score) {
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

    public record WikiPageContext(
            KnowledgePageHit page,
            List<WikiLinkResponse> outgoingLinks,
            List<WikiLinkResponse> backlinks,
            List<KnowledgeCitationResponse> citations
    ) {
    }
}

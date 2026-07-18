package com.noteweave.knowledge;

import com.noteweave.common.BusinessException;
import com.noteweave.workspace.WorkspaceQueryPort;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeGraphService {

    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceQueryPort workspaceQueryPort;
    private final KnowledgeWikiSearchEngine wikiSearchEngine;

    public KnowledgeGraphService(
            JdbcTemplate jdbcTemplate,
            WorkspaceQueryPort workspaceQueryPort,
            KnowledgeWikiSearchEngine wikiSearchEngine
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceQueryPort = workspaceQueryPort;
        this.wikiSearchEngine = wikiSearchEngine;
    }

    public WikiGraphResponse getWikiGraph(
            String workspaceId,
            String mode,
            String centerItemId,
            int depth,
            int limit,
            List<String> pageKinds
    ) {
        if (!workspaceQueryPort.exists(workspaceId)) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
        List<WikiSearchRow> rows = wikiSearchEngine.loadRows(workspaceId);
        List<WikiGraphNode> allNodes = rows.stream().map(this::toGraphNode).toList();
        List<WikiGraphEdge> allEdges = loadWikiGraphEdges(workspaceId);
        String normalizedMode = "ego".equalsIgnoreCase(mode) ? "ego" : "overview";
        int normalizedDepth = Math.max(1, Math.min(depth, 3));
        int normalizedLimit = Math.max(4, Math.min(limit, 120));
        Set<String> normalizedKinds = normalizeGraphKinds(pageKinds);
        List<WikiGraphNode> filteredNodes = filterGraphNodes(
                allNodes, normalizedMode, centerItemId, normalizedKinds);
        Set<String> filteredNodeIds = filteredNodes.stream()
                .map(WikiGraphNode::itemId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<WikiGraphEdge> filteredEdges = allEdges.stream()
                .filter(edge -> filteredNodeIds.contains(edge.sourceItemId())
                        && (edge.targetItemId() == null || filteredNodeIds.contains(edge.targetItemId())))
                .toList();

        List<String> selectedIds = "ego".equals(normalizedMode)
                ? selectEgoGraphNodeIds(
                        filteredNodes, filteredEdges, centerItemId, normalizedDepth, normalizedLimit)
                : selectOverviewGraphNodeIds(filteredNodes, normalizedLimit);

        if (selectedIds.isEmpty() && !filteredNodes.isEmpty()) {
            selectedIds = selectOverviewGraphNodeIds(filteredNodes, normalizedLimit);
            normalizedMode = "overview";
            centerItemId = "";
            normalizedDepth = 1;
        }

        Set<String> selectedIdSet = new LinkedHashSet<>(selectedIds);
        List<WikiGraphNode> selectedNodes = selectedIds.stream()
                .map(id -> filteredNodes.stream()
                        .filter(node -> node.itemId().equals(id))
                        .findFirst()
                        .orElse(null))
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

    private WikiGraphNode toGraphNode(WikiSearchRow row) {
        int degree = row.outgoingCount() + row.backlinkCount();
        return new WikiGraphNode(
                row.itemId(), row.title(), row.pageKind(), row.versionNo(), degree,
                row.outgoingCount(), row.backlinkCount(), row.citationCount(), row.unresolvedCount());
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
        return allNodes.stream().filter(node -> allowedIds.contains(node.itemId())).toList();
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
                    .sorted(Comparator.comparingInt((String itemId) -> nodeById
                                    .getOrDefault(itemId, nodeById.get(centerItemId)).degree()).reversed()
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

    private String normalizePageKind(String pageKind) {
        return (pageKind == null || pageKind.isBlank())
                ? "TOPIC"
                : pageKind.trim().toUpperCase(Locale.ROOT);
    }

    private record GraphHop(String itemId, int depth) {
    }
}

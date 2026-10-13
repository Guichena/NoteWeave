package com.noteweave.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在种子周围最多 3 跳的子图上做个性化 PageRank。
 * 邻接由调用方按批提供，不在这里读取全图。
 */
public final class WikiPersonalizedPageRank {

    public static final double RESTART_PROBABILITY = 0.3;
    public static final int MAX_ITERATIONS = 20;
    public static final double CONVERGENCE_L1 = 1.0e-6;
    public static final int ADJACENCY_BATCH = 50;
    public static final int MAX_HOPS = 3;

    private WikiPersonalizedPageRank() {
    }

    public record Seed(String itemId, String title, double weight) {
    }

    public interface AdjacencySource {
        List<WikiResolvedLink> linksTouching(List<String> itemIds);

        Map<String, Integer> inDegrees(List<String> itemIds);
    }

    public record Expanded(
            String itemId,
            String title,
            double pageRank,
            double adjustedScore,
            int hops,
            List<String> pathItemIds,
            List<String> pathTitles
    ) {
    }

    public record Exploration(Map<String, Double> pageRank, List<Expanded> expanded) {
    }

    public static Exploration expand(List<Seed> seeds, int maxHops, AdjacencySource source) {
        int hopLimit = Math.max(0, Math.min(maxHops, MAX_HOPS));
        List<Seed> usable = usableSeeds(seeds);
        if (usable.isEmpty() || hopLimit == 0 || source == null) {
            return new Exploration(Map.of(), List.of());
        }
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (Seed seed : usable) {
            Node node = new Node(seed.itemId());
            node.title = blankToEmpty(seed.title());
            node.hop = 0;
            node.rootSeedId = seed.itemId();
            node.rootWeight = Math.max(0, seed.weight());
            nodes.put(seed.itemId(), node);
        }
        Map<String, Integer> pairWeight = new HashMap<>();
        List<Node> frontier = new ArrayList<>(nodes.values());
        for (int hop = 1; hop <= hopLimit && !frontier.isEmpty(); hop++) {
            List<WikiResolvedLink> edges = loadEdges(source, frontier, pairWeight);
            Map<String, Node> layer = new HashMap<>();
            for (Node node : frontier) {
                layer.put(node.itemId, node);
            }
            Map<String, Candidate> found = new HashMap<>();
            for (WikiResolvedLink edge : edges) {
                Node fromSource = layer.get(edge.sourceItemId());
                if (fromSource != null) {
                    consider(fromSource, edge.targetItemId(), edge.targetTitle(), found, nodes);
                }
                Node fromTarget = layer.get(edge.targetItemId());
                if (fromTarget != null) {
                    consider(fromTarget, edge.sourceItemId(), edge.sourceTitle(), found, nodes);
                }
            }
            List<String> discovered = new ArrayList<>(found.keySet());
            Collections.sort(discovered);
            frontier = new ArrayList<>();
            for (String itemId : discovered) {
                Candidate candidate = found.get(itemId);
                Node node = new Node(itemId);
                node.title = blankToEmpty(candidate.title);
                node.hop = candidate.parent.hop + 1;
                node.parentId = candidate.parent.itemId;
                node.rootSeedId = candidate.parent.rootSeedId;
                node.rootWeight = candidate.parent.rootWeight;
                nodes.put(itemId, node);
                frontier.add(node);
            }
        }
        fillMissingTitles(nodes);
        double[] rank = pageRank(nodes, usable, pairWeight);
        Map<String, Integer> degrees = loadDegrees(source, nodes);
        List<String> ids = new ArrayList<>(nodes.keySet());
        Map<String, Double> scores = new LinkedHashMap<>();
        List<Expanded> expanded = new ArrayList<>();
        for (int index = 0; index < ids.size(); index++) {
            String itemId = ids.get(index);
            scores.put(itemId, rank[index]);
            Node node = nodes.get(itemId);
            if (node.hop == 0) {
                continue;
            }
            int degree = Math.max(0, degrees.getOrDefault(itemId, 0));
            double divisor = Math.log(1.0 + Math.max(degree, 1));
            double adjusted = rank[index] / divisor;
            List<String> pathIds = pathIds(node, nodes);
            List<String> pathTitles = new ArrayList<>();
            for (String pathId : pathIds) {
                Node pathNode = nodes.get(pathId);
                String title = pathNode == null ? "" : pathNode.title;
                pathTitles.add(title == null || title.isBlank() ? pathId : title);
            }
            expanded.add(new Expanded(
                    itemId,
                    node.title == null || node.title.isBlank() ? itemId : node.title,
                    rank[index],
                    adjusted,
                    node.hop,
                    List.copyOf(pathIds),
                    List.copyOf(pathTitles)
            ));
        }
        return new Exploration(Map.copyOf(scores), List.copyOf(expanded));
    }

    private static List<Seed> usableSeeds(List<Seed> seeds) {
        if (seeds == null || seeds.isEmpty()) {
            return List.of();
        }
        Map<String, Seed> unique = new LinkedHashMap<>();
        for (Seed seed : seeds) {
            if (seed == null || seed.itemId() == null || seed.itemId().isBlank()) {
                continue;
            }
            Seed previous = unique.get(seed.itemId());
            if (previous == null || seed.weight() > previous.weight()) {
                unique.put(seed.itemId(), seed);
            }
        }
        return List.copyOf(unique.values());
    }

    private static List<WikiResolvedLink> loadEdges(
            AdjacencySource source,
            List<Node> frontier,
            Map<String, Integer> pairWeight
    ) {
        List<String> ids = new ArrayList<>();
        for (Node node : frontier) {
            ids.add(node.itemId);
        }
        List<WikiResolvedLink> edges = new ArrayList<>();
        for (int offset = 0; offset < ids.size(); offset += ADJACENCY_BATCH) {
            List<String> batch = ids.subList(offset, Math.min(ids.size(), offset + ADJACENCY_BATCH));
            List<WikiResolvedLink> loaded = source.linksTouching(batch);
            if (loaded == null) {
                continue;
            }
            edges.addAll(loaded);
            absorbPairs(pairWeight, loaded);
        }
        return edges;
    }

    private static void absorbPairs(Map<String, Integer> pairWeight, List<WikiResolvedLink> edges) {
        Map<String, Integer> local = new HashMap<>();
        for (WikiResolvedLink edge : edges) {
            if (edge == null
                    || edge.sourceItemId() == null || edge.sourceItemId().isBlank()
                    || edge.targetItemId() == null || edge.targetItemId().isBlank()
                    || edge.sourceItemId().equals(edge.targetItemId())) {
                continue;
            }
            int weight = edge.mentionCount() <= 0 ? 1 : edge.mentionCount();
            local.merge(edge.sourceItemId() + "\0" + edge.targetItemId(), weight, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : local.entrySet()) {
            pairWeight.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    private static void consider(
            Node parent,
            String neighborId,
            String neighborTitle,
            Map<String, Candidate> found,
            Map<String, Node> nodes
    ) {
        if (neighborId == null || neighborId.isBlank() || neighborId.equals(parent.itemId) || nodes.containsKey(neighborId)) {
            return;
        }
        Candidate existing = found.get(neighborId);
        if (existing == null || prefer(parent, existing.parent)) {
            found.put(neighborId, new Candidate(parent, neighborTitle));
        }
    }

    private static boolean prefer(Node parent, Node current) {
        int weight = Double.compare(parent.rootWeight, current.rootWeight);
        if (weight != 0) {
            return weight > 0;
        }
        return parent.itemId.compareTo(current.itemId) < 0;
    }

    private static void fillMissingTitles(Map<String, Node> nodes) {
        for (Node node : nodes.values()) {
            if (node.title == null) {
                node.title = "";
            }
        }
    }

    private static double[] pageRank(
            Map<String, Node> nodes,
            List<Seed> seeds,
            Map<String, Integer> pairWeight
    ) {
        List<String> ids = new ArrayList<>(nodes.keySet());
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            index.put(ids.get(i), i);
        }
        int count = ids.size();
        double[] personal = new double[count];
        double personalSum = 0;
        for (Seed seed : seeds) {
            Integer at = index.get(seed.itemId());
            if (at == null) {
                continue;
            }
            double weight = seed.weight() > 0 ? seed.weight() : 0;
            personal[at] = weight;
            personalSum += weight;
        }
        if (personalSum <= 0) {
            for (Seed seed : seeds) {
                Integer at = index.get(seed.itemId());
                if (at != null) {
                    personal[at] = 1;
                    personalSum += 1;
                }
            }
        }
        for (int i = 0; i < count; i++) {
            personal[i] = personalSum <= 0 ? 0 : personal[i] / personalSum;
        }
        List<List<Out>> outgoing = new ArrayList<>();
        double[] outSum = new double[count];
        for (int i = 0; i < count; i++) {
            outgoing.add(new ArrayList<>());
        }
        for (Map.Entry<String, Integer> entry : pairWeight.entrySet()) {
            String[] ends = entry.getKey().split("\0", 2);
            Integer from = index.get(ends[0]);
            Integer to = index.get(ends[1]);
            if (from == null || to == null) {
                continue;
            }
            double weight = entry.getValue();
            addOut(outgoing, outSum, from, to, weight);
            addOut(outgoing, outSum, to, from, weight);
        }
        double[] rank = personal.clone();
        double stay = 1.0 - RESTART_PROBABILITY;
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            double[] walk = new double[count];
            double dangling = 0;
            for (int node = 0; node < count; node++) {
                if (outSum[node] <= 0) {
                    dangling += rank[node];
                    continue;
                }
                for (Out out : outgoing.get(node)) {
                    walk[out.to] += rank[node] * out.weight / outSum[node];
                }
            }
            double[] next = new double[count];
            double delta = 0;
            for (int node = 0; node < count; node++) {
                double value = RESTART_PROBABILITY * personal[node]
                        + stay * (walk[node] + dangling * personal[node]);
                delta += Math.abs(value - rank[node]);
                next[node] = value;
            }
            rank = next;
            if (delta < CONVERGENCE_L1) {
                break;
            }
        }
        return rank;
    }

    private static void addOut(List<List<Out>> outgoing, double[] outSum, int from, int to, double weight) {
        List<Out> edges = outgoing.get(from);
        for (int i = 0; i < edges.size(); i++) {
            Out existing = edges.get(i);
            if (existing.to == to) {
                edges.set(i, new Out(to, existing.weight + weight));
                outSum[from] += weight;
                return;
            }
        }
        edges.add(new Out(to, weight));
        outSum[from] += weight;
    }

    private static Map<String, Integer> loadDegrees(AdjacencySource source, Map<String, Node> nodes) {
        List<String> ids = new ArrayList<>();
        for (Node node : nodes.values()) {
            if (node.hop > 0) {
                ids.add(node.itemId);
            }
        }
        Map<String, Integer> degrees = new HashMap<>();
        for (int offset = 0; offset < ids.size(); offset += ADJACENCY_BATCH) {
            List<String> batch = ids.subList(offset, Math.min(ids.size(), offset + ADJACENCY_BATCH));
            Map<String, Integer> loaded = source.inDegrees(batch);
            if (loaded != null) {
                degrees.putAll(loaded);
            }
        }
        return degrees;
    }

    private static List<String> pathIds(Node node, Map<String, Node> nodes) {
        List<String> ids = new ArrayList<>();
        Node cursor = node;
        int guard = 0;
        while (cursor != null && guard++ <= MAX_HOPS + 1) {
            ids.add(cursor.itemId);
            cursor = cursor.parentId == null ? null : nodes.get(cursor.parentId);
        }
        Collections.reverse(ids);
        return ids;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class Node {
        private final String itemId;
        private String title;
        private int hop;
        private String parentId;
        private String rootSeedId;
        private double rootWeight;

        private Node(String itemId) {
            this.itemId = itemId;
        }
    }

    private record Candidate(Node parent, String title) {
    }

    private record Out(int to, double weight) {
    }
}

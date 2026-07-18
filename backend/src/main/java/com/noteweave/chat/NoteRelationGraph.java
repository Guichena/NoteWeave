package com.noteweave.chat;

import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class NoteRelationGraph {
    public Map<String, Integer> coOccurrence(Set<String> anchors, Map<String, Set<String>> groups) {
        if (anchors.isEmpty()) return Map.of();
        Map<String, Integer> result = new HashMap<>();
        for (Set<String> ids : groups.values()) {
            if (ids.stream().noneMatch(anchors::contains)) continue;
            for (String id : ids) if (!anchors.contains(id)) result.merge(id, 1, Integer::sum);
        }
        return result;
    }

    public Map<String, Map<String, Integer>> build(
            List<CandidateSource> sources, Map<String, Set<String>> noteGroups,
            Map<String, Set<String>> turnGroups) {
        Map<String, Map<String, Integer>> graph = new HashMap<>();
        Map<String, Set<String>> tags = new HashMap<>();
        for (CandidateSource source : sources) {
            graph.put(source.sourceId(), new HashMap<>());
            tags.put(source.sourceId(), tags(source.tagsJson()));
        }
        for (int i = 0; i < sources.size(); i++) for (int j = i + 1; j < sources.size(); j++) {
            CandidateSource left = sources.get(i), right = sources.get(j);
            int weight = overlap(tags.get(left.sourceId()), tags.get(right.sourceId())) * 2
                    + coCitation(noteGroups, left.sourceId(), right.sourceId()) * 3
                    + coCitation(turnGroups, left.sourceId(), right.sourceId()) * 4
                    + overlapTerms(left.title() + "\n" + left.summary(), right.title() + "\n" + right.summary());
            if (weight > 0) {
                graph.get(left.sourceId()).merge(right.sourceId(), weight, Integer::sum);
                graph.get(right.sourceId()).merge(left.sourceId(), weight, Integer::sum);
            }
        }
        return graph;
    }

    public Map<String, Double> propagate(Map<String, Map<String, Integer>> graph,
                                         Set<String> anchors, int steps, double restart) {
        if (graph.isEmpty() || anchors.isEmpty()) return Map.of();
        Map<String, Double> seed = new HashMap<>();
        double weight = 1d / anchors.size();
        for (String id : anchors) if (graph.containsKey(id)) seed.put(id, weight);
        if (seed.isEmpty()) return Map.of();
        Map<String, Double> current = new HashMap<>(seed);
        for (int step = 0; step < steps; step++) {
            Map<String, Double> next = new HashMap<>();
            seed.forEach((id, value) -> next.merge(id, restart * value, Double::sum));
            for (Map.Entry<String, Double> entry : current.entrySet()) {
                Map<String, Integer> neighbors = graph.getOrDefault(entry.getKey(), Map.of());
                if (neighbors.isEmpty()) { next.merge(entry.getKey(), (1d - restart) * entry.getValue(), Double::sum); continue; }
                int total = neighbors.values().stream().mapToInt(Integer::intValue).sum();
                if (total <= 0) continue;
                for (Map.Entry<String, Integer> neighbor : neighbors.entrySet())
                    next.merge(neighbor.getKey(), (1d - restart) * entry.getValue() * neighbor.getValue() / total, Double::sum);
            }
            current = next;
        }
        Map<String, Double> result = new HashMap<>();
        current.forEach((id, value) -> { if (!anchors.contains(id) && value > 0) result.put(id, value); });
        return result;
    }

    public Set<String> tags(String json) {
        if (json == null || json.isBlank()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("\"([^\"]{1,80})\"").matcher(json.toLowerCase(Locale.ROOT));
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    private int overlap(Set<String> left, Set<String> right) { int n = 0; for (String v : left) if (right.contains(v)) n++; return n; }
    private int coCitation(Map<String, Set<String>> groups, String left, String right) { int n = 0; for (Set<String> ids : groups.values()) if (ids.contains(left) && ids.contains(right)) n++; return n; }
    public int overlapTerms(String left, String right) {
        Set<String> a = terms(left), b = terms(right); int n = 0;
        for (String term : a) if (b.contains(term)) n += Math.max(1, term.length()); return n;
    }
    private Set<String> terms(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String part : (text == null ? "" : text.toLowerCase(Locale.ROOT)).split("[^\\p{IsHan}a-zA-Z0-9]+")) if (!part.isBlank()) result.add(part);
        return result;
    }
}

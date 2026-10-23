package com.noteweave.chat;

import com.noteweave.chat.NoteRetrievalService.NoteJournalHit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class NoteJournalRetriever {
    private final JdbcTemplate jdbcTemplate;

    public NoteJournalRetriever(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<NoteJournalHit> retrieve(String workspaceId, String query) {
        Set<String> terms = terms(query);
        Map<String, Freshness> freshness = freshness(workspaceId);
        List<NoteJournalHit> hits = jdbcTemplate.query("""
                select i.id, i.title, coalesce(v.summary, '') as summary, v.content, count(c.id) as citation_count
                from knowledge_item i join knowledge_version v on v.id = i.latest_version_id
                left join knowledge_version_citation kvc on kvc.knowledge_version_id = v.id
                left join citation c on c.id = kvc.citation_id
                where i.workspace_id = ? and i.item_type = 'NOTE' and i.status = 'ACTIVE'
                group by i.id, i.title, v.summary, v.content, i.updated_at order by i.updated_at desc limit 31
                """, (rs, n) -> new NoteJournalHit(rs.getString("id"), rs.getString("title"),
                rs.getString("summary"), rs.getString("content"), rs.getInt("citation_count"), 0,
                "fresh", "引用资料保持最新，可直接复用。", 0, 0), workspaceId);
        return hits.stream().map(hit -> {
                    Freshness f = freshness.getOrDefault(hit.noteId(), Freshness.fresh());
                    return hit.withFreshness(f.status(), f.note(), f.stale(), f.unavailable())
                            .withScore(Math.max(0, score(hit.title() + "\n" + hit.summary() + "\n" + hit.content(), terms) - f.penalty()));
                }).filter(hit -> hit.score() > 0 || terms.isEmpty())
                .sorted(Comparator.comparingInt((NoteJournalHit hit) -> priority(hit.freshnessStatus()))
                        .thenComparing(Comparator.comparingInt(NoteJournalHit::score).reversed())
                        .thenComparing(NoteJournalHit::title)).limit(3).toList();
    }

    public Map<String, Integer> sourceSignals(String workspaceId, Set<String> terms) {
        if (terms.isEmpty()) return Map.of();
        Map<String, Freshness> freshness = freshness(workspaceId);
        List<Signal> rows = jdbcTemplate.query("""
                select i.id as note_id, c.source_id, i.title, coalesce(v.summary, '') as summary, v.content
                from knowledge_item i join knowledge_version v on v.id = i.latest_version_id
                join knowledge_version_citation kvc on kvc.knowledge_version_id = v.id
                join citation c on c.id = kvc.citation_id
                where i.workspace_id = ? and i.item_type = 'NOTE' and i.status = 'ACTIVE'
                """, (rs, n) -> new Signal(rs.getString("note_id"), rs.getString("source_id"),
                rs.getString("title"), rs.getString("summary"), rs.getString("content")), workspaceId);
        Map<String, Integer> result = new HashMap<>();
        for (Signal row : rows) {
            int value = score(row.title() + "\n" + row.summary() + "\n" + row.content(), terms);
            Freshness f = freshness.getOrDefault(row.noteId(), Freshness.fresh());
            // 已删除和暂不可用的来源都不再提供检索信号，避免历史笔记把失效原文带回来
            if ("source-deleted".equals(f.status()) || "source-unavailable".equals(f.status())) continue;
            if ("stale-source-updated".equals(f.status())) value = Math.max(1, value / 2);
            if (value > 0) result.merge(row.sourceId(), value, Integer::sum);
        }
        return result;
    }

    private Map<String, Freshness> freshness(String workspaceId) {
        List<FreshnessRow> rows = jdbcTemplate.query("""
                select i.id as note_id,
                  sum(case when s.id is not null and s.status = 'DELETED' then 1 else 0 end) deleted_source_count,
                  sum(case when s.id is not null and s.status not in ('READY', 'DELETED') then 1 else 0 end) unavailable_source_count,
                  sum(case when s.id is not null and s.status = 'READY' and s.updated_at > v.created_at then 1 else 0 end) stale_source_count
                from knowledge_item i join knowledge_version v on v.id = i.latest_version_id
                left join knowledge_version_citation kvc on kvc.knowledge_version_id = v.id
                left join citation c on c.id = kvc.citation_id left join source s on s.id = c.source_id
                where i.workspace_id = ? and i.item_type = 'NOTE' and i.status = 'ACTIVE'
                group by i.id, v.created_at
                """, (rs, n) -> new FreshnessRow(rs.getString("note_id"), rs.getInt("stale_source_count"),
                rs.getInt("unavailable_source_count"), rs.getInt("deleted_source_count")), workspaceId);
        Map<String, Freshness> result = new HashMap<>();
        rows.forEach(row -> result.put(row.noteId(), Freshness.from(row.stale(), row.unavailable(), row.deleted())));
        return result;
    }

    private Set<String> terms(String query) {
        String value = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        for (String part : value.split("[^\\p{IsHan}a-zA-Z0-9]+")) if (!part.isBlank()) terms.add(part);
        if (terms.isEmpty() && value.length() >= 2) terms.add(value);
        return terms;
    }

    private int score(String content, Set<String> terms) {
        if (terms.isEmpty()) return 1;
        String lower = content == null ? "" : content.toLowerCase(Locale.ROOT);
        int score = 0; for (String term : terms) if (lower.contains(term)) score += Math.max(1, term.length());
        return score;
    }

    private int priority(String status) { return switch (status) { case "fresh" -> 0; case "stale-source-updated" -> 1; case "source-unavailable" -> 2; case "source-deleted" -> 3; default -> 4; }; }
    private record Signal(String noteId, String sourceId, String title, String summary, String content) {}
    private record FreshnessRow(String noteId, int stale, int unavailable, int deleted) {}
    private record Freshness(String status, String note, int stale, int unavailable, int deleted, int penalty) {
        static Freshness fresh() { return new Freshness("fresh", "引用资料保持最新，可直接复用。", 0, 0, 0, 0); }
        static Freshness from(int stale, int unavailable, int deleted) {
            if (deleted > 0) return new Freshness("source-deleted", "这条历史 Note 绑定的来源已删除，笔记内容仍可参考，原文不可引用。", stale, unavailable, deleted, 6);
            if (unavailable > 0) return new Freshness("source-unavailable", "这条历史 Note 绑定的来源暂不可用，笔记内容仍可参考。", stale, unavailable, deleted, 5);
            if (stale > 0) return new Freshness("stale-source-updated", "这条历史 Note 绑定的来源在记录后已更新，需要重新核对原文窗口。", stale, unavailable, deleted, 3);
            return fresh();
        }
    }
}

package com.noteweave.knowledge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class KnowledgeWikiSearchEngine {

    private final JdbcTemplate jdbcTemplate;

    KnowledgeWikiSearchEngine(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<WikiSearchRow> search(String workspaceId, String query) {
        Set<String> terms = extractTerms(query);
        return rank(loadRows(workspaceId, terms), terms).stream()
                .map(ScoredWikiRow::row)
                .toList();
    }

    List<KnowledgePageHit> findRelevantPages(String workspaceId, String query) {
        Set<String> terms = extractTerms(query);
        List<WikiSearchRow> rows = loadRows(workspaceId, terms);
        List<ScoredWikiRow> scored = rank(rows, terms).stream()
                .toList();
        if (!scored.isEmpty()) {
            return scored.stream().map(row -> row.row().toPageHit(row.score())).toList();
        }
        return loadRows(workspaceId).stream()
                .map(row -> row.toPageHit(0))
                .toList();
    }

    private List<ScoredWikiRow> rank(List<WikiSearchRow> rows, Set<String> terms) {
        return rows.stream()
                .map(row -> new ScoredWikiRow(row, scoreWikiRow(row, terms)))
                .filter(row -> row.score() > 0 || terms.isEmpty())
                .sorted(Comparator.comparingInt(ScoredWikiRow::score).reversed()
                        .thenComparing(Comparator.comparingInt(
                                (ScoredWikiRow row) -> row.row().citationCount()).reversed())
                        .thenComparing(Comparator.comparingInt(
                                (ScoredWikiRow row) -> row.row().backlinkCount()).reversed())
                        .thenComparing(Comparator.comparing(
                                (ScoredWikiRow row) -> row.row().updatedAt()).reversed()))
                .toList();
    }

    List<WikiSearchRow> loadRows(String workspaceId) {
        return loadRows(workspaceId, Set.of());
    }

    private List<WikiSearchRow> loadRows(String workspaceId, Set<String> terms) {
        StringBuilder sql = new StringBuilder("""
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
                """);
        java.util.ArrayList<Object> parameters = new java.util.ArrayList<>();
        parameters.add(workspaceId);
        if (!terms.isEmpty()) {
            sql.append(" and (");
            int index = 0;
            for (String term : terms) {
                if (index++ > 0) {
                    sql.append(" or ");
                }
                sql.append("lower(concat(coalesce(i.title, ''), ' ', coalesce(v.summary, ''), ' ', coalesce(v.content, ''))) like ?");
                parameters.add("%" + term.toLowerCase(Locale.ROOT) + "%");
            }
            sql.append(")");
        }
        sql.append(" order by i.updated_at desc");
        // Browse views remain bounded, but search queries must rank every
        // matching page so older pages do not become undiscoverable.
        if (terms.isEmpty()) {
            sql.append(" limit 120");
        }
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new WikiSearchRow(
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
        ), parameters.toArray());
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
        return titleScore + summaryScore + contentScore + graphScore
                + citationScore + versionScore + pageKindBonus - penalty;
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

    private Instant toInstant(Timestamp value) {
        return value == null ? Instant.now() : value.toInstant();
    }

    private record ScoredWikiRow(WikiSearchRow row, int score) {
    }
}

record WikiSearchRow(
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
                itemId, "WIKI", pageKind, title, "ACTIVE", versionId, versionNo,
                summary, updatedAt, outgoingCount, backlinkCount, citationCount, unresolvedCount);
    }

    KnowledgePageHit toPageHit(int score) {
        return new KnowledgePageHit(itemId, versionId, versionNo, title, content, summary, score);
    }
}

package com.noteweave.knowledge;

import com.noteweave.common.text.LexicalTerms;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Wiki 页面检索。
 * <p>
 * 按 BM25F 给页面打分：标题、摘要、正文三个字段分别做长度归一后按权重合并词频，再用 BM25 的饱和函数计分，
 * 词项的 IDF 在当前工作台的全部 Wiki 页面上统计。中文按相邻两字切分（与检索索引的 cjk_bigram 一致），
 * 所以问"缓存一致性怎么做"能命中写着缓存一致性的页面，不要求整句原样出现在页面里。
 * <p>
 * 文本得分之后沿页面链接传播一次：与高分页面直接相连的页面获得对方得分的一部分，
 * 让通过链接组织起来的概念页、对比页也能进入候选。被引用和被反链多的页面有一个按对数增长、
 * 带上限的小幅加成。页面必须覆盖足够比例的查询词项才算命中，再去掉得分低于最高分一定比例的长尾；
 * 没有页面命中时返回空，不再拿最近更新的页面充数。
 */
@Component
final class KnowledgeWikiSearchEngine {

    static final double K1 = 1.2d;
    static final double B = 0.75d;
    static final double TITLE_WEIGHT = 3.0d;
    static final double SUMMARY_WEIGHT = 2.0d;
    static final double CONTENT_WEIGHT = 1.0d;
    /** 相连页面获得的得分比例。 */
    static final double LINK_PROPAGATION = 0.35d;
    /** 引用和反链加成的系数与上限。 */
    static final double AUTHORITY_WEIGHT = 0.08d;
    static final double AUTHORITY_CAP = 0.3d;
    /** 得分低于最高分这个比例的页面视为噪声。 */
    static final double RELATIVE_CUTOFF = 0.15d;
    /**
     * 页面至少要包含这个比例的查询词项才算文本命中。只看相对得分时，一个完全无关的问题也会因为零星共享的
     * 常用词（例如"计算""发展"）选出"最高分"的页面。这里按词项个数算比例而不按 IDF 加权：
     * 页面很少的工作台里 IDF 失真，命中词的权重会被压得很低。
     */
    static final double MIN_QUERY_COVERAGE = 0.3d;
    /** 单个工作台参与检索的页面上限，避免异常数据把整张表读进内存。 */
    private static final int SEARCH_PAGE_CEILING = 5_000;
    private static final int BROWSE_LIMIT = 120;

    private final JdbcTemplate jdbcTemplate;

    KnowledgeWikiSearchEngine(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<WikiSearchRow> search(String workspaceId, String query) {
        Query parsed = Query.parse(query);
        if (parsed.terms().isEmpty()) {
            return loadRows(workspaceId);
        }
        return rank(workspaceId, parsed).stream().map(ScoredWikiRow::row).toList();
    }

    List<KnowledgePageHit> findRelevantPages(String workspaceId, String query) {
        Query parsed = Query.parse(query);
        if (parsed.terms().isEmpty()) {
            return List.of();
        }
        return rank(workspaceId, parsed).stream()
                .map(row -> row.row().toPageHit((int) Math.round(row.score() * 100)))
                .toList();
    }

    /** 浏览视图：最近更新的页面。 */
    List<WikiSearchRow> loadRows(String workspaceId) {
        return loadRows(workspaceId, BROWSE_LIMIT);
    }

    private List<ScoredWikiRow> rank(String workspaceId, Query query) {
        List<WikiSearchRow> rows = loadRows(workspaceId, SEARCH_PAGE_CEILING);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, Double> lexical = bm25f(rows, query);
        Map<String, Double> neighbor = neighborScores(workspaceId, lexical);
        List<ScoredWikiRow> scored = new ArrayList<>();
        for (WikiSearchRow row : rows) {
            double score = lexical.getOrDefault(row.itemId(), 0.0d)
                    + LINK_PROPAGATION * neighbor.getOrDefault(row.itemId(), 0.0d);
            if (score <= 0.0d) {
                continue;
            }
            double authority = Math.min(AUTHORITY_CAP,
                    AUTHORITY_WEIGHT * Math.log1p(row.citationCount() + row.backlinkCount()));
            scored.add(new ScoredWikiRow(row, score * (1.0d + authority)));
        }
        double top = scored.stream().mapToDouble(ScoredWikiRow::score).max().orElse(0.0d);
        return scored.stream()
                .filter(row -> row.score() >= top * RELATIVE_CUTOFF)
                .sorted(Comparator.comparingDouble(ScoredWikiRow::score).reversed()
                        .thenComparing(Comparator.comparingInt(
                                (ScoredWikiRow row) -> row.row().citationCount()).reversed())
                        .thenComparing(Comparator.comparingInt(
                                (ScoredWikiRow row) -> row.row().backlinkCount()).reversed())
                        .thenComparing(Comparator.comparing(
                                (ScoredWikiRow row) -> row.row().updatedAt()).reversed()))
                .toList();
    }

    /** BM25F：三个字段各自做长度归一，按字段权重合并成一个词频后再饱和。 */
    static Map<String, Double> bm25f(List<WikiSearchRow> rows, Query query) {
        Set<String> terms = query.terms();
        List<Field[]> documents = new ArrayList<>(rows.size());
        double[] totalLength = new double[3];
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (WikiSearchRow row : rows) {
            Field[] fields = {
                    Field.of(row.title(), TITLE_WEIGHT),
                    Field.of(row.summary(), SUMMARY_WEIGHT),
                    Field.of(row.content(), CONTENT_WEIGHT)};
            documents.add(fields);
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < fields.length; index++) {
                totalLength[index] += fields[index].length();
                for (String term : terms) {
                    if (fields[index].frequency(term) > 0) seen.add(term);
                }
            }
            seen.forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
        }
        int count = rows.size();
        double[] averageLength = new double[3];
        for (int index = 0; index < 3; index++) {
            averageLength[index] = Math.max(1.0d, totalLength[index] / count);
        }
        Map<String, Double> idf = new HashMap<>();
        for (String term : terms) {
            int df = documentFrequency.getOrDefault(term, 0);
            idf.put(term, Math.log(1.0d + (count - df + 0.5d) / (df + 0.5d)));
        }
        Map<String, Double> scores = new HashMap<>();
        for (int document = 0; document < count; document++) {
            Field[] fields = documents.get(document);
            double score = 0.0d;
            Set<String> matched = new HashSet<>();
            for (String term : terms) {
                double weighted = 0.0d;
                for (int index = 0; index < fields.length; index++) {
                    int frequency = fields[index].frequency(term);
                    if (frequency == 0) continue;
                    double normalization = 1.0d - B + B * fields[index].length() / averageLength[index];
                    weighted += fields[index].weight() * frequency / normalization;
                }
                if (weighted == 0.0d) continue;
                matched.add(term);
                score += idf.get(term) * weighted / (K1 + weighted);
            }
            if (score > 0.0d && query.covered(matched)) {
                scores.put(rows.get(document).itemId(), score);
            }
        }
        return scores;
    }

    /** 沿已解析的页面链接（不分方向），算出每个页面相邻页面中最高的文本得分。 */
    private Map<String, Double> neighborScores(String workspaceId, Map<String, Double> lexical) {
        if (lexical.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> neighbor = new HashMap<>();
        jdbcTemplate.query("""
                select source_item_id, target_item_id from knowledge_item_link
                where workspace_id = ? and relation_status = 'RESOLVED' and target_item_id is not null
                """, rs -> {
            String source = rs.getString(1);
            String target = rs.getString(2);
            if (source.equals(target)) return;
            neighbor.merge(target, lexical.getOrDefault(source, 0.0d), Math::max);
            neighbor.merge(source, lexical.getOrDefault(target, 0.0d), Math::max);
        }, workspaceId);
        return neighbor;
    }

    private List<WikiSearchRow> loadRows(String workspaceId, int limit) {
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
                limit ?
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
        ), workspaceId, limit);
    }

    private Instant toInstant(Timestamp value) {
        return value == null ? Instant.now() : value.toInstant();
    }

    private record Field(Map<String, Integer> frequencies, int length, double weight) {
        static Field of(String text, double weight) {
            List<String> tokens = LexicalTerms.tokens(text);
            Map<String, Integer> frequencies = new HashMap<>();
            tokens.forEach(token -> frequencies.merge(token, 1, Integer::sum));
            return new Field(frequencies, tokens.size(), weight);
        }

        int frequency(String term) {
            return frequencies.getOrDefault(term, 0);
        }
    }

    private record ScoredWikiRow(WikiSearchRow row, double score) {
    }

    /**
     * 检索词项。回答链路传进来的是带对话上下文的组合查询（"当前问题：……""主题锚点：……"加上对话窗口和摘要），
     * 这里只取当前问题和主题锚点；页面覆盖其中任意一组词项的足够比例即算命中，
     * 这样省略了主语的追问（例如"第二点为什么成立"）可以靠主题锚点找到页面。
     */
    record Query(Set<String> terms, List<Set<String>> groups) {
        private static final String CURRENT_PREFIX = "当前问题：";
        private static final String ANCHOR_PREFIX = "主题锚点：";

        static Query parse(String raw) {
            String text = raw == null ? "" : raw;
            List<Set<String>> groups = new ArrayList<>();
            for (String line : text.split("\\R")) {
                String value = line.strip();
                if (value.startsWith(CURRENT_PREFIX)) {
                    groups.add(LexicalTerms.queryTerms(value.substring(CURRENT_PREFIX.length())));
                } else if (value.startsWith(ANCHOR_PREFIX)) {
                    groups.add(LexicalTerms.queryTerms(value.substring(ANCHOR_PREFIX.length())));
                }
            }
            if (groups.isEmpty()) {
                groups.add(LexicalTerms.queryTerms(text));
            }
            groups.removeIf(Set::isEmpty);
            Set<String> terms = new java.util.LinkedHashSet<>();
            groups.forEach(terms::addAll);
            return new Query(terms, groups);
        }

        boolean covered(Set<String> matched) {
            for (Set<String> group : groups) {
                long hit = group.stream().filter(matched::contains).count();
                if (hit >= group.size() * MIN_QUERY_COVERAGE) return true;
            }
            return false;
        }
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

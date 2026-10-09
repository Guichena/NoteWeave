package com.noteweave.knowledge;

import com.noteweave.common.Ids;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeWikiMutationService {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeWikiMutationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PreparedWikiContent prepareContent(
            String workspaceId,
            String title,
            String content
    ) {
        String normalized = normalizeWikiContent(workspaceId, title, content);
        return new PreparedWikiContent(normalized, inferWikiPageKind(title, normalized));
    }

    public String inferWikiPageKind(String title, String content) {
        // Wiki ingest 生成的页面类型是确定的，按固定说明文字判断；
        // 否则正文里偶然出现的索引、概念等字样会把概念页归成总览页，把资料页归成概念页
        // Wiki Index 的目录会引用各页摘要（其中包含上述说明文字），所以先按标题判断
        String body = content == null ? "" : content;
        if ("Wiki Index".equalsIgnoreCase(title)) return "OVERVIEW";
        if (body.contains(WikiIngestService.CONCEPT_PAGE_MARKER)) return "CONCEPT";
        if (body.contains(WikiIngestService.SOURCE_PAGE_MARKER)) return "OVERVIEW";
        String normalized = ((title == null ? "" : title)
                + "\n" + (content == null ? "" : content)).toLowerCase(Locale.ROOT);
        if (normalized.contains("对比") || normalized.contains("比较") || normalized.contains("vs")) {
            return "COMPARISON";
        }
        if (normalized.contains("总览") || normalized.contains("概览")
                || normalized.contains("overview") || normalized.contains("索引")) {
            return "OVERVIEW";
        }
        if (normalized.contains("概念") || normalized.contains("定义")
                || normalized.contains("原理") || normalized.contains("concept")) {
            return "CONCEPT";
        }
        return "TOPIC";
    }

    public String findWikiItemIdByTitle(String workspaceId, String title) {
        List<String> ids = jdbcTemplate.queryForList("""
                select id from knowledge_item
                where workspace_id = ? and item_type = 'WIKI'
                  and lower(title) = lower(?) and status = 'ACTIVE'
                limit 1
                """, String.class, workspaceId, title);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public void replaceOutgoingLinks(
            String workspaceId,
            String sourceItemId,
            String sourceTitle,
            String content
    ) {
        jdbcTemplate.update("delete from knowledge_item_link where source_item_id = ?", sourceItemId);
        upsertWikiLinks(workspaceId, sourceItemId, sourceTitle, content);
    }

    public void upsertWikiLinks(
            String workspaceId,
            String sourceItemId,
            String sourceTitle,
            String content
    ) {
        Map<String, LinkDraft> linkDrafts = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : extractWikiLinks(content).entrySet()) {
            linkDrafts.put(entry.getKey(), new LinkDraft(
                    entry.getKey(), entry.getValue(), "WIKI_LINK"));
        }
        for (Map.Entry<String, Integer> entry : inferWikiTitleMentions(
                workspaceId, sourceTitle, content, linkDrafts.keySet()).entrySet()) {
            LinkDraft existing = findLinkDraft(linkDrafts, entry.getKey());
            if (existing == null) {
                linkDrafts.put(entry.getKey(), new LinkDraft(
                        entry.getKey(), entry.getValue(), "AUTO_LINK"));
                continue;
            }
            String relationType = "WIKI_LINK".equals(existing.relationType())
                    ? "HYBRID_LINK" : existing.relationType();
            linkDrafts.put(existing.title(), new LinkDraft(
                    existing.title(), existing.mentionCount() + entry.getValue(), relationType));
        }
        for (LinkDraft draft : linkDrafts.values()) {
            String targetTitle = draft.title();
            if (targetTitle.equalsIgnoreCase(sourceTitle)) {
                continue;
            }
            String targetItemId = findWikiItemIdByTitle(workspaceId, targetTitle);
            jdbcTemplate.update("""
                    insert into knowledge_item_link(
                        id, workspace_id, source_item_id, target_item_id, target_title,
                        relation_type, relation_status, mention_count
                    ) values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), workspaceId, sourceItemId, targetItemId, targetTitle,
                    draft.relationType(),
                    targetItemId == null ? "UNRESOLVED" : "RESOLVED",
                    draft.mentionCount());
        }
    }

    public void resolveIncomingLinksForCreatedWikiPage(
            String workspaceId,
            String itemId,
            String title
    ) {
        jdbcTemplate.update("""
                update knowledge_item_link
                set target_item_id = ?, target_title = ?, relation_status = 'RESOLVED',
                    updated_at = current_timestamp
                where workspace_id = ?
                  and relation_status = 'UNRESOLVED'
                  and lower(target_title) = lower(?)
                """, itemId, title, workspaceId, title);
    }

    public void logWiki(
            String workspaceId,
            String itemId,
            String eventType,
            String message
    ) {
        jdbcTemplate.update("""
                insert into wiki_log_entry(id, workspace_id, item_id, event_type, message)
                values (?, ?, ?, ?, ?)
                """, Ids.newId(), workspaceId, itemId, eventType, message);
    }

    private LinkDraft findLinkDraft(Map<String, LinkDraft> linkDrafts, String title) {
        return linkDrafts.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(title))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private Map<String, Integer> extractWikiLinks(String content) {
        if (content == null || content.isBlank()) {
            return Map.of();
        }
        Map<String, Integer> links = new HashMap<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\[\\[([^\\]]{1,120})]]").matcher(content);
        while (matcher.find()) {
            String raw = matcher.group(1).trim();
            String title = raw.contains("|")
                    ? raw.substring(0, raw.indexOf('|')).trim() : raw;
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
                if (candidateTitle == null || candidateTitle.isBlank()
                        || candidateTitle.equalsIgnoreCase(sourceTitle)) {
                    continue;
                }
                if ("OVERVIEW".equalsIgnoreCase(pageKind)
                        && !"Wiki Index".equalsIgnoreCase(candidateTitle)) {
                    continue;
                }
                if (!isGoodAutoLinkCandidate(candidateTitle)) {
                    continue;
                }
                int count = countOccurrences(
                        lowerContent, candidateTitle.toLowerCase(Locale.ROOT));
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
        return compact.length() > 3
                || !compact.chars().allMatch(ch -> ch < 128 && Character.isLetterOrDigit(ch));
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
                if (candidateTitle == null || candidateTitle.isBlank()
                        || candidateTitle.equalsIgnoreCase(sourceTitle)) {
                    continue;
                }
                if ("OVERVIEW".equalsIgnoreCase(pageKind)
                        && !"Wiki Index".equalsIgnoreCase(candidateTitle)) {
                    continue;
                }
                if (isGoodAutoLinkCandidate(candidateTitle)) {
                    titles.add(candidateTitle);
                }
            }
            return titles.stream()
                    .sorted(Comparator.comparingInt(String::length).reversed()
                            .thenComparing(String::compareToIgnoreCase))
                    .toList();
        }, workspaceId);
    }

    private String injectAutoLink(String content, String candidateTitle) {
        if (content == null || content.isBlank()
                || candidateTitle == null || candidateTitle.isBlank()) {
            return content == null ? "" : content;
        }
        if (extractWikiLinks(content).keySet().stream()
                .anyMatch(title -> title.equalsIgnoreCase(candidateTitle))) {
            return content;
        }
        List<TextSpan> forbidden = computeForbiddenSpans(content);
        int matchAt = findFirstSafeMention(content, candidateTitle, forbidden);
        if (matchAt < 0) {
            return content;
        }
        int matchEnd = matchAt + candidateTitle.length();
        return content.substring(0, matchAt)
                + "[[" + content.substring(matchAt, matchEnd) + "]]"
                + content.substring(matchEnd);
    }

    private int findFirstSafeMention(
            String content,
            String candidateTitle,
            List<TextSpan> forbidden
    ) {
        String lowerContent = content.toLowerCase(Locale.ROOT);
        String lowerCandidate = candidateTitle.toLowerCase(Locale.ROOT);
        int from = 0;
        while (from < lowerContent.length()) {
            int next = lowerContent.indexOf(lowerCandidate, from);
            if (next < 0) {
                return -1;
            }
            int end = next + candidateTitle.length();
            if (!overlapsForbidden(forbidden, next, end)
                    && (!needsAsciiBoundary(candidateTitle)
                    || hasAsciiBoundary(content, next, end))) {
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
        return isAsciiWord(text.charAt(0)) || isAsciiWord(text.charAt(text.length() - 1));
    }

    private boolean hasAsciiBoundary(String content, int start, int end) {
        if (start > 0 && isAsciiWord(content.charAt(start - 1))) {
            return false;
        }
        return end >= content.length() || !isAsciiWord(content.charAt(end));
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
                if (labelEnd > markdownStart && labelEnd + 1 < content.length()
                        && content.charAt(labelEnd + 1) == '(') {
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

    public record PreparedWikiContent(String content, String pageKind) {
    }

    private record TextSpan(int start, int end) {
    }

    private record LinkDraft(String title, int mentionCount, String relationType) {
    }
}

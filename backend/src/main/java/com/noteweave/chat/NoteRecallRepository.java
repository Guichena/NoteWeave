package com.noteweave.chat;

import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class NoteRecallRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ResearchGeneratedSourceReadGate generatedSourceGate;

    public NoteRecallRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null);
    }

    @Autowired
    public NoteRecallRepository(JdbcTemplate jdbcTemplate,
                                ResearchGeneratedSourceReadGate generatedSourceGate) {
        this.jdbcTemplate = jdbcTemplate;
        this.generatedSourceGate = generatedSourceGate;
    }

    public List<CandidateSource> findCurrentSources(String workspaceId) {
        List<CandidateSource> sources = jdbcTemplate.query("""
                select s.id, s.title, s.source_type, s.updated_at,
                       coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id,
                       coalesce(s.summary, '') as summary,
                       coalesce(s.tags_json, '[]') as tags_json,
                       coalesce(s.metadata_json, '{}') as metadata_json,
                       count(distinct c.id) as chunk_count,
                       count(distinct w.id) as window_count,
                       coalesce(min(c.content), '') as sample_text
                from source s
                left join source_snapshot ss on ss.source_id = s.id
                  and ss.index_status = 'INDEXED'
                  and ss.version_no = (select max(current_ss.version_no) from source_snapshot current_ss
                                       where current_ss.source_id = s.id and current_ss.index_status = 'INDEXED')
                left join source_chunk c on c.source_id = s.id and c.source_snapshot_id = ss.id
                left join source_window w on w.source_chunk_id = c.id
                where s.workspace_id = ? and s.status = 'READY' and s.index_status = 'INDEXED'
                group by s.id, s.title, s.source_type, s.updated_at, s.summary, s.tags_json, s.metadata_json
                order by s.id
                """, (rs, rowNum) -> new CandidateSource(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("source_type"),
                rs.getInt("chunk_count"),
                rs.getInt("window_count"),
                rs.getString("generated_by"),
                rs.getString("generated_ref_id"),
                rs.getString("summary"),
                rs.getString("tags_json"),
                rs.getString("metadata_json"),
                rs.getString("sample_text"),
                0,
                "",
                List.of(),
                List.of(),
                0,
                0,
                "",
                ""
        ), workspaceId);
        return filterVisible(workspaceId, sources);
    }

    /** 按资料 ID 回填当前快照元数据。空集合不访问数据库。 */
    public List<CandidateSource> findCurrentSourcesByIds(String workspaceId, Collection<String> sourceIds) {
        List<String> ids = limitedIds(sourceIds, 200);
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = placeholders(ids.size());
        List<Object> args = new ArrayList<>();
        args.add(workspaceId);
        args.addAll(ids);
        List<CandidateSource> sources = jdbcTemplate.query("""
                select s.id, s.title, s.source_type, s.updated_at,
                       coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id,
                       coalesce(s.summary, '') as summary,
                       coalesce(s.tags_json, '[]') as tags_json,
                       coalesce(s.metadata_json, '{}') as metadata_json,
                       count(distinct c.id) as chunk_count,
                       count(distinct w.id) as window_count,
                       coalesce(min(c.content), '') as sample_text
                from source s
                left join source_snapshot ss on ss.source_id = s.id
                  and ss.index_status = 'INDEXED'
                  and ss.version_no = (select max(current_ss.version_no) from source_snapshot current_ss
                                       where current_ss.source_id = s.id and current_ss.index_status = 'INDEXED')
                left join source_chunk c on c.source_id = s.id and c.source_snapshot_id = ss.id
                left join source_window w on w.source_chunk_id = c.id
                where s.workspace_id = ? and s.status = 'READY' and s.index_status = 'INDEXED'
                  and s.id in (%s)
                group by s.id, s.title, s.source_type, s.updated_at, s.summary, s.tags_json, s.metadata_json
                order by s.id
                """.formatted(placeholders), SOURCE_MAPPER, args.toArray());
        return filterVisible(workspaceId, sources);
    }

    /** 用标题、摘要、标签、元数据、类型和当前快照正文预筛降级 BM25F 的候选。 */
    public List<String> findLexicalSourceIds(String workspaceId, Collection<String> terms, int limit) {
        return findMatchingSourceIds(workspaceId, terms, Set.of(), limit, LEXICAL_TERM);
    }

    /** 标签通道：tags_json 命中查询词的资料。 */
    public List<String> findTagSourceIds(String workspaceId, Collection<String> terms, int limit) {
        return findMatchingSourceIds(workspaceId, terms, Set.of(), limit, TAG_TERM);
    }

    /** 从锚点标题和摘要的词项找出其他资料，排除已经在候选里的 ID。 */
    public List<String> findTitleSummarySourceIds(
            String workspaceId, Collection<String> terms, Collection<String> excludedIds, int limit) {
        return findMatchingSourceIds(workspaceId, terms, excludedIds, limit, TITLE_SUMMARY_TERM);
    }

    /** 从锚点标签找出其他资料。 */
    public List<String> findTagOverlapSourceIds(
            String workspaceId, Collection<String> terms, Collection<String> excludedIds, int limit) {
        return findMatchingSourceIds(workspaceId, terms, excludedIds, limit, TAG_TERM);
    }

    public List<String> findNoteCoCitedSourceIds(String workspaceId, Collection<String> anchorIds, int limit) {
        List<String> ids = limitedIds(anchorIds, 80);
        if (ids.isEmpty() || limit <= 0) {
            return List.of();
        }
        String placeholders = placeholders(ids.size());
        List<Object> args = new ArrayList<>();
        args.add(workspaceId);
        args.addAll(ids);
        args.addAll(ids);
        args.add(limit);
        return queryIds("""
                select distinct c2.source_id
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                join knowledge_version_citation kvc1 on kvc1.knowledge_version_id = v.id
                join citation c1 on c1.id = kvc1.citation_id
                join knowledge_version_citation kvc2 on kvc2.knowledge_version_id = v.id
                join citation c2 on c2.id = kvc2.citation_id
                where i.workspace_id = ?
                  and i.item_type = 'NOTE'
                  and i.status = 'ACTIVE'
                  and c1.source_id in (%s)
                  and c2.source_id not in (%s)
                limit ?
                """.formatted(placeholders, placeholders), args);
    }

    public List<String> findTurnCoCitedSourceIds(String workspaceId, Collection<String> anchorIds, int limit) {
        List<String> ids = limitedIds(anchorIds, 80);
        if (ids.isEmpty() || limit <= 0) {
            return List.of();
        }
        String placeholders = placeholders(ids.size());
        List<Object> args = new ArrayList<>();
        args.add(workspaceId);
        args.addAll(ids);
        args.addAll(ids);
        args.add(limit);
        return queryIds("""
                select distinct c2.source_id
                from conversation_message m
                join message_citation mc1 on mc1.message_id = m.id
                join citation c1 on c1.id = mc1.citation_id
                join message_citation mc2 on mc2.message_id = m.id
                join citation c2 on c2.id = mc2.citation_id
                where m.workspace_id = ?
                  and m.role = 'ASSISTANT'
                  and c1.source_id in (%s)
                  and c2.source_id not in (%s)
                limit ?
                """.formatted(placeholders, placeholders), args);
    }

    public Map<String, Set<String>> sourceIdsByNoteForSources(String workspaceId, Collection<String> sourceIds) {
        List<String> ids = limitedIds(sourceIds, 200);
        if (ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = placeholders(ids.size());
        List<Object> args = new ArrayList<>();
        args.add(workspaceId);
        args.addAll(ids);
        List<NoteCitationPair> rows = jdbcTemplate.query("""
                select i.id as note_id, c.source_id
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                join knowledge_version_citation kvc on kvc.knowledge_version_id = v.id
                join citation c on c.id = kvc.citation_id
                where i.workspace_id = ? and i.item_type = 'NOTE' and i.status = 'ACTIVE'
                  and c.source_id in (%s)
                """.formatted(placeholders), (rs, rowNum) -> new NoteCitationPair(
                rs.getString("note_id"),
                rs.getString("source_id")
        ), args.toArray());
        Map<String, Set<String>> sourceIdsByNote = new HashMap<>();
        for (NoteCitationPair row : rows) {
            sourceIdsByNote.computeIfAbsent(row.noteId(), ignored -> new LinkedHashSet<>()).add(row.sourceId());
        }
        return sourceIdsByNote;
    }

    public Map<String, Set<String>> sourceIdsByAnsweredTurnForSources(
            String workspaceId, Collection<String> sourceIds) {
        List<String> ids = limitedIds(sourceIds, 200);
        if (ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = placeholders(ids.size());
        List<Object> args = new ArrayList<>();
        args.add(workspaceId);
        args.addAll(ids);
        List<MessageCitationPair> rows = jdbcTemplate.query("""
                select m.id as message_id, c.source_id
                from conversation_message m
                join message_citation mc on mc.message_id = m.id
                join citation c on c.id = mc.citation_id
                where m.workspace_id = ? and m.role = 'ASSISTANT'
                  and c.source_id in (%s)
                """.formatted(placeholders), (rs, rowNum) -> new MessageCitationPair(
                rs.getString("message_id"),
                rs.getString("source_id")
        ), args.toArray());
        Map<String, Set<String>> sourceIdsByMessage = new HashMap<>();
        for (MessageCitationPair row : rows) {
            sourceIdsByMessage.computeIfAbsent(row.messageId(), ignored -> new LinkedHashSet<>()).add(row.sourceId());
        }
        return sourceIdsByMessage;
    }

    public Map<String, Set<String>> sourceIdsByNote(String workspaceId) {
        List<NoteCitationPair> rows = jdbcTemplate.query("""
                select i.id as note_id, c.source_id
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                join knowledge_version_citation kvc on kvc.knowledge_version_id = v.id
                join citation c on c.id = kvc.citation_id
                where i.workspace_id = ? and i.item_type = 'NOTE' and i.status = 'ACTIVE'
                """, (rs, rowNum) -> new NoteCitationPair(
                rs.getString("note_id"),
                rs.getString("source_id")
        ), workspaceId);
        Map<String, Set<String>> sourceIdsByNote = new HashMap<>();
        for (NoteCitationPair row : rows) {
            sourceIdsByNote.computeIfAbsent(row.noteId(), ignored -> new LinkedHashSet<>()).add(row.sourceId());
        }
        return sourceIdsByNote;
    }

    public Map<String, Set<String>> sourceIdsByAnsweredTurn(String workspaceId) {
        List<MessageCitationPair> rows = jdbcTemplate.query("""
                select m.id as message_id, c.source_id
                from conversation_message m
                join message_citation mc on mc.message_id = m.id
                join citation c on c.id = mc.citation_id
                where m.workspace_id = ? and m.role = 'ASSISTANT'
                """, (rs, rowNum) -> new MessageCitationPair(
                rs.getString("message_id"),
                rs.getString("source_id")
        ), workspaceId);
        Map<String, Set<String>> sourceIdsByMessage = new HashMap<>();
        for (MessageCitationPair row : rows) {
            sourceIdsByMessage.computeIfAbsent(row.messageId(), ignored -> new LinkedHashSet<>()).add(row.sourceId());
        }
        return sourceIdsByMessage;
    }

    private List<CandidateSource> filterVisible(String workspaceId, List<CandidateSource> sources) {
        if (sources == null) {
            return List.of();
        }
        if (generatedSourceGate == null) {
            return sources;
        }
        return sources.stream()
                .filter(source -> generatedSourceGate.visible(
                        workspaceId, source.generatedBy(), source.generatedRefId()))
                .toList();
    }

    private List<String> findMatchingSourceIds(
            String workspaceId,
            Collection<String> terms,
            Collection<String> excludedIds,
            int limit,
            String termPredicate
    ) {
        List<String> patterns = likePatterns(terms);
        List<String> excluded = limitedIds(excludedIds, 200);
        if (patterns.isEmpty() || limit <= 0) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                select s.id
                from source s
                where s.workspace_id = ? and s.status = 'READY' and s.index_status = 'INDEXED'
                """);
        List<Object> args = new ArrayList<>();
        args.add(workspaceId);
        if (!excluded.isEmpty()) {
            sql.append(" and s.id not in (").append(placeholders(excluded.size())).append(')');
            args.addAll(excluded);
        }
        sql.append(" and (");
        for (int index = 0; index < patterns.size(); index++) {
            if (index > 0) {
                sql.append(" or ");
            }
            sql.append('(').append(termPredicate).append(')');
            int holes = countPlaceholders(termPredicate);
            for (int hole = 0; hole < holes; hole++) {
                args.add(patterns.get(index));
            }
        }
        sql.append("""
                )
                order by s.updated_at desc
                limit ?
                """);
        args.add(limit);
        return queryIds(sql.toString(), args);
    }

    private List<String> queryIds(String sql, List<Object> args) {
        List<String> ids = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString(1), args.toArray());
        return ids == null ? List.of() : ids;
    }

    private List<String> limitedIds(Collection<String> ids, int limit) {
        if (ids == null || ids.isEmpty() || limit <= 0) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            unique.add(id);
            if (unique.size() >= limit) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    private List<String> likePatterns(Collection<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> patterns = new LinkedHashSet<>();
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            String cleaned = term.toLowerCase(Locale.ROOT).replace("%", "").replace("_", "").replace("\\", "");
            if (cleaned.isBlank()) {
                continue;
            }
            patterns.add("%" + cleaned + "%");
            if (patterns.size() >= 12) {
                break;
            }
        }
        return List.copyOf(patterns);
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private int countPlaceholders(String sql) {
        int count = 0;
        for (int index = 0; index < sql.length(); index++) {
            if (sql.charAt(index) == '?') {
                count++;
            }
        }
        return count;
    }

    private static final String LEXICAL_TERM = """
            lower(s.title) like ?
            or lower(coalesce(s.summary, '')) like ?
            or lower(coalesce(s.tags_json, '')) like ?
            or lower(coalesce(s.metadata_json, '')) like ?
            or lower(coalesce(s.source_type, '')) like ?
            or exists (
                select 1 from source_chunk c
                join source_snapshot ss on ss.id = c.source_snapshot_id
                  and ss.index_status = 'INDEXED'
                  and ss.version_no = (
                      select max(current_ss.version_no) from source_snapshot current_ss
                      where current_ss.source_id = s.id and current_ss.index_status = 'INDEXED')
                where c.source_id = s.id and lower(c.content) like ?
            )
            """;

    private static final String TITLE_SUMMARY_TERM = """
            lower(s.title) like ? or lower(coalesce(s.summary, '')) like ?
            """;

    private static final String TAG_TERM = "lower(coalesce(s.tags_json, '')) like ?";

    private static final org.springframework.jdbc.core.RowMapper<CandidateSource> SOURCE_MAPPER = (rs, rowNum) ->
            new CandidateSource(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("source_type"),
                    rs.getInt("chunk_count"),
                    rs.getInt("window_count"),
                    rs.getString("generated_by"),
                    rs.getString("generated_ref_id"),
                    rs.getString("summary"),
                    rs.getString("tags_json"),
                    rs.getString("metadata_json"),
                    rs.getString("sample_text"),
                    0,
                    "",
                    List.of(),
                    List.of(),
                    0,
                    0,
                    "",
                    ""
            );

    private record NoteCitationPair(String noteId, String sourceId) {
    }

    private record MessageCitationPair(String messageId, String sourceId) {
    }
}

package com.noteweave.chat;

import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class NoteRecallRepository {
    private final JdbcTemplate jdbcTemplate;

    public NoteRecallRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CandidateSource> findCandidates(String workspaceId) {
        return jdbcTemplate.query("""
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
                left join source_chunk c on c.source_id = s.id
                left join source_window w on w.source_chunk_id = c.id
                where s.workspace_id = ? and s.status = 'READY'
                group by s.id, s.title, s.source_type, s.updated_at, s.summary, s.tags_json, s.metadata_json
                order by s.updated_at desc
                limit 40
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

    private record NoteCitationPair(String noteId, String sourceId) {
    }

    private record MessageCitationPair(String messageId, String sourceId) {
    }
}

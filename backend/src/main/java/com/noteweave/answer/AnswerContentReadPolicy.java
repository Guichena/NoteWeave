package com.noteweave.answer;

import com.noteweave.research.ResearchGeneratedSourceReadGate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 消息、运行快照和事件共用的正文可见性规则。 */
@Component
public class AnswerContentReadPolicy {
    private final JdbcTemplate jdbc;
    private final ResearchGeneratedSourceReadGate generatedSourceGate;

    public AnswerContentReadPolicy(JdbcTemplate jdbc, ResearchGeneratedSourceReadGate generatedSourceGate) {
        this.jdbc = jdbc;
        this.generatedSourceGate = generatedSourceGate;
    }

    public boolean canReadRun(String workspaceId, String runId) {
        return !readableRunIds(workspaceId, List.of(runId)).isEmpty();
    }

    public Set<String> readableRunIds(String workspaceId, List<String> runIds) {
        if (runIds.isEmpty()) return Set.of();
        record Run(String id, String messageId) {}
        List<Run> runs = jdbc.query("""
                select id, answer_message_id from answer_run
                where workspace_id = ? and id in (%s)
                """.formatted(placeholders(runIds)),
                (rs, row) -> new Run(rs.getString(1), rs.getString(2)), parameters(workspaceId, runIds));
        Set<String> messages = readableMessageIds(workspaceId,
                runs.stream().map(Run::messageId).filter(id -> id != null && !id.isBlank()).toList());
        Set<String> readable = new HashSet<>();
        for (Run run : runs) {
            if (run.messageId() == null || run.messageId().isBlank() || messages.contains(run.messageId())) {
                readable.add(run.id());
            }
        }
        return readable;
    }

    public Set<String> readableMessageIds(String workspaceId, List<String> messageIds) {
        if (messageIds.isEmpty()) return Set.of();
        String ids = placeholders(messageIds);
        Object[] parameters = parameters(workspaceId, messageIds);
        Set<String> readable = new HashSet<>(jdbc.queryForList("""
                select m.id from conversation_message m
                where m.workspace_id = ? and m.id in (%s) and m.context_status <> 'DELETED'
                  and not exists (
                    select 1 from answer_run r
                    left join run_input_snapshot snapshot on snapshot.answer_run_id = r.id
                    left join conversation_message query_message on query_message.id = r.query_message_id
                    where r.workspace_id = m.workspace_id
                      and (r.answer_message_id = m.id or r.assistant_request_id = m.assistant_request_id)
                      and (query_message.context_status = 'DELETED'
                           or snapshot.replay_availability = 'METADATA_ONLY')
                  )
                """.formatted(ids), String.class, parameters));
        record SourceReference(String messageId, String sourceId, String sourceWorkspace, String status,
                               String generatedBy, String generatedRefId) {}
        List<SourceReference> references = jdbc.query("""
                select refs.message_id, refs.source_id, s.workspace_id, s.status,
                       coalesce(s.generated_by, ''), coalesce(s.generated_ref_id, '')
                from (
                    select mc.message_id, c.source_id
                    from conversation_message m
                    join message_citation mc on mc.message_id = m.id
                    join citation c on c.id = mc.citation_id
                    where m.workspace_id = ? and m.id in (%s)
                    union
                    select r.answer_message_id, e.source_id
                    from answer_run r join answer_run_evidence_manifest e on e.answer_run_id = r.id
                    where r.workspace_id = ? and r.answer_message_id in (%s) and e.source_id is not null
                ) refs left join source s on s.id = refs.source_id
                """.formatted(ids, ids), (rs, row) -> new SourceReference(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)),
                combined(parameters, parameters));
        for (SourceReference reference : references) {
            if (!readable.contains(reference.messageId())) continue;
            if (!workspaceId.equals(reference.sourceWorkspace()) || !"READY".equals(reference.status())) {
                readable.remove(reference.messageId());
            } else if (!generatedSourceGate.visible(workspaceId, reference.generatedBy(), reference.generatedRefId())) {
                readable.remove(reference.messageId());
            }
        }
        return Set.copyOf(readable);
    }

    private static String placeholders(List<String> ids) {
        return String.join(",", Collections.nCopies(ids.size(), "?"));
    }

    private static Object[] parameters(String workspaceId, List<String> ids) {
        List<Object> values = new ArrayList<>();
        values.add(workspaceId);
        values.addAll(ids);
        return values.toArray();
    }

    private static Object[] combined(Object[] first, Object[] second) {
        Object[] values = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, values, first.length, second.length);
        return values;
    }
}

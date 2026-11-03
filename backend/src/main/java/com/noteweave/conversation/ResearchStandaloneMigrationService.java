package com.noteweave.conversation;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 历史独立研究迁入对话。只补会话和消息指针，不删除研究、不重新执行。
 * 已经挂在对话上的运行跳过，重复调用幂等。
 */
@Service
public class ResearchStandaloneMigrationService {

    private final JdbcTemplate jdbcTemplate;

    public ResearchStandaloneMigrationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Receipt migrateWorkspace(String workspaceId) {
        requireWorkspace(workspaceId);
        List<String> runIds = jdbcTemplate.queryForList("""
                select id from research_run
                where workspace_id = ? and conversation_id is null
                order by created_at, id
                """, String.class, workspaceId);
        int migrated = 0;
        List<String> conversationIds = new ArrayList<>();
        for (String runId : runIds) {
            String conversationId = attach(workspaceId, runId);
            if (conversationId != null) {
                migrated++;
                conversationIds.add(conversationId);
            }
        }
        return new Receipt(migrated, runIds.size() - migrated, List.copyOf(conversationIds));
    }

    @Transactional
    public Receipt migrateOne(String workspaceId, String runId) {
        requireWorkspace(workspaceId);
        String conversationId = attach(workspaceId, runId);
        if (conversationId == null) {
            Integer exists = jdbcTemplate.queryForObject("""
                    select count(*) from research_run where workspace_id = ? and id = ?
                    """, Integer.class, workspaceId, runId);
            if (exists == null || exists == 0) {
                throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "研究运行不存在", HttpStatus.NOT_FOUND);
            }
            return new Receipt(0, 1, List.of());
        }
        return new Receipt(1, 0, List.of(conversationId));
    }

    private String attach(String workspaceId, String runId) {
        StandaloneRun run = jdbcTemplate.query("""
                select question, final_report_markdown, conversation_id
                from research_run where workspace_id = ? and id = ? for update
                """, rs -> rs.next() ? new StandaloneRun(
                rs.getString("question"), rs.getString("final_report_markdown"),
                rs.getString("conversation_id")) : null, workspaceId, runId);
        if (run == null || run.conversationId() != null) {
            return null;
        }
        String conversationId = Ids.newId();
        String userMessageId = Ids.newId();
        String assistantMessageId = Ids.newId();
        String question = run.question() == null || run.question().isBlank() ? "历史研究" : run.question().strip();
        String title = question.length() <= 80 ? question : question.substring(0, 80);
        jdbcTemplate.update("""
                insert into conversation(id, workspace_id, title, conversation_type, status, next_message_seq)
                values (?, ?, ?, 'RESEARCH', 'ACTIVE', 3)
                """, conversationId, workspaceId, title);
        jdbcTemplate.update("""
                insert into conversation_message(
                    id, conversation_id, workspace_id, message_seq, role, answer_mode, content, context_status)
                values (?, ?, ?, 1, 'USER', 'DEEP_RESEARCH', ?, 'CURRENT')
                """, userMessageId, conversationId, workspaceId, question);
        jdbcTemplate.update("""
                insert into conversation_message(
                    id, conversation_id, workspace_id, message_seq, role, answer_mode, content,
                    context_status, reply_to_message_id)
                values (?, ?, ?, 2, 'ASSISTANT', 'DEEP_RESEARCH', ?, 'CURRENT', ?)
                """, assistantMessageId, conversationId, workspaceId,
                run.reportMarkdown() == null ? "" : run.reportMarkdown(), userMessageId);
        int updated = jdbcTemplate.update("""
                update research_run
                set conversation_id = ?, query_message_id = ?, answer_message_id = ?,
                    updated_at = current_timestamp
                where workspace_id = ? and id = ? and conversation_id is null
                """, conversationId, userMessageId, assistantMessageId, workspaceId, runId);
        if (updated != 1) {
            throw new BusinessException("RESEARCH_STANDALONE_ATTACH_FAILED",
                    "历史研究未能挂到对话", HttpStatus.CONFLICT);
        }
        return conversationId;
    }

    private void requireWorkspace(String workspaceId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from workspace where id = ?", Integer.class, workspaceId);
        if (count == null || count == 0) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在", HttpStatus.NOT_FOUND);
        }
    }

    private record StandaloneRun(String question, String reportMarkdown, String conversationId) { }

    public record Receipt(int migrated, int skipped, List<String> conversationIds) {
        public Receipt {
            conversationIds = conversationIds == null ? List.of() : List.copyOf(conversationIds);
        }
    }
}

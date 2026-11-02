package com.noteweave.answer;

import com.noteweave.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 发送前的持久回放资格。普通取消/历史METADATA_ONLY不等同正文撤销。 */
@Service
public class AnswerReplayAccessGuard {
    private final JdbcTemplate jdbc;
    public AnswerReplayAccessGuard(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public boolean isReadable(String runId) { return readable(null,runId); }

    public void requireReadable(String workspaceId,String runId) {
        if(!readable(workspaceId,runId)) throw new BusinessException(
                "ANSWER_REPLAY_REVOKED","回答正文已撤销或不可读取",HttpStatus.CONFLICT);
    }

    private boolean readable(String workspaceId,String runId) {
        if(runId==null || runId.isBlank()) return false;
        Integer count=jdbc.queryForObject("""
                select count(*) from answer_run r
                join conversation_message q on q.id=r.query_message_id
                  and q.workspace_id=r.workspace_id and q.conversation_id=r.conversation_id
                join conversation_message a on a.id=r.answer_message_id
                  and a.workspace_id=r.workspace_id and a.conversation_id=r.conversation_id
                where r.id=? and (? is null or r.workspace_id=?)
                  and coalesce(r.error_code,'') not in ('ANSWER_MESSAGE_DELETED','ANSWER_EVIDENCE_REVOKED')
                  and q.context_status<>'DELETED' and a.context_status<>'DELETED'
                """,Integer.class,runId,workspaceId,workspaceId);
        return count!=null && count==1;
    }
}

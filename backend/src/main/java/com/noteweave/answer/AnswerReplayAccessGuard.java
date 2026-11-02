package com.noteweave.answer;

import com.noteweave.common.BusinessException;
import java.util.List;
import java.util.Map;
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

    /** 传输层用：只判断是否已撤销。没有运行记录的事件（研究进度等）不算撤销。 */
    public boolean isRevoked(String runId) {
        if(runId==null || runId.isBlank()) return false;
        List<Map<String,Object>> rows=load(runId);
        return !rows.isEmpty() && revoked(rows.get(0));
    }

    /**
     * 只拦真正撤销的回答正文。没有运行记录一律不可读，非回答事件由调用方先判断类型；
     * 尚未落回答消息的运行仍可读，回答消息被删除时按 error_code 与消息状态判定。
     */
    private boolean readable(String workspaceId,String runId) {
        if(runId==null || runId.isBlank()) return false;
        List<Map<String,Object>> rows=load(runId);
        if(rows.isEmpty()) return false;
        Map<String,Object> row=rows.get(0);
        if(workspaceId!=null && !workspaceId.isBlank()
                && !workspaceId.equals(String.valueOf(row.get("workspace_id")))) return false;
        return !revoked(row);
    }

    private List<Map<String,Object>> load(String runId) {
        return jdbc.queryForList("""
                select r.workspace_id as workspace_id, r.error_code as error_code,
                       q.context_status as query_status, a.context_status as answer_status
                from answer_run r
                left join conversation_message q on q.id=r.query_message_id
                  and q.workspace_id=r.workspace_id and q.conversation_id=r.conversation_id
                left join conversation_message a on a.id=r.answer_message_id
                  and a.workspace_id=r.workspace_id and a.conversation_id=r.conversation_id
                where r.id=?
                """,runId);
    }

    private boolean revoked(Map<String,Object> row) {
        String errorCode=row.get("error_code")==null?null:String.valueOf(row.get("error_code"));
        if("ANSWER_MESSAGE_DELETED".equals(errorCode) || "ANSWER_EVIDENCE_REVOKED".equals(errorCode)) return true;
        if("DELETED".equals(row.get("query_status"))) return true;
        return "DELETED".equals(row.get("answer_status"));
    }
}

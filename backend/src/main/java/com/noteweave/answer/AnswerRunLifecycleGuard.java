package com.noteweave.answer;

import com.noteweave.chat.CitationBindingService;
import com.noteweave.common.BusinessException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 状态仲裁辅助类。锁方法加入调用方事务，不能在网络生成期间保留锁。 */
@Service
public class AnswerRunLifecycleGuard {
    private final JdbcTemplate jdbc;
    private final CitationBindingService citations;
    public AnswerRunLifecycleGuard(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        this.citations=new CitationBindingService(jdbc);
    }
    public record LockedRun(String runId,String conversationId,String queryMessageId,
                            String answerMessageId,String status,String streamOwner) {}

    public LockedRun lockForPreparation(String workspaceId,String runId,List<String> existingCitationIds) {
        LockedRun run=load(workspaceId,runId,false);
        citations.lockBindingConversations(workspaceId,run.conversationId(),existingCitationIds);
        run=load(workspaceId,runId,true);
        requireMessages(workspaceId,run,true);
        if (!List.of("PREPARING","RETRIEVING").contains(run.status())) throw revoked("回答不再允许准备");
        return run;
    }

    public LockedRun lockForCompletion(String workspaceId,String runId,String owner) {
        LockedRun run=load(workspaceId,runId,false);
        citations.lockBindingConversations(workspaceId,run.conversationId(),List.of());
        run=load(workspaceId,runId,true);
        requireMessages(workspaceId,run,true);
        if (!"GENERATING".equals(run.status()) || owner == null || !owner.equals(run.streamOwner()))
            throw revoked("回答已取消、失败或流归属已变化");
        requireLease(workspaceId,runId,owner);
        return run;
    }

    public void requireStreamEligible(String workspaceId,String runId,String owner) {
        LockedRun run=load(workspaceId,runId,false);
        requireMessages(workspaceId,run,false);
        if (!"GENERATING".equals(run.status()) || owner == null || !owner.equals(run.streamOwner()))
            throw revoked("回答已停止生成");
        requireLease(workspaceId,runId,owner);
    }

    /** 消息删除与完成使用相同conversation锁；终态撤销同样阻止已完成回放。 */
    public List<String> revokeForDeletedMessage(String workspaceId,String conversationId,String messageId) {
        jdbc.queryForObject("select id from conversation where workspace_id=? and id=? for update",String.class,workspaceId,conversationId);
        List<String> ids=jdbc.queryForList("""
                select id from answer_run where workspace_id=? and conversation_id=?
                  and (query_message_id=? or answer_message_id=? or id in (
                      select answer_run_id from run_input_snapshot where workspace_id=?
                        and execution_kind='ANSWER' and snapshot_json like ?)) order by id for update
                """,String.class,workspaceId,conversationId,messageId,messageId,workspaceId,"%"+messageId+"%");
        for (String id:ids) {
            jdbc.update("""
                    update answer_run set status='CANCELLED',error_code='ANSWER_MESSAGE_DELETED',
                        error_message='关联消息已删除',stream_owner=null,stream_lease_until=null,
                        finished_at=coalesce(finished_at,current_timestamp),version=version+1,updated_at=current_timestamp
                    where workspace_id=? and id=?
                    """,workspaceId,id);
        }
        return ids;
    }

    private LockedRun load(String workspaceId,String runId,boolean lock) {
        LockedRun run=jdbc.query("select id,conversation_id,query_message_id,answer_message_id,status,stream_owner from answer_run where workspace_id=? and id=?"+(lock?" for update":""),
                rs->rs.next()?new LockedRun(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)):null,workspaceId,runId);
        if(run==null) throw revoked("回答运行不存在");
        return run;
    }
    private void requireMessages(String workspaceId,LockedRun run,boolean lock) {
        if(run.queryMessageId()==null || run.answerMessageId()==null) throw revoked("回答消息身份不完整");
        for(String id:List.of(run.queryMessageId(),run.answerMessageId()).stream().distinct().sorted().toList()) {
            String state=jdbc.query("select context_status from conversation_message where workspace_id=? and conversation_id=? and id=?"+(lock?" for update":""),
                    rs->rs.next()?rs.getString(1):null,workspaceId,run.conversationId(),id);
            if(state==null || "DELETED".equals(state) || "FAILED".equals(state)) throw revoked("关联消息不可用");
        }
    }
    private void requireLease(String workspaceId,String runId,String owner) {
        Integer valid=jdbc.queryForObject("select count(*) from answer_run where workspace_id=? and id=? and stream_owner=? and stream_lease_until>=current_timestamp",Integer.class,workspaceId,runId,owner);
        if(valid==null || valid!=1) throw revoked("生成租约已过期");
    }
    private BusinessException revoked(String message) {
        return new BusinessException("ANSWER_RUN_REVOKED",message,HttpStatus.CONFLICT);
    }
}

package com.noteweave.chat;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 跨生命周期绑定只复制消息自有引用；来源身份保持不变，便于合规撤销反查。 */
@Service
public class CitationBindingService {
    private final JdbcTemplate jdbc;
    private final com.noteweave.answer.CitationLockOrder lockOrder;
    public CitationBindingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.lockOrder = new com.noteweave.answer.CitationLockOrder(jdbc);
    }

    public record BindResult(List<String> citationIds, Map<String, String> copiedIds) {
        public BindResult { citationIds = List.copyOf(citationIds); copiedIds = Map.copyOf(copiedIds); }
    }

    /** 必须在调用方取得 run/message/knowledge item 锁之前调用。 */
    public void lockBindingConversations(String workspaceId, String targetConversationId, List<String> ids) {
        lockOrder.lockBindingConversations(workspaceId, targetConversationId, ids);
    }

    /** 全部父行先于会话锁；防止INSERT citation的外键父锁与来源撤销反序。 */
    public void lockCitationParents(String workspaceId, List<String> citationIds) {
        lockOrder.lockCitationParents(workspaceId, citationIds);
    }

    /** 准备入口必须一次传入新证据和既有引用，不能锁完一组后再追加另一组父行。 */
    public void lockEvidenceParents(String workspaceId, com.noteweave.answer.strategy.EvidenceBundle bundle,
                                    List<String> citationIds) {
        lockOrder.lockEvidenceParents(workspaceId, bundle, citationIds);
    }

    private void lockParents(String workspaceId, List<ParentRef> parents) {
        List<String> sources = parents.stream().map(ParentRef::sourceId).filter(this::present).distinct().sorted().toList();
        List<String> snapshots = parents.stream().map(ParentRef::snapshotId).filter(this::present).distinct().sorted().toList();
        List<String> chunks = parents.stream().map(ParentRef::chunkId).filter(this::present).distinct().sorted().toList();
        for (String id : sources) {
            List<String> states = jdbc.queryForList("select status from source where workspace_id=? and id=? for update",String.class,workspaceId,id);
            if (states.size()!=1 || !"READY".equals(states.get(0))) throw invalid("引用来源不再具备当前资格");
        }
        for (String id : snapshots) {
            List<String> rows = jdbc.queryForList("select ss.id from source_snapshot ss join source s on s.id=ss.source_id where s.workspace_id=? and ss.id=? for update",String.class,workspaceId,id);
            if (rows.size()!=1) throw invalid("引用快照不存在");
        }
        for (ParentRef parent : parents) {
            if (!present(parent.sourceId())) continue;
            if (!present(parent.snapshotId())) throw invalid("引用缺少绑定快照");
            Integer current = jdbc.queryForObject("""
                    select count(*) from source_snapshot ss where ss.id=? and ss.source_id=?
                      and ss.version_no=(select max(latest.version_no) from source_snapshot latest where latest.source_id=ss.source_id)
                    """, Integer.class, parent.snapshotId(), parent.sourceId());
            if (current == null || current != 1) throw invalid("引用绑定快照已过期");
        }
        for (String id : chunks) {
            List<String> rows = jdbc.queryForList("select id from source_chunk where workspace_id=? and id=? for update",String.class,workspaceId,id);
            if (rows.size()!=1) throw invalid("引用分块不存在");
        }
    }

    private boolean present(String value) { return value != null && !value.isBlank(); }
    private record ParentRef(String sourceId, String snapshotId, String chunkId) {}

    @Transactional
    public BindResult bindToMessage(String workspaceId, String targetMessageId, List<String> ids, int sortOffset) {
        String conversation = jdbc.query("select conversation_id from conversation_message where workspace_id=? and id=?",
                rs -> rs.next() ? rs.getString(1) : null, workspaceId, targetMessageId);
        if (conversation == null) throw invalid("目标消息不存在");
        lockBindingConversations(workspaceId, conversation, ids);
        Integer alive = jdbc.queryForObject("select count(*) from conversation_message where workspace_id=? and id=? and context_status <> 'DELETED'",
                Integer.class, workspaceId, targetMessageId);
        if (alive == null || alive != 1) throw invalid("目标消息已删除");
        return bind(workspaceId, targetMessageId, null, ids, sortOffset);
    }

    @Transactional
    public BindResult bindToKnowledgeVersion(String workspaceId, String versionId, List<String> ids) {
        lockBindingConversations(workspaceId, null, ids);
        Integer owned = jdbc.queryForObject("select count(*) from knowledge_version v join knowledge_item i on i.id=v.item_id where v.id=? and i.workspace_id=? and i.status='ACTIVE'",
                Integer.class, versionId, workspaceId);
        if (owned == null || owned != 1) throw invalid("目标知识版本不可用");
        return bind(workspaceId, null, versionId, ids, 0);
    }

    private BindResult bind(String workspaceId, String messageId, String versionId, List<String> input, int offset) {
        List<String> ids = distinct(input);
        Map<String, CitationRow> locked = new LinkedHashMap<>();
        for (String id : ids.stream().sorted().toList()) {
            CitationRow row = jdbc.query("select id,owner_message_id from citation where workspace_id=? and id=? for update",
                    rs -> rs.next() ? new CitationRow(rs.getString(1),rs.getString(2)) : null, workspaceId,id);
            if (row == null) throw invalid("引用不存在或不属于当前工作台");
            locked.put(id, row);
        }
        List<String> actual = new ArrayList<>();
        Map<String,String> copies = new LinkedHashMap<>();
        for (String id : ids) {
            CitationRow row = locked.get(id);
            String boundId = id;
            if (row.owner() != null && !row.owner().isBlank() && !row.owner().equals(messageId)) {
                requirePublishedOwner(workspaceId, row.owner());
                boundId = Ids.newId();
                int copied = jdbc.update("""
                        insert into citation(id,workspace_id,source_id,source_snapshot_id,source_chunk_id,
                            title,quote_text,page_no,location_info,research_run_id,research_evidence_key,owner_message_id)
                        select ?,workspace_id,source_id,source_snapshot_id,source_chunk_id,
                            title,quote_text,page_no,location_info,research_run_id,research_evidence_key,?
                        from citation where workspace_id=? and id=?
                        """, boundId,messageId,workspaceId,id);
                if (copied != 1) throw invalid("引用复制未成功");
                copies.put(id,boundId);
            }
            if (messageId != null) {
                jdbc.update("insert into message_citation(id,message_id,citation_id,sort_order) values (?,?,?,?)",
                        Ids.newId(),messageId,boundId,offset + actual.size());
            } else {
                jdbc.update("insert into knowledge_version_citation(id,knowledge_version_id,citation_id,sort_order) values (?,?,?,?)",
                        Ids.newId(),versionId,boundId,actual.size());
            }
            actual.add(boundId);
        }
        return new BindResult(actual,copies);
    }

    /** 保存消息入口先锁源会话，再读取引用，禁止删除后被当作无来源笔记保存。 */
    public List<String> citationIdsForPublishedMessage(String workspaceId, String messageId) {
        String conversation = jdbc.query("select conversation_id from conversation_message where workspace_id=? and id=?",
                rs -> rs.next() ? rs.getString(1) : null,workspaceId,messageId);
        if(conversation==null) throw invalid("来源消息不存在");
        List<String> observed = jdbc.queryForList("select citation_id from message_citation where message_id=? order by sort_order,id",String.class,messageId);
        lockBindingConversations(workspaceId,conversation,observed);
        Integer alive=jdbc.queryForObject("select count(*) from conversation_message where workspace_id=? and id=? and role='ASSISTANT' and context_status<>'DELETED'",Integer.class,workspaceId,messageId);
        if(alive==null || alive!=1) throw invalid("来源助手消息不可用");
        List<String> states=jdbc.queryForList("select status from answer_run where workspace_id=? and answer_message_id=?",String.class,workspaceId,messageId);
        if(!states.isEmpty() && states.stream().anyMatch(s -> !"COMPLETED".equals(s)))
            throw invalid("未发布消息不能保存引用");
        List<String> ids=jdbc.queryForList("select citation_id from message_citation where message_id=? order by sort_order,id",String.class,messageId);
        if(!ids.equals(observed)) throw invalid("消息引用在绑定准备期间已变化");
        if(states.isEmpty()) {
            Integer owned=jdbc.queryForObject("select count(*) from citation where workspace_id=? and owner_message_id=?",Integer.class,workspaceId,messageId);
            if(owned!=null && owned>0) throw invalid("消息自有引用缺少已完成运行");
        }
        return ids;
    }

    private void requirePublishedOwner(String workspaceId, String messageId) {
        Integer published = jdbc.queryForObject("""
                select count(*) from conversation_message m
                join answer_run r on r.answer_message_id=m.id and r.workspace_id=m.workspace_id
                join conversation_message q on q.id=r.query_message_id and q.workspace_id=r.workspace_id
                where m.workspace_id=? and m.id=? and m.role='ASSISTANT'
                  and m.context_status <> 'DELETED' and q.context_status <> 'DELETED'
                  and r.status='COMPLETED'
                """, Integer.class,workspaceId,messageId);
        if (published == null || published != 1) throw invalid("未发布或已删除消息的引用不能跨生命周期绑定");
    }

    /** 调用方已持有消息会话锁；历史共享 owner 行只解除本消息绑定，不清正文。 */
    public void unbindAndRedactOwned(String workspaceId, String messageId) {
        List<String> owned = jdbc.queryForList("select id from citation where workspace_id=? and owner_message_id=? order by id for update",
                String.class,workspaceId,messageId);
        jdbc.update("delete from message_citation where message_id=?",messageId);
        for (String id : owned) {
            jdbc.update("""
                    update citation set title='',quote_text='',location_info=null
                    where workspace_id=? and id=?
                      and not exists (select 1 from message_citation mc where mc.citation_id=citation.id)
                      and not exists (select 1 from knowledge_version_citation kc where kc.citation_id=citation.id)
                    """,workspaceId,id);
        }
    }

    private List<String> distinct(List<String> ids) {
        if (ids == null) return List.of();
        if (ids.stream().anyMatch(id -> id == null || id.isBlank())) throw invalid("引用身份不能为空");
        return List.copyOf(new LinkedHashSet<>(ids));
    }
    private BusinessException invalid(String message) {
        return new BusinessException("CITATION_BINDING_NOT_ELIGIBLE",message,HttpStatus.CONFLICT);
    }
    private record CitationRow(String id,String owner) {}
}

package com.noteweave.answer;

import com.noteweave.common.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 引用父行与会话的加锁顺序。
 *
 * <p>顺序固定为 source、source_snapshot、source_chunk、conversation，全部按 id 升序，
 * 且父行先于会话行，避免 INSERT citation 的外键父锁与来源撤销反序。
 * 放在 answer 包内，运行生命周期守卫不再依赖 chat 包的绑定实现。</p>
 */
public class CitationLockOrder {
    private final JdbcTemplate jdbc;

    public CitationLockOrder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 必须在调用方取得 run/message/knowledge item 锁之前调用。 */
    public void lockBindingConversations(String workspaceId, String targetConversationId, List<String> ids) {
        lockCitationParents(workspaceId, ids);
        TreeSet<String> conversations = new TreeSet<>();
        if (targetConversationId != null && !targetConversationId.isBlank()) {
            conversations.add(targetConversationId);
        }
        for (String id : distinct(ids)) {
            conversations.addAll(jdbc.queryForList("""
                    select m.conversation_id from citation c
                    join conversation_message m on m.id = c.owner_message_id
                    where c.workspace_id = ? and c.id = ? and m.workspace_id = ?
                    """, String.class, workspaceId, id, workspaceId));
        }
        for (String id : conversations) {
            List<String> locked = jdbc.queryForList(
                    "select id from conversation where workspace_id = ? and id = ? for update",
                    String.class, workspaceId, id);
            if (locked.size() != 1) {
                throw invalid("引用所属会话不可用");
            }
        }
    }

    /** 全部父行先于会话锁；防止 INSERT citation 的外键父锁与来源撤销反序。 */
    public void lockCitationParents(String workspaceId, List<String> citationIds) {
        List<ParentRef> parents = new ArrayList<>();
        for (String id : distinct(citationIds)) {
            parents.addAll(jdbc.query("select source_id,source_snapshot_id,source_chunk_id from citation where workspace_id=? and id=?",
                    (rs, n) -> new ParentRef(rs.getString(1), rs.getString(2), rs.getString(3)), workspaceId, id));
        }
        lockParents(workspaceId, parents);
    }

    /** 准备入口必须一次传入新证据和既有引用，不能锁完一组后再追加另一组父行。 */
    public void lockEvidenceParents(String workspaceId, com.noteweave.answer.strategy.EvidenceBundle bundle,
                                    List<String> citationIds) {
        List<ParentRef> parents = new ArrayList<>();
        if (bundle != null) {
            for (var evidence : bundle.evidence()) {
                if ("PASSAGE".equals(evidence.kind())) {
                    parents.add(new ParentRef(
                            evidence.sourceId(), evidence.sourceSnapshotId(), evidence.passageId()));
                }
            }
        }
        for (String id : distinct(citationIds)) {
            parents.addAll(jdbc.query("select source_id,source_snapshot_id,source_chunk_id from citation where workspace_id=? and id=?",
                    (rs, n) -> new ParentRef(rs.getString(1), rs.getString(2), rs.getString(3)), workspaceId, id));
        }
        lockParents(workspaceId, parents);
    }

    private void lockParents(String workspaceId, List<ParentRef> parents) {
        List<String> sources = parents.stream().map(ParentRef::sourceId).filter(this::present).distinct().sorted().toList();
        List<String> snapshots = parents.stream().map(ParentRef::snapshotId).filter(this::present).distinct().sorted().toList();
        List<String> chunks = parents.stream().map(ParentRef::chunkId).filter(this::present).distinct().sorted().toList();
        for (String id : sources) {
            List<String> states = jdbc.queryForList(
                    "select status from source where workspace_id=? and id=? for update", String.class, workspaceId, id);
            if (states.size() != 1 || !"READY".equals(states.get(0))) {
                throw invalid("引用来源不再具备当前资格");
            }
        }
        for (String id : snapshots) {
            List<String> rows = jdbc.queryForList(
                    "select ss.id from source_snapshot ss join source s on s.id=ss.source_id where s.workspace_id=? and ss.id=? for update",
                    String.class, workspaceId, id);
            if (rows.size() != 1) {
                throw invalid("引用快照不存在");
            }
        }
        for (ParentRef parent : parents) {
            if (!present(parent.sourceId())) {
                continue;
            }
            if (!present(parent.snapshotId())) {
                throw invalid("引用缺少绑定快照");
            }
            Integer current = jdbc.queryForObject("""
                    select count(*) from source_snapshot ss where ss.id=? and ss.source_id=?
                      and ss.version_no=(select max(latest.version_no) from source_snapshot latest where latest.source_id=ss.source_id)
                    """, Integer.class, parent.snapshotId(), parent.sourceId());
            if (current == null || current != 1) {
                throw invalid("引用绑定快照已过期");
            }
        }
        for (String id : chunks) {
            List<String> rows = jdbc.queryForList(
                    "select id from source_chunk where workspace_id=? and id=? for update", String.class, workspaceId, id);
            if (rows.size() != 1) {
                throw invalid("引用分块不存在");
            }
        }
    }

    private List<String> distinct(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(this::present).distinct().toList();
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private BusinessException invalid(String message) {
        return new BusinessException("CITATION_BINDING_INVALID", message, HttpStatus.CONFLICT);
    }

    private record ParentRef(String sourceId, String snapshotId, String chunkId) {
    }
}

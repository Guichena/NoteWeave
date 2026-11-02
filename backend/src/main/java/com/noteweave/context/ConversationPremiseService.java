package com.noteweave.context;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F2-04 会话前提的持久化入口：折叠按来源消息与抽取版本幂等，生效前提在冻结截止内读取。
 * 前提只描述用户陈述，不登记引用、不进入长期记忆；删除来源消息时撤销并清空展示文本。
 */
@Service
public class ConversationPremiseService {

    private final JdbcTemplate jdbcTemplate;
    private final PremiseResolver resolver = new PremiseResolver();

    public ConversationPremiseService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 冻结截止内仍然生效的前提，按来源序号升序。 */
    public List<ConversationPremise> effective(String workspaceId, String conversationId, int cutoffSeq) {
        return jdbcTemplate.query("""
                select id, workspace_id, conversation_id, subject_key, premise_text, source_message_id,
                       source_seq, scope, topic_id, valid_from_seq, invalid_after_seq, status,
                       superseded_by, extraction_version
                from conversation_premise
                where workspace_id = ? and conversation_id = ? and status = 'ACTIVE'
                  and valid_from_seq <= ?
                  and (invalid_after_seq is null or invalid_after_seq > ?)
                order by source_seq asc, id asc
                """, (rs, rowNum) -> map(rs), workspaceId, conversationId, cutoffSeq, cutoffSeq);
    }

    /** 会话内全部前提行，供折叠时判断幂等与目标。 */
    public List<ConversationPremise> all(String workspaceId, String conversationId) {
        return jdbcTemplate.query("""
                select id, workspace_id, conversation_id, subject_key, premise_text, source_message_id,
                       source_seq, scope, topic_id, valid_from_seq, invalid_after_seq, status,
                       superseded_by, extraction_version
                from conversation_premise
                where workspace_id = ? and conversation_id = ?
                order by source_seq asc, id asc
                """, (rs, rowNum) -> map(rs), workspaceId, conversationId);
    }

    /** 折叠一次抽取；重复来源与重复操作由 {@link PremiseResolver} 拒绝。 */
    @Transactional
    public PremiseResolver.Resolution apply(
            String workspaceId,
            String conversationId,
            List<PremiseOp> operations
    ) {
        PremiseResolver.Resolution resolution = resolver.resolve(new PremiseResolver.Input(
                workspaceId, conversationId, all(workspaceId, conversationId), operations));
        List<ConversationPremise> touched = new ArrayList<>(resolution.inserted());
        touched.addAll(resolution.updated());
        for (ConversationPremise premise : touched) {
            write(premise);
        }
        return resolution;
    }

    /** 删除来源消息时撤销前提并清空展示文本；返回受影响行数。 */
    @Transactional
    public int revokeBySourceMessage(String workspaceId, String sourceMessageId) {
        return jdbcTemplate.update("""
                update conversation_premise
                set status = 'REVOKED', premise_text = '', invalid_after_seq = null,
                    updated_at = current_timestamp
                where workspace_id = ? and source_message_id = ?
                  and status in ('ACTIVE', 'UNRESOLVED')
                """, workspaceId, sourceMessageId);
    }

    private void write(ConversationPremise premise) {
        int updated = jdbcTemplate.update("""
                update conversation_premise
                set subject_key = ?, premise_text = ?, source_message_id = ?, source_seq = ?,
                    scope = ?, topic_id = ?, valid_from_seq = ?, invalid_after_seq = ?,
                    status = ?, superseded_by = ?, extraction_version = ?,
                    updated_at = current_timestamp
                where id = ?
                """, premise.subjectKey(), premise.premiseText(), premise.sourceMessageId(),
                premise.sourceSeq(), premise.scope().dbScope(), blankToNull(premise.topicId()),
                premise.validFromSeq(), premise.invalidAfterSeq(), premise.status(),
                blankToNull(premise.supersededBy()), premise.extractionVersion(), premise.premiseId());
        if (updated > 0) {
            return;
        }
        jdbcTemplate.update("""
                insert into conversation_premise(
                    id, workspace_id, conversation_id, subject_key, premise_text, source_message_id,
                    source_seq, scope, topic_id, valid_from_seq, invalid_after_seq, status,
                    superseded_by, extraction_version
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, premise.premiseId(), premise.workspaceId(), premise.conversationId(),
                premise.subjectKey(), premise.premiseText(), premise.sourceMessageId(),
                premise.sourceSeq(), premise.scope().dbScope(), blankToNull(premise.topicId()),
                premise.validFromSeq(), premise.invalidAfterSeq(), premise.status(),
                blankToNull(premise.supersededBy()), premise.extractionVersion());
    }

    private ConversationPremise map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Integer invalidAfter = (Integer) rs.getObject("invalid_after_seq");
        return new ConversationPremise(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("conversation_id"),
                rs.getString("subject_key"),
                rs.getString("premise_text"),
                rs.getString("source_message_id"),
                rs.getInt("source_seq"),
                ConversationPremise.PremiseScope.fromDb(rs.getString("scope")),
                rs.getString("topic_id"),
                rs.getInt("valid_from_seq"),
                invalidAfter,
                rs.getString("status"),
                rs.getString("superseded_by"),
                rs.getString("extraction_version"));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

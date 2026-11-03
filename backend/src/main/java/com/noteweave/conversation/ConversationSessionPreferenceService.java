package com.noteweave.conversation;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.memory.CanonicalMemoryReviewService;
import com.noteweave.memory.MemoryReviewDecisionRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F5-03：只用于本对话的确认。
 *
 * <p>“只用于本对话”必须同时做两件事：把原候选按既有生命周期拒绝，避免留下已生效的长期记忆；
 * 再生成一条会话要求，只在本对话后续回答中使用。重复点击按来源修订幂等。</p>
 */
@Service
public class ConversationSessionPreferenceService {

    private static final int MAX_REQUIREMENT_CHARS = 500;

    private final JdbcTemplate jdbcTemplate;
    private final CanonicalMemoryReviewService reviewService;

    public ConversationSessionPreferenceService(
            JdbcTemplate jdbcTemplate,
            CanonicalMemoryReviewService reviewService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.reviewService = reviewService;
    }

    @Transactional
    public SessionRequirement sessionOnly(
            String workspaceId,
            String conversationId,
            String revisionId
    ) {
        requireConversation(workspaceId, conversationId);
        RevisionRow revision = jdbcTemplate.query("""
                select r.id, r.status, r.display_text
                from memory_runtime_revision r
                join memory_item i on i.id = r.memory_item_id
                where r.id = ? and i.workspace_id = ?
                """, rs -> rs.next() ? new RevisionRow(
                rs.getString("id"), rs.getString("status"), rs.getString("display_text")) : null,
                revisionId, workspaceId);
        if (revision == null) {
            throw new BusinessException("MEMORY_REVISION_NOT_FOUND",
                    "Memory revision 不存在或不属于当前工作台", HttpStatus.NOT_FOUND);
        }
        SessionRequirement existing = findBySourceRevision(workspaceId, revisionId);
        if (existing != null) {
            return existing;
        }
        if (!"PROPOSED".equals(revision.status())) {
            throw new BusinessException("MEMORY_REVISION_NOT_PROPOSED",
                    "只有待确认的 Memory 候选可以只用于本对话", HttpStatus.CONFLICT);
        }
        String text = revision.displayText() == null ? "" : revision.displayText().strip();
        if (text.isBlank()) {
            throw new BusinessException("MEMORY_REVISION_TEXT_EMPTY",
                    "Memory 候选没有可用的要求文本", HttpStatus.CONFLICT);
        }
        if (text.length() > MAX_REQUIREMENT_CHARS) {
            text = text.substring(0, MAX_REQUIREMENT_CHARS);
        }
        // 先拒绝原候选：不留下已生效的长期记忆；事件与候选状态由既有 review 生命周期处理。
        reviewService.review(workspaceId, revisionId,
                new MemoryReviewDecisionRequest("REJECT", "只用于本对话"));
        String id = Ids.newId();
        jdbcTemplate.update("""
                insert into conversation_session_requirement(
                    id, workspace_id, conversation_id, source_revision_id, requirement_text, status)
                values (?, ?, ?, ?, ?, 'ACTIVE')
                """, id, workspaceId, conversationId, revisionId, text);
        return new SessionRequirement(id, conversationId, revisionId, text, "ACTIVE");
    }

    public List<String> activeRequirementTexts(String workspaceId, String conversationId) {
        if (workspaceId == null || workspaceId.isBlank()
                || conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.queryForList("""
                select requirement_text from conversation_session_requirement
                where workspace_id = ? and conversation_id = ? and status = 'ACTIVE'
                order by created_at, id
                """, String.class, workspaceId, conversationId);
    }

    /** 用户在对话里直接写下的“只用于本对话”要求，不依赖 Memory 候选。 */
    @Transactional
    public SessionRequirement addText(String workspaceId, String conversationId, String text) {
        requireConversation(workspaceId, conversationId);
        String requirement = text == null ? "" : text.strip();
        if (requirement.isBlank()) {
            throw new BusinessException("SESSION_REQUIREMENT_TEXT_EMPTY",
                    "会话要求不能为空", HttpStatus.BAD_REQUEST);
        }
        if (requirement.length() > MAX_REQUIREMENT_CHARS) {
            requirement = requirement.substring(0, MAX_REQUIREMENT_CHARS);
        }
        String id = Ids.newId();
        jdbcTemplate.update("""
                insert into conversation_session_requirement(
                    id, workspace_id, conversation_id, source_revision_id, requirement_text, status)
                values (?, ?, ?, null, ?, 'ACTIVE')
                """, id, workspaceId, conversationId, requirement);
        return new SessionRequirement(id, conversationId, null, requirement, "ACTIVE");
    }

    public List<SessionRequirement> list(String workspaceId, String conversationId) {
        requireConversation(workspaceId, conversationId);
        return jdbcTemplate.query("""
                select id, conversation_id, source_revision_id, requirement_text, status
                from conversation_session_requirement
                where workspace_id = ? and conversation_id = ? and status = 'ACTIVE'
                order by created_at, id
                """, (rs, rowNum) -> new SessionRequirement(
                rs.getString("id"), rs.getString("conversation_id"),
                rs.getString("source_revision_id"), rs.getString("requirement_text"),
                rs.getString("status")), workspaceId, conversationId);
    }

    /**
     * 本对话里待确认的 Memory 候选。
     *
     * <p>系统设计上不新增候选-会话外键：候选通过 {@code created_from_signal_ids_json}
     * 指向 memory_signal，signal 的 {@code source_type=MESSAGE}/{@code source_id} 就是来源消息；
     * 消息再归属会话。这样保持既有 Memory 生命周期，不需要回填或复制会话身份。</p>
     */
    public List<PendingProposal> pendingProposals(String workspaceId, String conversationId) {
        requireConversation(workspaceId, conversationId);
        List<ProposalRow> rows = jdbcTemplate.query("""
                select r.id, r.display_text, c.created_from_signal_ids_json
                from memory_runtime_revision r
                join memory_candidate c on c.id = r.provenance_ref
                where r.workspace_id = ? and r.status = 'PROPOSED'
                  and r.provenance_type = 'MEMORY_CANDIDATE'
                order by r.created_at, r.id
                """, (rs, rowNum) -> new ProposalRow(
                rs.getString("id"), rs.getString("display_text"),
                rs.getString("created_from_signal_ids_json")), workspaceId);
        List<PendingProposal> proposals = new ArrayList<>();
        for (ProposalRow row : rows) {
            List<String> messageIds = sourceMessageIds(workspaceId, row.signalIdsJson());
            List<String> inConversation = messageIds.stream()
                    .filter(messageId -> belongsToConversation(workspaceId, conversationId, messageId))
                    .toList();
            if (!inConversation.isEmpty()) {
                proposals.add(new PendingProposal(row.revisionId(),
                        row.displayText() == null ? "" : row.displayText(), inConversation));
            }
        }
        return List.copyOf(proposals);
    }

    /** 本对话里已经生效、可以撤销的记忆。撤销仍走 canonical review 的 REVOKE。 */
    public List<PendingProposal> activeMemories(String workspaceId, String conversationId) {
        requireConversation(workspaceId, conversationId);
        List<ProposalRow> rows = jdbcTemplate.query("""
                select r.id, r.display_text, c.created_from_signal_ids_json
                from memory_runtime_revision r
                join memory_candidate c on c.id = r.provenance_ref
                where r.workspace_id = ? and r.status = 'ACTIVE'
                  and r.provenance_type = 'MEMORY_CANDIDATE'
                order by r.created_at, r.id
                """, (rs, rowNum) -> new ProposalRow(
                rs.getString("id"), rs.getString("display_text"),
                rs.getString("created_from_signal_ids_json")), workspaceId);
        List<PendingProposal> active = new ArrayList<>();
        for (ProposalRow row : rows) {
            List<String> inConversation = sourceMessageIds(workspaceId, row.signalIdsJson()).stream()
                    .filter(messageId -> belongsToConversation(workspaceId, conversationId, messageId))
                    .toList();
            if (!inConversation.isEmpty()) {
                active.add(new PendingProposal(row.revisionId(),
                        row.displayText() == null ? "" : row.displayText(), inConversation));
            }
        }
        return List.copyOf(active);
    }

    private List<String> sourceMessageIds(String workspaceId, String signalIdsJson) {
        if (signalIdsJson == null || signalIdsJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> signalIds = new ObjectMapper().readValue(
                    signalIdsJson, new TypeReference<List<String>>() { });
            if (signalIds.isEmpty()) {
                return List.of();
            }
            String placeholders = String.join(",", java.util.Collections.nCopies(signalIds.size(), "?"));
            Object[] parameters = new Object[signalIds.size() + 1];
            parameters[0] = workspaceId;
            for (int index = 0; index < signalIds.size(); index++) {
                parameters[index + 1] = signalIds.get(index);
            }
            return jdbcTemplate.queryForList("""
                    select source_id from memory_signal
                    where workspace_id = ? and source_type = 'MESSAGE' and source_id is not null
                      and id in (%s)
                    """.formatted(placeholders), String.class, parameters);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private boolean belongsToConversation(String workspaceId, String conversationId, String messageId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from conversation_message
                where workspace_id = ? and conversation_id = ? and id = ?
                """, Integer.class, workspaceId, conversationId, messageId);
        return count != null && count > 0;
    }

    @Transactional
    public void deactivate(String workspaceId, String conversationId, String requirementId) {
        int updated = jdbcTemplate.update("""
                update conversation_session_requirement
                set status = 'INACTIVE', updated_at = current_timestamp
                where id = ? and workspace_id = ? and conversation_id = ? and status = 'ACTIVE'
                """, requirementId, workspaceId, conversationId);
        if (updated == 0) {
            throw new BusinessException("SESSION_REQUIREMENT_NOT_FOUND",
                    "会话要求不存在或已停用", HttpStatus.NOT_FOUND);
        }
    }

    private SessionRequirement findBySourceRevision(String workspaceId, String revisionId) {
        return jdbcTemplate.query("""
                select id, conversation_id, source_revision_id, requirement_text, status
                from conversation_session_requirement
                where workspace_id = ? and source_revision_id = ?
                """, rs -> rs.next() ? new SessionRequirement(
                rs.getString("id"), rs.getString("conversation_id"), rs.getString("source_revision_id"),
                rs.getString("requirement_text"), rs.getString("status")) : null,
                workspaceId, revisionId);
    }

    private void requireConversation(String workspaceId, String conversationId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from conversation where workspace_id = ? and id = ?
                """, Integer.class, workspaceId, conversationId);
        if (count == null || count == 0) {
            throw new BusinessException("CONVERSATION_NOT_FOUND",
                    "Conversation does not exist in this workspace", HttpStatus.NOT_FOUND);
        }
    }

    private record RevisionRow(String id, String status, String displayText) {
    }

    private record ProposalRow(String revisionId, String displayText, String signalIdsJson) {
    }

    public record PendingProposal(String revisionId, String displayText, List<String> sourceMessageIds) {
        public PendingProposal {
            sourceMessageIds = sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds);
        }
    }

    public record SessionRequirement(String id, String conversationId, String sourceRevisionId,
                                     String requirementText, String status) {
    }
}

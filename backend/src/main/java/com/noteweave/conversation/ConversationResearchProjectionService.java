package com.noteweave.conversation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Owns projection of Research lifecycle state into an existing conversation placeholder. */
@Service
public class ConversationResearchProjectionService {

    private final JdbcTemplate jdbcTemplate;
    private final ConversationSegmentBuildService segmentBuildService;

    public ConversationResearchProjectionService(
            JdbcTemplate jdbcTemplate,
            ConversationSegmentBuildService segmentBuildService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.segmentBuildService = segmentBuildService;
    }

    public void projectCompletedReport(
            String workspaceId,
            String conversationId,
            String messageId,
            String researchRunId,
            String reportTitle
    ) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        String card = "研究报告已完成：" + reportTitle + "\n\nresearch_run_id: " + researchRunId;
        int projected = jdbcTemplate.update("""
                update conversation_message
                set content = ?, content_hash = ?, context_status = 'CURRENT'
                where workspace_id = ? and conversation_id = ? and id = ?
                  and context_status = 'PENDING'
                """, card, sha256(card), workspaceId, conversationId, messageId);
        if (projected == 1 && conversationId != null && !conversationId.isBlank()) {
            segmentBuildService.queueBuildForActivePrefix(workspaceId, conversationId);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}

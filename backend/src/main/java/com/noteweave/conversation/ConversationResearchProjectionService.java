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
        projectCompletedReport(workspaceId, conversationId, messageId, researchRunId, reportTitle, null);
    }

    /**
     * 研究完成后把报告写进对话里那条等待中的助手消息。前端按研究编号渲染进度与报告，这里的正文主要供之后的
     * 普通问答当作上文：写入报告正文（去掉证据编号与末尾的引用核对，限 6000 字），用户接着追问时模型能看到结论。
     */
    public void projectCompletedReport(
            String workspaceId,
            String conversationId,
            String messageId,
            String researchRunId,
            String reportTitle,
            String reportMarkdown
    ) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        String body = contextBody(reportMarkdown);
        String card = "研究报告已完成：" + reportTitle + (body.isEmpty() ? "" : "\n\n" + body)
                + "\n\nresearch_run_id: " + researchRunId;
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

    private static final int CONTEXT_BODY_CHARS = 6000;

    static String contextBody(String reportMarkdown) {
        if (reportMarkdown == null || reportMarkdown.isBlank()) return "";
        String body = reportMarkdown;
        int audit = body.indexOf("\n## Citation audit");
        if (audit >= 0) body = body.substring(0, audit);
        body = body.replaceFirst("^\\s*#\\s+[^\\n]*\\n+", "")
                .replaceAll("\\s*\\[evidence[:-][^\\]\\s]+\\]", "")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
        return body.length() > CONTEXT_BODY_CHARS ? body.substring(0, CONTEXT_BODY_CHARS) + "…" : body;
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

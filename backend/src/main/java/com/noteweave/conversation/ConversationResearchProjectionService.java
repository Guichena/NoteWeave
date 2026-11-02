package com.noteweave.conversation;

import com.noteweave.context.ResearchProjection;
import com.noteweave.research.ResearchProjectionService;
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
    private final ResearchProjectionService projectionService;

    public ConversationResearchProjectionService(
            JdbcTemplate jdbcTemplate,
            ConversationSegmentBuildService segmentBuildService,
            ResearchProjectionService projectionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.segmentBuildService = segmentBuildService;
        this.projectionService = projectionService;
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
        ResearchProjection projection = projectionService.build(
                researchRunId, ResearchProjection.LEVEL_P2);
        String projectionJson = projectionService.toJson(projection);
        String card = card(projection.asLevel(ResearchProjection.LEVEL_P1), researchRunId, reportTitle);
        int projected = jdbcTemplate.update("""
                update conversation_message
                set content = ?, content_hash = ?, research_projection_json = ?,
                    context_status = 'CURRENT'
                where workspace_id = ? and conversation_id = ? and id = ?
                  and context_status = 'PENDING'
                """, card, sha256(card), projectionJson, workspaceId, conversationId, messageId);
        if (projected == 1 && conversationId != null && !conversationId.isBlank()) {
            segmentBuildService.queueBuildForActivePrefix(workspaceId, conversationId);
        }
    }

    /**
     * 结论卡只由结构化投影生成：P0 身份/标题/状态，P1 执行摘要与需求覆盖。
     * 历史报告结构缺失时使用显式兼容说明，不从报告正文推断“已核实”结论。
     */
    private String card(ResearchProjection projection, String researchRunId, String reportTitle) {
        String title = projection.title().isBlank()
                ? (reportTitle == null ? "" : reportTitle) : projection.title();
        StringBuilder builder = new StringBuilder("研究报告");
        builder.append(switch (projection.status()) {
            case "COMPLETED" -> "已完成";
            case "RUNNING" -> "进行中";
            case "FAILED" -> "失败";
            case "CANCELLED" -> "已取消";
            default -> "等待开始";
        });
        if (!title.isBlank()) {
            builder.append("：").append(title);
        }
        builder.append("\n\nresearch_run_id: ").append(researchRunId);
        if (!projection.executiveSummary().isBlank()) {
            builder.append("\n\n执行摘要：").append(projection.executiveSummary());
        }
        if (!projection.requirements().isEmpty()) {
            builder.append("\n\n需求覆盖：");
            for (ResearchProjection.Requirement requirement : projection.requirements()) {
                builder.append("\n- [").append(requirement.coverage()).append("] ")
                        .append(requirement.label());
            }
        } else {
            builder.append("\n\n历史报告缺少结构化投影，详见研究详情。");
        }
        return builder.toString();
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

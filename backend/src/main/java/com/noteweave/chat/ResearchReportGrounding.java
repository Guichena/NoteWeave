package com.noteweave.chat;

import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.ResearchReportEvidence;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.common.BusinessException;
import com.noteweave.conversation.EffectiveRetrievalConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 解析本轮问答可以取证的研究报告，并把 REPORT 步骤加进 QA 检索计划。
 *
 * <p>显式 groundingRefs 必须都是本工作台的研究运行；AUTO 没有指定时，取这段对话里最近完成的研究，
 * 研究回合的消息被删除后不再参与。REPORT 步骤排在工作台检索之前：用户追问研究报告时，
 * 报告引用的原文是主要依据，工作台资料作为补充，两组各自保持组内顺序。</p>
 */
@Component
public class ResearchReportGrounding {

    static final String PLAN_VERSION_SUFFIX = "+research-report-v1";
    static final int REPORT_CANDIDATE_LIMIT = 6;
    static final int MAX_CONVERSATION_REPORTS = 3;

    private final JdbcTemplate jdbcTemplate;

    public ResearchReportGrounding(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Set<String> resolve(String workspaceId, String conversationId, EffectiveRetrievalConfig config) {
        if (!config.channels().contains("REPORT")) {
            return Set.of();
        }
        if (!config.groundingRefs().isEmpty()) {
            List<String> refs = config.groundingRefs();
            List<Object> parameters = new ArrayList<>(refs.size() + 1);
            parameters.add(workspaceId);
            parameters.addAll(refs);
            List<String> known = jdbcTemplate.queryForList("""
                    select id from research_run where workspace_id = ? and id in (%s)
                    """.formatted(String.join(",", Collections.nCopies(refs.size(), "?"))),
                    String.class, parameters.toArray());
            if (known.size() != refs.size()) {
                throw new BusinessException("RETRIEVAL_GROUNDING_REF_INVALID",
                        "引用的研究报告不存在或不属于当前工作台", HttpStatus.NOT_FOUND);
            }
            return new LinkedHashSet<>(refs);
        }
        List<String> recent = conversationId == null ? List.of() : jdbcTemplate.queryForList("""
                select rr.id from research_run rr
                join conversation_message m on m.id = rr.answer_message_id
                where rr.workspace_id = ? and rr.conversation_id = ? and rr.status = 'COMPLETED'
                  and m.context_status <> 'DELETED'
                order by rr.updated_at desc
                limit ?
                """, String.class, workspaceId, conversationId, MAX_CONVERSATION_REPORTS);
        if (recent.isEmpty() && !config.channels().contains("WORKSPACE")) {
            throw new BusinessException("RESEARCH_REPORT_NOT_FOUND",
                    "这段对话里还没有已完成的研究报告", HttpStatus.CONFLICT);
        }
        return new LinkedHashSet<>(recent);
    }

    /** 在策略计划前加入 REPORT 步骤；只用 REPORT 通道时去掉工作台检索步骤。 */
    public RetrievalPlan withReportStep(RetrievalPlan base, AnswerContext context, boolean includeWorkspace) {
        List<RetrievalPlan.Step> steps = new ArrayList<>();
        steps.add(new RetrievalPlan.Step(
                ResearchReportEvidence.CHANNEL,
                REPORT_CANDIDATE_LIMIT,
                1.0d,
                Map.of(
                        "workspace_id", context.workspaceId(),
                        ResearchReportEvidence.FILTER_RESEARCH_RUN_IDS,
                        String.join(",", context.reportScope().stream().sorted().toList()))));
        if (includeWorkspace) {
            steps.addAll(base.steps());
        }
        return new RetrievalPlan(base.version() + PLAN_VERSION_SUFFIX, base.mode(), steps, base.budget());
    }
}

package com.noteweave.chat;

import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.EvidenceRetrievalResult;
import com.noteweave.answer.strategy.EvidenceRetriever;
import com.noteweave.answer.strategy.ResearchReportEvidence;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.research.ResearchCellFollowUpService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * F4-03：已定位研究单元格的证据通道。
 *
 * <p>只把 VERIFIED、非歧义且所在研究运行已完成的单元格结论作为证据；证据身份沿用
 * 研究运行的 evidence key，因此资格检查仍由工作台所有权适配器执行。相关网页存在
 * 不代表单元格已核实，未核实单元格不会进入该通道。</p>
 */
@Component
public class ResearchCellEvidenceRetriever implements EvidenceRetriever {

    public static final String CHANNEL = "RESEARCH_CELL_EVIDENCE";

    private final JdbcTemplate jdbcTemplate;
    private final ResearchCellFollowUpService followUpService;

    public ResearchCellEvidenceRetriever(JdbcTemplate jdbcTemplate,
                                         ResearchCellFollowUpService followUpService) {
        this.jdbcTemplate = jdbcTemplate;
        this.followUpService = followUpService;
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public EvidenceRetrievalResult retrieve(AnswerContext context, RetrievalPlan plan,
                                            RetrievalPlan.Step step) {
        if (context.conversationId() == null || context.queryMessageId() == null) {
            return empty();
        }
        Integer cutoff = jdbcTemplate.query("""
                select message_seq from conversation_message where id = ? and workspace_id = ?
                """, rs -> rs.next() ? rs.getInt(1) : null,
                context.queryMessageId(), context.workspaceId());
        if (cutoff == null) {
            return empty();
        }
        Set<String> completedRuns = new LinkedHashSet<>(jdbcTemplate.queryForList("""
                select id from research_run
                where workspace_id = ? and status = 'COMPLETED'
                """, String.class, context.workspaceId()));
        List<ResearchCellFollowUpService.Match> matches = followUpService.match(
                context.workspaceId(), context.conversationId(), context.query(), cutoff);
        Map<String, EvidenceBundle.Evidence> evidence = new LinkedHashMap<>();
        for (ResearchCellFollowUpService.Match match : matches) {
            if (match.ambiguous() || !"VERIFIED".equals(match.status())
                    || !completedRuns.contains(match.researchRunId())) {
                continue;
            }
            for (String evidenceKey : match.evidenceIds()) {
                String identity = match.researchRunId() + ":" + evidenceKey;
                evidence.putIfAbsent(identity, toEvidence(match, evidenceKey));
            }
        }
        List<EvidenceBundle.Evidence> selected = evidence.values().stream()
                .limit(Math.max(0, step.candidateLimit()))
                .toList();
        return new EvidenceRetrievalResult(
                selected,
                Map.of("research_cell_evidence_version", "research-cell-evidence-v1"),
                false,
                List.of(),
                Map.of("cell_matches", (long) matches.size(),
                        "cell_evidence", (long) selected.size()),
                List.of());
    }

    private EvidenceRetrievalResult empty() {
        return new EvidenceRetrievalResult(List.of(), Map.of(), false, List.of(), Map.of(), List.of());
    }

    private EvidenceBundle.Evidence toEvidence(ResearchCellFollowUpService.Match match, String evidenceKey) {
        String excerpt = match.conclusion() == null || match.conclusion().isBlank()
                ? match.objectName() + " · " + match.dimensionName() : match.conclusion();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("research_cell_id", match.cellId());
        metadata.put("research_cell_status", match.status());
        metadata.put("match_reason", "RESEARCH_CELL");
        metadata.put("freshness_status", "CURRENT_AT_RETRIEVAL");
        return new EvidenceBundle.Evidence(
                ResearchReportEvidence.evidenceId(match.researchRunId(), evidenceKey),
                ResearchReportEvidence.KIND,
                "",
                "",
                evidenceKey,
                "",
                "",
                match.objectName() + " · " + match.dimensionName(),
                excerpt,
                "",
                1.0d,
                1.0d,
                0.0d,
                ResearchReportEvidence.accessScope(match.researchRunId()),
                Instant.now(),
                "RESEARCH_CELL",
                excerpt.length(),
                metadata,
                com.noteweave.answer.strategy.ScoreInfo.unknown());
    }
}

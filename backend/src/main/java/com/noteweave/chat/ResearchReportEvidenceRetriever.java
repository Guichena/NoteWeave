package com.noteweave.chat;

import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.EvidenceRetrievalResult;
import com.noteweave.answer.strategy.EvidenceRetriever;
import com.noteweave.answer.strategy.ResearchReportEvidence;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.common.BusinessException;
import com.noteweave.common.text.LexicalTerms;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import com.noteweave.source.eligibility.AccessPurpose;
import com.noteweave.source.eligibility.MaterialEligibilityPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * REPORT 通道：从已完成的研究报告里取证。证据单位是报告正文实际引用过的网页原文摘录，
 * 研究过程中已经逐条核对过；报告的结论句只作为摘录的说明，不单独当作事实。
 *
 * <p>报告能否作为当前证据，沿用保存和导出报告时的依赖校验：研究的输入上下文被删除、
 * 或报告依赖的工作台资料已撤销时，整份报告不参与取证，并在降级原因里说明。</p>
 */
@Component
public class ResearchReportEvidenceRetriever implements EvidenceRetriever {

    static final String DEGRADED_NOT_COMPLETED = "RESEARCH_REPORT_NOT_COMPLETED";
    static final String DEGRADED_NOT_USABLE = "RESEARCH_REPORT_NOT_USABLE";
    static final String METADATA_REPORT_TITLE = "report_title";
    /** 引用卡片只展示网页原文，不带报告结论前缀。 */
    static final String METADATA_QUOTE = "citation_quote";
    // 证据键带 evidence: 前缀，与 source_evidence.evidence_key、报告引用核对（ResearchReportCitations）一致
    private static final Pattern INLINE = Pattern.compile("\\[(evidence:[0-9a-f]{16,64})]");
    private static final String AUDIT_HEADING = "\n## Citation audit";
    // 与 QA 生成提示对单条片段的截断长度一致，预算按实际进入提示的文字计费
    static final int EXCERPT_LIMIT = 900;
    private static final int CLAIM_LIMIT = 200;
    private static final int TITLE_LIMIT = 200;

    private final JdbcTemplate jdbcTemplate;
    private final MaterialEligibilityPort eligibilityPort;
    private final ResearchGeneratedSourceReadGate readGate;

    @Autowired
    public ResearchReportEvidenceRetriever(
            JdbcTemplate jdbcTemplate,
            MaterialEligibilityPort eligibilityPort,
            ResearchGeneratedSourceReadGate readGate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.eligibilityPort = eligibilityPort;
        this.readGate = readGate;
    }

    @Override
    public String channel() {
        return ResearchReportEvidence.CHANNEL;
    }

    @Override
    public EvidenceRetrievalResult retrieve(AnswerContext context, RetrievalPlan plan, RetrievalPlan.Step step) {
        String declared = step.filters().getOrDefault(ResearchReportEvidence.FILTER_RESEARCH_RUN_IDS, "");
        List<String> runIds = Arrays.stream(declared.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty() && context.reportScope().contains(id))
                .distinct()
                .toList();
        Set<String> queryTerms = new LinkedHashSet<>(LexicalTerms.queryTerms(context.query()));
        queryTerms.addAll(LexicalTerms.queryTerms(
                context.attributes().getOrDefault(QaAnswerModeStrategy.ATTRIBUTE_REQUEST_CONTENT, "")));
        List<Candidate> candidates = new ArrayList<>();
        List<String> degradation = new ArrayList<>();
        for (String runId : runIds) {
            Report report = loadReport(context.workspaceId(), runId);
            if (report == null) {
                degradation.add(DEGRADED_NOT_COMPLETED);
                continue;
            }
            if (!usable(context.workspaceId(), runId)) {
                degradation.add(DEGRADED_NOT_USABLE);
                continue;
            }
            List<String> keys = citedKeys(report.markdown());
            Map<String, EvidenceRow> rows = loadEvidence(runId, keys);
            for (int index = 0; index < keys.size(); index++) {
                EvidenceRow row = rows.get(keys.get(index));
                if (row == null || row.quote().isBlank()) {
                    continue;
                }
                candidates.add(new Candidate(runId, report.title(), row, index,
                        overlap(queryTerms, row.title() + " " + row.claim() + " " + row.quote())));
            }
        }
        // 先按与问题的词项重合排序，同分时保持报告里的引用顺序
        candidates.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparingInt(Candidate::reportRank));
        List<EvidenceBundle.Evidence> evidence = candidates.stream().map(this::toEvidence).toList();
        return new EvidenceRetrievalResult(
                evidence,
                Map.of(),
                !degradation.isEmpty(),
                degradation.stream().distinct().toList(),
                Map.of("research_report_candidates", (long) candidates.size()));
    }

    private boolean usable(String workspaceId, String researchRunId) {
        try {
            if (readGate != null && !readGate.visible(workspaceId, "research_agent", researchRunId)) {
                return false;
            }
        } catch (BusinessException unreadable) {
            return false;
        }
        return eligibilityPort == null || eligibilityPort.assessGeneratedDependenciesDetailed(
                workspaceId, "research_agent", researchRunId, AccessPurpose.CURRENT_EVIDENCE)
                .allAllowed(AccessPurpose.CURRENT_EVIDENCE);
    }

    private Report loadReport(String workspaceId, String researchRunId) {
        List<Report> rows = jdbcTemplate.query("""
                select coalesce(final_report_title, question) as title, final_report_markdown
                from research_run
                where workspace_id = ? and id = ? and status = 'COMPLETED'
                  and final_report_markdown is not null
                """, (rs, rowNum) -> new Report(rs.getString("title"), rs.getString("final_report_markdown")),
                workspaceId, researchRunId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 报告正文（引用核对小节之前）里出现过的证据键，按首次出现的顺序。 */
    static List<String> citedKeys(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        int audit = markdown.indexOf(AUDIT_HEADING);
        String body = audit >= 0 ? markdown.substring(0, audit) : markdown;
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = INLINE.matcher(body);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return List.copyOf(keys);
    }

    private Map<String, EvidenceRow> loadEvidence(String researchRunId, List<String> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        List<Object> parameters = new ArrayList<>(keys.size() + 1);
        parameters.add(researchRunId);
        parameters.addAll(keys);
        Map<String, EvidenceRow> rows = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select evidence_key, source_title, source_url, quote_text, claim_text, created_at
                from source_evidence
                where research_run_id = ? and evidence_key in (%s)
                """.formatted(String.join(",", Collections.nCopies(keys.size(), "?"))), rs -> {
            Timestamp createdAt = rs.getTimestamp("created_at");
            rows.put(rs.getString("evidence_key"), new EvidenceRow(
                    rs.getString("evidence_key"),
                    oneLine(rs.getString("source_title")),
                    text(rs.getString("source_url")).strip(),
                    oneLine(rs.getString("quote_text")),
                    oneLine(rs.getString("claim_text")),
                    createdAt == null ? null : createdAt.toInstant()));
        }, parameters.toArray());
        return rows;
    }

    private EvidenceBundle.Evidence toEvidence(Candidate candidate) {
        EvidenceRow row = candidate.row();
        String title = trim(row.title().isBlank() ? domainOf(row.url()) : row.title(), TITLE_LIMIT);
        String excerpt = excerpt(row);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("research_run_id", candidate.researchRunId());
        metadata.put("evidence_key", row.key());
        metadata.put(METADATA_REPORT_TITLE, trim(candidate.reportTitle(), TITLE_LIMIT));
        metadata.put("raw_title", title);
        metadata.put(METADATA_QUOTE, trim(row.quote(), 360));
        return new EvidenceBundle.Evidence(
                ResearchReportEvidence.evidenceId(candidate.researchRunId(), row.key()),
                ResearchReportEvidence.KIND,
                null,
                null,
                row.key(),
                null,
                null,
                title,
                excerpt,
                row.url().isEmpty() ? null : row.url(),
                candidate.score(),
                candidate.score(),
                0.0d,
                ResearchReportEvidence.accessScope(candidate.researchRunId()),
                row.createdAt(),
                "research_report_citation",
                excerpt.length(),
                metadata
        );
    }

    /** 报告里依据这条摘录写出的结论放在前面说明用途，后面是核对过的原文。 */
    static String excerpt(EvidenceRow row) {
        StringBuilder builder = new StringBuilder();
        if (!row.claim().isBlank()) {
            builder.append("报告结论：").append(trim(row.claim(), CLAIM_LIMIT)).append("\n");
        }
        builder.append("网页原文：").append(row.quote());
        return trim(builder.toString(), EXCERPT_LIMIT);
    }

    private static int overlap(Set<String> queryTerms, String text) {
        if (queryTerms.isEmpty()) {
            return 0;
        }
        Set<String> terms = LexicalTerms.queryTerms(text);
        int score = 0;
        for (String term : queryTerms) {
            if (terms.contains(term)) {
                score++;
            }
        }
        return score;
    }

    private static String domainOf(String url) {
        String value = url.replaceFirst("^[a-zA-Z]+://", "");
        int slash = value.indexOf('/');
        String domain = slash >= 0 ? value.substring(0, slash) : value;
        return domain.isBlank() ? "网页来源" : domain;
    }

    private static String trim(String value, int max) {
        String text = text(value);
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String oneLine(String value) {
        return text(value).replaceAll("\\s+", " ").strip();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private record Report(String title, String markdown) {
    }

    record EvidenceRow(String key, String title, String url, String quote, String claim, Instant createdAt) {
    }

    private record Candidate(String researchRunId, String reportTitle, EvidenceRow row, int reportRank, int score) {
    }
}

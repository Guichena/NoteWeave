package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Deterministic, bounded Table-as-State planner for newly-created research runs. */
@Service
class ResearchMatrixPlanningService {
    static final String PLANNER_VERSION = "intent-matrix.v2";
    private static final String DIGEST_DOMAIN = "research-matrix-plan.v2";
    private static final int MAX_ROWS = 20;
    private static final int MAX_COLUMNS = 8;
    private static final int MAX_CELLS = 80;
    private static final List<ColumnPlan> BASE_COLUMNS = List.of(
            new ColumnPlan("answer", "Answer", false, "QUALIFIED_SOURCE"),
            new ColumnPlan("key_evidence", "Key evidence", false, "QUALIFIED_SOURCE"),
            new ColumnPlan("limitations", "Limitations", false, "QUALIFIED_SOURCE"),
            new ColumnPlan("implications", "Implications", false, "QUALIFIED_SOURCE"));
    private static final Pattern COMPARISON_MARKER = Pattern.compile(
            "(?i)\\b(?:vs\\.?|versus)\\b|比较|对比|对照");
    private static final Pattern ENTITY_SEPARATOR = Pattern.compile(
            "(?i)\\s+(?:vs\\.?|versus|and)\\s+|、|，|,|和|与|及");
    private static final Pattern SCENARIO_QUALIFIER = Pattern.compile(
            "\\s*在[^、，,和与及]{1,40}?[下中里上时]$");
    private static final Pattern HIGH_RISK = Pattern.compile(
            "(?i)安全|风险|合规|法律|医疗|财务|隐私|security|safety|legal|medical|financial|privacy");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentCompletionCanonicalizer canonicalizer;
    private final boolean enabled;
    private final ResearchAgentFeatureFlagService featureFlags;

    ResearchMatrixPlanningService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchAgentCompletionCanonicalizer canonicalizer,
            ResearchAgentFeatureFlagService featureFlags,
            @Value("${noteweave.research.intent-matrix-v2:false}") boolean enabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
        this.featureFlags = featureFlags;
        this.enabled = enabled;
    }

    MatrixPlan planAndRecord(String runId, String question, ResearchIntentResponse rawIntent) {
        ResearchIntentResponse intent = ResearchIntentPolicy.normalize(rawIntent);
        MatrixPlan plan = plan(question, intent);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planner_version", plan.plannerVersion());
        payload.put("rows", plan.rows());
        payload.put("columns", plan.columns());
        payload.put("cell_count", plan.cellCount());
        payload.put("bounded", plan.bounded());
        payload.put("reason_codes", plan.reasonCodes());
        jdbcTemplate.update("""
                insert into research_matrix_plan(
                    id, research_run_id, planner_version, plan_mode, plan_status,
                    row_count, column_count, cell_count, bounded, reason_codes_json,
                    plan_json, plan_digest)
                values (?, ?, ?, 'INTENT_MATRIX_V2', ?, ?, ?, ?, ?, ?, ?, ?)
                """, Ids.newId(), runId, PLANNER_VERSION, enabledForRun(runId) ? "ACTIVE" : "SHADOW",
                plan.rows().size(), plan.columns().size(), plan.cellCount(), plan.bounded(),
                Json.write(objectMapper, plan.reasonCodes()), Json.write(objectMapper, payload), plan.planDigest());
        return plan;
    }

    MatrixPlan plan(String question, ResearchIntentResponse intent) {
        String normalizedQuestion = Normalizer.normalize(question == null ? "" : question.strip(), Normalizer.Form.NFC);
        List<RowPlan> rows = comparisonRows(normalizedQuestion, intent);
        List<ColumnPlan> columns = new ArrayList<>(BASE_COLUMNS);
        for (String finding : intent.constraints()) {
            if (columns.size() >= MAX_COLUMNS) break;
            String key = "finding-" + shortDigest(finding);
            if (columns.stream().noneMatch(column -> column.key().equals(key))) {
                columns.add(new ColumnPlan(key, finding, HIGH_RISK.matcher(finding).find(),
                        HIGH_RISK.matcher(finding).find() ? "TWO_INDEPENDENT_SOURCES" : "QUALIFIED_SOURCE"));
            }
        }
        boolean bounded = intent.constraints().size() > Math.max(0, MAX_COLUMNS - BASE_COLUMNS.size());
        if (rows.size() > MAX_ROWS) {
            rows = new ArrayList<>(rows.subList(0, MAX_ROWS));
            bounded = true;
        }
        int maxRowsForCells = Math.max(1, MAX_CELLS / columns.size());
        if (rows.size() > maxRowsForCells) {
            rows = new ArrayList<>(rows.subList(0, maxRowsForCells));
            bounded = true;
        }
        List<String> reasons = bounded ? List.of("PLAN_BOUNDED") : List.of();
        Map<String, Object> digestInput = new LinkedHashMap<>();
        digestInput.put("planner_version", PLANNER_VERSION);
        digestInput.put("question", normalizedQuestion);
        digestInput.put("intent", intent);
        digestInput.put("rows", rows);
        digestInput.put("columns", columns);
        digestInput.put("bounded", bounded);
        String digest = canonicalizer.domainSeparatedDigest(DIGEST_DOMAIN, digestInput);
        return new MatrixPlan(PLANNER_VERSION, List.copyOf(rows), List.copyOf(columns),
                rows.size() * columns.size(), bounded, reasons, digest);
    }

    boolean enabledForRun(String runId) {
        return enabled && featureFlags.enabledForRun(runId, ResearchAgentFeatureFlagService.INTENT_MATRIX);
    }

    private List<RowPlan> comparisonRows(String question, ResearchIntentResponse intent) {
        boolean comparison = COMPARISON_MARKER.matcher(question).find()
                || intent.researchType().contains("COMPARISON");
        if (!comparison) return List.of(new RowPlan("subject", "Subject"));
        String candidate = question;
        java.util.regex.Matcher marker = COMPARISON_MARKER.matcher(candidate);
        if (marker.find() && marker.start() == 0) candidate = candidate.substring(marker.end()).strip();
        int possessive = candidate.indexOf('的');
        if (possessive > 0) candidate = candidate.substring(0, possessive);
        // 去掉对象列表后面的场景限定语，例如 A、B 和 C 在 RAG 场景下；否则会粘在最后一个对象上
        candidate = SCENARIO_QUALIFIER.matcher(candidate).replaceFirst("").strip();
        Set<String> labels = new LinkedHashSet<>();
        for (String raw : ENTITY_SEPARATOR.split(candidate)) {
            String label = raw.replaceAll("^[\\s:：?？]+|[\\s:：?？]+$", "").strip();
            if (!label.isBlank() && label.codePointCount(0, label.length()) <= 120) labels.add(label);
        }
        if (labels.size() < 2) return List.of(new RowPlan("subject", "Subject"));
        List<RowPlan> rows = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (String label : labels) {
            String base = "entity-" + shortDigest(label);
            String key = base;
            int suffix = 2;
            while (!keys.add(key)) key = base + "-" + suffix++;
            rows.add(new RowPlan(key, label));
        }
        return rows;
    }

    private String shortDigest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                            .getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    record MatrixPlan(
            String plannerVersion,
            List<RowPlan> rows,
            List<ColumnPlan> columns,
            int cellCount,
            boolean bounded,
            List<String> reasonCodes,
            String planDigest
    ) { }
    record RowPlan(String key, String label) { }
    record ColumnPlan(String key, String label, boolean highRisk, String requiredProvenanceLevel) { }
}

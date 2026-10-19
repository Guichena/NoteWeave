package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 研究表格的规划阶段。
 *
 * <p>创建 Run 时先由正则规则建出骨架表格，保证任何情况下研究都能进行；随后下发 PLANNING 任务，
 * 由模型从多个视角提出实体和研究维度。这里对模型的提案做权威校验（数量上限、标签去重、
 * 域名格式），通过后替换骨架表格。规划完成前，Coordinator 不会下发深挖任务。
 * 模型不可用、输出被拒绝或任务失败时保留骨架表格继续执行，并记录降级原因。</p>
 */
@Service
public class ResearchPlanningService {
    static final String STATUS_PLANNING = "PLANNING";
    static final String STATUS_REVIEW = "REVIEW";
    static final String STATUS_READY = "READY";
    static final String RESULT_SCHEMA = "research-plan-matrix.v1";
    static final String PLANNER_VERSION = "llm-matrix.v1";
    static final int MAX_ROWS = 20;
    static final int MIN_COLUMNS = 2;
    static final int MAX_COLUMNS = 8;
    static final int MAX_CELLS = 80;
    static final int MAX_ENTITY_LABEL_CHARS = 120;
    static final int MAX_COLUMN_LABEL_CHARS = 200;
    static final int MAX_TEXT_CHARS = 1000;
    static final int MAX_DOMAINS = 8;
    static final Set<String> ANSWER_FORMATS = Set.of("SHORT_TEXT", "LONG_TEXT", "NUMBER", "DATE", "LIST", "BOOLEAN");
    static final Set<String> EFFORT_TIERS = Set.of("SIMPLE", "STANDARD", "COMPLEX");
    private static final Set<String> ACTIVE_TASK_STATUSES = Set.of("PENDING", "CLAIMED", "RUNNING", "RETRY_WAIT", "EXPIRED");
    private static final Pattern DOMAIN = Pattern.compile("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");
    private static final String DIGEST_DOMAIN = "research-plan-input.v1";
    private static final Logger log = LoggerFactory.getLogger(ResearchPlanningService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentTaskCoordinatorService coordinator;
    private final ResearchAgentCompletionCanonicalizer canonicalizer;
    private final boolean enabled;
    private final long reviewSeconds;

    public ResearchPlanningService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchAgentTaskCoordinatorService coordinator,
            ResearchAgentCompletionCanonicalizer canonicalizer,
            @Value("${noteweave.research.llm-planning-v1:true}") boolean enabled,
            @Value("${noteweave.research.plan-review-seconds:0}") long reviewSeconds,
            @Value("${noteweave.research.agent.high-risk-quorum-enabled:false}") boolean highRiskQuorumEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.coordinator = coordinator;
        this.canonicalizer = canonicalizer;
        this.enabled = enabled;
        this.reviewSeconds = Math.max(0, reviewSeconds);
        this.highRiskQuorumEnabled = highRiskQuorumEnabled;
    }

    /**
     * 高风险单元格会走两路独立取证并要求两个独立来源，耗时与失败率都明显更高。
     * 默认关闭：规划给出的 high_risk 只在开启时生效。
     */
    private final boolean highRiskQuorumEnabled;

    /** 新建的 Run 在骨架表格建好之后进入规划阶段；从断点恢复的 Run 已有表格，不经过这里。 */
    public void requirePlanning(String runId) {
        if (!enabled) return;
        jdbcTemplate.update("update research_run set plan_status = ? where id = ?", STATUS_PLANNING, runId);
    }

    /**
     * Coordinator 每次 tick 先调用这里。返回 {@link GateOutcome#proceed()} 时继续原有流程；
     * 其余结果表示规划阶段还没结束，本次 tick 到此为止。
     */
    @Transactional
    public GateOutcome advance(String runId) {
        String status = jdbcTemplate.query("select plan_status from research_run where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, runId);
        if (status == null || STATUS_READY.equals(status)) return GateOutcome.proceed();
        if (STATUS_REVIEW.equals(status)) {
            Timestamp deadline = jdbcTemplate.queryForObject(
                    "select plan_review_deadline from research_run where id = ?", Timestamp.class, runId);
            if (deadline != null && deadline.toInstant().isAfter(Instant.now())) {
                return GateOutcome.waiting("PLAN_REVIEW_PENDING", null);
            }
            markReady(runId);
            return GateOutcome.proceed();
        }
        List<String> planningStatuses = jdbcTemplate.queryForList("""
                select status from research_agent_task
                where research_run_id = ? and role = 'PLANNING'
                order by created_at desc, id desc
                """, String.class, runId);
        if (planningStatuses.isEmpty()) {
            ResearchAgentTaskCoordinatorService.CoordinatorReceipt receipt =
                    coordinator.planPlanningTask(runId, planningInput(runId));
            return GateOutcome.waiting("PLANNING_TASKIZED", receipt);
        }
        String taskStatus = planningStatuses.get(0);
        if (ACTIVE_TASK_STATUSES.contains(taskStatus)) return GateOutcome.waiting("PLANNING_PENDING", null);
        // 任务已经结束却没有把状态推进到 REVIEW 或 READY：结果被拒绝或任务失败，保留骨架表格继续
        fallback(runId, "PLANNER_TASK_" + taskStatus);
        return GateOutcome.proceed();
    }

    /** PLANNING 任务的输入。摘要只包含规划所需的字段，结果提交时据此判断是否过期。 */
    Map<String, Object> planningInput(String runId) {
        Map<String, Object> run = jdbcTemplate.queryForMap("""
                select question, research_intent_json from research_run where id = ?
                """, runId);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schema_version", "research-plan-input.v1");
        input.put("question", String.valueOf(run.get("QUESTION")));
        input.put("research_intent", readMap(run.get("RESEARCH_INTENT_JSON")));
        input.put("max_rows", MAX_ROWS);
        input.put("min_columns", MIN_COLUMNS);
        input.put("max_columns", MAX_COLUMNS);
        input.put("max_cells", MAX_CELLS);
        input.put("answer_formats", ANSWER_FORMATS.stream().sorted().toList());
        input.put("effort_tiers", EFFORT_TIERS.stream().sorted().toList());
        String digest = canonicalizer.domainSeparatedDigest(DIGEST_DOMAIN, input);
        input.put("input_digest", digest);
        return Map.copyOf(input);
    }

    /** 由 RoleResultService 在提交 PLANNING 结果的事务中调用。 */
    void applyResult(ResearchAgentCompletionCommitter.TaskRow task, Map<String, Object> result) {
        if (!RESULT_SCHEMA.equals(text(result.get("result_schema_version")))
                || !ResearchAgentRoles.PLANNING.equals(text(result.get("role")))) {
            throw invalid("Planning result schema or role is invalid");
        }
        String expectedDigest = text(planningInputOf(task).get("input_digest"));
        if (expectedDigest.isBlank() || !expectedDigest.equals(text(result.get("input_digest")))) {
            throw new BusinessException("RESEARCH_AGENT_SNAPSHOT_STALE", "Planning input digest is stale", HttpStatus.CONFLICT);
        }
        String status = text(result.get("status"));
        if ("FALLBACK".equals(status)) {
            fallback(task.runId(), "PLANNER_" + String.join(",", stringList(result.get("degradation_reasons"))));
            return;
        }
        if (!"PLANNED".equals(status)) throw invalid("Planning result status is invalid");
        if (!STATUS_PLANNING.equals(planStatus(task.runId()))) {
            // 用户已经确认或系统已降级，迟到的结果不再改动表格
            log.info("Ignore late planning result for run {} in status {}", task.runId(), planStatus(task.runId()));
            return;
        }
        ValidatedPlan plan = validate(mapValue(result.get("plan")));
        replaceMatrix(task.runId(), plan, PLANNER_VERSION, stringList(result.get("degradation_reasons")));
        if (reviewSeconds > 0) {
            jdbcTemplate.update("""
                    update research_run set plan_status = ?, plan_review_deadline = ? where id = ?
                    """, STATUS_REVIEW, Timestamp.from(Instant.now().plusSeconds(reviewSeconds)), task.runId());
        } else {
            markReady(task.runId());
        }
    }

    /** 规划状态和当前表格：实体、列定义、研究简报、投入等级、权威域名。 */
    @Transactional(readOnly = true)
    public Map<String, Object> readPlan(String workspaceId, String runId) {
        List<Map<String, Object>> runs = jdbcTemplate.queryForList("""
                select plan_status, plan_review_deadline from research_run where id = ? and workspace_id = ?
                """, runId, workspaceId);
        if (runs.isEmpty()) {
            throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "研究任务不存在", HttpStatus.NOT_FOUND);
        }
        Map<String, Object> run = runs.get(0);
        Map<String, Object> plan = previousPlan(runId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("plan_status", run.get("PLAN_STATUS") == null ? STATUS_READY : run.get("PLAN_STATUS"));
        Object deadline = run.get("PLAN_REVIEW_DEADLINE");
        response.put("review_deadline", deadline instanceof Timestamp timestamp ? timestamp.toInstant().toString() : null);
        response.put("planner", plan.isEmpty() ? "DETERMINISTIC" : text(plan.get("planner_version")));
        response.put("rows", jdbcTemplate.queryForList("""
                select row_key as entity_key, source_title as label from research_row
                where research_run_id = ? order by created_at, row_key
                """, runId).stream().map(ResearchPlanningService::lowerKeys).toList());
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                select column_key, label, description, answer_format, high_risk from research_column
                where research_run_id = ? order by created_at, column_key
                """, runId).stream().map(ResearchPlanningService::lowerKeys).toList();
        if (columns.isEmpty()) {
            columns = jdbcTemplate.queryForList("""
                    select distinct column_key from research_cell where research_run_id = ? order by column_key
                    """, String.class, runId).stream()
                    .map(key -> Map.<String, Object>of("column_key", key, "label", key)).toList();
        }
        response.put("columns", columns);
        response.put("brief", mapValue(plan.get("brief")));
        response.put("effort_tier", text(plan.get("effort_tier")));
        response.put("authoritative_domains", stringList(plan.get("authoritative_domains")));
        response.put("perspectives", listOfMaps(plan.get("perspectives")));
        return response;
    }

    private static Map<String, Object> lowerKeys(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> result.put(key.toLowerCase(Locale.ROOT), value));
        return result;
    }

    /** 用户在确认阶段直接开始研究。 */
    @Transactional
    public void approve(String workspaceId, String runId) {
        requireReviewable(workspaceId, runId);
        markReady(runId);
    }

    /** 用户在确认阶段修改实体和维度；修改后仍处于确认阶段，由用户决定何时开始。 */
    @Transactional
    public void revise(String workspaceId, String runId, Map<String, Object> plan) {
        requireReviewable(workspaceId, runId);
        Map<String, Object> merged = new LinkedHashMap<>(previousPlan(runId));
        merged.put("entities", plan.get("entities"));
        merged.put("dimensions", plan.get("dimensions"));
        Integer revisions = jdbcTemplate.queryForObject("""
                select count(*) from research_matrix_plan
                where research_run_id = ? and planner_version like 'user-revision.%'
                """, Integer.class, runId);
        replaceMatrix(runId, validate(merged), "user-revision." + ((revisions == null ? 0 : revisions) + 1), List.of());
    }

    ValidatedPlan validate(Map<String, Object> plan) {
        List<String> reasons = new ArrayList<>();
        List<ValidatedEntity> entities = new ArrayList<>();
        Set<String> entityLabels = new LinkedHashSet<>();
        for (Map<String, Object> raw : listOfMaps(plan.get("entities"))) {
            String label = clean(text(raw.get("label")), MAX_ENTITY_LABEL_CHARS);
            if (label.isBlank() || !entityLabels.add(normalizedKey(label))) continue;
            List<String> aliases = stringList(raw.get("aliases")).stream()
                    .map(alias -> clean(alias, MAX_ENTITY_LABEL_CHARS)).filter(alias -> !alias.isBlank())
                    .distinct().limit(5).toList();
            entities.add(new ValidatedEntity("entity-" + shortDigest(label), label, aliases));
        }
        if (entities.isEmpty()) throw invalid("Planning result has no usable entity");
        List<ValidatedColumn> columns = new ArrayList<>();
        Set<String> columnLabels = new LinkedHashSet<>();
        for (Map<String, Object> raw : listOfMaps(plan.get("dimensions"))) {
            if (columns.size() >= MAX_COLUMNS) {
                reasons.add("COLUMNS_TRUNCATED");
                break;
            }
            String label = clean(text(raw.get("label")), MAX_COLUMN_LABEL_CHARS);
            if (label.isBlank() || !columnLabels.add(normalizedKey(label))) continue;
            String format = text(raw.get("answer_format")).toUpperCase(Locale.ROOT);
            List<String> intents = stringList(raw.get("search_intents")).stream()
                    .map(intent -> clean(intent, MAX_COLUMN_LABEL_CHARS)).filter(intent -> !intent.isBlank())
                    .distinct().limit(5).toList();
            boolean highRisk = highRiskQuorumEnabled && Boolean.TRUE.equals(raw.get("high_risk"));
            columns.add(new ValidatedColumn("dim-" + shortDigest(label), label,
                    clean(text(raw.get("description")), MAX_TEXT_CHARS),
                    ANSWER_FORMATS.contains(format) ? format : "SHORT_TEXT", highRisk,
                    highRisk ? "TWO_INDEPENDENT_SOURCES" : "QUALIFIED_SOURCE", intents));
        }
        if (columns.size() < MIN_COLUMNS) throw invalid("Planning result needs at least two dimensions");
        int maxRows = Math.min(MAX_ROWS, MAX_CELLS / columns.size());
        if (entities.size() > maxRows) {
            entities = new ArrayList<>(entities.subList(0, maxRows));
            reasons.add("ROWS_TRUNCATED");
        }
        List<String> domains = stringList(plan.get("authoritative_domains")).stream()
                .map(domain -> domain.strip().toLowerCase(Locale.ROOT))
                .map(domain -> domain.startsWith("www.") ? domain.substring(4) : domain)
                .filter(domain -> DOMAIN.matcher(domain).matches())
                .distinct().limit(MAX_DOMAINS).toList();
        String effortTier = text(plan.get("effort_tier")).toUpperCase(Locale.ROOT);
        Map<String, Object> rawBrief = mapValue(plan.get("brief"));
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("objective", clean(text(rawBrief.get("objective")), MAX_TEXT_CHARS));
        brief.put("scope", clean(text(rawBrief.get("scope")), MAX_TEXT_CHARS));
        brief.put("time_range", clean(text(rawBrief.get("time_range")), MAX_COLUMN_LABEL_CHARS));
        brief.put("deliverable", clean(text(rawBrief.get("deliverable")), MAX_COLUMN_LABEL_CHARS));
        brief.put("exclusions", stringList(rawBrief.get("exclusions")).stream()
                .map(item -> clean(item, MAX_COLUMN_LABEL_CHARS)).filter(item -> !item.isBlank())
                .distinct().limit(10).toList());
        // 专家必查要点：成文时逐条作答，避免已核验的内容只被复述、没有给出要点要的结论
        brief.put("required_points", stringList(plan.get("required_points")).stream()
                .map(item -> clean(item, MAX_TEXT_CHARS)).filter(item -> !item.isBlank())
                .distinct().limit(6).toList());
        List<Map<String, Object>> perspectives = listOfMaps(plan.get("perspectives")).stream().limit(6)
                .map(item -> Map.<String, Object>of(
                        "name", clean(text(item.get("name")), MAX_COLUMN_LABEL_CHARS),
                        "questions", stringList(item.get("questions")).stream()
                                .map(question -> clean(question, MAX_COLUMN_LABEL_CHARS)).limit(5).toList()))
                .toList();
        // 不适用的研究对象 · 列组合不建单元格；至少保留一个单元格，否则忽略这份标注
        Map<String, String> entityKeys = new java.util.HashMap<>();
        entities.forEach(entity -> entityKeys.put(normalizedKey(entity.label()), entity.key()));
        Map<String, String> columnKeys = new java.util.HashMap<>();
        columns.forEach(column -> columnKeys.put(normalizedKey(column.label()), column.key()));
        Set<String> notApplicable = new java.util.TreeSet<>();
        for (Map<String, Object> raw : listOfMaps(plan.get("not_applicable"))) {
            String entityKey = entityKeys.get(normalizedKey(clean(text(raw.get("entity")), MAX_ENTITY_LABEL_CHARS)));
            String columnKey = columnKeys.get(normalizedKey(clean(text(raw.get("dimension")), MAX_COLUMN_LABEL_CHARS)));
            if (entityKey != null && columnKey != null) notApplicable.add(entityKey + ":" + columnKey);
        }
        if (notApplicable.size() >= entities.size() * columns.size()) notApplicable.clear();
        return new ValidatedPlan(List.copyOf(entities), List.copyOf(columns), domains,
                EFFORT_TIERS.contains(effortTier) ? effortTier : "STANDARD",
                clean(text(plan.get("research_type")), 64), Map.copyOf(brief), perspectives, List.copyOf(reasons),
                Set.copyOf(notApplicable));
    }

    private void replaceMatrix(String runId, ValidatedPlan plan, String plannerVersion, List<String> workerReasons) {
        Integer cellTasks = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task where research_run_id = ? and role <> 'PLANNING'
                """, Integer.class, runId);
        if (cellTasks != null && cellTasks > 0) {
            throw new BusinessException("RESEARCH_PLAN_ALREADY_EXECUTING",
                    "Research plan cannot change after cell tasks were created", HttpStatus.CONFLICT);
        }
        String branchId = jdbcTemplate.queryForObject("""
                select id from research_branch where research_run_id = ? and branch_key = 'branch-main'
                """, String.class, runId);
        Map<String, Object> run = jdbcTemplate.queryForMap("select question from research_run where id = ?", runId);
        String question = String.valueOf(run.get("QUESTION"));
        jdbcTemplate.update("delete from research_cell where research_run_id = ?", runId);
        jdbcTemplate.update("delete from research_row where research_run_id = ?", runId);
        jdbcTemplate.update("delete from research_column where research_run_id = ?", runId);
        for (ValidatedEntity entity : plan.entities()) {
            String rowId = Ids.newId();
            jdbcTemplate.update("""
                    insert into research_row(
                        id, research_run_id, row_key, branch_id, source_title, search_query, read_focus,
                        row_status, verification_status)
                    values (?, ?, ?, ?, ?, ?, ?, 'DISCOVERED', 'PENDING')
                    """, rowId, runId, entity.key(), branchId, entity.label(), question,
                    text(plan.brief().get("objective")).isBlank() ? question : text(plan.brief().get("objective")));
            for (ValidatedColumn column : plan.columns()) {
                if (plan.notApplicable().contains(entity.key() + ":" + column.key())) continue;
                jdbcTemplate.update("""
                        insert into research_cell(
                            id, research_run_id, research_row_id, cell_key, branch_id, column_key,
                            candidate_value, cell_status, evidence_refs_json, repair_count,
                            cell_version, plan_revision, entity_set_version, high_risk)
                        values (?, ?, ?, ?, ?, ?, null, 'GAP', '[]', 0, 0, 1, 1, ?)
                        """, Ids.newId(), runId, rowId, entity.key() + ":" + column.key(), branchId,
                        column.key(), column.highRisk());
            }
        }
        for (ValidatedColumn column : plan.columns()) {
            jdbcTemplate.update("""
                    insert into research_column(
                        id, research_run_id, column_key, label, description, answer_format, high_risk,
                        required_provenance_level, search_intents_json, plan_revision)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                    """, Ids.newId(), runId, column.key(), column.label(), column.description(),
                    column.answerFormat(), column.highRisk(), column.requiredProvenanceLevel(),
                    Json.write(objectMapper, column.searchIntents()));
        }
        Map<String, Object> planJson = new LinkedHashMap<>();
        planJson.put("planner_version", plannerVersion);
        planJson.put("rows", plan.entities().stream().map(entity -> Map.of(
                "key", entity.key(), "label", entity.label(), "aliases", entity.aliases())).toList());
        planJson.put("columns", plan.columns().stream().map(column -> Map.of(
                "key", column.key(), "label", column.label(), "description", column.description(),
                "answer_format", column.answerFormat(), "high_risk", column.highRisk(),
                "required_provenance_level", column.requiredProvenanceLevel(),
                "search_intents", column.searchIntents())).toList());
        planJson.put("brief", plan.brief());
        planJson.put("research_type", plan.researchType());
        planJson.put("effort_tier", plan.effortTier());
        planJson.put("authoritative_domains", plan.authoritativeDomains());
        planJson.put("perspectives", plan.perspectives());
        planJson.put("reason_codes", plan.reasonCodes());
        planJson.put("not_applicable", List.copyOf(new java.util.TreeSet<>(plan.notApplicable())));
        planJson.put("worker_degradation_reasons", workerReasons);
        int cellCount = plan.entities().size() * plan.columns().size();
        String digest = canonicalizer.domainSeparatedDigest("research-matrix-plan.v2", planJson);
        jdbcTemplate.update("""
                update research_matrix_plan set plan_status = 'SUPERSEDED'
                where research_run_id = ? and plan_status in ('ACTIVE', 'SHADOW')
                """, runId);
        jdbcTemplate.update("""
                insert into research_matrix_plan(
                    id, research_run_id, planner_version, plan_mode, plan_status,
                    row_count, column_count, cell_count, bounded, reason_codes_json,
                    plan_json, plan_digest)
                values (?, ?, ?, 'LLM_MATRIX', 'ACTIVE', ?, ?, ?, ?, ?, ?, ?)
                """, Ids.newId(), runId, plannerVersion, plan.entities().size(), plan.columns().size(), cellCount,
                !plan.reasonCodes().isEmpty(), Json.write(objectMapper, plan.reasonCodes()),
                Json.write(objectMapper, planJson), digest);
        jdbcTemplate.update("update research_run set research_brief_json = ? where id = ?",
                Json.write(objectMapper, plan.brief()), runId);
        jdbcTemplate.update("""
                insert into research_trace(id, research_run_id, trace_type, trace_message, payload_json)
                values (?, ?, 'PLAN_COMPILED', ?, ?)
                """, Ids.newId(), runId, "研究表格已由模型规划",
                Json.write(objectMapper, Map.of("planner_version", plannerVersion, "rows", plan.entities().size(),
                        "columns", plan.columns().size(), "reason_codes", plan.reasonCodes())));
    }

    private void fallback(String runId, String reason) {
        markReady(runId);
        jdbcTemplate.update("""
                insert into research_trace(id, research_run_id, trace_type, trace_message, payload_json)
                values (?, ?, 'PLAN_FALLBACK', ?, ?)
                """, Ids.newId(), runId, "模型规划不可用，沿用规则生成的表格",
                Json.write(objectMapper, Map.of("reason_code", "PLANNER_FALLBACK_DETERMINISTIC", "detail", reason)));
        log.info("Research plan fallback for run {}: {}", runId, reason);
    }

    private void markReady(String runId) {
        jdbcTemplate.update("update research_run set plan_status = ?, plan_review_deadline = null where id = ?",
                STATUS_READY, runId);
    }

    private String planStatus(String runId) {
        return jdbcTemplate.query("select plan_status from research_run where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, runId);
    }

    private void requireReviewable(String workspaceId, String runId) {
        String status = jdbcTemplate.query("""
                select plan_status from research_run where id = ? and workspace_id = ? for update
                """, rs -> rs.next() ? rs.getString(1) : null, runId, workspaceId);
        if (!STATUS_REVIEW.equals(status)) {
            throw new BusinessException("RESEARCH_PLAN_NOT_IN_REVIEW", "研究表格当前不处于确认阶段", HttpStatus.CONFLICT);
        }
    }

    private Map<String, Object> previousPlan(String runId) {
        List<String> plans = jdbcTemplate.queryForList("""
                select plan_json from research_matrix_plan
                where research_run_id = ? and plan_mode = 'LLM_MATRIX'
                order by created_at desc, id desc limit 1
                """, String.class, runId);
        return plans.isEmpty() ? Map.of() : readMap(plans.get(0));
    }

    private Map<String, Object> planningInputOf(ResearchAgentCompletionCommitter.TaskRow task) {
        Map<String, Object> context = readMap(task.executionContextJson());
        return mapValue(mapValue(context.get("query_policy")).get("planning_input"));
    }

    private Map<String, Object> readMap(Object raw) {
        if (raw == null) return Map.of();
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(String.valueOf(raw));
            if (node.isTextual()) node = objectMapper.readTree(node.asText());
            if (!node.isObject()) return Map.of();
            return objectMapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String clean(String value, int maxChars) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFC)
                .replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").strip();
        return normalized.codePointCount(0, normalized.length()) <= maxChars
                ? normalized
                : normalized.substring(0, normalized.offsetByCodePoints(0, maxChars)).strip();
    }

    private static String normalizedKey(String label) {
        return Normalizer.normalize(label, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String shortDigest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizedKey(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("RESEARCH_AGENT_COMPLETION_INVALID", message);
    }

    public record GateOutcome(boolean waiting, String outcome,
                              ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskization) {
        static GateOutcome proceed() {
            return new GateOutcome(false, "PLAN_READY", null);
        }

        static GateOutcome waiting(String outcome, ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskization) {
            return new GateOutcome(true, outcome, taskization);
        }
    }

    record ValidatedEntity(String key, String label, List<String> aliases) { }

    record ValidatedColumn(String key, String label, String description, String answerFormat, boolean highRisk,
                           String requiredProvenanceLevel, List<String> searchIntents) { }

    record ValidatedPlan(List<ValidatedEntity> entities, List<ValidatedColumn> columns,
                         List<String> authoritativeDomains, String effortTier, String researchType,
                         Map<String, Object> brief, List<Map<String, Object>> perspectives,
                         List<String> reasonCodes, Set<String> notApplicable) {
        ValidatedPlan {
            notApplicable = notApplicable == null ? Set.of() : Set.copyOf(notApplicable);
        }

        ValidatedPlan(List<ValidatedEntity> entities, List<ValidatedColumn> columns,
                      List<String> authoritativeDomains, String effortTier, String researchType,
                      Map<String, Object> brief, List<Map<String, Object>> perspectives, List<String> reasonCodes) {
            this(entities, columns, authoritativeDomains, effortTier, researchType, brief, perspectives, reasonCodes, Set.of());
        }
    }
}

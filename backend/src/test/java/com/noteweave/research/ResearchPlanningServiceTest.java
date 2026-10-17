package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ResearchPlanningServiceTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ResearchPlanningService planning;
    @Autowired private ResearchAgentTaskCoordinatorService coordinator;
    @Autowired private ResearchAgentCompletionCanonicalizer canonicalizer;
    @Autowired private ResearchAgentRunBootstrapService bootstrap;
    @Autowired private ResearchAgentCoordinatorSnapshotService snapshots;
    @MockBean private ResearchAgentExternalEvidencePolicy externalEvidencePolicy;

    private String workspaceId;
    private String runId;

    @BeforeEach
    void setUp() throws Exception {
        workspaceId = Ids.newId();
        runId = Ids.newId();
        String parentTaskId = Ids.newId();
        String fileId = Ids.newId();
        String sourceId = Ids.newId();
        String snapshotId = Ids.newId();
        String chunkId = Ids.newId();
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', 'planning-test', 'ACTIVE')", workspaceId);
        jdbcTemplate.update("insert into task(id, workspace_id, task_type, task_status, target_type, target_id) values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)", parentTaskId, workspaceId, runId);
        jdbcTemplate.update("insert into file_object(id, workspace_id, object_key, sha256, file_size) values (?, ?, ?, ?, 20)", fileId, workspaceId, "source/plan.txt", "c".repeat(64));
        jdbcTemplate.update("insert into source(id, workspace_id, file_object_id, title, source_type, status, parse_status, index_status) values (?, ?, ?, 'Database notes', 'TEXT', 'READY', 'READY', 'READY')", sourceId, workspaceId, fileId);
        jdbcTemplate.update("insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status) values (?, ?, ?, 1, 'source/plan.txt', ?, 'READY', 'READY')", snapshotId, sourceId, fileId, "d".repeat(64));
        jdbcTemplate.update("insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, chunk_no, content, token_estimate) values (?, ?, ?, ?, 1, 'JSONB supports GIN indexes.', 5)", chunkId, workspaceId, sourceId, snapshotId);
        jdbcTemplate.update("insert into source_window(id, source_chunk_id, window_no, content) values (?, ?, 1, 'JSONB supports GIN indexes.')", Ids.newId(), chunkId);
        jdbcTemplate.update("""
                insert into research_run(
                    id, workspace_id, task_id, question, profile_key, source_scope_json,
                    research_intent_json, control_pack_json, retrieval_mode, status, agent_execution_mode)
                values (?, ?, ?, 'Compare PostgreSQL and MySQL JSON indexing', 'DEFAULT', ?, ?, ?,
                    'WEB_PLUS_SEEDS', 'RUNNING', 'INCREMENTAL_V1')
                """, runId, workspaceId, parentTaskId, objectMapper.writeValueAsString(List.of(sourceId)),
                objectMapper.writeValueAsString(Map.of("depth", "STANDARD", "research_type", "AUTO",
                        "constraints", List.of())),
                objectMapper.writeValueAsString(Map.of(
                        "pack_type", "RESEARCH_AGENT", "target_key", "DEFAULT", "task_neighborhood", "RESEARCH_DEFAULT")));
        bootstrap.bootstrap(runId, "Compare PostgreSQL and MySQL JSON indexing", ResearchIntentPolicy.defaultIntent());
        planning.requirePlanning(runId);
    }

    @Test
    void shouldTaskizeOnePlanningTaskAndHoldCellTasksUntilPlanned() throws Exception {
        var first = planning.advance(runId);
        var second = planning.advance(runId);

        assertThat(first.waiting()).isTrue();
        assertThat(first.outcome()).isEqualTo("PLANNING_TASKIZED");
        assertThat(second.outcome()).isEqualTo("PLANNING_PENDING");
        List<Map<String, Object>> tasks = jdbcTemplate.queryForList(
                "select role, target_cells_json, execution_context_json from research_agent_task where research_run_id = ?", runId);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).get("ROLE")).isEqualTo("PLANNING");
        JsonNode policy = objectMapper.readTree(String.valueOf(tasks.get(0).get("EXECUTION_CONTEXT_JSON"))).path("query_policy");
        assertThat(policy.path("allowed_tools").get(0).asText()).isEqualTo("search");
        assertThat(policy.path("planning_input").path("input_digest").asText()).startsWith("sha256:");
        assertThat(policy.path("planning_input").path("max_cells").asInt()).isEqualTo(80);
        // 规划任务不计入深挖波次，Coordinator 看到的仍是一个没有任务的 Run
        var snapshot = snapshots.snapshot(runId);
        assertThat(snapshot.taskCount()).isZero();
        assertThat(snapshot.activeTaskCount()).isZero();
    }

    @Test
    void shouldReplaceSkeletonWithValidatedModelPlanAndCarryGuidanceIntoCellTasks() throws Exception {
        var task = planningTask();

        planning.applyResult(task, result("PLANNED", plan(List.of("PostgreSQL", "MySQL", " mysql "),
                List.of("JSON index types", "Indexed operators", "JSON index types"))));

        assertThat(planStatus()).isEqualTo("READY");
        assertThat(jdbcTemplate.queryForList("select source_title from research_row where research_run_id = ? order by source_title",
                String.class, runId)).containsExactly("MySQL", "PostgreSQL");
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_cell where research_run_id = ?", Integer.class, runId))
                .isEqualTo(4);
        assertThat(jdbcTemplate.queryForList("select label from research_column where research_run_id = ? order by label",
                String.class, runId)).containsExactly("Indexed operators", "JSON index types");
        assertThat(jdbcTemplate.queryForObject("""
                select plan_status from research_matrix_plan where research_run_id = ? and plan_mode = 'LLM_MATRIX'
                """, String.class, runId)).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForList("""
                select plan_status from research_matrix_plan where research_run_id = ? and plan_mode = 'INTENT_MATRIX_V2'
                """, String.class, runId)).containsOnly("SUPERSEDED");

        coordinator.planAndEnqueueForWave(runId, 1);

        List<String> contexts = jdbcTemplate.queryForList("""
                select execution_context_json from research_agent_task where research_run_id = ? and role = 'DEEP_CELL'
                """, String.class, runId);
        assertThat(contexts).isNotEmpty();
        JsonNode policy = objectMapper.readTree(contexts.get(0)).path("query_policy");
        assertThat(policy.path("objective").asText()).isEqualTo("Compare JSON indexing");
        assertThat(policy.path("effort_tier").asText()).isEqualTo("STANDARD");
        assertThat(policy.path("authoritative_domains").toString()).contains("postgresql.org").doesNotContain("not a domain");
        assertThat(policy.path("boundaries").get(0).asText()).isEqualTo("Other databases");
        JsonNode column = policy.path("column_specs").get(0);
        assertThat(column.path("description").asText()).isNotBlank();
        assertThat(column.path("search_intents").isArray()).isTrue();
    }

    @Test
    void shouldKeepSkeletonWhenWorkerFallsBack() {
        List<String> skeletonCells = cellKeys();

        planning.applyResult(planningTask(), result("FALLBACK", null));

        assertThat(planStatus()).isEqualTo("READY");
        assertThat(cellKeys()).isEqualTo(skeletonCells);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_trace where research_run_id = ? and trace_type = 'PLAN_FALLBACK'
                """, Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void shouldFallBackWhenThePlanningTaskEndsWithoutAResult() {
        planning.advance(runId);
        jdbcTemplate.update("update research_agent_task set status = 'FAILED' where research_run_id = ? and role = 'PLANNING'", runId);

        var outcome = planning.advance(runId);

        assertThat(outcome.waiting()).isFalse();
        assertThat(planStatus()).isEqualTo("READY");
    }

    @Test
    void shouldRejectStaleInputDigestAndInvalidPlans() {
        var task = planningTask();
        Map<String, Object> stale = new LinkedHashMap<>(result("PLANNED", plan(List.of("A"), List.of("x", "y"))));
        stale.put("input_digest", "sha256:" + "0".repeat(64));
        assertThatThrownBy(() -> planning.applyResult(task, stale))
                .isInstanceOf(BusinessException.class).hasMessageContaining("stale");
        assertThatThrownBy(() -> planning.applyResult(task, result("PLANNED", plan(List.of("A"), List.of("only one")))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("two dimensions");
        assertThat(planStatus()).isEqualTo("PLANNING");
    }

    @Test
    void validationShouldTruncateRowsToTheCellBudgetAndDropBadDomains() {
        List<String> entities = new ArrayList<>();
        for (int index = 0; index < 20; index++) entities.add("Entity " + index);
        List<String> dimensions = List.of("d1", "d2", "d3", "d4", "d5", "d6", "d7", "d8");

        var validated = planning.validate(plan(entities, dimensions));

        assertThat(validated.columns()).hasSize(8);
        assertThat(validated.entities()).hasSize(10);
        assertThat(validated.reasonCodes()).contains("ROWS_TRUNCATED");
        assertThat(validated.authoritativeDomains()).containsExactly("postgresql.org", "dev.mysql.com");
    }

    @Test
    void reviewWindowShouldWaitUntilApprovedAndAllowRevision() {
        var reviewing = new ResearchPlanningService(jdbcTemplate, objectMapper, coordinator, canonicalizer, true, 3600, true);
        reviewing.applyResult(planningTask(), result("PLANNED", plan(List.of("PostgreSQL"), List.of("a", "b"))));

        assertThat(planStatus()).isEqualTo("REVIEW");
        assertThat(reviewing.advance(runId).outcome()).isEqualTo("PLAN_REVIEW_PENDING");

        reviewing.revise(workspaceId, runId, Map.of(
                "entities", List.of(Map.of("label", "PostgreSQL"), Map.of("label", "SQLite")),
                "dimensions", List.of(Map.of("label", "a"), Map.of("label", "c"))));
        Map<String, Object> plan = reviewing.readPlan(workspaceId, runId);
        assertThat(plan.get("plan_status")).isEqualTo("REVIEW");
        assertThat(plan.get("planner")).isEqualTo("user-revision.1");
        assertThat(cellKeys()).hasSize(4);

        reviewing.approve(workspaceId, runId);
        assertThat(planStatus()).isEqualTo("READY");
        assertThatThrownBy(() -> reviewing.approve(workspaceId, runId)).isInstanceOf(BusinessException.class);
    }

    private ResearchAgentCompletionCommitter.TaskRow planningTask() {
        planning.advance(runId);
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select id, execution_context_json from research_agent_task where research_run_id = ? and role = 'PLANNING'
                """, runId);
        return new ResearchAgentCompletionCommitter.TaskRow(
                String.valueOf(row.get("ID")), runId, "RUNNING", "PLANNING", "planning", "branch-main", 1, 1,
                "[]", "{}", String.valueOf(row.get("EXECUTION_CONTEXT_JSON")), "research-agent-task-snapshot.v3",
                "sha256:x", "worker-a", 1, 1L, "planning:" + runId, null, 1, 1, true);
    }

    private Map<String, Object> result(String status, Map<String, Object> plan) {
        Map<String, Object> input = planning.planningInput(runId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result_schema_version", "research-plan-matrix.v1");
        result.put("role", "PLANNING");
        result.put("status", status);
        result.put("input_digest", input.get("input_digest"));
        result.put("degradation_reasons", List.of());
        if (plan != null) result.put("plan", plan);
        return result;
    }

    @Test
    void notApplicablePairsAreNotCreatedAndHighRiskNeedsTheQuorumSwitch() {
        Map<String, Object> raw = plan(List.of("OAuth 2.1", "OpenID Connect"), List.of("Removed grants", "OIDC additions"));
        raw.put("not_applicable", List.of(Map.of("entity", "OAuth 2.1", "dimension", "OIDC additions")));
        raw.put("dimensions", List.of(
                Map.of("label", "Removed grants", "description", "d", "high_risk", true),
                Map.of("label", "OIDC additions", "description", "d")));
        var defaults = new ResearchPlanningService(jdbcTemplate, objectMapper, coordinator, canonicalizer, true, 0, false);

        defaults.applyResult(planningTask(), result("PLANNED", raw));

        // 2 × 2 去掉一个不适用的组合
        assertThat(cellKeys()).hasSize(3);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_cell where research_run_id = ? and high_risk = true", Integer.class, runId)).isZero();
    }

    private Map<String, Object> plan(List<String> entities, List<String> dimensions) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("brief", Map.of("objective", "Compare JSON indexing", "exclusions", List.of("Other databases")));
        plan.put("effort_tier", "standard");
        plan.put("entities", entities.stream().map(label -> Map.<String, Object>of("label", label)).toList());
        plan.put("dimensions", dimensions.stream().map(label -> Map.<String, Object>of(
                "label", label, "description", "Evidence about " + label, "answer_format", "LIST",
                "search_intents", List.of(label + " documentation"))).toList());
        plan.put("authoritative_domains", List.of("www.postgresql.org", "dev.mysql.com", "not a domain"));
        return plan;
    }

    private String planStatus() {
        return jdbcTemplate.queryForObject("select plan_status from research_run where id = ?", String.class, runId);
    }

    private List<String> cellKeys() {
        return jdbcTemplate.queryForList("select cell_key from research_cell where research_run_id = ? order by cell_key",
                String.class, runId);
    }
}

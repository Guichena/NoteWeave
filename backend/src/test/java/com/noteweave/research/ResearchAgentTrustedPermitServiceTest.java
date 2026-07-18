package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ResearchAgentTrustedPermitServiceTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchAgentTaskService taskService;
    @Autowired private ResearchAgentPermitService permitService;
    @Autowired private ResearchExternalSnapshotArchiveService externalSnapshotArchiveService;
    @Autowired private MockMvc mockMvc;
    @MockBean private ResearchAgentRateLimitService rateLimitService;

    private String workspaceId;
    private String runId;
    private ResearchAgentTaskService.ClaimedTask claim;

    @BeforeEach
    void setUp() {
        workspaceId = Ids.newId();
        runId = Ids.newId();
        String parentTaskId = Ids.newId();
        jdbcTemplate.update(
                "insert into workspace(id, owner_id, name, status) values (?, 'local-user', 'permit-test', 'ACTIVE')",
                workspaceId);
        jdbcTemplate.update("""
                insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
                values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)
                """, parentTaskId, workspaceId, runId);
        jdbcTemplate.update("""
                insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json,
                    status, agent_execution_mode)
                values (?, ?, ?, 'permit question', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1')
                """, runId, workspaceId, parentTaskId);
        String rowId = Ids.newId();
        jdbcTemplate.update(
                "insert into research_row(id, research_run_id, row_key, row_status) values (?, ?, 'entity-1', 'CANDIDATE_READY')",
                rowId, runId);
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key,
                    candidate_value, cell_status, repair_count, cell_version, plan_revision, entity_set_version)
                values (?, ?, ?, 'entity-1:method', 'method', 'old', 'CANDIDATE_READY', 0, 3, 2, 3)
                """, Ids.newId(), runId, rowId);

        ResearchAgentTaskService.TaskSnapshot task = taskService.createTask(
                new ResearchAgentTaskService.CreateTaskCommand(
                        runId, "permit-task", "permit-idempotency", 1, "DEEP_CELL", "entity-1",
                        "branch-main", 2, 3, List.of("entity-1:method"), Map.of("llm_calls", 2),
                        List.of(new ResearchAgentTaskService.TargetCellBinding("entity-1:method", 3)),
                        new ResearchAgentTaskService.TaskExecutionContext(
                                "research-default",
                                Map.of("allow_workspace_sources", true),
                                Map.of("query", "permit question"))));
        claim = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(task.taskId(), "worker-a", 30));
    }

    @Test
    void shouldDeriveRateLimitScopeOnlyFromLockedAuthoritativeSnapshot() {
        ResearchAgentPermitService.PermitReceipt receipt = permitService.requirePermit(command("search"));

        ArgumentCaptor<ResearchAgentRateLimitService.PermitRequest> scope =
                ArgumentCaptor.forClass(ResearchAgentRateLimitService.PermitRequest.class);
        verify(rateLimitService).requirePermit(scope.capture());
        assertThat(scope.getValue()).isEqualTo(new ResearchAgentRateLimitService.PermitRequest(
                "research-default", workspaceId, runId, "DEEP_CELL"));
        assertThat(receipt.toolIdentity()).isEqualTo("search");
    }

    @Test
    void shouldRejectCallerSuppliedLegacyScopeFieldsInsteadOfIgnoringSpoof() throws Exception {
        mockMvc.perform(post("/internal/research-agent/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "task_id":"%s",
                                  "worker_instance_id":"worker-a",
                                  "lease_epoch":%d,
                                  "fencing_token":%d,
                                  "tool_identity":"search",
                                  "provider_key":"attacker-provider",
                                  "workspace_id":"attacker-workspace",
                                  "research_run_id":"attacker-run",
                                  "role":"SYNTHESIS"
                                }
                                """.formatted(claim.taskId(), claim.leaseEpoch(), claim.fencingToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESEARCH_AGENT_PERMIT_INVALID"));

        verifyNoInteractions(rateLimitService);
    }

    @Test
    void shouldReturnBadRequestForMalformedPermitJsonInsteadOfInternalError() throws Exception {
        mockMvc.perform(post("/internal/research-agent/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{task_id:invalid}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(rateLimitService);
    }

    @Test
    void shouldAcceptOnlyExactLeaseAndToolIdentityHttpContract() throws Exception {
        mockMvc.perform(post("/internal/research-agent/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "task_id":"%s",
                                  "worker_instance_id":"worker-a",
                                  "lease_epoch":%d,
                                  "fencing_token":%d,
                                  "tool_identity":"search"
                                }
                                """.formatted(claim.taskId(), claim.leaseEpoch(), claim.fencingToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("GRANTED"))
                .andExpect(jsonPath("$.data.tool_identity").value("search"));

        verify(rateLimitService).requirePermit(new ResearchAgentRateLimitService.PermitRequest(
                "research-default", workspaceId, runId, "DEEP_CELL"));
    }

    @Test
    void shouldRejectWrongOwnerEpochFenceExpiredLeaseAndForbiddenToolBeforeRateLimit() {
        assertStale(new ResearchAgentPermitService.PermitCommand(
                claim.taskId(), "worker-b", claim.leaseEpoch(), claim.fencingToken(), "search"));
        assertStale(new ResearchAgentPermitService.PermitCommand(
                claim.taskId(), "worker-a", claim.leaseEpoch() + 1, claim.fencingToken(), "search"));
        assertStale(new ResearchAgentPermitService.PermitCommand(
                claim.taskId(), "worker-a", claim.leaseEpoch(), claim.fencingToken() + 1, "search"));

        assertThatThrownBy(() -> permitService.requirePermit(command("synthesis")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_PERMIT_TOOL_FORBIDDEN");

        jdbcTemplate.update("""
                update research_agent_task set lease_expires_at = timestampadd(second, -1, current_timestamp)
                where id = ?
                """, claim.taskId());
        assertStale(command("search"));
        verifyNoInteractions(rateLimitService);
    }

    @Test
    void shouldRejectSnapshotTamperingBeforeDerivingProviderScope() {
        jdbcTemplate.update("""
                update research_agent_task
                set execution_context_json = '{"provider_key":"attacker","source_policy":{"x":true},"query_policy":{"q":"x"}}'
                where id = ?
                """, claim.taskId());

        assertThatThrownBy(() -> permitService.requirePermit(command("search")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_TASK_SNAPSHOT_INVALID");
        verifyNoInteractions(rateLimitService);
    }

    @Test
    void heartbeatShouldOnlyAdvanceLeaseStateAndPreserveSnapshotBudgetAndCellBinding() {
        Map<String, Object> taskBefore = jdbcTemplate.queryForMap("""
                select target_cells_json, budget_json, target_bindings_json, execution_context_json,
                    snapshot_schema_version, snapshot_digest, role, entity_id, branch_id, plan_revision,
                    entity_set_version, lease_epoch, fencing_token, worker_instance_id
                from research_agent_task where id = ?
                """, claim.taskId());
        Map<String, Object> cellBefore = jdbcTemplate.queryForMap("""
                select active_task_id, lease_epoch, fencing_token, cell_version, candidate_value
                from research_cell where research_run_id = ? and cell_key = 'entity-1:method'
                """, runId);

        ResearchAgentTaskService.ClaimedTask heartbeat = taskService.heartbeat(
                new ResearchAgentTaskService.LeaseCommand(
                        claim.taskId(), "worker-a", claim.leaseEpoch(), claim.fencingToken(), 45));

        Map<String, Object> taskAfter = jdbcTemplate.queryForMap("""
                select target_cells_json, budget_json, target_bindings_json, execution_context_json,
                    snapshot_schema_version, snapshot_digest, role, entity_id, branch_id, plan_revision,
                    entity_set_version, lease_epoch, fencing_token, worker_instance_id
                from research_agent_task where id = ?
                """, claim.taskId());
        Map<String, Object> cellAfter = jdbcTemplate.queryForMap("""
                select active_task_id, lease_epoch, fencing_token, cell_version, candidate_value
                from research_cell where research_run_id = ? and cell_key = 'entity-1:method'
                """, runId);

        assertThat(taskAfter).isEqualTo(taskBefore);
        assertThat(cellAfter).isEqualTo(cellBefore);
        assertThat(heartbeat.taskSnapshotJson()).isEqualTo(claim.taskSnapshotJson());
        assertThat(heartbeat.snapshotDigest()).isEqualTo(claim.snapshotDigest());
    }

    @Test
    void shouldArchiveExternalTextOnlyUnderTheExactAuthoritativeLeaseAndReplayIdentically() {
        ResearchExternalSnapshotArchiveService.ArchiveCommand command = archiveCommand("Archived web evidence.");

        ResearchExternalSnapshotArchiveService.ArchiveReceipt first = externalSnapshotArchiveService.archive(command);
        ResearchExternalSnapshotArchiveService.ArchiveReceipt replay = externalSnapshotArchiveService.archive(command);

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(first.snapshotStatus()).isEqualTo("EXTERNAL_ARCHIVED");
        assertThat(first.snapshotKey()).startsWith("research/external/" + claim.taskId() + "/");
        assertThat(replay).isEqualTo(new ResearchExternalSnapshotArchiveService.ArchiveReceipt(
                first.archiveId(), claim.taskId(), "external-window-1", "web-source-1", "EXTERNAL_ARCHIVED",
                first.snapshotKey(), first.contentSha256(), true));
        assertThat(jdbcTemplate.queryForMap("""
                select research_run_id, research_agent_task_id, source_url, source_domain, content_text, content_sha256
                from research_external_snapshot where id = ?
                """, first.archiveId()))
                .containsEntry("research_run_id", runId)
                .containsEntry("research_agent_task_id", claim.taskId())
                .containsEntry("source_url", "https://example.com/research")
                .containsEntry("source_domain", "example.com")
                .containsEntry("content_text", "Archived web evidence.")
                .containsEntry("content_sha256", first.contentSha256());
        verifyNoInteractions(rateLimitService);
    }

    @Test
    void shouldRejectExternalArchiveWhenLeaseIsStaleOrIdentityIsReusedWithDifferentContent() {
        ResearchExternalSnapshotArchiveService.ArchiveCommand command = archiveCommand("Original archived web evidence.");
        externalSnapshotArchiveService.archive(command);

        assertThatThrownBy(() -> externalSnapshotArchiveService.archive(
                archiveCommand("Tampered archived web evidence.")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_EXTERNAL_ARCHIVE_CONFLICT");
        assertThatThrownBy(() -> externalSnapshotArchiveService.archive(
                new ResearchExternalSnapshotArchiveService.ArchiveCommand(
                        claim.taskId(), "worker-b", claim.leaseEpoch(), claim.fencingToken(), "other-window", "web-source-2",
                        "Other source", "https://example.com/other", "search-provider", "external_url", "Other content")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_TASK_STALE_LEASE");
        verifyNoInteractions(rateLimitService);
    }

    private ResearchAgentPermitService.PermitCommand command(String toolIdentity) {
        return new ResearchAgentPermitService.PermitCommand(
                claim.taskId(), "worker-a", claim.leaseEpoch(), claim.fencingToken(), toolIdentity);
    }

    private ResearchExternalSnapshotArchiveService.ArchiveCommand archiveCommand(String content) {
        return new ResearchExternalSnapshotArchiveService.ArchiveCommand(
                claim.taskId(), "worker-a", claim.leaseEpoch(), claim.fencingToken(), "external-window-1", "web-source-1",
                "External source", "https://example.com/research", "search-provider", "external_url", content);
    }

    private void assertStale(ResearchAgentPermitService.PermitCommand command) {
        assertThatThrownBy(() -> permitService.requirePermit(command))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_TASK_STALE_LEASE");
    }
}

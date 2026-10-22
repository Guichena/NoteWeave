package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.config.InternalServiceAuthFilter;
import java.util.LinkedHashMap;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/** 使用真实任务、租约、事务和 HTTP 过滤器核对新版成文运行边界。 */
@SpringBootTest(properties = {
        "noteweave.internal.research-auth-token=completion-test-token",
        "noteweave.research.worker-synthesis-v1=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResearchCompletionRuntimeIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ResearchAgentTaskService tasks;
    @Autowired private ResearchSynthesisRecoveryService recovery;
    @Autowired private ResearchSynthesisUploadService uploads;
    @Autowired private ResearchBudgetAndCheckpointService budgets;
    @Autowired private ResearchAgentLifecycleService lifecycle;
    @Autowired private ResearchAgentCommandOutboxService outbox;
    @Autowired private MockMvc http;
    @Autowired private PlatformTransactionManager transactions;
    private String run;
    private String workspace;

    @BeforeEach
    void createRun() {
        run = Ids.newId(); workspace = Ids.newId();
        String parent = Ids.newId();
        jdbc.update("insert into workspace(id,owner_id,name,status) values (?,'local-user','completion integration','ACTIVE')",workspace);
        jdbc.update("insert into workspace_member(id,workspace_id,user_id,role,status) values (?,?,'local-user','OWNER','ACTIVE')",Ids.newId(),workspace);
        jdbc.update("insert into task(id,workspace_id,task_type,task_status,target_type,target_id) values (?,?,'RESEARCH_RUN','RUNNING','RESEARCH_RUN',?)",parent,workspace,run);
        jdbc.update("insert into research_run(id,workspace_id,task_id,question,profile_key,source_scope_json,status,agent_execution_mode,agent_feature_flags_json) values (?,?,?,'Compare features','DEFAULT','[]','RUNNING','INCREMENTAL_V1',?)",
                run,workspace,parent,"{\"requirement_coverage_v1\":true,\"requirement_state_version\":5}");
        jdbc.update("update research_run set control_pack_json='{}' where id=?",run);
    }

    @AfterEach
    void cancelRemainingRun() {
        if ("RUNNING".equals(jdbc.queryForObject("select status from research_run where id=?",String.class,run)))
            lifecycle.cancelRun(run,"COMPLETION_TEST_FINISHED");
    }

    @Test
    void interruptedGenerationMustKeepItsConsumptionAcrossRealLeasesAndCancellation() {
        String task = synthesisTask();
        String reservation = budgets.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(run,task,"reserve",Map.of("llm_calls",2L))).reservationId();
        var first = claim(task,"worker-first");
        var firstLease = lease(first,"worker-first");
        recovery.permitCall(task,firstLease,0,"research.synthesis");
        jdbc.update("update research_agent_task set lease_expires_at=timestampadd(second,-1,current_timestamp) where id=?",task);
        tasks.expireLeases();
        var second = claim(task,"worker-second");
        var secondLease = lease(second,"worker-second");
        assertThat(second.leaseEpoch()).isGreaterThan(first.leaseEpoch());
        assertThat(recovery.recover(task,secondLease)).containsEntry("recorded_llm_calls",1);
        assertThatThrownBy(() -> recovery.permitCall(task,firstLease,1,"research.synthesis")).isInstanceOf(BusinessException.class);
        recovery.permitCall(task,secondLease,0,"research.synthesis");
        assertThatThrownBy(() -> recovery.permitCall(task,secondLease,1,"research.synthesis")).isInstanceOf(BusinessException.class);
        lifecycle.cancelRun(run,"COMPLETION_TEST_CANCELLED");
        var settled = jdbc.queryForMap("select consumed_json,released_json,state from research_budget_reservation where id=?",reservation);
        assertThat(settled.get("state")).isEqualTo("RELEASED");
        assertThat(budgetValue(settled.get("consumed_json"))).isEqualTo(2);
        assertThat(budgetValue(settled.get("released_json"))).isZero();
    }

    @Test
    void concurrentReplaysMustProduceOneDurablePermit() throws Exception {
        String task = synthesisTask();
        var current = lease(claim(task,"worker-concurrent"),"worker-concurrent");
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> { start.await(); return recovery.permitCall(task,current,0,"research.synthesis"); });
            var second = executor.submit(() -> { start.await(); return recovery.permitCall(task,current,0,"research.synthesis"); });
            start.countDown();
            assertThat(List.of(first.get(15,TimeUnit.SECONDS).get("idempotent_replay"),second.get(15,TimeUnit.SECONDS).get("idempotent_replay")))
                    .containsExactlyInAnyOrder(false,true);
            assertThat(jdbc.queryForObject("select count(*) from research_synthesis_call_permit where task_id=?",Integer.class,task)).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void internalHttpMustRejectMissingAuthenticationAndTamperedSnapshot() throws Exception {
        String task = synthesisTask();
        var current = claim(task,"worker-http");
        Map<String,Object> body = new LinkedHashMap<>(Map.of("worker_instance_id","worker-http","lease_epoch",current.leaseEpoch(),
                "fencing_token",current.fencingToken(),"snapshot_digest",current.snapshotDigest(),"call_index",0,"purpose","research.synthesis"));
        String url = "/internal/research-agent-tasks/"+task+"/synthesis-call-permit";
        http.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isUnauthorized());
        body.put("snapshot_digest","sha256:"+"0".repeat(64));
        http.perform(post(url).header(InternalServiceAuthFilter.HEADER_NAME,"completion-test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from research_synthesis_call_permit where task_id=?",Integer.class,task)).isZero();
        body.put("snapshot_digest",current.snapshotDigest());
        http.perform(post(url).header(InternalServiceAuthFilter.HEADER_NAME,"completion-test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.recorded_llm_calls").value(1));
    }

    @Test
    void requirementsMustBeBoundToTheRequestedWorkspace() throws Exception {
        http.perform(get("/api/v2/workspaces/{workspace}/research-runs/{run}/requirements",workspace,run))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));
        http.perform(get("/api/v2/workspaces/{workspace}/research-runs/{run}/requirements",Ids.newId(),run))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void retentionMustProtectActiveLeaseAndUnsettledConsumption() {
        String task = synthesisTask();
        var first = claim(task,"worker-retention-first");
        var firstLease = lease(first,"worker-retention-first");
        byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = ResearchSynthesisUploadService.digest(body);
        recovery.permitCall(task,firstLease,0,"research.synthesis");
        recovery.start(task,new ResearchSynthesisRecoveryService.GenerationRequest(firstLease,digest,1,body.length,1));
        uploads.upload(task,new ResearchSynthesisUploadService.PartRequest("worker-retention-first",first.leaseEpoch(),first.fencingToken(),first.snapshotDigest(),
                digest,0,1,body.length,digest,Base64.getEncoder().encodeToString(body)));
        jdbc.update("update research_agent_task set lease_expires_at=timestampadd(second,-1,current_timestamp) where id=?",task);
        tasks.expireLeases();
        var second = claim(task,"worker-retention-second");
        assertThat(recovery.recover(task,lease(second,"worker-retention-second"))).containsEntry("ready",true);
        for (String table : List.of("research_synthesis_upload_part","research_synthesis_generation","research_synthesis_call_permit"))
            jdbc.update("update "+table+" set created_at=timestampadd(day,-10,current_timestamp) where task_id=?",task);
        var retention = new ResearchSynthesisRetentionService(jdbc,transactions,true,7);
        retention.clean();
        assertThat(jdbc.queryForObject("select count(*) from research_synthesis_upload_part where task_id=?",Integer.class,task)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select lease_epoch from research_synthesis_upload_part where task_id=?",Integer.class,task)).isEqualTo(second.leaseEpoch());
        assertThat(jdbc.queryForObject("select count(*) from research_synthesis_call_permit where task_id=?",Integer.class,task)).isEqualTo(1);
        lifecycle.cancelRun(run,"COMPLETION_RETENTION_TEST_FINISHED");
        retention.clean();
        for (String table : List.of("research_synthesis_upload_part","research_synthesis_generation","research_synthesis_call_permit"))
            assertThat(jdbc.queryForObject("select count(*) from "+table+" where task_id=?",Integer.class,task)).isZero();
    }

    @Test
    void newAnswerPolicyFreezesCriteriaAndSeparatesSupplementaryRequirements() {
        String task = synthesisTask();
        jdbc.update("update research_run set question='Which version first provided it and what is the current status?', agent_feature_flags_json=? where id=?",
                "{\"requirement_coverage_v1\":true,\"requirement_state_version\":5,\"requirement_definition_version\":2,\"requirement_answer_policy_version\":1}",run);
        var coverage = new ResearchRequirementCoverageService(jdbc,mapper);
        Map<String,Object> policy = coverage.attach(run,List.of("entity:feature"),Map.of("entity_label","Atlas",
                "query","Which version first provided it and what is the current status?",
                "column_requirements",Map.of("feature",List.of("Installation method")),
                "requirement_definitions",List.of(Map.of("text","Identify the first version","question_quote","Which version first provided it",
                        "dimension_key","feature","entity_keys",List.of(),"scope_type","CELL","completion_mode","SINGLE","minimum_count",1))));
        var contract = mapper.valueToTree(policy.get("requirement_contract"));
        assertThat(contract.path(ResearchAnswerPolicy.FLAG).asInt()).isEqualTo(1);
        assertThat(contract.path("requirements").get(0).path("scope").path("temporal_mode").asText()).isEqualTo("HISTORICAL");
        assertThat(contract.path("requirements").get(1).path("mandatory").asBoolean()).isFalse();
        assertThat(coverage.readModel(run)).containsEntry("required_count",1).containsEntry("optional_count",1);
        assertThat(coverage.requiredCapability(write(Map.of("query_policy",policy)))).isEqualTo("requirement_answer_policy_v1");
    }

    @Test
    void staleSnapshotFailureEndsInvalidTaskWithoutWaitingForRepeatedLeases() {
        snapshotFailure(true);
    }

    @Test
    void structuredIdentifierScopeDoesNotInheritTheCurrentStatusOfTheWholeQuestion() {
        synthesisTask();
        jdbc.update("update research_run set question='目前支持状态，PEP 编号和标题，引用官方文档',agent_feature_flags_json=? where id=?",
                "{\"requirement_coverage_v1\":true,\"requirement_state_version\":5,\"requirement_definition_version\":2,\"requirement_answer_policy_version\":1,\"completion_gap_policy_version\":1}",run);
        Map<String,Object> definition=new LinkedHashMap<>(Map.of("text","Name the relevant PEP","question_quote","PEP 编号和标题",
                "dimension_key","feature","entity_keys",List.of(),"scope_type","CELL","completion_mode","SINGLE","minimum_count",1));
        definition.putAll(Map.of("answer_kind","IDENTIFIER","temporal_mode","TIME_INVARIANT","conditions",List.of("PEP 编号","PEP 标题"),
                "source_constraints",List.of(Map.of("policy","PRIMARY_SOURCE","question_quote","引用官方文档"))));
        var coverage=new ResearchRequirementCoverageService(jdbc,mapper);
        Map<String,Object> policy=coverage.attach(run,List.of("entity:feature"),Map.of("entity_label","Atlas",
                "column_requirements",Map.of("feature",List.of("背景说明")),"requirement_definitions",List.of(definition)));
        var contract=mapper.valueToTree(policy.get("requirement_contract"));
        var scope=contract.path("requirements").get(0).path("scope");
        assertThat(scope.path("temporal_mode").asText()).isEqualTo("TIME_INVARIANT");
        assertThat(scope.path("answer_contract").path("version").asInt()).isEqualTo(2);
        assertThat(scope.path("answer_contract").path("conditions")).hasSize(2);
        assertThat(scope.path("answer_contract").path("source_constraints")).hasSize(1);
        assertThat(scope.has("answer_definition")).isFalse();
        assertThat(coverage.requiredCapability(write(Map.of("query_policy",policy)))).isEqualTo("completion_gap_policy_v1");
        assertThat(contract.path("related_materials").isArray()).isTrue();
    }

    @Test
    void fallbackWithoutExplicitDefinitionsMustNotMakeEveryRequirementOptional() {
        synthesisTask();
        jdbc.update("update research_run set agent_feature_flags_json=? where id=?",
                "{\"requirement_coverage_v1\":true,\"requirement_state_version\":5,\"requirement_answer_policy_version\":1}",run);
        var coverage = new ResearchRequirementCoverageService(jdbc,mapper);
        coverage.attach(run,List.of("entity:feature"),Map.of("entity_label","Atlas",
                "column_requirements",Map.of("feature",List.of("Identify the first version"))));
        assertThat(coverage.readModel(run)).containsEntry("required_count",1).containsEntry("optional_count",0);
    }

    @Test
    void workerFailureCodeCannotEndTaskWhoseServerSnapshotStillMatches() {
        snapshotFailure(false);
    }

    private void snapshotFailure(boolean invalidate) {
        String task = synthesisTask();
        jdbc.update("update research_run set agent_feature_flags_json=? where id=?",
                "{\"requirement_coverage_v1\":true,\"requirement_state_version\":5,\"requirement_answer_policy_version\":1}",run);
        jdbc.update("update research_agent_task set role='COUNTERFACTUAL' where id=?",task);
        jdbc.update("update research_cell set active_task_id=?, plan_revision=? where research_run_id=?",task,invalidate ? 1 : 0,run);
        String reservation = budgets.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(run,task,"scope-reserve",Map.of("llm_calls",2L))).reservationId();
        var delivery = outbox.enqueue(task);
        lifecycle.recordDeliveryFailure(new ResearchAgentLifecycleService.DeliveryFailureCommand(run,task,delivery.outboxId(),
                "scope-failure","RESEARCH_AGENT_TASK_SNAPSHOT_STALE","sha256:scope",1));
        assertThat(jdbc.queryForObject("select status from research_agent_task where id=?",String.class,task)).isEqualTo(invalidate ? "FAILED" : "PENDING");
        if (invalidate) {
            assertThat(jdbc.queryForObject("select terminal_reason from research_agent_task where id=?",String.class,task)).isEqualTo("SNAPSHOT_STALE_REPLAN");
            assertThat(jdbc.queryForObject("select state from research_budget_reservation where id=?",String.class,reservation)).isEqualTo("RELEASED");
            assertThat(jdbc.queryForObject("select active_task_id from research_cell where research_run_id=?",String.class,run)).isNull();
        }
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private String synthesisTask() {
        String digest = "sha256:"+"a".repeat(64);
        String row = Ids.newId();
        jdbc.update("insert into research_row(id,research_run_id,row_key,row_status) values (?,?,'entity','CANDIDATE_READY')",row,run);
        jdbc.update("insert into research_cell(id,research_run_id,research_row_id,cell_key,column_key,cell_status,repair_count,cell_version,plan_revision,entity_set_version) values (?,?,?,'entity:feature','feature','GAP',0,0,0,0)",Ids.newId(),run,row);
        return tasks.createTask(new ResearchAgentTaskService.CreateTaskCommand(run,"synthesis-test","synthesis-test",1,"SYNTHESIS","entity","branch-main",0,0,
                List.of("entity:feature"),Map.of("llm_calls",2),List.of(new ResearchAgentTaskService.TargetCellBinding("entity:feature",0)),new ResearchAgentTaskService.TaskExecutionContext("research-default",Map.of("allow_web_search",true),
                Map.of("synthesis_input",Map.of("report_upload_version",1,"report_recovery_version",1,"report_call_meter_version",1,
                        "narrative_llm_budget",2,"ledger_digest",digest,"audit_digest",digest))))).taskId();
    }
    private ResearchAgentTaskService.ClaimedTask claim(String task,String worker) {
        return tasks.claimTask(new ResearchAgentTaskService.ClaimCommand(task,worker,60,List.of("completion_runtime_v1")));
    }
    private ResearchSynthesisRecoveryService.LeaseRequest lease(ResearchAgentTaskService.ClaimedTask task,String worker) {
        return new ResearchSynthesisRecoveryService.LeaseRequest(worker,task.leaseEpoch(),task.fencingToken(),task.snapshotDigest());
    }
    private long budgetValue(Object value) {
        try {
            var node = mapper.readTree(String.valueOf(value));
            if (node.isTextual()) node = mapper.readTree(node.asText());
            return node.path("llm_calls").asLong(-1);
        } catch (Exception failure) { throw new AssertionError("预算 JSON 无法读取",failure); }
    }
}

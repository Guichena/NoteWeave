package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.mockito.Mockito.*;

/** 使用受控租约和数据库核对恢复协议，不访问 Provider。 */
public class CompletionRuntimeV5Replay {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        var db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:runtimev5;MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("create table research_agent_task(id varchar(36) primary key,role varchar(32),execution_context_json varchar(8000))");
        db.execute("create table research_run(id varchar(36) primary key)");
        db.execute("create table research_requirement(requirement_id varchar(64) primary key)");
        db.execute("create table research_agent_completion(id varchar(36) primary key)");
        String task = "00000000-0000-0000-0000-000000000005", snapshot = "sha256:" + "c".repeat(64);
        String context = mapper.writeValueAsString(Map.of("query_policy",Map.of("synthesis_input",Map.of("report_upload_version",1,"report_recovery_version",1,
                "narrative_llm_budget",6,"ledger_digest",snapshot,"audit_digest",snapshot))));
        db.update("insert into research_agent_task values (?,'SYNTHESIS',?)",task,context);
        for (String migration : List.of("V185__research_synthesis_upload_parts.sql","V187__research_synthesis_generation.sql",
                "V188__research_report_feedback.sql","V189__research_runtime_metrics.sql","V190__research_requirement_revision_lineage.sql","V191__research_synthesis_call_permit.sql"))
            for (String sql : Files.readString(Path.of(System.getenv("NOTEWEAVE_REPLAY_ROOT"),"backend/src/main/resources/db/migration",migration)).split(";")) if (!sql.isBlank()) db.execute(sql);
        int[] active = {1};
        var tasks = new ResearchAgentTaskService(db,mapper,null,null,null) {
            @Override public ClaimedTask heartbeat(LeaseCommand command) {
                if (command.leaseEpoch() != active[0] || command.fencingToken() != active[0] * 10L) throw new BusinessException("STALE_LEASE","旧租约");
                return new ClaimedTask(command.taskId(),command.leaseEpoch(),command.fencingToken(),java.time.Instant.now(),"[]","{}",context,snapshot);
            }
        };
        var recovery = new ResearchSynthesisRecoveryService(db,mapper,tasks);
        var uploads = new ResearchSynthesisUploadService(db,mapper,tasks);
        byte[] body = mapper.writeValueAsBytes(Map.of("result_schema_version","research-synthesis-candidate.v2","role","SYNTHESIS","status","CANDIDATE",
                "ledger_digest",snapshot,"audit_digest",snapshot,"markdown","x".repeat(150000)));
        String digest = ResearchSynthesisUploadService.digest(body);
        int count = (body.length + 65535) / 65536;
        var lease1 = new ResearchSynthesisRecoveryService.LeaseRequest("offline-worker",1,10,snapshot);
        recovery.start(task,new ResearchSynthesisRecoveryService.GenerationRequest(lease1,digest,count,body.length,2));
        uploads.upload(task,part(body,task,snapshot,1,0));
        active[0] = 2;
        var lease2 = new ResearchSynthesisRecoveryService.LeaseRequest("offline-worker",2,20,snapshot);
        var pending = recovery.recover(task,lease2);
        require(Boolean.FALSE.equals(pending.get("ready")) && Integer.valueOf(2).equals(pending.get("recorded_llm_calls")),"未完成报告恢复状态错误");
        var started = recovery.start(task,new ResearchSynthesisRecoveryService.GenerationRequest(lease2,digest,count,body.length,1));
        require(List.of(0).equals(started.get("present_parts")),"相同摘要的旧片段未被复用");
        for (int i = 1; i < count; i++) uploads.upload(task,part(body,task,snapshot,2,i));
        boolean stale = false;
        try { uploads.upload(task,part(body,task,snapshot,1,1)); } catch (BusinessException error) { stale = true; }
        require(stale,"旧租约仍能写入");
        active[0] = 3;
        var lease3 = new ResearchSynthesisRecoveryService.LeaseRequest("offline-worker",3,30,snapshot);
        var recovered = recovery.recover(task,lease3);
        require(Boolean.TRUE.equals(recovered.get("ready")) && Integer.valueOf(3).equals(recovered.get("recorded_llm_calls")),"恢复重复计算了生成消耗");
        var taskRow = new ResearchAgentCompletionCommitter.TaskRow(task,"run","CLAIMED","SYNTHESIS","row","branch",1,1,"[]","{}",context,
                "research-agent-task-snapshot.v3",snapshot,"offline-worker",3,30L,"logical",null,1,1,true);
        recovery.requireRegistered(taskRow,Map.of(),3);
        var reference = (Map<String,Object>)recovered.get("reference");
        require("x".repeat(150000).equals(uploads.resolve(taskRow,reference).get("markdown")),"恢复后的正文发生变化");
        boolean undercharged = false;
        try { recovery.requireRegistered(taskRow,Map.of(),1); } catch (BusinessException error) { undercharged = true; }
        require(undercharged,"恢复消耗未结算仍通过");
        String meterTask = "00000000-0000-0000-0000-000000000006";
        String meterContext = mapper.writeValueAsString(Map.of("query_policy",Map.of("synthesis_input",Map.of("report_upload_version",1,"report_recovery_version",1,
                "report_call_meter_version",1,"narrative_llm_budget",2,"ledger_digest",snapshot,"audit_digest",snapshot))));
        db.update("insert into research_agent_task values (?,'SYNTHESIS',?)",meterTask,meterContext);
        active[0] = 1;
        var firstPermit = recovery.permitCall(meterTask,lease1,0,"research.synthesis");
        require(Integer.valueOf(1).equals(firstPermit.get("recorded_llm_calls")),"生成前调用未计入预算");
        require(Boolean.TRUE.equals(recovery.permitCall(meterTask,lease1,0,"research.synthesis").get("idempotent_replay")),"授权重试重复结算");
        boolean sameLeaseReplay = false;
        try { recovery.recover(meterTask,lease1); } catch (BusinessException error) { sameLeaseReplay = true; }
        require(sameLeaseReplay,"中断的同租约调用可以重复消费");
        active[0] = 2;
        require(Integer.valueOf(1).equals(recovery.recover(meterTask,lease2).get("recorded_llm_calls")),"尚未登记报告的中断消耗丢失");
        recovery.permitCall(meterTask,lease2,0,"research.synthesis");
        boolean exhausted = false;
        try { recovery.permitCall(meterTask,lease2,1,"research.synthesis"); } catch (BusinessException error) { exhausted = true; }
        require(exhausted,"重领后重新获得已消耗预算");
        recovery.start(meterTask,new ResearchSynthesisRecoveryService.GenerationRequest(lease2,digest,count,body.length,1));
        for (int i = 0; i < count; i++) uploads.upload(meterTask,part(body,meterTask,snapshot,2,i));
        active[0] = 3;
        require(Integer.valueOf(2).equals(recovery.recover(meterTask,lease3).get("recorded_llm_calls")),"恢复完整报告时授权消耗重复计算");
        lifecycleBudget(db,mapper,meterTask);
        scopeChecks(mapper);
        planningCompleteness(mapper);
        var wire = new ResearchAgentCompletionEnvelopeParser().parse(Files.readAllBytes(Path.of(System.getenv("NOTEWEAVE_REPLAY_OUT"),"completion-v5.json")));
        new ResearchAgentCompletionCanonicalizer().validateAndVerify(wire);
        require(mapper.valueToTree(wire.extractionDiagnostics()).path("runtime_metrics").path("input_tokens").isNull(),"未知 token 被改成了零或丢失");
        System.out.println("{\"completion_runtime_v5\":\"PASSED\",\"provider_calls\":0}");
    }
    private static void lifecycleBudget(JdbcTemplate db,ObjectMapper mapper,String task) throws Exception {
        db.execute("alter table research_run add status varchar(32)");
        db.execute("alter table research_run add agent_feature_flags_json varchar(2000)");
        db.execute("alter table research_agent_task add research_run_id varchar(36)");
        db.execute("alter table research_agent_task add status varchar(32)");
        db.execute("alter table research_agent_completion add research_agent_task_id varchar(36)");
        db.execute("create table research_budget_reservation(id varchar(36) primary key,research_run_id varchar(36),research_agent_task_id varchar(36),idempotency_key varchar(100),reserved_json varchar(2000),consumed_json varchar(2000),released_json varchar(2000),state varchar(32),agent_completion_id varchar(36),settled_at timestamp,updated_at timestamp)");
        db.update("insert into research_run values ('run','RUNNING',?)","{\"requirement_coverage_v1\":true,\"requirement_state_version\":5}");
        db.update("update research_agent_task set research_run_id = 'run',status = 'FAILED' where id = ?",task);
        db.update("insert into research_budget_reservation(id,research_run_id,research_agent_task_id,idempotency_key,reserved_json,consumed_json,released_json,state) values ('reservation','run',?,'reserve','{\"llm_calls\":2}','{\"llm_calls\":0}','{\"llm_calls\":0}','RESERVED')",task);
        var released = new ResearchBudgetAndCheckpointService(db,mapper).releaseForLifecycle("run",task,"reservation");
        require(Long.valueOf(2).equals(released.consumed().get("llm_calls")) && Long.valueOf(0).equals(released.released().get("llm_calls")),"失败任务退回了已经授权的模型预算");
    }
    private static void planningCompleteness(ObjectMapper mapper) {
        var clauses = ResearchPlanningCompleteness.clauses("比较 Atlas 和 Boreal，限定版本");
        Map<String,Object> frozen = Map.of("requested_clauses",clauses);
        Map<String,Object> incomplete = Map.of("schema_version","research-planning-completeness.v1","complete",false,"model_calls",1,
                "clauses",List.of(Map.of("clause_id","clause:0","status","MAPPED","requirement_indexes",List.of(0)),
                        Map.of("clause_id","clause:1","status","MISSING","requirement_indexes",List.of())));
        Map<String,Object> result = new java.util.LinkedHashMap<>();
        result.put("plan",Map.of("user_requirements",List.of(Map.of("text","比较"))));
        result.put("planning_completeness",incomplete);
        ResearchPlanningCompleteness.validate(mapper,frozen,result,1);
        var tampered = new java.util.LinkedHashMap<String,Object>(incomplete); tampered.put("complete",true);
        result.put("planning_completeness",tampered);
        boolean rejected = false;
        try { ResearchPlanningCompleteness.validate(mapper,frozen,result,1); } catch (BusinessException error) { rejected = true; }
        require(rejected,"规划遗漏分句仍被标为完整");
    }
    private static ResearchSynthesisUploadService.PartRequest part(byte[] body,String task,String snapshot,int lease,int index) {
        byte[] bytes = java.util.Arrays.copyOfRange(body,index*65536,Math.min(body.length,(index+1)*65536));
        return new ResearchSynthesisUploadService.PartRequest("offline-worker",lease,lease*10L,snapshot,ResearchSynthesisUploadService.digest(body),index,
                (body.length+65535)/65536,body.length,ResearchSynthesisUploadService.digest(bytes),Base64.getEncoder().encodeToString(bytes));
    }
    private static void scopeChecks(ObjectMapper mapper) {
        var envelope = mock(ResearchAgentCompletionEnvelope.class);
        var cluster = mapper.valueToTree(Map.of("cell_key","row:scope","cluster_key","cluster:scope","evidence_keys",List.of("evidence:a")));
        var scope = mapper.valueToTree(Map.of("temporal_mode","CURRENT","as_of_date","2026-10-20"));
        var source = new ResearchAgentCompletionCommitter.TrustedEvidenceSource("source","Atlas","https://atlas.example/docs","provider","external_url",
                "EXTERNAL_ARCHIVED","snapshot","external","atlas.example","sha256:"+"a".repeat(64),"Updated 2026-10-19. Atlas supports indexes.");
        Map<String,Object> check = new java.util.LinkedHashMap<>(Map.of("cell_key","row:scope","cluster_key","cluster:scope","product","MATCH","branch","APPLICABLE",
                "time","APPLICABLE","date","2026-10-19","date_kind","UPDATED","date_quote","Updated 2026-10-19."));
        when(envelope.budgetUsage()).thenReturn(Map.of("llm_calls",1L));
        for (String scenario : List.of("MATCH","UNKNOWN","MISMATCH","FUTURE","FABRICATED")) {
            check.put("product",SetProduct(scenario)); check.put("date",scenario.equals("FUTURE") ? "2026-10-21" : "2026-10-19");
            check.put("date_quote",scenario.equals("FABRICATED") ? "Fabricated 2026-10-19." : "Updated 2026-10-19.");
            when(envelope.extractionDiagnostics()).thenReturn(Map.of("source_scope_checks",Map.of("schema_version","research-source-scope-checks.v1","model_calls",1,"checks",List.of(check))));
            List<String> reasons = new ArrayList<>();
            ResearchSourceScopeChecks.check(envelope,cluster,scope,Map.of("evidence:a",source),reasons);
            require(scenario.equals("MATCH") == reasons.isEmpty(),"来源语义范围未按预期限制："+scenario);
        }
    }
    private static String SetProduct(String scenario) { return List.of("UNKNOWN","MISMATCH").contains(scenario) ? scenario : "MATCH"; }
    private static void require(boolean okay,String message) { if (!okay) throw new IllegalStateException(message); }
}

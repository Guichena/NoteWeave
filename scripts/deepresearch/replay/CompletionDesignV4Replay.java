package com.noteweave.research;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.noteweave.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
public class CompletionDesignV4Replay {
  public static void main(String[] args) throws Exception {
    var mapper=new ObjectMapper();
    var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:designv4;MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
    var service=new ResearchReportCoverageService(db,mapper);
    ObjectNode zero=null;
    for(String name:List.of("report-coverage-zero-v4.json","report-coverage-repair-v4.json")) {
      ObjectNode fixture=(ObjectNode)mapper.readTree(Files.readString(Path.of(System.getenv("NOTEWEAVE_REPLAY_OUT") + "/"+name)));
      var input=mapper.convertValue(fixture.path("input"),Map.class);
      Map<String,Object> result=Map.of("narrative",mapper.convertValue(fixture.path("narrative"),Map.class));
      int calls=fixture.path("input").path("narrative_llm_budget").asInt();
      new ResearchStatementSupportValidation().validate(mapper,input,result,calls);
      service.validate(input,result,calls);
      List<Map<String,Object>> cells=mapper.convertValue(fixture.path("input").path("cells"),List.class);
      var claims=cells.stream().map(cell -> Map.<String,Object>of("cell_key",cell.get("cell_key"),"claim_text",cell.get("candidate_value"),"evidence_keys",cell.get("evidence_keys"),"guarded",false)).toList();
      new ResearchAgentSynthesisValidationService().validate(Set.of("row:features"),cells,claims,fixture.path("markdown").asText(),mapper.convertValue(fixture.path("narrative"),Map.class),fixture.path("input").path("question").asText(),false,true);
      ObjectNode changed=fixture.deepCopy();
      ((ArrayNode)changed.path("narrative").path("report_coverage").path("items")).remove(0);
      try { service.validate(input,Map.of("narrative",mapper.convertValue(changed.path("narrative"),Map.class)),calls); throw new IllegalStateException("遗漏报告需求被接受"); }
      catch(BusinessException expected) { }
      if(name.contains("zero")) zero=fixture;
    }
    var selection=mapper.readTree(Files.readString(Path.of(System.getenv("NOTEWEAVE_REPLAY_OUT") + "/fact-selection-v4.json")));
    ResearchDeferredMaterialService.validate(selection);
    db.execute("create table research_run(id varchar(36),agent_feature_flags_json json)");
    db.update("insert into research_run values ('new',?)","{\"requirement_coverage_v1\":true,\"requirement_state_version\":4}");
    db.update("insert into research_run values ('old',?)","{\"requirement_coverage_v1\":true,\"requirement_state_version\":3}");
    db.execute("create table research_agent_role_result(id varchar(36),payload_json json)");
    db.execute("create table research_agent_synthesis_candidate(id varchar(36),research_run_id varchar(36),role_result_id varchar(36),status varchar(32),created_at timestamp)");
    db.update("insert into research_agent_role_result values ('result',?)",mapper.writeValueAsString(Map.of("narrative",zero.path("narrative"))));
    db.update("insert into research_agent_synthesis_candidate values ('candidate','new','result','VALIDATED',current_timestamp)");
    var decision=new ResearchAgentRunCompletionGate.CompletionDecision(ResearchAgentRunCompletionGate.CompletionTerminalState.COMPLETED_VERIFIED,
      List.of(new ResearchAgentRunCompletionGate.PromotableClaim("row:features","features","verified",List.of("evidence:a"))),List.of(),List.of(),List.of());
    var partial=service.apply("new",decision,true);
    if(((Number)service.publicView("new").get("answered_count")).longValue()!=0) throw new IllegalStateException("报告展示计数不可信");
    if(partial.terminalState()!=ResearchAgentRunCompletionGate.CompletionTerminalState.COMPLETED_WITH_LIMITATIONS || partial.promotableClaims().size()!=1) throw new IllegalStateException("报告缺项未降低完成状态或删除了事实");
    if(service.apply("old",decision,true).terminalState()!=decision.terminalState()) throw new IllegalStateException("旧 Run 被切换到新报告口径");
    db.execute("create table research_agent_task(id varchar(36),research_run_id varchar(36),plan_revision int,entity_set_version int,wave_no int,target_cells_json longtext)");
    db.execute("create table research_agent_execution(id varchar(36),research_agent_task_id varchar(36),extraction_diagnostics_json json)");
    db.update("insert into research_agent_task values ('t','new',0,0,1,'[\"row:features\"]')");
    db.update("insert into research_agent_execution values ('e','t',?)",mapper.writeValueAsString(Map.of("fact_selection",selection)));
    var deferred=new ResearchDeferredMaterialService(db,mapper).load("new","row:features",0,0);
    if(deferred.size()!=1 || !new ResearchDeferredMaterialService(db,mapper).load("new","other:features",0,0).isEmpty()) throw new IllegalStateException("延后材料未按对象范围读取");
    var requirement=zero.path("input").path("requirement_coverage").path("requirements").get(0);
    var contract=mapper.valueToTree(Map.of("requirements",List.of(requirement),"missing_requirement_ids",List.of(requirement.path("requirement_id").asText()),"facts",List.of(),"deferred_materials",deferred));
    if(!ResearchRequirementActionPlanner.plan(contract,"row:features",false,true,0).get("action").equals("READ_TARGETED")) throw new IllegalStateException("没有优先处理延后材料");
    if(!ResearchRequirementActionPlanner.plan(contract,"row:features",false,true,2).get("action").equals("CORROBORATE")) throw new IllegalStateException("无增益时仍重复阅读");
    if(!ResearchRequirementActionPlanner.plan(contract,"row:features",true,true,0).get("action").equals("RESOLVE_CONFLICT")) throw new IllegalStateException("冲突动作优先级错误");
    db.execute("create table research_cell(research_run_id varchar(36),cell_key varchar(160),plan_revision int,entity_set_version int)");
    db.execute("create table research_requirement(research_run_id varchar(36),cell_key varchar(160),plan_revision int,entity_set_version int,completion_mode varchar(32),minimum_count int)");
    db.update("insert into research_cell values ('new','row:features',0,0)");
    db.update("insert into research_requirement values ('new','row:features',0,0,'DISTINCT_FACTS',20)");
    if(ResearchAgentGapProjectionService.repairLimit(db,"new")!=4 || ResearchAgentGapProjectionService.repairLimit(db,"old")!=2) throw new IllegalStateException("数量需求与旧 Run 补查上限不一致");
    System.out.println("{\"report_mapping_and_citations_validated\":true,\"missing_requirement_rejected\":true,\"incomplete_report_downgraded\":true,\"qualified_facts_preserved\":true,\"old_run_unchanged\":true,\"deferred_material_scoped\":true,\"typed_actions_selected\":true}");
  }
}

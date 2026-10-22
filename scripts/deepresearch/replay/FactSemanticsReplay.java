package com.noteweave.research;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
public class FactSemanticsReplay {
  public static void exercise(JdbcTemplate db, ObjectMapper mapper) throws Exception {
    var semantics = new ResearchFactSemanticCoverage(db);
    List<String> ids = java.util.stream.IntStream.range(0, 12).mapToObj(i -> "fact:" + i).toList();
    Map<String,String> known = new LinkedHashMap<>();
    int batches=0, checks=0;
    while (!semantics.allChecked(ids, known)) {
      var pending = semantics.pendingPairs(ids, known, 20);
      if (pending.size() > 20 || pending.isEmpty()) throw new IllegalStateException("审计批次不符合预算");
      for (var pair : pending) known.put(ResearchFactSemanticCoverage.pair(pair.get("left_cluster_key"),pair.get("right_cluster_key")),"NOT_EQUIVALENT");
      batches++; checks+=pending.size();
    }
    if (checks!=66 || batches!=4 || semantics.certifiedCount(ids,known)!=12) throw new IllegalStateException("分批审计漏掉事实对");
    var coverage = new ResearchRequirementCoverageService(db,mapper);
    String b = db.queryForObject("select fact_id from research_requirement_fact where claim_text='Atlas updates indexes during writes.'",String.class);
    String q = ResearchRequirementCoverageService.key("fact:","quorum-replay");
    String duplicate = ResearchRequirementCoverageService.key("fact:","duplicate-replay");
    RequirementCoverageReplay.source(db,"dupe","Atlas maintains indexes as writes occur.");
    var task = RequirementCoverageReplay.task("task-dupe",RequirementCoverageReplay.policy(coverage));
    var fact = new ResearchRequirementCoverageService.Fact(duplicate,RequirementCoverageReplay.CELL,"Atlas maintains indexes as writes occur.",List.of("evidence:dupe"),"cluster:dupe",List.of(),List.of(),List.of());
    coverage.persist(task,"completion-dupe",new ResearchRequirementCoverageService.Assessment(List.of(fact),List.of(),Set.of(),1,0,true));
    String requirement=ResearchRequirementCoverageService.key("req:","count-replay");
    db.update("insert into research_requirement(requirement_id,research_run_id,cell_key,plan_revision,entity_set_version,requirement_text,entity_label,origin,mandatory,as_of_date,completion_mode,minimum_count,scope_type,scope_json) values (?,'run-1',?,0,0,'Two capabilities','Atlas','EXPLICIT',true,'2026-10-20','DISTINCT_FACTS',2,'CELL','{}')",requirement,RequirementCoverageReplay.CELL);
    db.update("insert into research_requirement_observation(observation_id,requirement_id,completion_id,fact_ids_json,coverage_status) values ('count-observation',?,'completion-dupe',?,'SATISFIED')",requirement,mapper.writeValueAsString(List.of(b,duplicate,q)));
    if (status(coverage,requirement).equals("SATISFIED")) throw new IllegalStateException("未审计的不同事实被直接计数");
    String[][] pairs={{b,duplicate,"EQUIVALENT"},{b,q,"NOT_EQUIVALENT"},{duplicate,q,"NOT_EQUIVALENT"}};
    for(int i=0;i<pairs.length;i++) db.update("insert into research_semantic_check values (?,'run-1',?,?,?,'NOT_CONTRADICTORY',?,1,current_timestamp)","relation"+i,RequirementCoverageReplay.CELL,pairs[i][0],pairs[i][1],pairs[i][2]);
    if (!status(coverage,requirement).equals("SATISFIED")) throw new IllegalStateException("审计完成后数量未恢复");
    if (coverage.facts("run-1",RequirementCoverageReplay.CELL,0,0).size()!=3 || coverage.synthesisFacts("run-1",RequirementCoverageReplay.CELL,0,0).size()!=2)
      throw new IllegalStateException("成文没有合并跨轮等价事实");
    System.out.println("{\"semantic_pairs\":66,\"bounded_audit_batches\":4,\"unknown_distinctness_blocked\":true,\"raw_facts\":3,\"synthesis_fact_groups\":2}");
    db.execute("create table research_agent_candidate(research_run_id varchar(36),cell_key varchar(160),quorum_group_key varchar(80),candidate_slot int,candidate_value longtext)");
    for (int scenario=0;scenario<4;scenario++) {
      String leftClaim="Atlas scenario "+scenario+" indexes vector representations.";
      String rightClaim="Atlas scenario "+scenario+" creates an index over vector data.";
      String left=ResearchRequirementCoverageService.key("fact:","run-1",RequirementCoverageReplay.CELL,0,0,leftClaim);
      String right=ResearchRequirementCoverageService.key("fact:","run-1",RequirementCoverageReplay.CELL,0,0,rightClaim);
      for (int slot=1;slot<=2;slot++) {
        String suffix="alias-"+scenario+"-"+slot;
        String claim=slot==1?leftClaim:rightClaim;
        RequirementCoverageReplay.source(db,suffix,claim);
        String group=scenario==2&&slot==2?"other-group":"alias-group-"+scenario;
        db.update("insert into research_agent_candidate values ('run-1',?,?,?,?)",RequirementCoverageReplay.CELL,group,slot,claim);
        var aliasTask=new ResearchAgentCompletionCommitter.TaskRow("task-"+suffix,"run-1","CLAIMED","DEEP_CELL","row","branch",0,0,
          "[]","{}","{}","research-agent-task-snapshot.v3","digest","worker",1,1L,"logical-"+suffix,group,2,slot,true);
        var alias=new ResearchRequirementCoverageService.Fact(slot==1?left:right,RequirementCoverageReplay.CELL,claim,List.of("evidence:"+suffix),"cluster:"+suffix,
          List.of(),List.of("domain:"+(scenario==1?"same":suffix)+".example","lineage:"+suffix),List.of("exact:"+(scenario==3?"same":suffix)));
        coverage.persist(aliasTask,"completion-"+suffix,new ResearchRequirementCoverageService.Assessment(List.of(alias),List.of(),Set.of(),0,0,true));
      }
      if (coverage.auditFacts("run-1",RequirementCoverageReplay.CELL,0,0).stream().noneMatch(f -> left.equals(f.get("fact_id")))) throw new IllegalStateException("审计看不到双路候选");
      if (coverage.facts("run-1",RequirementCoverageReplay.CELL,0,0).stream().anyMatch(f -> left.equals(f.get("fact_id"))||right.equals(f.get("fact_id")))) throw new IllegalStateException("等价审计前提前确认");
      db.update("insert into research_semantic_check values (?,'run-1',?,?,?,'NOT_CONTRADICTORY','EQUIVALENT',2,current_timestamp)","alias-relation-"+scenario,RequirementCoverageReplay.CELL,left,right);
      long count=coverage.facts("run-1",RequirementCoverageReplay.CELL,0,0).stream().filter(f -> left.equals(f.get("fact_id"))||right.equals(f.get("fact_id"))).count();
      if(count!=(scenario==0?2:0)) throw new IllegalStateException("同义双路资格或独立性检查错误："+scenario);
      if(coverage.equivalentQuorum("run-1",RequirementCoverageReplay.CELL,0,0,"alias-group-"+scenario)!=(scenario==0)) throw new IllegalStateException("双路代表结论的修复复用判断错误");
    }
    System.out.println("{\"provisional_audit_visible\":true,\"synonymous_quorum_confirmed\":true,\"same_source_rejected\":true,\"cross_group_rejected\":true,\"copied_quote_rejected\":true}");
  }
  static String status(ResearchRequirementCoverageService coverage,String id) {
    return ((List<?>)coverage.readModel("run-1").get("requirements")).stream().map(value -> (Map<?,?>)value)
      .filter(value -> id.equals(value.get("requirement_id"))).findFirst().orElseThrow().get("status").toString();
  }
}

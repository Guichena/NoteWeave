package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.noteweave.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchCompletionGapPolicyTest {
    @Test
    void identifierUsesExplicitScopeAndCitationRemainsAConstraint() {
        var fields=ResearchCompletionGapPolicy.fields(definition(),"目前支持状态，PEP 编号和标题，引用官方文档");
        assertThat(fields).containsEntry("answer_kind","IDENTIFIER").containsEntry("temporal_mode","TIME_INVARIANT");
        assertThat((List<?>) fields.get("conditions")).hasSize(2);
        assertThat((List<?>) fields.get("source_constraints")).hasSize(1);
    }

    @Test
    void currentIdentityAndUnrequestedSourceConditionsAreRejected() {
        var fields=definition(); fields.put("temporal_mode","CURRENT");
        assertThatThrownBy(() -> ResearchCompletionGapPolicy.fields(fields,"PEP 编号和标题，引用官方文档")).isInstanceOf(BusinessException.class);
        fields.put("temporal_mode","TIME_INVARIANT");
        assertThatThrownBy(() -> ResearchCompletionGapPolicy.fields(fields,"PEP 编号和标题")).isInstanceOf(BusinessException.class);
        fields.put("answer_kind","PROVENANCE");
        assertThatThrownBy(() -> ResearchCompletionGapPolicy.fields(fields,"引用官方文档")).isInstanceOf(BusinessException.class);
    }

    @Test
    void targetedRepairProtectsMandatoryConditionsAndKeepsSiblingSourcesAsReadTargets() {
        var requirement=Map.of("requirement_id","required","cell_key","entity:status","text","Current supported status","mandatory",true,
                "scope",Map.of("temporal_mode","CURRENT","answer_contract",Map.of("answer_kind","FACT","conditions",List.of("status","effective release"))));
        var optional=Map.of("requirement_id","optional","cell_key","entity:status","text","Installation","mandatory",false);
        var contract=new ObjectMapper().valueToTree(Map.of(ResearchCompletionGapPolicy.FLAG,1,
                "requirements",List.of(requirement,optional),"missing_requirement_ids",List.of("required","optional"),"facts",List.of(),
                "deferred_materials",List.of(Map.of("cell_key","entity:status","requirement_ids",List.of("optional"),"source_url","https://optional.example/install")),
                "related_materials",List.of(Map.of("cell_key","entity:status","source_cell_key","entity:history","source_url","https://official.example/release"))));
        var action=ResearchRequirementActionPlanner.plan(contract,"entity:status",false,false,0);
        assertThat(action).containsEntry("action","READ_TARGETED").containsEntry("missing_requirement_ids",List.of("required"));
        assertThat(action.get("read_urls")).isEqualTo(List.of("https://official.example/release"));
        assertThat((List<?>) action.get("condition_targets")).hasSize(1);
    }

    private Map<String,Object> definition() {
        return new LinkedHashMap<>(Map.of("answer_kind","IDENTIFIER","temporal_mode","TIME_INVARIANT",
                "conditions",List.of("PEP 编号","PEP 标题"),"source_constraints",List.of(Map.of("policy","PRIMARY_SOURCE","question_quote","引用官方文档"))));
    }

    @Test
    void clauseMappingCannotOverrideRejectedAtomicConditions() {
        var mapper=new ObjectMapper();
        var input=Map.<String,Object>of(ResearchCompletionGapPolicy.FLAG,1,"requested_clauses",List.of(Map.of("clause_id","clause:0","text","引用官方文档")));
        var report=new LinkedHashMap<String,Object>(Map.of("schema_version","research-planning-completeness.v2","model_calls",1,"complete",false,
                "clauses",List.of(Map.of("clause_id","clause:0","status","MAPPED","requirement_indexes",List.of(0))),
                "requirement_checks",List.of(Map.of("requirement_index",0,"status","EXPANDED","invalid_condition_indexes",List.of(0)))));
        Map<String,Object> result=Map.of("plan",Map.of("user_requirements",List.of(Map.of("conditions",List.of("安装方式")))),"planning_completeness",report);
        assertThatThrownBy(() -> ResearchPlanningCompleteness.validate(mapper,input,result,1)).isInstanceOf(BusinessException.class);
        report.put("complete",true);
        report.put("requirement_checks",List.of(Map.of("requirement_index",0,"status","VALID","invalid_condition_indexes",List.of())));
        ResearchPlanningCompleteness.validate(mapper,input,result,1);
    }
}

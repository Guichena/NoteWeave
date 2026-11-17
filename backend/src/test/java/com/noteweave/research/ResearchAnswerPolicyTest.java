package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchAnswerPolicyTest {
    @Test
    void identifierRequirementDoesNotInheritCurrentStatusFromTheWholeQuestion() {
        var policy = ResearchAnswerPolicy.freeze("Identify governing PEP number and title", "引用官方文档", "EXPLICIT", "SINGLE", 1,
                Map.of("temporal_mode","CURRENT"),true);
        assertThat(policy).containsEntry("temporal_mode","TIME_INVARIANT").containsEntry("mandatory",true);
        assertThat(policy.get("criterion")).isEqualTo("Identify governing PEP number and title");
    }

    @Test
    void historicalAndCurrentRequirementsRemainSeparate() {
        assertThat(ResearchAnswerPolicy.freeze("Identify the first version", "first version and current status", "EXPLICIT", "SINGLE",1,
                Map.of("temporal_mode","CURRENT"),true)).containsEntry("temporal_mode","HISTORICAL");
        assertThat(ResearchAnswerPolicy.freeze("Determine current support status and effective version", "当前状态", "EXPLICIT", "SINGLE",1,
                Map.of("temporal_mode","HISTORICAL"),true)).containsEntry("temporal_mode","CURRENT");
    }

    @Test
    void derivedRequirementsAreOptionalOnlyWhenExplicitDefinitionsExist() {
        assertThat(ResearchAnswerPolicy.freeze("Installation details", "", "DERIVED", "SINGLE",1,Map.of(),true)).containsEntry("mandatory",false);
        assertThat(ResearchAnswerPolicy.freeze("Installation details", "", "DERIVED", "SINGLE",1,Map.of(),false)).containsEntry("mandatory",true);
    }

    @Test
    void sharedQuoteKeepsBothQualifiedFactsIndependentOfTheirOrder() {
        var first = new ResearchRequirementCoverageService.FactReference("first","cell",true);
        var second = new ResearchRequirementCoverageService.FactReference("second","cell",true);
        assertThat(ResearchRequirementCoverageService.selectReferences(List.of(first,second),"cell").ids()).containsExactly("first","second");
        assertThat(ResearchRequirementCoverageService.selectReferences(List.of(second,first),"cell").ids()).containsExactly("first","second");
    }

    @Test
    void mixedQualifiedAndRejectedSupportIsRetainedWithoutGrantingCompletion() {
        var selected = ResearchRequirementCoverageService.selectReferences(List.of(
                new ResearchRequirementCoverageService.FactReference("valid","cell",true),
                new ResearchRequirementCoverageService.FactReference("invalid","cell",false)),"cell");
        assertThat(selected.ids()).containsExactly("valid"); assertThat(selected.partial()).isTrue();
        assertThatThrownBy(() -> ResearchRequirementCoverageService.selectReferences(List.of(
                new ResearchRequirementCoverageService.FactReference("foreign","other-cell",true)),"cell")).isInstanceOf(BusinessException.class);
    }

    @Test
    void reportAnswerMustMatchFinalVerifiedStatements() {
        var input = input(); var result = result();
        var service = new ResearchReportCoverageService(null,new ObjectMapper());
        service.validate(input,result,1);
        var narrative = cast(result.get("narrative"));
        var forged = new LinkedHashMap<>(cast(((List<?>)narrative.get("answers")).get(0)));
        forged.put("text","Unsupported extra conclusion"); narrative.put("answers",List.of(forged));
        assertThatThrownBy(() -> service.validate(input,result,1)).isInstanceOf(BusinessException.class);
    }

    private Map<String,Object> input() {
        return Map.of(ResearchAnswerPolicy.FLAG,1,"report_feedback_version",1,
                "requirement_coverage",Map.of("requirements",List.of(Map.of("requirement_id","req","cell_key","row:column",
                        "scope_type","CELL","status","SATISFIED","mandatory",true,"minimum_count",1))),
                "cells",List.of(Map.of("cell_key","row:column","facts",List.of(Map.of("fact_id","fact")))));
    }
    private Map<String,Object> result() {
        var item = Map.of("requirement_id","req","status","ANSWERED","statement_ids",List.of("statement"),
                "gap_type","NONE","missing_detail","","reason_code","ANSWER_COMPLETE");
        Map<String,Object> narrative = new LinkedHashMap<>();
        narrative.put("report_coverage",Map.of("schema_version","research-report-coverage.v1","items",List.of(item),"model_calls",1,"complete",true));
        narrative.put("statement_support",Map.of("model_calls",0,"checks",List.of(Map.of("statement_id","statement","text","Verified answer",
                "verdict","SUPPORTED","basis","FROZEN_FACT","fact_ids",List.of("fact")))));
        narrative.put("answers",List.of(Map.of("requirement_id","req","text","Verified answer","fact_ids",List.of("fact"),
                "statement_ids",List.of("statement"),"status","ANSWERED","missing_conditions",List.of(),"reason_code","ANSWER_COMPLETE")));
        return Map.of("narrative",narrative);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> cast(Object value) { return (Map<String,Object>)value; }
}

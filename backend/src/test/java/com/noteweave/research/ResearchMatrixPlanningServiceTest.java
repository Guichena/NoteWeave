package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ResearchMatrixPlanningServiceTest {

    private final ResearchMatrixPlanningService planner = new ResearchMatrixPlanningService(
            mock(JdbcTemplate.class), new ObjectMapper(), new ResearchAgentCompletionCanonicalizer(),
            mock(ResearchAgentFeatureFlagService.class), true);

    @Test
    void multiPartQuestionUsesItsSubQuestionsAsColumnsAndDropsCitationInstructions() {
        ResearchIntentResponse intent = new ResearchIntentResponse(
                "回答问题", "report", List.of(), "", "STANDARD", "AUTO");

        ResearchMatrixPlanningService.MatrixPlan plan = planner.plan(
                "Java 虚拟线程在哪个 JDK 版本正式发布，synchronized 导致的线程固定问题在哪个版本改善？引用 JEP。", intent);

        assertThat(plan.columns()).extracting(ResearchMatrixPlanningService.ColumnPlan::label).containsExactly(
                "Java 虚拟线程在哪个 JDK 版本正式发布", "synchronized 导致的线程固定问题在哪个版本改善");
        assertThat(plan.columns()).allMatch(column -> column.key().startsWith("q-"));
    }

    @Test
    void singleQuestionKeepsTheGeneralColumns() {
        ResearchIntentResponse intent = new ResearchIntentResponse(
                "回答问题", "report", List.of(), "", "STANDARD", "AUTO");

        ResearchMatrixPlanningService.MatrixPlan plan = planner.plan("PostgreSQL 17 的增量备份怎么使用？", intent);

        assertThat(plan.columns()).extracting(ResearchMatrixPlanningService.ColumnPlan::key)
                .containsExactly("answer", "key_evidence", "limitations", "implications");
    }

    @Test
    void conflictQuestionUsesSupportingOpposingAndConditionColumns() {
        ResearchIntentResponse intent = new ResearchIntentResponse(
                "回答问题", "report", List.of(), "", "STANDARD", "AUTO");

        for (String question : List.of("提高测试覆盖率门槛是否降低了缺陷逃逸率？比较不同组织的实证结果。",
                "微服务架构是否提高了中型团队的长期交付速度？请比较支持和反对的证据并说明证据来源。")) {
            assertThat(planner.plan(question, intent).columns()).extracting(ResearchMatrixPlanningService.ColumnPlan::key)
                    .containsExactly("conflict-supporting", "conflict-opposing", "conflict-conditions");
        }
    }

    @Test
    void identicalIntentProducesAnIdenticalBoundedPlanDigest() {
        ResearchIntentResponse intent = new ResearchIntentResponse(
                "Compare two systems", "table", List.of("licensing", "security"),
                "2024-2026", "DEEP", "PRODUCT_COMPARISON");

        ResearchMatrixPlanningService.MatrixPlan first = planner.plan("Alpha vs Beta", intent);
        ResearchMatrixPlanningService.MatrixPlan second = planner.plan("Alpha vs Beta", intent);

        assertThat(first).isEqualTo(second);
        assertThat(first.rows()).extracting(ResearchMatrixPlanningService.RowPlan::label)
                .containsExactly("Alpha", "Beta");
        assertThat(first.cellCount()).isEqualTo(12);
        assertThat(first.planDigest()).startsWith("sha256:");
    }

    @Test
    void chineseProductComparisonCreatesOneEntityRowPerProduct() {
        ResearchIntentResponse intent = new ResearchIntentResponse(
                "比较两个数据库", "table", List.of(), "", "DEEP", "PRODUCT_COMPARISON");

        ResearchMatrixPlanningService.MatrixPlan plan = planner.plan(
                "比较 PostgreSQL 17 与 MySQL 8.4 的 JSON 索引与查询能力", intent);

        assertThat(plan.rows()).extracting(ResearchMatrixPlanningService.RowPlan::label)
                .containsExactly("PostgreSQL 17", "MySQL 8.4");
        assertThat(plan.cellCount()).isEqualTo(8);
    }

    @Test
    void scenarioQualifierBeforePossessiveShouldNotStickToTheLastEntity() {
        ResearchIntentResponse intent = new ResearchIntentResponse(
                "给出选型建议", "对比报告", List.of(), "", "STANDARD", "AUTO");

        ResearchMatrixPlanningService.MatrixPlan plan = planner.plan(
                "对比 Elasticsearch、Milvus 和 pgvector 在 RAG 混合检索场景下的能力、部署成本和适用规模", intent);
        assertThat(plan.rows()).extracting(ResearchMatrixPlanningService.RowPlan::label)
                .containsExactly("Elasticsearch", "Milvus", "pgvector");

        // 以在字开头的实体名不能被当成场景限定语截掉
        ResearchMatrixPlanningService.MatrixPlan online = planner.plan("对比在线学习和离线学习的效果", intent);
        assertThat(online.rows()).extracting(ResearchMatrixPlanningService.RowPlan::label)
                .containsExactly("在线学习", "离线学习");
    }

    @Test
    void overlargeComparisonIsExplicitlyBoundedToEightyCells() {
        String entities = IntStream.rangeClosed(1, 30)
                .mapToObj(index -> "Entity" + index).collect(java.util.stream.Collectors.joining(", "));
        List<String> constraints = IntStream.rangeClosed(1, 12)
                .mapToObj(index -> "finding-" + index).toList();
        ResearchIntentResponse intent = new ResearchIntentResponse(
                "Compare", "table", constraints, "", "DEEP", "PRODUCT_COMPARISON");

        ResearchMatrixPlanningService.MatrixPlan plan = planner.plan("Compare " + entities, intent);

        assertThat(plan.columns()).hasSize(8);
        assertThat(plan.rows()).hasSize(10);
        assertThat(plan.cellCount()).isEqualTo(80);
        assertThat(plan.bounded()).isTrue();
        assertThat(plan.reasonCodes()).containsExactly("PLAN_BOUNDED");
    }

    @Test
    void highRiskFindingRequiresIndependentSources() {
        ResearchIntentResponse intent = new ResearchIntentResponse(
                "Assess", "memo", List.of("security risk"), "", "STANDARD", "AUTO");

        ResearchMatrixPlanningService.MatrixPlan plan = planner.plan("Assess Alpha", intent);

        ResearchMatrixPlanningService.ColumnPlan column = plan.columns().stream()
                .filter(item -> item.label().equals("security risk"))
                .findFirst().orElseThrow();
        assertThat(column.highRisk()).isTrue();
        assertThat(column.requiredProvenanceLevel()).isEqualTo("TWO_INDEPENDENT_SOURCES");
    }
}

package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.chat.ChatLlmClient;
import com.noteweave.task.TaskService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 产物编辑事件消费测试（B-08 验收 + FB-04 重做）：
 * 重复事件幂等、同事件不同载荷明确冲突；内容无变化不入队；无 usage 的 outcome 无副作用完成；
 * 新偏好必须走模型判定 + candidate（隐式证据不自动晋升）；
 * 处理关闭时持久 PENDING 回执不丢，恢复扫描接管；
 * 阶段独立重试（一个阶段失败不清空另一阶段的到期时间与完成状态，N-03）；
 * 模型暂时故障 RETRY_WAIT 而不是静默 SKIPPED；
 * 旧回执缺差异输入按明确终态处理，绝不空差异计满幅 1.0（RB-006）。
 */
@SpringBootTest
@ActiveProfiles("test")
class MemoryArtifactEditServiceTest {
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TaskService taskService;
    @Autowired MemoryCandidatePolicy candidatePolicy;
    @Autowired MemoryCandidateService candidateService;
    @Autowired CanonicalMemoryReviewService reviewService;
    @Autowired MemoryStatementMatcher statementMatcher;
    @Autowired ChatLlmClient llmClient;
    @Autowired MemoryEvidenceService evidenceService;
    @Autowired MemoryWordingJudge wordingJudge;
    @Autowired MemoryApplicationFeedbackService applicationFeedback;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private static final String PREFERENCE_JSON =
            "[{\"statement\":\"以后结论都用要点列表\",\"category\":\"structure\",\"explicit\":true,"
                    + "\"slot\":\"format.structure\",\"value\":\"要点列表\",\"polarity\":\"REQUIRE\",\"feedback\":false}]";

    private MemoryConversationExtractionService extractionWithModel(ChatLlmClient model) {
        return new MemoryConversationExtractionService(
                jdbcTemplate, objectMapper, taskService, candidatePolicy, candidateService,
                reviewService, statementMatcher, model, evidenceService, wordingJudge,
                transactionManager, "noteweave.memory.extraction", true);
    }

    private MemoryArtifactEditService service(MemoryConversationExtractionService extraction, boolean enabled) {
        return new MemoryArtifactEditService(
                jdbcTemplate, applicationFeedback, extraction, transactionManager, enabled);
    }

    private MemoryArtifactEditService.EditEvent event(String editEventId, List<String> sections, String actor) {
        return new MemoryArtifactEditService.EditEvent(
                editEventId, "ws-artifact-edit", "version-1", "version-0", "origin-task-1",
                "digest-before", "digest-after", sections, actor);
    }

    private String receiptColumn(String column, String editEventId) {
        return jdbcTemplate.queryForObject(
                "select " + column + " from memory_artifact_edit_receipt where edit_event_id = ?",
                String.class, editEventId);
    }

    @org.junit.jupiter.api.BeforeEach
    void seedWorkspace() {
        // memory_signal 有 workspace 外键：候选阶段写入信号前需要工作区行
        jdbcTemplate.update("""
                merge into workspace(id, owner_id, name, status) key(id)
                values ('ws-artifact-edit', 'local-user', 'artifact-edit-test', 'ACTIVE')
                """);
        jdbcTemplate.update("""
                merge into users(id, username, email, display_name, status) key(id)
                values ('user-9', 'artifact-editor', 'editor@example.com', '编辑者', 'ACTIVE')
                """);
    }

    @Test
    void duplicateEventMustBeIdempotent() {
        MemoryArtifactEditService service = service(extractionWithModel(llmClient), true);
        var first = service.handleArtifactEdit(event("evt-1", List.of("结论改成了要点列表"), "user-1"));
        var second = service.handleArtifactEdit(event("evt-1", List.of("结论改成了要点列表"), "user-1"));
        // 回执唯一，重复事件返回既有状态，不重复处理
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from memory_artifact_edit_receipt where edit_event_id = 'evt-1'",
                Integer.class)).isEqualTo(1);
        assertThat(second.outcomeStatus()).isEqualTo(first.outcomeStatus());
        assertThat(second.candidateStatus()).isEqualTo(first.candidateStatus());
    }

    @Test
    void sameEventIdWithDifferentPayloadMustConflictWithoutOverwriting() {
        MemoryArtifactEditService service = service(extractionWithModel(llmClient), true);
        service.handleArtifactEdit(event("evt-conflict", List.of("原始差异"), "user-1"));

        assertThatThrownBy(() -> service.handleArtifactEdit(
                event("evt-conflict", List.of("完全不同的差异"), "user-1")))
                .isInstanceOf(com.noteweave.common.BusinessException.class)
                .hasMessageContaining("不同载荷");

        // 旧载荷不被新正文替换
        assertThat(receiptColumn("changed_sections_json", "evt-conflict")).contains("原始差异");
    }

    @Test
    void unchangedContentMustBeSkippedWithoutReceipt() {
        MemoryArtifactEditService service = service(extractionWithModel(llmClient), true);
        var result = service.handleArtifactEdit(new MemoryArtifactEditService.EditEvent(
                "evt-nochange", "ws-artifact-edit", "v", "b", "t", "same-digest", "same-digest",
                List.of("x"), "user-1"));
        assertThat(result.reasonCode()).isEqualTo("NO_CONTENT_CHANGE");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from memory_artifact_edit_receipt where edit_event_id = 'evt-nochange'",
                Integer.class)).isZero();
    }

    @Test
    void outcomeWithoutUsageCompletesAsSkippedWithoutSideEffects() {
        MemoryArtifactEditService service = service(extractionWithModel(llmClient), true);
        var result = service.handleArtifactEdit(event("evt-no-usage", List.of("改写了一段"), null));
        assertThat(result.outcomeStatus()).isEqualTo(MemoryArtifactEditService.OUTCOME_SKIPPED);
        assertThat(result.candidateStatus()).isEqualTo(MemoryArtifactEditService.OUTCOME_SKIPPED);
        // 无 usage 不写任何反馈事件
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from memory_application_outcome where workspace_id = 'ws-artifact-edit'",
                Integer.class)).isZero();
    }

    @Test
    void candidateRequiresActingUserAndModelJudgment() {
        // 没有操作用户：候选保守跳过，不伪造归属
        MemoryArtifactEditService service = service(extractionWithModel(llmClient), true);
        var noActor = service.handleArtifactEdit(event("evt-no-actor", List.of("以后都用要点列表"), null));
        assertThat(noActor.candidateStatus()).isEqualTo(MemoryArtifactEditService.OUTCOME_SKIPPED);

        // 模型判定有长期偏好：经过 signal→candidate，证据为 ARTIFACT_FEEDBACK 隐式分层
        MemoryExtractionModelStub model = new MemoryExtractionModelStub(Map.of(
                "以后都用要点列表", PREFERENCE_JSON));
        model.enable();
        MemoryArtifactEditService withModel = service(extractionWithModel(model), true);
        var applied = withModel.handleArtifactEdit(event("evt-candidate", List.of("以后都用要点列表"), "user-9"));
        assertThat(applied.candidateStatus()).isEqualTo(MemoryArtifactEditService.OUTCOME_APPLIED);
        Integer signals = jdbcTemplate.queryForObject("""
                select count(*) from memory_signal
                where workspace_id = 'ws-artifact-edit' and source_id like 'artifact-edit:evt-candidate:%'
                """, Integer.class);
        assertThat(signals).isEqualTo(1);
        String band = jdbcTemplate.queryForObject("""
                select evidence_band from memory_signal
                where workspace_id = 'ws-artifact-edit' and source_id like 'artifact-edit:evt-candidate:%'
                """, String.class);
        // ARTIFACT_FEEDBACK 属隐式证据：未晋升前不参与新答案
        assertThat(band).isEqualTo("INFERRED");

        // 模型未启用（桩关闭）：候选保守跳过，不把一般文本修改当偏好
        var noModel = service.handleArtifactEdit(event("evt-no-model", List.of("改了一段普通事实"), "user-9"));
        assertThat(noModel.candidateStatus()).isEqualTo(MemoryArtifactEditService.OUTCOME_SKIPPED);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from memory_signal
                where workspace_id = 'ws-artifact-edit' and source_id like 'artifact-edit:evt-no-model:%'
                """, Integer.class)).isZero();
    }

    @Test
    void disabledProcessingMustPersistPendingReceiptAndScanRecoversAfterEnable() {
        // FB-03 5.2：处理关闭时合法事件持久 PENDING 回执后确认（PROCESSING_PAUSED）
        MemoryArtifactEditService paused = service(extractionWithModel(llmClient), false);
        var result = paused.handleArtifactEdit(event("evt-paused", List.of("改写了一段"), null));
        assertThat(result.reasonCode()).isEqualTo("PROCESSING_PAUSED");
        assertThat(receiptColumn("outcome_status", "evt-paused"))
                .isEqualTo(MemoryArtifactEditService.OUTCOME_PENDING);

        // 重新启用后恢复扫描接管：回执离开 PENDING，完成两阶段处理
        MemoryArtifactEditService enabled = service(extractionWithModel(llmClient), true);
        enabled.scanDue();
        assertThat(receiptColumn("outcome_status", "evt-paused"))
                .isNotEqualTo(MemoryArtifactEditService.OUTCOME_PENDING);
        assertThat(receiptColumn("candidate_status", "evt-paused"))
                .isNotEqualTo(MemoryArtifactEditService.OUTCOME_PENDING);
    }

    @Test
    void pausedSwitchExposesThePendingReceiptBacklog() {
        // MEM-04：开关关着的时候回执只堆积不推进，必须能从指标看出来
        MemoryArtifactEditService paused = service(extractionWithModel(llmClient), false);
        paused.handleArtifactEdit(event("evt-backlog", List.of("改写了一段"), null));

        Map<String, Long> backlog = paused.receiptBacklog();
        assertThat(backlog.get("enabled")).isZero();
        assertThat(backlog.get("pending")).isPositive();
        assertThat(backlog.get("retry_wait")).isNotNull();
        assertThat(backlog.get("terminal_failure")).isNotNull();
    }

    @Test
    void retryableModelFailureKeepsDueAtAndOtherStageCompletion() {
        // 模型对这条消息抛错：candidate 进入 RETRY_WAIT 且保留退避到期时间（N-03）
        MemoryExtractionModelStub model = new MemoryExtractionModelStub(Map.of(
                "以后都用要点列表", MemoryExtractionModelStub.THROW));
        model.enable();
        MemoryArtifactEditService service = service(extractionWithModel(model), true);
        var result = service.handleArtifactEdit(event("evt-retry", List.of("以后都用要点列表"), "user-9"));

        assertThat(result.candidateStatus()).isEqualTo(MemoryArtifactEditService.OUTCOME_RETRY);
        assertThat(jdbcTemplate.queryForObject("""
                        select candidate_next_attempt_at is not null
                        from memory_artifact_edit_receipt where edit_event_id = 'evt-retry'""",
                Boolean.class)).isTrue();
        // outcome 阶段不受 candidate 失败影响：已完成状态与到期时间不被覆盖
        assertThat(result.outcomeStatus()).isEqualTo(MemoryArtifactEditService.OUTCOME_SKIPPED);
        assertThat(jdbcTemplate.queryForObject("""
                        select outcome_next_attempt_at is null
                        from memory_artifact_edit_receipt where edit_event_id = 'evt-retry'""",
                Boolean.class)).isTrue();

        // 模型恢复后（换回可用桩）：到期重试成功转 APPLIED
        MemoryExtractionModelStub recovered = new MemoryExtractionModelStub(Map.of(
                "以后都用要点列表", PREFERENCE_JSON));
        recovered.enable();
        jdbcTemplate.update("""
                update memory_artifact_edit_receipt
                set candidate_next_attempt_at = timestamp '2020-01-01 00:00:00'
                where edit_event_id = 'evt-retry'
                """);
        service(extractionWithModel(recovered), true).scanDue();
        assertThat(receiptColumn("candidate_status", "evt-retry"))
                .isEqualTo(MemoryArtifactEditService.OUTCOME_APPLIED);
    }

    @Test
    void legacyReceiptWithoutDiffInputEndsTerminalNeverFullMagnitude() {
        // V200 旧回执：没有差异输入快照 → 明确终态，且不写任何反馈（绝不空差异计 1.0）
        jdbcTemplate.update("""
                insert into memory_artifact_edit_receipt(
                    id, workspace_id, edit_event_id, origin_task_id, outcome_status, candidate_status)
                values ('legacy-1', 'ws-artifact-edit', 'evt-legacy', 'origin-task-1', 'PENDING', 'PENDING')
                """);
        MemoryArtifactEditService service = service(extractionWithModel(llmClient), true);
        service.scanDue();

        assertThat(receiptColumn("outcome_status", "evt-legacy"))
                .isEqualTo(MemoryArtifactEditService.OUTCOME_TERMINAL);
        assertThat(receiptColumn("outcome_error_code", "evt-legacy"))
                .isEqualTo("TERMINAL_INPUT_UNRECOVERABLE");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from memory_application_outcome where workspace_id = 'ws-artifact-edit'",
                Integer.class)).isZero();
    }

    @Test
    void diffStatsTakePriorityOverSectionsForMagnitude() {
        // 可信差异统计优先：标题很短但统计显示大改动，幅度按统计而不是标题密度
        MemoryArtifactEditService.ReceiptInput input = new MemoryArtifactEditService.ReceiptInput(
                "evt-stats", "ws", "v", "t", "u", 2, null,
                List.of("结论"), Map.of("changedChars", 8000, "totalChars", 10000), List.of());
        assertThat(MemoryArtifactEditService.editMagnitude(input)).isGreaterThanOrEqualTo(0.8);

        // 空差异不是满幅：没有任何输入时幅度为 0（RB-006）
        MemoryArtifactEditService.ReceiptInput empty = new MemoryArtifactEditService.ReceiptInput(
                "evt-empty", "ws", "v", "t", "u", 2, null, List.of(), Map.of(), List.of());
        assertThat(MemoryArtifactEditService.editMagnitude(empty)).isEqualTo(0.0);
    }

    @Test
    void tailModificationMustCountAndBoundedInputIsEnforced() {
        // 长文尾部修改：章节差异不能因截断丢失判定
        String tail = "x".repeat(500) + " 以后都用要点列表";
        double magnitude = MemoryArtifactEditService.magnitudeFromSections(List.of(tail));
        assertThat(magnitude).isGreaterThan(0.0);
        // 输入有上限：超长章节拼接被截断到上限
        String bounded = MemoryArtifactEditService.sectionsText(List.of("a".repeat(10_000)));
        assertThat(bounded.length()).isLessThanOrEqualTo(MemoryArtifactEditService.MAX_EVENT_CHARS);
    }

    @Test
    void boundedSnapshotLimitsSectionsAndFragments() {
        // 输入快照有界：章节 64 条、片段 16 条，单条截断
        List<String> manySections = java.util.stream.IntStream.rangeClosed(1, 100)
                .mapToObj(i -> "s" + i).toList();
        assertThat(MemoryArtifactEditService.boundedSections(manySections)).hasSize(64);
        List<String> longFragments = List.of("f".repeat(2_000));
        assertThat(MemoryArtifactEditService.boundedFragments(longFragments).get(0).length())
                .isEqualTo(MemoryArtifactEditService.MAX_FRAGMENT_CHARS);
    }
}

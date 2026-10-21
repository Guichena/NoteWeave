package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import com.noteweave.task.TaskService;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 偏好已抽出、跟进反馈却判成可重试的消息，重投时不能因为「已经抽过」直接短路：
 * 任务还停在非终态就说明只有反馈这一步没跑完，重投只补跑反馈判定。
 * 不依赖 Spring 上下文，只替换模型、任务状态和反馈判定。
 */
class MemoryConversationExtractionFeedbackRetryTest {

    private static final String WORKSPACE = "ws-1";
    private static final String MESSAGE_ID = "msg-1";
    private static final String TASK_ID = "task-1";
    private static final String USER_ID = "user-1";
    private static final String USER_MESSAGE = "刚才那段太长了，以后短一点";
    private static final String PREFERENCE_JSON = """
            [{"statement":"以后回答简短一点","category":"style","explicit":true,
              "slot":"answer.length","value":"简短","polarity":"REQUIRE","feedback":true}]
            """;
    private static final String RETRY_MESSAGE = "偏好已提取，本轮只补写跟进反馈";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void feedbackJudgmentFailureLeavesTheTaskRetryableAndTheRedeliveryWritesTheFeedbackOnly() {
        Fixture fixture = new Fixture();

        // 第一次投递：偏好抽出并写进 memory_signal，跟进反馈判成可重试 → 任务标成可重试
        MemoryConversationExtractionService.ExtractionResult first =
                fixture.service.extract(TASK_ID, WORKSPACE, MESSAGE_ID, USER_ID, USER_MESSAGE);

        assertThat(first.method()).isEqualTo(MemoryConversationExtractionService.METHOD_LLM);
        assertThat(first.candidates()).singleElement()
                .satisfies(candidate -> assertThat(candidate.statement()).isEqualTo("以后回答简短一点"));
        assertThat(fixture.signalCount.get()).isEqualTo(1);
        verify(fixture.taskService).failTask(TASK_ID, MemoryConversationExtractionService.RETRY_PHASE,
                "模型不可用，本轮未完成记忆处理", MemoryConversationExtractionService.RETRY_PHASE, true);
        assertThat(fixture.taskStatus).hasValue("FAILED");
        assertThat(fixture.client.extractionCalls).isEqualTo(1);

        // 既有重驱入口把任务复位成 PENDING/RUNNING 后再投一次：只补跑反馈，判定这一次成功
        fixture.taskStatus.set("RUNNING");
        fixture.feedbackResult.set(MemoryApplicationFeedbackService.FollowUpResult.ACCEPTED);

        MemoryConversationExtractionService.ExtractionResult retry =
                fixture.service.extract(TASK_ID, WORKSPACE, MESSAGE_ID, USER_ID, USER_MESSAGE);

        assertThat(retry.method()).isEqualTo(MemoryConversationExtractionService.METHOD_FEEDBACK_RETRY);
        assertThat(retry.candidates()).isEmpty();
        // 没有重抽偏好，也没有再写一条信号
        assertThat(fixture.client.extractionCalls).isEqualTo(1);
        assertThat(fixture.signalCount.get()).isEqualTo(1);
        // 反馈判定补跑成功，任务这次真的收尾
        verify(fixture.feedback, times(2)).recordFollowUp(WORKSPACE, MESSAGE_ID, USER_MESSAGE);
        verify(fixture.taskService).completeTask(TASK_ID, "COMPLETED", RETRY_MESSAGE, MESSAGE_ID);
        verify(fixture.taskService, times(1)).failTask(anyString(), anyString(), anyString(), anyString(),
                anyBoolean());
        assertThat(fixture.taskStatus).hasValue("COMPLETED");

        // 再投一次就是普通的重复投递：不重抽，也不再碰反馈判定
        MemoryConversationExtractionService.ExtractionResult duplicate =
                fixture.service.extract(TASK_ID, WORKSPACE, MESSAGE_ID, USER_ID, USER_MESSAGE);

        assertThat(duplicate.method()).isEqualTo("DUPLICATE_DELIVERY");
        verify(fixture.feedback, times(2)).recordFollowUp(WORKSPACE, MESSAGE_ID, USER_MESSAGE);
        assertThat(fixture.client.extractionCalls).isEqualTo(1);
    }

    @Test
    void redeliveryBeforeTheRedriveStillWritesTheFeedback() {
        Fixture fixture = new Fixture();

        fixture.service.extract(TASK_ID, WORKSPACE, MESSAGE_ID, USER_ID, USER_MESSAGE);
        fixture.feedbackResult.set(MemoryApplicationFeedbackService.FollowUpResult.ACCEPTED);

        // 还没走重驱就重投（任务仍是 FAILED）：仍然补跑反馈并写入，任务状态留给重驱后的那一轮收尾
        MemoryConversationExtractionService.ExtractionResult retry =
                fixture.service.extract(TASK_ID, WORKSPACE, MESSAGE_ID, USER_ID, USER_MESSAGE);

        assertThat(retry.method()).isEqualTo(MemoryConversationExtractionService.METHOD_FEEDBACK_RETRY);
        verify(fixture.feedback, times(2)).recordFollowUp(WORKSPACE, MESSAGE_ID, USER_MESSAGE);
        assertThat(fixture.client.extractionCalls).isEqualTo(1);
        // 任务已经失败过，不重复写失败事件
        verify(fixture.taskService, times(1)).failTask(anyString(), anyString(), anyString(), anyString(),
                anyBoolean());
        verify(fixture.taskService, never()).completeTask(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void duplicateDeliveryOfASettledTaskStaysANoOp() {
        Fixture fixture = new Fixture();
        fixture.signalCount.set(1);
        fixture.taskStatus.set("COMPLETED");

        MemoryConversationExtractionService.ExtractionResult duplicate =
                fixture.service.extract(TASK_ID, WORKSPACE, MESSAGE_ID, USER_ID, USER_MESSAGE);

        assertThat(duplicate.method()).isEqualTo("DUPLICATE_DELIVERY");
        assertThat(duplicate.candidates()).isEmpty();
        assertThat(fixture.client.extractionCalls).isZero();
        verify(fixture.feedback, never()).recordFollowUp(anyString(), anyString(), anyString());
        verify(fixture.taskService, never()).completeTask(anyString(), anyString(), anyString(), anyString());
        verify(fixture.taskService, never()).failTask(anyString(), anyString(), anyString(), anyString(),
                anyBoolean());
    }

    /**
     * 假库：memory_signal 的行数就是「这条消息抽过没有」，任务状态按 TaskService 的真实流转更新，
     * 所以「第一次投递失败、重驱后重投」和真实的调用顺序一致。
     */
    private final class Fixture {

        private final AtomicInteger signalCount = new AtomicInteger();
        private final AtomicReference<String> taskStatus = new AtomicReference<>("RUNNING");
        private final AtomicReference<MemoryApplicationFeedbackService.FollowUpResult> feedbackResult =
                new AtomicReference<>(MemoryApplicationFeedbackService.FollowUpResult.RETRYABLE);
        private final CountingClient client = new CountingClient(PREFERENCE_JSON);
        private final TaskService taskService = mock(TaskService.class);
        private final MemoryApplicationFeedbackService feedback = mock(MemoryApplicationFeedbackService.class);
        private final MemoryConversationExtractionService service;

        private Fixture() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForObject(contains("from memory_signal"), eq(Integer.class), any(Object[].class)))
                    .thenAnswer(invocation -> signalCount.get());
            when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
                if (((String) invocation.getArgument(0)).contains("insert into memory_signal")) {
                    signalCount.incrementAndGet();
                }
                return 1;
            });
            when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
            when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                    .thenAnswer(invocation -> {
                        String sql = invocation.getArgument(0);
                        if (sql.contains("from task where id")) {
                            return taskStatus.get();
                        }
                        if (sql.contains("earlier.content")) {
                            return null;
                        }
                        return "conversation:c-1";
                    });
            doAnswer(invocation -> {
                taskStatus.set("COMPLETED");
                return null;
            }).when(taskService).completeTask(anyString(), anyString(), anyString(), anyString());
            doAnswer(invocation -> {
                taskStatus.set("FAILED");
                return null;
            }).when(taskService).failTask(anyString(), anyString(), anyString(), anyString(), anyBoolean());
            when(feedback.recordFollowUp(anyString(), anyString(), any())).thenAnswer(invocation -> feedbackResult.get());

            MemoryCandidateService candidateService = mock(MemoryCandidateService.class);
            when(candidateService.buildCandidates(anyString(), any())).thenReturn(List.of(new MemoryCandidateResponse(
                    "cand-1", WORKSPACE, "PREFERENCE", "以后回答简短一点", List.of(), "AUTO",
                    0.6, 0.6, false, "NONE", "FRESH", "READY", 0.1, "GLOBAL", "memory-candidate-policy-v2")));
            this.service = new MemoryConversationExtractionService(jdbc, objectMapper, taskService,
                    mock(MemoryCandidatePolicy.class), candidateService, mock(CanonicalMemoryReviewService.class),
                    mock(MemoryStatementMatcher.class), client, mock(MemoryEvidenceService.class),
                    mock(MemoryWordingJudge.class), mock(PlatformTransactionManager.class),
                    "noteweave.memory.extraction", true);
            service.setApplicationFeedback(feedback);
        }
    }

    /** 分类器固定答 yes，抽取返回预置偏好，并记下抽取调用次数以证明重投没有重抽。 */
    private static final class CountingClient implements ChatLlmClient {

        private final String extractionOutput;
        private int extractionCalls;

        private CountingClient(String extractionOutput) {
            this.extractionOutput = extractionOutput;
        }

        @Override
        public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                 Consumer<String> onToken) {
            if (maximumOutputTokens == MemoryExtractionClassifier.MAX_OUTPUT_TOKENS) {
                return "yes";
            }
            extractionCalls++;
            return extractionOutput;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}

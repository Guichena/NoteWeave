package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.task.TaskService;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 入队保留 4000 字、提示词只用 2000 字的边界，不依赖 Spring 上下文。
 */
class MemoryConversationExtractionQueueTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void messagesUpToFourThousandCharsEnterTheQueue() {
        assertThat(MemoryConversationExtractionService.shouldExtract("a".repeat(4_000))).isTrue();
        assertThat(MemoryConversationExtractionService.shouldExtract("a".repeat(4_001))).isFalse();
    }

    @Test
    void queuedContentKeepsFourThousandCharsWhilePromptContentUsesTwoThousand() {
        String longMessage = "b".repeat(3_600);

        assertThat(MemoryConversationExtractionService.queuedContent(longMessage))
                .hasSize(3_600).isEqualTo(longMessage);
        assertThat(MemoryConversationExtractionService.queuedContent("c".repeat(4_500)))
                .hasSize(4_000).isEqualTo("c".repeat(4_000));
        assertThat(MemoryConversationExtractionService.promptContent(longMessage))
                .hasSize(2_000).isEqualTo("b".repeat(2_000));
        assertThat(MemoryConversationExtractionService.promptContent("  以后回答先给结论  "))
                .isEqualTo("以后回答先给结论");
        assertThat(MemoryConversationExtractionService.queuedContent(null)).isEmpty();
        assertThat(MemoryConversationExtractionService.promptContent(null)).isEmpty();
    }

    @Test
    void outboxPayloadStoresTheWholeMessageBeyondTwoThousandChars() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskService taskService = mock(TaskService.class);
        String message = "我比较喜欢用表格对比不同方案的优缺点".repeat(200);
        assertThat(message).hasSize(3_600);
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of(message));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(taskService.createTask(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("task-1");
        AtomicReference<String> inserted = new AtomicReference<>();
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            for (Object argument : invocation.getArguments()) {
                collectJson(argument, inserted);
            }
            return 1;
        });

        boolean queued = service(jdbc, taskService).queueForUserMessage(
                "ws-1", "conv-1", "msg-1", "user-1");

        assertThat(queued).isTrue();
        JsonNode payload = readJson(inserted.get());
        // 修复前这里只有 2000 字，超出部分在入队时被截掉
        assertThat(payload.path("content").asText()).isEqualTo(message);
        assertThat(payload.path("message_id").asText()).isEqualTo("msg-1");
        assertThat(payload.path("task_id").asText()).isEqualTo("task-1");
    }

    @Test
    void promptTruncationOnlyAffectsTheExtractionPrompt() {
        MemoryConversationExtractionService service = service(mock(JdbcTemplate.class), mock(TaskService.class));
        String filler = "填充".repeat(1_100);
        String preference = "以后回答先给结论再展开";

        // 偏好落在 2000 字之后，提示词截断后规则提取看不到它
        assertThat(service.extractPreferences(filler + "。" + preference, null).preferences()).isEmpty();
        // 同一条偏好只要在 2000 字以内就会被提取，说明入队内容没有被提前砍掉
        assertThat(service.extractPreferences(filler.substring(0, 1_000) + "。" + preference, null).preferences())
                .extracting(MemoryConversationExtractionService.Preference::statement)
                .containsExactly(preference);
    }

    private MemoryConversationExtractionService service(JdbcTemplate jdbc, TaskService taskService) {
        return new MemoryConversationExtractionService(jdbc, objectMapper, taskService, null, null, null, null,
                null, null, null, mock(PlatformTransactionManager.class), "noteweave.memory.extraction", true);
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void collectJson(Object argument, AtomicReference<String> sink) {
        if (argument instanceof String text) {
            if (text.startsWith("{")) {
                sink.set(text);
            }
        } else if (argument instanceof Object[] nested) {
            for (Object item : nested) {
                collectJson(item, sink);
            }
        }
    }
}

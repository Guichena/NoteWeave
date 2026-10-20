package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.chat.ChatLlmClient;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 合并扫描必须按 (created_at, id) keyset 游标推进，扫到末尾（不足 40 条）后复位从头开始。
 */
class MemoryConsolidationCursorTest {

    private static final Timestamp BASE = Timestamp.valueOf("2024-01-01 00:00:00");
    private static final Timestamp ORIGIN = Timestamp.from(Instant.EPOCH);

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ChatLlmClient llmClient = mock(ChatLlmClient.class);
    private final MemoryStatementMatcher statementMatcher = mock(MemoryStatementMatcher.class);
    private final MemoryEvidenceService evidenceService = mock(MemoryEvidenceService.class);
    private final CanonicalMemoryReviewService reviewService = mock(CanonicalMemoryReviewService.class);

    private final List<List<MemoryConsolidationService.Item>> batches = new ArrayList<>();
    private final List<Object[]> queries = new ArrayList<>();

    private MemoryConsolidationService service;

    @BeforeEach
    void setUp() {
        service = new MemoryConsolidationService(
                jdbcTemplate, llmClient, statementMatcher, evidenceService, reviewService, true, 90);
        when(llmClient.isEnabled()).thenReturn(true);
        when(statementMatcher.equivalent(any(), any())).thenReturn(false);
        when(jdbcTemplate.query(anyString(),
                ArgumentMatchers.<RowMapper<MemoryConsolidationService.Item>>any(),
                any(), any(), any())).thenAnswer(invocation -> {
                    queries.add(new Object[] {
                            invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4)});
                    return batches.isEmpty() ? List.of() : batches.remove(0);
                });
    }

    @Test
    void nextScanStartsAfterTheLastRowOfThePreviousFullBatch() {
        batches.add(batch(1, 40, "ws-1"));
        batches.add(batch(41, 3, "ws-1"));

        service.consolidate();
        service.consolidate();

        assertThat(queries).hasSize(2);
        assertThat(queries.get(0)[0]).isEqualTo(ORIGIN);
        assertThat(queries.get(0)[1]).isEqualTo(ORIGIN);
        assertThat(queries.get(0)[2]).isEqualTo("");

        MemoryConsolidationService.Item lastOfFirstBatch = item(40, "ws-1");
        assertThat(queries.get(1)[0]).isEqualTo(lastOfFirstBatch.createdAt());
        assertThat(queries.get(1)[1]).isEqualTo(lastOfFirstBatch.createdAt());
        assertThat(queries.get(1)[2]).isEqualTo(lastOfFirstBatch.id());
    }

    @Test
    void cursorResetsToTheBeginningOnceTheTailBatchIsShort() {
        batches.add(batch(1, 10, "ws-1"));
        batches.add(batch(1, 10, "ws-1"));

        service.consolidate();
        service.consolidate();

        assertThat(queries).hasSize(2);
        assertThat(queries.get(1)[0]).isEqualTo(ORIGIN);
        assertThat(queries.get(1)[1]).isEqualTo(ORIGIN);
        assertThat(queries.get(1)[2]).isEqualTo("");
    }

    @Test
    void cursorResetsToTheBeginningWhenTheScanFindsNothing() {
        service.consolidate();
        service.consolidate();

        assertThat(queries).hasSize(2);
        assertThat(queries.get(1)[0]).isEqualTo(ORIGIN);
        assertThat(queries.get(1)[2]).isEqualTo("");
    }

    @Test
    void mergeCandidatesAreStillLimitedToTheSameWorkspace() {
        when(statementMatcher.equivalent(any(), any())).thenReturn(true);
        batches.add(List.of(item(1, "ws-1"), item(2, "ws-2")));

        assertThat(service.consolidate()).isZero();

        verify(llmClient, never()).streamChat(anyString(), anyString(), anyInt(), any());
        verify(reviewService, never()).mergeDuplicate(any(), any(), any());
    }

    private static List<MemoryConsolidationService.Item> batch(int firstIndex, int size, String workspaceId) {
        List<MemoryConsolidationService.Item> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            items.add(item(firstIndex + i, workspaceId));
        }
        return items;
    }

    private static MemoryConsolidationService.Item item(int index, String workspaceId) {
        return new MemoryConsolidationService.Item(
                "id-%03d".formatted(index),
                workspaceId,
                1,
                "tone",
                "text-" + index,
                new Timestamp(BASE.getTime() + index * 60_000L));
    }
}

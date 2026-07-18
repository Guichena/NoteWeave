package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;
import com.noteweave.chat.RetrievalHydrator.NoteSourceStats;
import com.noteweave.chat.NoteRetrievalService.ReadingWindow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RetrievalHydratorTest {

    @Test
    void passageOwnershipShouldUseOneQueryForOneHundredHits() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        RetrievalHydrator hydrator = new RetrievalHydrator(jdbcTemplate);
        List<String> chunkIds = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> "chunk-" + index)
                .toList();

        var result = hydrator.hydratePassageOwnership("workspace", chunkIds);

        assertThat(result).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1))
                .query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("join source s")
                .contains("join source_snapshot ss")
                .contains("c.workspace_id = ?")
                .contains("s.status = 'READY'")
                .contains("s.index_status = 'INDEXED'")
                .contains("ss.index_status = 'INDEXED'")
                .contains("select max(current_ss.version_no)")
                .contains("c.projection_status = 'PROJECTED'");
    }

    @Test
    void noteHydrationShouldStayConstantAcrossManySources() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        RetrievalHydrator hydrator = new RetrievalHydrator(jdbcTemplate);
        List<String> sourceIds = java.util.stream.IntStream.range(0, 80)
                .mapToObj(index -> "source-" + index)
                .toList();

        var windows = hydrator.hydrateNoteWindows("workspace", sourceIds);
        var stats = hydrator.hydrateNoteSourceStats("workspace", sourceIds);

        assertThat(windows).isEmpty();
        assertThat(stats).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2))
                .query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getAllValues().get(0))
                .contains("join source_snapshot ss")
                .contains("select max(current_ss.version_no)")
                .contains("c.projection_status = 'PROJECTED'");
    }

    @Test
    void hydratorShouldDeduplicateIdsWithoutChangingReturnedModels() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        RetrievalHydrator hydrator = new RetrievalHydrator(jdbcTemplate);

        assertThat(hydrator.hydratePassageOwnership(
                "workspace", List.of("chunk", "chunk"))).isEmpty();
        verify(jdbcTemplate).query(
                anyString(), any(RowMapper.class),
                org.mockito.ArgumentMatchers.eq("workspace"),
                org.mockito.ArgumentMatchers.eq("chunk"));
    }
}

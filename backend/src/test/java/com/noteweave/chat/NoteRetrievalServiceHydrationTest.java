package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.common.BusinessException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class NoteRetrievalServiceHydrationTest {

    @Test
    void noteMetadataQueriesShouldStayConstantAcrossManySources() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        List<CandidateSource> sources = java.util.stream.IntStream.range(0, 80)
                .mapToObj(index -> candidate("source-" + index))
                .toList();
        List<String> sourceIds = sources.stream().map(CandidateSource::sourceId).toList();
        when(hydrator.hydrateNoteWindows("workspace", sourceIds)).thenReturn(Map.of());
        when(hydrator.hydrateNoteSourceStats("workspace", sourceIds)).thenReturn(Map.of());
        NoteRecallRepository recallRepository = new NoteRecallRepository(jdbcTemplate);
        NoteRetrievalService service = new NoteRetrievalService(
                jdbcTemplate, new ObjectMapper(), hydrator, new NoteReadingPlanner(),
                new NoteRelationGraph(), recallRepository);

        var result = service.readEntriesMetadataForNote("workspace", sources, "query");

        assertThat(result).hasSize(80);
        verify(hydrator).hydrateNoteWindows("workspace", sourceIds);
        verify(hydrator).hydrateNoteSourceStats("workspace", sourceIds);
        verify(jdbcTemplate, times(4))
                .query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void malformedTagsMustNotUseRegexFallback() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        CandidateSource source = new CandidateSource(
                "source-1", "Source", "MARKDOWN", 0, 0,
                "", "", "", "{\"topic\":\"secret\"}", "{}", "", 0, "",
                List.of(), List.of(), 0, 0, "", ""
        );
        when(hydrator.hydrateNoteWindows("workspace", List.of("source-1"))).thenReturn(Map.of());
        when(hydrator.hydrateNoteSourceStats("workspace", List.of("source-1"))).thenReturn(Map.of());
        NoteRetrievalService service = new NoteRetrievalService(
                jdbcTemplate, new ObjectMapper(), hydrator, new NoteReadingPlanner(),
                new NoteRelationGraph(), new NoteRecallRepository(jdbcTemplate));

        assertThatThrownBy(() -> service.readEntriesMetadataForNote("workspace", List.of(source), "query"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.code()).isEqualTo("SOURCE_TAG_DATA_INVALID"));
    }

    private CandidateSource candidate(String sourceId) {
        return new CandidateSource(
                sourceId, "Source " + sourceId, "MARKDOWN", 0, 0,
                "", "", "", "[]", "{}", "", 0, "",
                List.of(), List.of(), 0, 0, "", ""
        );
    }
}

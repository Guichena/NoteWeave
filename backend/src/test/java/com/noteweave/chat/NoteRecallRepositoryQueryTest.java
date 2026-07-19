package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class NoteRecallRepositoryQueryTest {
    @Test
    void candidatePoolShouldNotBeRestrictedToRecentFortySources() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NoteRecallRepository repository = new NoteRecallRepository(jdbc);

        repository.findCurrentSources("workspace");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(org.springframework.jdbc.core.RowMapper.class), any(Object.class));
        assertThat(sql.getValue().toLowerCase()).doesNotContain("limit 40");
        assertThat(sql.getValue()).contains("ss.version_no", "s.index_status = 'INDEXED'");
    }
}

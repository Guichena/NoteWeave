package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.answer.strategy.EvidenceOwnershipPort.EvidenceIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcEvidenceOwnershipAdapterTest {

    @Test
    void shouldVerifyManyPassagesAndKnowledgeVersionsWithTwoBatchQueries() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        JdbcEvidenceOwnershipAdapter adapter = new JdbcEvidenceOwnershipAdapter(jdbcTemplate);
        List<EvidenceIdentity> identities = java.util.stream.IntStream.range(0, 100)
                .boxed()
                .flatMap(index -> java.util.stream.Stream.of(
                        new EvidenceIdentity(
                                "PASSAGE", "source-" + index, "snapshot-" + index,
                                "passage-" + index, "", ""),
                        new EvidenceIdentity(
                                "KNOWLEDGE_VERSION", "", "", "",
                                "item-" + index, "version-" + index)
                ))
                .toList();

        assertThat(adapter.findCurrent("workspace", identities)).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2))
                .query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getAllValues().get(0))
                .contains("c.workspace_id = ?")
                .contains("select max(current_ss.version_no)")
                .contains("c.projection_status = 'PROJECTED'");
        assertThat(sql.getAllValues().get(1))
                .contains("i.workspace_id = ?")
                .contains("v.id = i.latest_version_id")
                .contains("i.status = 'ACTIVE'");
    }
}

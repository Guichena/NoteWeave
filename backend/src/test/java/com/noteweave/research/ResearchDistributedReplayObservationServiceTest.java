package com.noteweave.research;

import com.noteweave.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ResearchDistributedReplayObservationServiceTest {

    @Test
    void observationSurfaceIsFailClosedByDefault() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResearchDistributedReplayObservationService service =
                new ResearchDistributedReplayObservationService(jdbcTemplate, false);

        assertThatThrownBy(() -> service.observe("run-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_DISTRIBUTED_REPLAY_DISABLED");
        verifyNoInteractions(jdbcTemplate);
    }
}

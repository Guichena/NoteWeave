package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.retrieval.index.RetrievalIndexManager;
import com.noteweave.source.SourceCatalogVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RetrievalBackfillServiceCatalogGateTest {
    @Test
    void rejectsAliasSwitchPreparationWhenWorkspaceCatalogChanged() {
        SourceCatalogVersionService catalogVersionService = mock(SourceCatalogVersionService.class);
        when(catalogVersionService.current("workspace")).thenReturn(12L);
        RetrievalBackfillService service = new RetrievalBackfillService(
                mock(JdbcTemplate.class), mock(NoteWeaveProperties.class),
                mock(RetrievalIndexBuildRepository.class), mock(SourceRetrievalProjectionService.class),
                mock(RetrievalIndexManager.class), catalogVersionService);

        assertThatCode(() -> service.requireStableCatalog("workspace", 12L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requireStableCatalog("workspace", 11L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed during retrieval backfill");
    }
}

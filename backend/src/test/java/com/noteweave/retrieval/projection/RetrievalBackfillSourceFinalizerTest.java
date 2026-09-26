package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.noteweave.source.SourceCatalogVersionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

class RetrievalBackfillSourceFinalizerTest {
    @Test
    void successfulBackfillMarksChunksSnapshotAndSourceIndexed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SourceCatalogVersionService catalog = mock(SourceCatalogVersionService.class);
        RetrievalBackfillSourceFinalizer finalizer = new RetrievalBackfillSourceFinalizer(jdbc, catalog);
        org.mockito.Mockito.when(jdbc.update(
                org.mockito.ArgumentMatchers.contains("update source_chunk"),
                org.mockito.ArgumentMatchers.eq("workspace"),
                org.mockito.ArgumentMatchers.eq("source"),
                org.mockito.ArgumentMatchers.eq("snapshot"))).thenReturn(2);
        org.mockito.Mockito.when(jdbc.update(
                org.mockito.ArgumentMatchers.contains("update source_snapshot"),
                org.mockito.ArgumentMatchers.eq("snapshot"),
                org.mockito.ArgumentMatchers.eq("source"),
                org.mockito.ArgumentMatchers.eq("source"))).thenReturn(1);
        org.mockito.Mockito.when(jdbc.update(
                org.mockito.ArgumentMatchers.contains("update source\n"),
                org.mockito.ArgumentMatchers.eq("source"),
                org.mockito.ArgumentMatchers.eq("workspace"))).thenReturn(1);

        finalizer.markIndexed("workspace", List.of(
                new RetrievalBackfillService.SnapshotTarget("source", "snapshot", 2)));

        InOrder order = inOrder(jdbc, catalog);
        order.verify(jdbc).update(org.mockito.ArgumentMatchers.contains("update source_chunk"),
                org.mockito.ArgumentMatchers.eq("workspace"), org.mockito.ArgumentMatchers.eq("source"),
                org.mockito.ArgumentMatchers.eq("snapshot"));
        order.verify(jdbc).update(org.mockito.ArgumentMatchers.contains("update source_snapshot"),
                org.mockito.ArgumentMatchers.eq("snapshot"), org.mockito.ArgumentMatchers.eq("source"),
                org.mockito.ArgumentMatchers.eq("source"));
        order.verify(jdbc).update(org.mockito.ArgumentMatchers.contains("update source\n"),
                org.mockito.ArgumentMatchers.eq("source"), org.mockito.ArgumentMatchers.eq("workspace"));
        order.verify(catalog).bump("workspace");
    }

    @Test
    void incompleteChunkSetDoesNotMarkSnapshotOrSourceIndexed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SourceCatalogVersionService catalog = mock(SourceCatalogVersionService.class);
        RetrievalBackfillSourceFinalizer finalizer = new RetrievalBackfillSourceFinalizer(jdbc, catalog);
        org.mockito.Mockito.when(jdbc.update(
                org.mockito.ArgumentMatchers.contains("update source_chunk"),
                org.mockito.ArgumentMatchers.eq("workspace"),
                org.mockito.ArgumentMatchers.eq("source"),
                org.mockito.ArgumentMatchers.eq("snapshot"))).thenReturn(1);

        assertThatThrownBy(() -> finalizer.markIndexed("workspace", List.of(
                new RetrievalBackfillService.SnapshotTarget("source", "snapshot", 2))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chunk set changed");

        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.contains("update source_snapshot"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(catalog, never()).bump("workspace");
    }
}

package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.storage.ObjectStorage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ArtifactVideoMaterialCleanupTest {
    @Test
    void deletesOnlyOldUnreferencedFramesWithinControlledPrefix() {
        ObjectStorage storage = mock(ObjectStorage.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NoteWeaveProperties properties = mock(NoteWeaveProperties.class);
        NoteWeaveProperties.Storage storageConfig = mock(NoteWeaveProperties.Storage.class);
        NoteWeaveProperties.Minio minio = mock(NoteWeaveProperties.Minio.class);
        when(properties.storage()).thenReturn(storageConfig);
        when(storageConfig.minio()).thenReturn(minio);
        when(minio.bucketExport()).thenReturn("export-test");
        String prefix = "artifacts/video-material/";
        String kept = prefix + "bundle-1/kept.png";
        String orphan = prefix + "bundle-2/orphan.png";
        String recent = prefix + "bundle-3/recent.png";
        String unrelated = "artifacts/staged/other.png";
        Instant old = Instant.now().minus(25, ChronoUnit.HOURS);
        when(storage.list("export-test", prefix, "", 100)).thenReturn(List.of(
                new ObjectStorage.StoredObject(kept, old),
                new ObjectStorage.StoredObject(orphan, old),
                new ObjectStorage.StoredObject(recent, Instant.now()),
                new ObjectStorage.StoredObject(unrelated, old)));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("export-test"), eq(kept)))
                .thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("export-test"), eq(orphan)))
                .thenReturn(0);

        ArtifactVideoMaterialService service = new ArtifactVideoMaterialService(
                mock(ArtifactJobReadRepository.class), jdbc, new ObjectMapper(),
                mock(ArtifactWorkerExportClient.class), storage, properties);
        assertThat(service.cleanupOrphanedFrameFiles()).isEqualTo(1);
        verify(storage).delete("export-test", orphan);
        verify(storage, never()).delete("export-test", kept);
        verify(storage, never()).delete("export-test", recent);
        verify(storage, never()).delete("export-test", unrelated);
    }
}

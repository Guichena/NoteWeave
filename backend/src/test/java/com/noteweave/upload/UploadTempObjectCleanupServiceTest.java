package com.noteweave.upload;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.noteweave.storage.ObjectStorage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class UploadTempObjectCleanupServiceTest {

    @Test
    void successfulObjectCleanupRemovesChunkRows() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("upload-1")))
                .thenReturn(List.of("workspace/ws/upload_tmp/upload-1/0"));

        UploadTempObjectCleanupService service = new UploadTempObjectCleanupService(
                jdbcTemplate, storage, 86400);

        service.cleanupAfterCommit("upload-1");

        verify(storage).delete("noteweave-source", "workspace/ws/upload_tmp/upload-1/0");
        verify(jdbcTemplate).update("delete from upload_chunk where upload_id = ?", "upload-1");
    }

    @Test
    void failedObjectCleanupKeepsChunkRowsForRetry() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("upload-1")))
                .thenReturn(List.of("workspace/ws/upload_tmp/upload-1/0"));
        doThrow(new IllegalStateException("storage unavailable"))
                .when(storage).delete("noteweave-source", "workspace/ws/upload_tmp/upload-1/0");

        UploadTempObjectCleanupService service = new UploadTempObjectCleanupService(
                jdbcTemplate, storage, 86400);

        service.cleanupAfterCommit("upload-1");

        verify(jdbcTemplate, never()).update("delete from upload_chunk where upload_id = ?", "upload-1");
    }
}

package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import com.noteweave.storage.ObjectStorage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ArtifactFileReconcileReadbackTest {
    @Test
    void reconcilesVerifiedFilesButKeepsExpiredWorkerExportsDegraded() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:artifact-readback-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table artifact_job(id varchar(36) primary key, task_id varchar(36))");
        jdbc.execute("""
                create table artifact_version(
                    id varchar(36) primary key, artifact_job_id varchar(36), version_no int,
                    title varchar(100), content_markdown clob, result_payload_json clob,
                    origin_task_id varchar(36), delivery_status varchar(20), created_at timestamp default current_timestamp)
                """);
        jdbc.execute("""
                create table artifact_file(
                    id varchar(36) primary key, artifact_version_id varchar(36), file_format varchar(20),
                    file_name varchar(100), media_type varchar(100), storage_backend varchar(20),
                    bucket_name varchar(100), object_key varchar(200), size_bytes bigint,
                    checksum_sha256 varchar(64), status varchar(20), error_message varchar(100),
                    file_role varchar(40), variant varchar(40), sequence_no int,
                    created_at timestamp default current_timestamp)
                """);
        byte[] markdown = "# Recovered".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(markdown));
        jdbc.update("insert into artifact_job(id, task_id) values ('job', 'task')");
        jdbc.update("""
                insert into artifact_version(id, artifact_job_id, version_no, title, content_markdown,
                                             result_payload_json, origin_task_id, delivery_status)
                values ('version', 'job', 1, 'Recovered', '# Recovered', '{}', 'task', 'DEGRADED')
                """);
        jdbc.update("""
                insert into artifact_file(id, artifact_version_id, file_format, file_name, media_type,
                                          storage_backend, bucket_name, object_key, size_bytes,
                                          checksum_sha256, status, error_message, file_role, variant, sequence_no)
                values ('file', 'version', 'MARKDOWN', 'recovered.md', 'text/markdown',
                        'local', 'export', 'artifact/recovered.md', ?, ?, 'READY', '',
                        'PRIMARY_MARKDOWN', '', 0)
                """, markdown.length, digest);
        ObjectStorage storage = mock(ObjectStorage.class);
        ArtifactWorkerExportClient workerExports = mock(ArtifactWorkerExportClient.class);
        byte[] corrupted = "wrong".getBytes(StandardCharsets.UTF_8);
        when(storage.read("export", "artifact/recovered.md"))
                .thenReturn(corrupted, corrupted, corrupted, markdown);
        NoteWeaveProperties properties = mock(NoteWeaveProperties.class);
        NoteWeaveProperties.Storage storageConfig = mock(NoteWeaveProperties.Storage.class);
        NoteWeaveProperties.Minio minio = mock(NoteWeaveProperties.Minio.class);
        when(properties.storage()).thenReturn(storageConfig);
        when(storageConfig.minio()).thenReturn(minio);
        when(minio.bucketExport()).thenReturn("export");
        ArtifactExportService service = new ArtifactExportService(
                jdbc, new ObjectMapper(), storage, workerExports,
                mock(ArtifactSkillCatalogService.class), mock(ArtifactMemoryRevisionGuard.class),
                mock(ArtifactContextV2ShadowSnapshotService.class),
                mock(ResearchGeneratedSourceReadGate.class), properties);

        service.reconcileDegradedFiles();
        assertThat(deliveryStatus(jdbc)).isEqualTo("DEGRADED");
        service.reconcileDegradedFiles();
        assertThat(deliveryStatus(jdbc)).isEqualTo("READY");
        verify(storage, times(2)).write(eq("export"), eq("artifact/recovered.md"), eq(markdown));

        byte[] source = "# Independent source".getBytes(StandardCharsets.UTF_8);
        String sourceDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        jdbc.update("""
                insert into artifact_file(id, artifact_version_id, file_format, file_name, media_type,
                                          storage_backend, bucket_name, object_key, size_bytes,
                                          checksum_sha256, status, error_message, file_role, variant, sequence_no)
                values ('source-file', 'version', 'MARKDOWN', 'source.md', 'text/markdown',
                        'local', 'export', 'artifact/source.md', ?, ?, 'READY', '',
                        'SOURCE_MD', '', 0)
                """, source.length, sourceDigest);
        jdbc.update("update artifact_version set delivery_status = 'DEGRADED' where id = 'version'");
        when(storage.read("export", "artifact/recovered.md")).thenReturn(markdown);
        when(storage.read("export", "artifact/source.md")).thenReturn(corrupted, source);
        when(workerExports.fetch("task", "source.md")).thenReturn(source);
        service.reconcileDegradedFiles();
        assertThat(deliveryStatus(jdbc)).isEqualTo("READY");
        verify(workerExports).fetch("task", "source.md");
        verify(storage).write(eq("export"), eq("artifact/source.md"), eq(source));

        jdbc.update("update artifact_version set delivery_status = 'DEGRADED' where id = 'version'");
        when(storage.read("export", "artifact/source.md")).thenReturn(corrupted);
        when(workerExports.fetch("task", "source.md"))
                .thenThrow(new IllegalStateException("Worker export expired"));
        service.reconcileDegradedFiles();
        assertThat(deliveryStatus(jdbc)).isEqualTo("DEGRADED");
    }

    private String deliveryStatus(JdbcTemplate jdbc) {
        return jdbc.queryForObject("select delivery_status from artifact_version where id = 'version'",
                String.class);
    }
}

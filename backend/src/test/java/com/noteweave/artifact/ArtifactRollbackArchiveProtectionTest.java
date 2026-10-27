package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import com.noteweave.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * NR07：已有 READY 归档不被回源失败破坏。
 * Worker 离线或其导出文件过期时：materializeExports 不再抓 Worker PDF，
 * 已发布版本的 READY 记录（key/长度/摘要）保持不变，回滚可从归档继续准备。
 */
class ArtifactRollbackArchiveProtectionTest {

    @Test
    void readyPdfArchiveIsNotTouchedWhenWorkerIsUnavailable() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:artifact-archive-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table artifact_job(id varchar(36) primary key, task_id varchar(36))");
        jdbc.execute("""
                create table artifact_version(
                    id varchar(36) primary key, artifact_job_id varchar(36), version_no int,
                    title varchar(100), content_markdown clob, result_payload_json clob,
                    origin_task_id varchar(36), delivery_status varchar(20),
                    created_at timestamp default current_timestamp)
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
        jdbc.execute("create table artifact_job_run(task_id varchar(36), input_snapshot_id varchar(36))");
        jdbc.execute("""
                create table artifact_run_input_snapshot(
                    id varchar(36) primary key, workspace_id varchar(36), user_requirement varchar(200),
                    source_scope_snapshot_json clob, upstream_refs_json clob)
                """);
        jdbc.update("insert into artifact_job(id, task_id) values ('job', 'task')");
        jdbc.update("""
                insert into artifact_version(id, artifact_job_id, version_no, title, content_markdown,
                                             result_payload_json, origin_task_id, delivery_status)
                values ('version', 'job', 1, 'Lesson', '# Lesson',
                        '{"export_trace":{"status":"COMPILED","format":"PDF","file_name":"lesson.pdf"}}',
                        'task', 'READY')
                """);
        jdbc.update("""
                insert into artifact_file(id, artifact_version_id, file_format, file_name, media_type,
                                          storage_backend, bucket_name, object_key, size_bytes,
                                          checksum_sha256, status, error_message, file_role, variant, sequence_no)
                values ('pdf-file', 'version', 'PDF', 'lesson.pdf', 'application/pdf',
                        'local', 'export', 'artifacts/job/v1/lesson.pdf', 1234,
                        'cafe1234', 'READY', '', 'PRIMARY_PDF', '', 0)
                """);

        ObjectStorage storage = mock(ObjectStorage.class);
        ArtifactWorkerExportClient workerExports = mock(ArtifactWorkerExportClient.class);
        // Worker 彻底不可用：任何抓取都失败——修复前会覆盖 READY 记录为 FAILED
        when(workerExports.fetch(anyString(), anyString()))
                .thenThrow(new IllegalStateException("worker offline"));
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

        service.materializeExports("version");

        // READY 归档不被触碰：无 Worker 抓取，记录保持原 key/长度/摘要/状态
        verify(workerExports, never()).fetch(anyString(), anyString());
        var row = jdbc.queryForMap(
                "select object_key, size_bytes, checksum_sha256, status from artifact_file where id = 'pdf-file'");
        assertThat(row.get("OBJECT_KEY")).isEqualTo("artifacts/job/v1/lesson.pdf");
        assertThat(row.get("SIZE_BYTES")).isEqualTo(1234L);
        assertThat(row.get("CHECKSUM_SHA256")).isEqualTo("cafe1234");
        assertThat(row.get("STATUS")).isEqualTo("READY");
    }
}

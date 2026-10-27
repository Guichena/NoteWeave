package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import com.noteweave.storage.ObjectStorage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * ART-02：DEGRADED 版本的文件补偿不能因为修不好的版本把批次占满。
 *
 * <p>旧实现每轮取「最早创建的 10 条 DEGRADED」，坏版本每轮都排在队头：
 * 只要坏版本攒到 10 条，后续新变 DEGRADED 的版本永远轮不到补偿。</p>
 */
class ArtifactFileReconcileBackoffTest {

    private JdbcTemplate jdbc;
    private ArtifactExportService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:artifact-reconcile-backoff-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa", ""));
        jdbc.execute("create table artifact_job(id varchar(36) primary key, task_id varchar(36))");
        jdbc.execute("""
                create table artifact_version(
                    id varchar(36) primary key, artifact_job_id varchar(36), version_no int,
                    title varchar(100), content_markdown clob, result_payload_json clob,
                    origin_task_id varchar(36), delivery_status varchar(20),
                    created_at timestamp default current_timestamp,
                    reconcile_attempts int not null default 0,
                    reconcile_next_attempt_at timestamp,
                    reconcile_exhausted_at timestamp)
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
        jdbc.update("insert into artifact_job(id, task_id) values ('job', 'task')");

        ObjectStorage storage = mock(ObjectStorage.class);
        byte[] markdown = "# Recovered".getBytes(StandardCharsets.UTF_8);
        when(storage.read("export", "artifact/good.md")).thenReturn(markdown);

        NoteWeaveProperties properties = mock(NoteWeaveProperties.class);
        NoteWeaveProperties.Storage storageConfig = mock(NoteWeaveProperties.Storage.class);
        NoteWeaveProperties.Minio minio = mock(NoteWeaveProperties.Minio.class);
        when(properties.storage()).thenReturn(storageConfig);
        when(storageConfig.minio()).thenReturn(minio);
        when(minio.bucketExport()).thenReturn("export");

        service = new ArtifactExportService(
                jdbc, new ObjectMapper(), storage, mock(ArtifactWorkerExportClient.class),
                mock(ArtifactSkillCatalogService.class), mock(ArtifactMemoryRevisionGuard.class),
                mock(ArtifactContextV2ShadowSnapshotService.class),
                mock(ResearchGeneratedSourceReadGate.class), properties);
    }

    @Test
    void unrepairableVersionsBackOffSoNewlyDegradedOnesStillGetReconciled() {
        // 10 条修不好的版本（缺少 READY 的 Markdown 文件）先入队，把旧实现的批次占满
        for (int index = 0; index < 10; index++) {
            insertVersion("broken-" + index, "DEGRADED", index);
        }
        // 第 11 条是可以修好的：文件行齐全，对象存储里的字节与摘要一致
        insertVersion("recoverable", "DEGRADED", 100);
        insertMarkdownFile("recoverable", "artifact/good.md", "# Recovered");

        service.reconcileDegradedFiles();

        // 第一批只处理了队头的 10 条坏版本：它们被推后并计数，可修好的那条还没轮到
        assertThat(deliveryStatus("broken-0")).isEqualTo("DEGRADED");
        assertThat(attempts("broken-0")).isEqualTo(1);
        assertThat(nextAttemptAt("broken-0")).isNotNull();
        assertThat(deliveryStatus("recoverable")).isEqualTo("DEGRADED");
        assertThat(attempts("recoverable")).isZero();

        // 下一轮：坏版本已退避，队列让出来，可修好的那条被补偿成功
        service.reconcileDegradedFiles();

        assertThat(deliveryStatus("recoverable")).isEqualTo("READY");
        assertThat(attempts("recoverable")).isZero();
        assertThat(deliveryStatus("broken-9")).isEqualTo("DEGRADED");
        assertThat(attempts("broken-9")).isEqualTo(1);
    }

    @Test
    void repeatedFailuresReachTerminalStateAndManualRetryPutsTheVersionBack() {
        ReflectionTestUtils.setField(service, "fileReconcileMaxAttempts", 2);
        insertVersion("broken", "DEGRADED", 0);

        service.reconcileDegradedFiles();
        assertThat(attempts("broken")).isEqualTo(1);
        assertThat(exhaustedAt("broken")).isNull();

        // 退避到期后仍然修不好，达到上限转终态
        jdbc.update("update artifact_version set reconcile_next_attempt_at = null where id = 'broken'");
        service.reconcileDegradedFiles();
        assertThat(attempts("broken")).isEqualTo(2);
        assertThat(exhaustedAt("broken")).isNotNull();

        Map<String, Long> metrics = service.fileReconcileMetrics();
        assertThat(metrics.get("degraded_exhausted")).isEqualTo(1L);
        assertThat(metrics.get("degraded_pending")).isZero();

        // 终态不再参与自动补偿：再跑一轮计数也不涨
        service.reconcileDegradedFiles();
        assertThat(attempts("broken")).isEqualTo(2);

        // 人工重驱把它放回队列
        assertThat(service.retryFileReconcile("broken")).isTrue();
        assertThat(attempts("broken")).isZero();
        assertThat(exhaustedAt("broken")).isNull();
        assertThat(service.fileReconcileMetrics().get("degraded_pending")).isEqualTo(1L);

        // 已不是 DEGRADED 的版本不接受重驱
        jdbc.update("update artifact_version set delivery_status = 'READY' where id = 'broken'");
        assertThat(service.retryFileReconcile("broken")).isFalse();
    }

    private void insertVersion(String id, String deliveryStatus, int createdAtOffsetSeconds) {
        jdbc.update("""
                insert into artifact_version(id, artifact_job_id, version_no, title, content_markdown,
                                             result_payload_json, origin_task_id, delivery_status, created_at)
                values (?, 'job', 1, ?, '# Recovered', '{}', 'task', ?,
                        dateadd('SECOND', ?, current_timestamp))
                """, id, id, deliveryStatus, createdAtOffsetSeconds);
    }

    private void insertMarkdownFile(String versionId, String objectKey, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        jdbc.update("""
                insert into artifact_file(id, artifact_version_id, file_format, file_name, media_type,
                                          storage_backend, bucket_name, object_key, size_bytes,
                                          checksum_sha256, status, error_message, file_role, variant, sequence_no)
                values (?, ?, 'MARKDOWN', 'good.md', 'text/markdown', 'local', 'export', ?, ?, ?,
                        'READY', '', 'PRIMARY_MARKDOWN', '', 0)
                """, "file-" + versionId, versionId, objectKey, bytes.length, sha256(bytes));
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String deliveryStatus(String versionId) {
        return jdbc.queryForObject(
                "select delivery_status from artifact_version where id = ?", String.class, versionId);
    }

    private int attempts(String versionId) {
        Integer value = jdbc.queryForObject(
                "select reconcile_attempts from artifact_version where id = ?", Integer.class, versionId);
        return value == null ? 0 : value;
    }

    private Object nextAttemptAt(String versionId) {
        return jdbc.queryForObject(
                "select reconcile_next_attempt_at from artifact_version where id = ?",
                Object.class, versionId);
    }

    private Object exhaustedAt(String versionId) {
        return jdbc.queryForObject(
                "select reconcile_exhausted_at from artifact_version where id = ?",
                Object.class, versionId);
    }
}

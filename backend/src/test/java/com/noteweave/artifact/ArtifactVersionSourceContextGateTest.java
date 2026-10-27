package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import com.noteweave.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * ART-03：来源上下文查不到时不能默认放行。
 *
 * <p>旧实现里「查不到冻结输入快照」直接 return，于是快照被清理之后，
 * 来源已经全部撤销的旧版本仍然可以下载。这里区分两种情况：
 * 早于快照机制的存量版本（没有 input_snapshot_id）继续放行；
 * 冻结过快照但快照已消失的版本拒绝，并在版本列表里隐藏。</p>
 */
class ArtifactVersionSourceContextGateTest {

    private JdbcTemplate jdbc;
    private ArtifactExportService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:artifact-source-context-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa", ""));
        jdbc.execute("create table artifact_job(id varchar(36) primary key, workspace_id varchar(36), task_id varchar(36))");
        jdbc.execute("""
                create table artifact_version(
                    id varchar(36) primary key, artifact_job_id varchar(36), version_no int,
                    title varchar(100), content_markdown clob, result_payload_json clob,
                    origin_task_id varchar(36), delivery_status varchar(20),
                    created_at timestamp default current_timestamp,
                    reconcile_attempts int not null default 0,
                    reconcile_next_attempt_at timestamp, reconcile_exhausted_at timestamp)
                """);
        jdbc.execute("""
                create table artifact_job_run(
                    task_id varchar(36), artifact_job_id varchar(36), input_snapshot_id varchar(36))
                """);
        jdbc.execute("""
                create table artifact_run_input_snapshot(
                    id varchar(36) primary key, workspace_id varchar(36), user_requirement clob,
                    source_scope_snapshot_json clob, upstream_refs_json clob)
                """);
        jdbc.update("insert into artifact_job(id, workspace_id, task_id) values ('job', 'ws', 'task')");
        jdbc.update("""
                insert into artifact_version(id, artifact_job_id, version_no, title, content_markdown,
                                             result_payload_json, origin_task_id, delivery_status)
                values ('version', 'job', 1, 'Report', '# Report', '{}', 'task', 'READY')
                """);

        NoteWeaveProperties properties = mock(NoteWeaveProperties.class);
        NoteWeaveProperties.Storage storageConfig = mock(NoteWeaveProperties.Storage.class);
        NoteWeaveProperties.Minio minio = mock(NoteWeaveProperties.Minio.class);
        org.mockito.Mockito.when(properties.storage()).thenReturn(storageConfig);
        org.mockito.Mockito.when(storageConfig.minio()).thenReturn(minio);
        org.mockito.Mockito.when(minio.bucketExport()).thenReturn("export");

        service = new ArtifactExportService(
                jdbc, new ObjectMapper(), mock(ObjectStorage.class),
                mock(ArtifactWorkerExportClient.class), mock(ArtifactSkillCatalogService.class),
                mock(ArtifactMemoryRevisionGuard.class),
                mock(ArtifactContextV2ShadowSnapshotService.class),
                mock(ResearchGeneratedSourceReadGate.class), properties);
    }

    @Test
    void versionWhoseFrozenSnapshotDisappearedIsNoLongerReadableOrListed() {
        // 冻结过快照，但快照行已经不在了：依赖是否仍然有效无从验证
        jdbc.update("""
                insert into artifact_job_run(task_id, artifact_job_id, input_snapshot_id)
                values ('task', 'job', 'snapshot-deleted')
                """);

        assertThatThrownBy(() -> service.requireVersionReadable("ws", "job", 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("ARTIFACT_SOURCE_CONTEXT_MISSING"));
        assertThat(service.versionVisibleForListing("ws", "job", 1)).isFalse();
    }

    @Test
    void legacyRunWithoutFrozenSnapshotStaysReadable() {
        // 早于快照机制的存量记录：没有 input_snapshot_id，保持历史行为
        jdbc.update("""
                insert into artifact_job_run(task_id, artifact_job_id, input_snapshot_id)
                values ('task', 'job', null)
                """);

        assertThatCode(() -> service.requireVersionReadable("ws", "job", 1))
                .doesNotThrowAnyException();
        assertThat(service.versionVisibleForListing("ws", "job", 1)).isTrue();
    }

    @Test
    void versionWithoutAnyRunRowStaysReadable() {
        assertThatCode(() -> service.requireVersionReadable("ws", "job", 1))
                .doesNotThrowAnyException();
    }
}

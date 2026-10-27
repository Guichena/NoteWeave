package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import com.noteweave.storage.ObjectStorage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ART-01：完成回执的 prepare 段（校验 + staged 写对象）必须在数据库事务外，
 * 提交事务里只写 artifact_file 元数据，不再出现任何存储调用。
 */
class ArtifactCompletionStagingBoundaryTest {

    private static final String TASK_ID = "task-1";
    private static final String VERSION_ID = "version-1";

    private JdbcTemplate jdbc;
    private ProbeStorage storage;
    private ArtifactExportService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:artifact-staging-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        jdbc.update("insert into artifact_job(id, task_id, skill_key) values ('job-1', ?, 'course_note')",
                TASK_ID);
        jdbc.update("""
                insert into artifact_job_run(task_id, artifact_job_id, reserved_version_id)
                values (?, 'job-1', ?)
                """, TASK_ID, VERSION_ID);
        jdbc.update("""
                insert into artifact_version(id, artifact_job_id, version_no, title, content_markdown,
                                             result_payload_json, origin_task_id)
                values (?, 'job-1', 1, '讲义', '# 讲义', '{}', ?)
                """, VERSION_ID, TASK_ID);

        storage = new ProbeStorage();
        ArtifactSkillCatalogService skillCatalog = mock(ArtifactSkillCatalogService.class);
        when(skillCatalog.requiredFileRoles("course_note")).thenReturn(List.of("PRIMARY_MARKDOWN"));
        NoteWeaveProperties properties = mock(NoteWeaveProperties.class);
        NoteWeaveProperties.Storage storageConfig = mock(NoteWeaveProperties.Storage.class);
        NoteWeaveProperties.Minio minio = mock(NoteWeaveProperties.Minio.class);
        when(properties.storage()).thenReturn(storageConfig);
        when(storageConfig.minio()).thenReturn(minio);
        when(minio.bucketExport()).thenReturn("export");
        service = new ArtifactExportService(
                jdbc,
                new ObjectMapper(),
                storage,
                mock(ArtifactWorkerExportClient.class),
                skillCatalog,
                mock(ArtifactMemoryRevisionGuard.class),
                mock(ArtifactContextV2ShadowSnapshotService.class),
                mock(ResearchGeneratedSourceReadGate.class),
                properties);
    }

    @Test
    void stagingRunsOutsideTransactionAndCommitOnlyWritesMetadata() throws Exception {
        byte[] markdown = "# 讲义\n\n正文".getBytes(StandardCharsets.UTF_8);
        List<ArtifactExportService.PreparedFile> files = List.of(
                new ArtifactExportService.PreparedFile("MARKDOWN", "lesson.md",
                        "text/markdown; charset=UTF-8", markdown, sha256(markdown)));

        List<ArtifactExportService.StagedFile> staged =
                service.prepareCompletionStagedFiles(TASK_ID, files);

        assertThat(storage.writeSawTransaction).isFalse();
        assertThat(staged).hasSize(1);
        assertThat(staged.get(0).objectKey()).contains(VERSION_ID).contains("PRIMARY_MARKDOWN");

        // 没有事务时不允许提交，避免文件元数据脱离版本事务单独落库
        assertThatThrownBy(() -> service.commitStagedFiles(VERSION_ID, staged))
                .isInstanceOf(IllegalStateException.class);

        int writesBefore = storage.writes;
        new TransactionTemplate(new DataSourceTransactionManager(dataSource()))
                .execute(status -> {
                    service.commitStagedFiles(VERSION_ID, staged);
                    return null;
                });

        assertThat(storage.writes).isEqualTo(writesBefore);
        assertThat(jdbc.queryForObject("""
                select count(*) from artifact_file where artifact_version_id = ? and status = 'READY'
                """, Integer.class, VERSION_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select object_key from artifact_file where artifact_version_id = ?
                """, String.class, VERSION_ID)).isEqualTo(staged.get(0).objectKey());
    }

    private javax.sql.DataSource dataSource() {
        return jdbc.getDataSource();
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private void createSchema() {
        jdbc.execute("""
                create table artifact_job(
                    id varchar(36) primary key,
                    task_id varchar(36),
                    skill_key varchar(64)
                )
                """);
        jdbc.execute("""
                create table artifact_job_run(
                    task_id varchar(36) primary key,
                    artifact_job_id varchar(36) not null,
                    reserved_version_id varchar(36)
                )
                """);
        jdbc.execute("""
                create table artifact_version(
                    id varchar(36) primary key,
                    artifact_job_id varchar(36) not null,
                    version_no int not null,
                    title varchar(300),
                    content_markdown clob,
                    result_payload_json clob,
                    origin_task_id varchar(36)
                )
                """);
        jdbc.execute("""
                create table artifact_file(
                    id varchar(36) primary key,
                    artifact_version_id varchar(36) not null,
                    file_format varchar(32) not null,
                    file_role varchar(32),
                    variant varchar(64),
                    sequence_no int,
                    file_name varchar(300),
                    media_type varchar(160),
                    storage_backend varchar(32),
                    bucket_name varchar(120),
                    object_key varchar(500),
                    size_bytes bigint,
                    checksum_sha256 varchar(64),
                    status varchar(32),
                    error_message varchar(1000)
                )
                """);
    }

    /** 记录「写对象」发生时是否处在事务里。 */
    private static final class ProbeStorage implements ObjectStorage {
        private final Map<String, byte[]> objects = new HashMap<>();
        boolean writeSawTransaction;
        int writes;

        @Override
        public Path write(String bucket, String objectKey, byte[] content) {
            writeSawTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            writes++;
            objects.put(objectKey, content);
            return null;
        }

        @Override
        public byte[] read(String bucket, String objectKey) {
            byte[] content = objects.get(objectKey);
            if (content == null) throw new IllegalStateException("missing object " + objectKey);
            return content;
        }

        @Override
        public InputStream openStream(String bucket, String objectKey) {
            return new java.io.ByteArrayInputStream(read(bucket, objectKey));
        }

        @Override
        public boolean exists(String bucket, String objectKey) {
            return objects.containsKey(objectKey);
        }

        @Override
        public void delete(String bucket, String objectKey) {
            objects.remove(objectKey);
        }

        @Override
        public List<StoredObject> list(String bucket, String prefix, String afterKey, int limit) {
            return List.of();
        }

        @Override
        public String backendName() {
            return "probe";
        }
    }
}

package com.noteweave.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.source.SourceCatalogVersionService;
import com.noteweave.source.SourceMessagingMode;
import com.noteweave.source.SourceParsePort;
import com.noteweave.source.SourceTopicNames;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.task.TaskCommandPort;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * UP-01：完成上传时，合成、整对象 SHA-256 与内容安全校验必须在事务外完成，
 * 只有落库与对象复制在事务内，避免长时间持有 document_upload 行锁。
 */
class UploadCompletionTransactionBoundaryTest {

    private static final String WORKSPACE = "workspace-1";
    private static final String UPLOAD = "upload-1";

    private JdbcTemplate jdbcTemplate;
    private ProbeStorage storage;
    private UploadSecurityPolicy securityPolicy;
    private UploadService uploadService;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:upload-boundary-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();
        storage = new ProbeStorage();

        WorkspaceAccessGuard accessGuard = mock(WorkspaceAccessGuard.class);
        SourceParsePort sourceParsePort = mock(SourceParsePort.class);
        TaskCommandPort taskCommandPort = mock(TaskCommandPort.class);
        when(taskCommandPort.createTask(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("task-1");
        SourceMessagingMode messagingMode = mock(SourceMessagingMode.class);
        when(messagingMode.isAsyncEnabled()).thenReturn(true);
        securityPolicy = mock(UploadSecurityPolicy.class);
        AuditActorProvider actorProvider = mock(AuditActorProvider.class);
        when(actorProvider.currentOrSystem(anyString())).thenReturn("actor");
        SourceCatalogVersionService catalogVersionService = mock(SourceCatalogVersionService.class);
        UploadTempObjectCleanupService cleanupService = mock(UploadTempObjectCleanupService.class);
        SourceTopicNames topicNames = mock(SourceTopicNames.class);
        when(topicNames.parse()).thenReturn("source.parse");

        uploadService = new UploadService(
                jdbcTemplate,
                accessGuard,
                storage,
                sourceParsePort,
                taskCommandPort,
                new ObjectMapper(),
                messagingMode,
                securityPolicy,
                actorProvider,
                catalogVersionService,
                cleanupService,
                topicNames);
    }

    @Test
    void heavyWorkRunsOutsideTransactionAndOnlyPersistenceHoldsIt() {
        seedUpload();

        CompleteUploadResponse response = uploadService.completeUpload(UPLOAD);

        assertThat(response.sourceId()).isNotBlank();
        assertThat(storage.composeSawTransaction).isFalse();
        assertThat(storage.copySawTransaction).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select status from document_upload where id = ?", String.class, UPLOAD))
                .isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from source where workspace_id = ?", Integer.class, WORKSPACE))
                .isEqualTo(1);
    }

    @Test
    void repeatedCompletionReusesTheSameSource() {
        seedUpload();

        String first = uploadService.completeUpload(UPLOAD).sourceId();
        String second = uploadService.completeUpload(UPLOAD).sourceId();

        assertThat(second).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from source where workspace_id = ?", Integer.class, WORKSPACE))
                .isEqualTo(1);
    }

    @Test
    void rejectedMergedFileLeavesNoTemporaryObjectAndKeepsUploadRetryable() {
        seedUpload();
        doThrow(new IllegalStateException("内容不合规"))
                .when(securityPolicy).validateMergedFile(anyString(), anyLong(), any(Path.class));

        assertThatThrownBy(() -> uploadService.completeUpload(UPLOAD))
                .isInstanceOf(IllegalStateException.class);

        assertThat(storage.exists("noteweave-source",
                "workspace/" + WORKSPACE + "/upload_tmp/" + UPLOAD + "/merged")).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "select status from document_upload where id = ?", String.class, UPLOAD))
                .isEqualTo("UPLOADING");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from source where workspace_id = ?", Integer.class, WORKSPACE))
                .isZero();
    }

    private void seedUpload() {
        byte[] content = "hello noteweave".getBytes(StandardCharsets.UTF_8);
        storage.put("workspace/" + WORKSPACE + "/upload_tmp/" + UPLOAD + "/0", content);
        jdbcTemplate.update("""
                insert into document_upload(
                    id, workspace_id, file_name, file_size, mime_type, chunk_size, total_chunks,
                    status, uploaded_chunks, expected_sha256, updated_at, created_by, updated_by
                ) values (?, ?, 'a.txt', ?, 'text/plain', 1024, 1, 'UPLOADING', 1, null, ?, 'actor', 'actor')
                """, UPLOAD, WORKSPACE, (long) content.length,
                java.sql.Timestamp.from(Instant.now()));
        jdbcTemplate.update("""
                insert into upload_chunk(id, upload_id, chunk_index, object_key, byte_size)
                values ('chunk-1', ?, 0, ?, ?)
                """, UPLOAD, "workspace/" + WORKSPACE + "/upload_tmp/" + UPLOAD + "/0", content.length);
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                create table document_upload(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    file_name varchar(300) not null,
                    file_size bigint not null,
                    mime_type varchar(160),
                    chunk_size int not null,
                    total_chunks int not null,
                    status varchar(32) not null,
                    uploaded_chunks int not null default 0,
                    source_id varchar(36),
                    task_id varchar(36),
                    expected_sha256 varchar(64),
                    created_at timestamp not null default current_timestamp,
                    updated_at timestamp not null default current_timestamp,
                    created_by varchar(80) not null default 'SYSTEM',
                    updated_by varchar(80) not null default 'SYSTEM'
                )
                """);
        jdbcTemplate.execute("""
                create table upload_chunk(
                    id varchar(36) primary key,
                    upload_id varchar(36) not null,
                    chunk_index int not null,
                    content_md5 varchar(128),
                    object_key varchar(500) not null,
                    byte_size bigint not null,
                    created_at timestamp not null default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table file_object(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    object_key varchar(500) not null,
                    sha256 varchar(64) not null,
                    file_size bigint not null,
                    mime_type varchar(160),
                    ref_count int not null
                )
                """);
        jdbcTemplate.execute("""
                create table source(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    file_object_id varchar(36),
                    title varchar(300) not null,
                    source_type varchar(32) not null,
                    status varchar(32) not null,
                    parse_status varchar(32) not null,
                    index_status varchar(32) not null,
                    created_by varchar(80),
                    updated_by varchar(80)
                )
                """);
        jdbcTemplate.execute("""
                create table source_snapshot(
                    id varchar(36) primary key,
                    source_id varchar(36) not null,
                    file_object_id varchar(36),
                    version_no int not null,
                    object_key varchar(500),
                    sha256 varchar(64),
                    parse_status varchar(32) not null,
                    index_status varchar(32) not null
                )
                """);
        jdbcTemplate.execute("""
                create table task_outbox(
                    id varchar(36) primary key,
                    task_id varchar(36) not null,
                    topic varchar(120) not null,
                    message_key varchar(120),
                    payload_json clob not null,
                    status varchar(32) not null
                )
                """);
    }

    /** 记录「合成」与「复制」发生时是否处在事务里，用来验证事务边界。 */
    private static final class ProbeStorage implements ObjectStorage {
        private final Map<String, byte[]> objects = new HashMap<>();
        boolean composeSawTransaction;
        boolean copySawTransaction;

        void put(String key, byte[] content) {
            objects.put(key, content);
        }

        @Override
        public Path write(String bucket, String objectKey, byte[] content) {
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
        public void compose(String sourceBucket, List<String> sourceKeys, String targetBucket, String targetKey) {
            composeSawTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            for (String key : sourceKeys) {
                buffer.writeBytes(read(sourceBucket, key));
            }
            objects.put(targetKey, buffer.toByteArray());
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
        public void copy(String sourceBucket, String sourceKey, String targetBucket, String targetKey) {
            copySawTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            objects.put(targetKey, read(sourceBucket, sourceKey));
        }

        @Override
        public String backendName() {
            return "probe";
        }
    }
}

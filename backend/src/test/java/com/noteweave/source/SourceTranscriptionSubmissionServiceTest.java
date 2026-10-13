package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.task.TaskCommandPort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class SourceTranscriptionSubmissionServiceTest {
    private JdbcTemplate jdbc;
    private ObjectStorage storage;
    private SourceTranscriptionPort port;
    private SourceTranscriptionSubmissionService service;
    private DataSourceTransactionManager manager;
    private String sha;
    private final byte[] media = "真实替身媒体字节".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:transcription-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        jdbc.execute("""
                create table source(id varchar(36) primary key, workspace_id varchar(36), title varchar(300),
                    source_type varchar(64), status varchar(32), updated_at timestamp default current_timestamp)
                """);
        jdbc.execute("""
                create table file_object(id varchar(36) primary key, mime_type varchar(160))
                """);
        jdbc.execute("""
                create table source_snapshot(id varchar(36) primary key, source_id varchar(36), file_object_id varchar(36),
                    version_no int, parse_status varchar(32), processing_stage varchar(32), object_key varchar(500),
                    sha256 varchar(64))
                """);
        jdbc.execute("""
                create table task(id varchar(36) primary key, workspace_id varchar(36), target_id varchar(36),
                    task_type varchar(64), task_status varchar(32), created_at timestamp default current_timestamp)
                """);
        jdbc.execute("alter table source_snapshot add column index_status varchar(32) default 'PENDING'");
        jdbc.execute("alter table task add column target_type varchar(32) default 'SOURCE'");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V149__source_transcription_submission.sql"))
                .execute(dataSource);
        sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(media));
        jdbc.update("insert into source(id, workspace_id, title, source_type, status) values ('src', 'ws', '音频.mp3', 'AUDIO', 'PROCESSING')");
        jdbc.update("insert into file_object values ('file', 'audio/mpeg')");
        jdbc.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, parse_status, processing_stage,
                    object_key, sha256) values ('snap', 'src', 'file', 1, 'PENDING', 'TRANSCRIBING', 'original', ?)
                """, sha);
        jdbc.update("insert into task(id, workspace_id, target_id, task_type, task_status) values ('task', 'ws', 'src', 'SOURCE_PARSE', 'RUNNING')");
        storage = mock(ObjectStorage.class);
        port = mock(SourceTranscriptionPort.class);
        doAnswer(invocation -> { Files.write(invocation.getArgument(2, Path.class), media); return null; })
                .when(storage).readToFile(eq("noteweave-source"), eq("original"), any(Path.class));
        service = new SourceTranscriptionSubmissionService(jdbc, storage, port, manager, 4, 300, 180);
    }

    private SourceTranscriptionPort.Request request() {
        return new SourceTranscriptionPort.Request("ws", "src", "snap", "音频.mp3", "audio/mpeg", "task");
    }

    private void enqueue() {
        new TransactionTemplate(manager).executeWithoutResult(ignored -> service.enqueue(request(), "original", sha));
    }

    private String status() {
        return jdbc.queryForObject("select status from source_transcription_submission where task_id = 'task'", String.class);
    }

    private void makeDue() {
        jdbc.update("update source_transcription_submission set next_retry_at = ?", Timestamp.from(Instant.now().minusSeconds(1)));
    }

    @Test
    void queueFullPersistsBackoffAndKeepsTranscribingThenRetriesSuccessfully() {
        enqueue();
        var entered = jdbc.queryForObject("select updated_at from source where id = 'src'", Timestamp.class);
        doThrow(new BusinessException("SOURCE_TRANSCRIPTION_QUEUE_FULL", "队列暂满", HttpStatus.TOO_MANY_REQUESTS))
                .doNothing().when(port).submit(any(), any());
        service.dispatchPending();
        assertThat(status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select next_retry_at from source_transcription_submission", Timestamp.class).toInstant())
                .isAfter(Instant.now()).isBefore(Instant.now().plusSeconds(301));
        assertThat(jdbc.queryForObject("select last_error_code from source_transcription_submission", String.class))
                .isEqualTo("SOURCE_TRANSCRIPTION_QUEUE_FULL");
        assertThat(jdbc.queryForObject("select processing_stage from source_snapshot", String.class)).isEqualTo("TRANSCRIBING");
        assertThat(jdbc.queryForObject("select updated_at from source where id = 'src'", Timestamp.class)).isEqualTo(entered);
        service.dispatchPending();
        verify(port, times(1)).submit(any(), any());
        makeDue();
        service.dispatchPending();
        assertThat(status()).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("select attempt_count from source_transcription_submission", Integer.class)).isEqualTo(2);
        verify(port, times(2)).submit(eq(request()), any());
    }

    @Test
    void temporaryServiceFailureAlsoUsesDurableRetry() {
        enqueue();
        doThrow(new BusinessException("SOURCE_TRANSCRIPTION_SUBMIT_FAILED", "服务暂不可用"))
                .when(port).submit(any(), any());
        service.dispatchPending();
        assertThat(status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select last_error_code from source_transcription_submission", String.class))
                .isEqualTo("SOURCE_TRANSCRIPTION_SUBMIT_FAILED");
    }

    @Test
    void processRestartCanResumePendingSubmission() {
        enqueue();
        new SourceTranscriptionSubmissionService(jdbc, storage, port, manager, 4, 300, 180).dispatchPending();
        assertThat(status()).isEqualTo("SUBMITTED");
        verify(port).submit(eq(request()), any());
    }

    @Test
    void liveLeaseIsNotReclaimedButExpiredLeaseIs() {
        enqueue();
        jdbc.update("update source_transcription_submission set status='SUBMITTING', lease_token='old', lease_until=?",
                Timestamp.from(Instant.now().plusSeconds(30)));
        service.dispatchPending();
        verifyNoInteractions(port);
        jdbc.update("update source_transcription_submission set lease_until=?", Timestamp.from(Instant.now().minusSeconds(1)));
        service.dispatchPending();
        assertThat(status()).isEqualTo("SUBMITTED");
    }

    @Test
    void newerTaskCancelsOldSubmissionAndRejectsOldCallback() {
        enqueue();
        jdbc.update("""
                insert into task(id, workspace_id, target_id, task_type, task_status, created_at)
                values ('new-task', 'ws', 'src', 'SOURCE_PARSE', 'RUNNING', ?)
                """,
                Timestamp.from(Instant.now().plusSeconds(1)));
        service.dispatchPending();
        assertThat(status()).isEqualTo("CANCELLED");
        assertThat(service.acceptsCallback("snap", "task")).isFalse();
        assertThat(service.acceptsCallback("snap", null)).isFalse();
        verifyNoInteractions(port, storage);
    }

    @Test
    void newerSnapshotCancelsSubmissionWithoutReadingOriginal() {
        enqueue();
        jdbc.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, parse_status, processing_stage,
                    object_key, sha256) values ('new-snap', 'src', 'file', 2, 'PENDING', null, 'new-original', ?)
                """, sha);
        service.dispatchPending();
        assertThat(status()).isEqualTo("CANCELLED");
        verifyNoInteractions(port, storage);
    }

    @Test
    void deletedSourceAndWrongWorkspaceCannotBeSubmitted() {
        enqueue();
        jdbc.update("update source set status='DELETED'");
        service.dispatchPending();
        assertThat(status()).isEqualTo("CANCELLED");
        verifyNoInteractions(port, storage);
    }

    @Test
    void taskWorkspaceMismatchCancelsSubmission() {
        enqueue();
        jdbc.update("update task set workspace_id='other'");
        service.dispatchPending();
        assertThat(status()).isEqualTo("CANCELLED");
        verifyNoInteractions(port, storage);
    }

    @Test
    void changedOriginalBytesAreNotSent() throws Exception {
        enqueue();
        doAnswer(invocation -> { Files.writeString(invocation.getArgument(2, Path.class), "错误原件"); return null; })
                .when(storage).readToFile(any(), any(), any());
        service.dispatchPending();
        verifyNoInteractions(port);
        assertThat(status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select last_error_code from source_transcription_submission", String.class))
                .isEqualTo("SOURCE_TRANSCRIPTION_ORIGINAL_MISMATCH");
    }

    @Test
    void quickCallbackCompletesLedgerBeforeSubmitReturnsWithoutBeingOverwritten() {
        enqueue();
        doAnswer(invocation -> {
            assertThat(jdbc.queryForObject("select processing_stage from source_snapshot", String.class)).isEqualTo("TRANSCRIBING");
            assertThat(service.acceptsCallback("snap", "task")).isTrue();
            assertThat(service.acceptsCallback("snap", "old-task")).isFalse();
            service.completeCallback("snap", "task");
            return null;
        }).when(port).submit(any(), any());
        service.dispatchPending();
        assertThat(status()).isEqualTo("COMPLETED");
    }

    @Test
    void oldLeaseCannotOverwriteCompletion() {
        enqueue();
        doAnswer(invocation -> {
            service.completeCallback("snap", "task");
            throw new BusinessException("SOURCE_TRANSCRIPTION_SUBMIT_FAILED", "响应丢失");
        }).when(port).submit(any(), any());
        service.dispatchPending();
        assertThat(status()).isEqualTo("COMPLETED");
    }

    @Test
    void legacyCallbackWithoutSubmissionRecordRemainsCompatible() {
        assertThat(service.acceptsCallback("snap", null)).isTrue();
    }

    @Test
    void cancelledFailedAndForeignTasksCannotAcceptCallbacks() {
        enqueue();
        assertThat(service.acceptsCallback("snap", "task")).isTrue();
        for (String status : java.util.List.of("CANCELLED", "FAILED", "COMPLETED")) {
            jdbc.update("update task set task_status=?", status);
            assertThat(service.acceptsCallback("snap", "task")).isFalse();
        }
        jdbc.update("update task set task_status='RUNNING', workspace_id='other'");
        assertThat(service.acceptsCallback("snap", "task")).isFalse();
        jdbc.update("update task set workspace_id='ws', target_type='ARTIFACT'");
        assertThat(service.acceptsCallback("snap", "task")).isFalse();
    }

    @Test
    void callbackRequiresMatchingCurrentTaskAndFreezesCompletion() {
        enqueue();
        var parse = mock(SourceParseService.class);
        var failures = mock(SourceParseFailureFinalizer.class);
        var callbacks = new SourceTranscriptionService(jdbc, parse, failures, 180);
        callbacks.setSubmissions(service);
        var old = new SourceTranscriptionService.TranscriptionCallback("ws", "src", "COMPLETED", "文字稿",
                "zh", 1.0, 1, 0, null, null, "old-task");
        assertThat(callbacks.handle("snap", old)).isFalse();
        verifyNoInteractions(parse, failures);
        when(parse.acceptTranscript("ws", "src", "snap", "文字稿", 1.0, 0)).thenReturn(true);
        var current = new SourceTranscriptionService.TranscriptionCallback("ws", "src", "COMPLETED", "文字稿",
                "zh", 1.0, 1, 0, null, null, "task");
        assertThat(callbacks.handle("snap", current)).isTrue();
        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(callbacks.handle("snap", current)).isFalse();
        verify(parse, times(1)).acceptTranscript("ws", "src", "snap", "文字稿", 1.0, 0);
    }

    @Test
    void wrongWorkspaceAndOlderSnapshotCallbackAreDiscarded() {
        enqueue();
        var parse = mock(SourceParseService.class);
        var failures = mock(SourceParseFailureFinalizer.class);
        var callbacks = new SourceTranscriptionService(jdbc, parse, failures, 180);
        callbacks.setSubmissions(service);
        assertThat(callbacks.handle("snap", new SourceTranscriptionService.TranscriptionCallback("other", "src",
                "COMPLETED", "文字稿", null, null, null, null, null, null, "task"))).isFalse();
        jdbc.update("""
                insert into source_snapshot(id, source_id, version_no, parse_status)
                values ('new-snap', 'src', 2, 'PENDING')
                """);
        assertThat(callbacks.handle("snap", new SourceTranscriptionService.TranscriptionCallback("ws", "src",
                "COMPLETED", "文字稿", null, null, null, null, null, null, "task"))).isFalse();
        verifyNoInteractions(parse, failures);
    }

    @Test
    void callbackControllerExplicitlyAcknowledgesDiscardedOldTask() {
        var callbacks = mock(SourceTranscriptionService.class);
        var controller = new SourceTranscriptionCallbackController(callbacks);
        var callback = new SourceTranscriptionService.TranscriptionCallback("ws", "src", "FAILED", null,
                null, null, null, null, "ERROR", "识别失败", "old-task");
        assertThat(controller.complete("snap", callback).data()).containsEntry("accepted", false).containsEntry("discarded", true);
    }

    @Test
    void invalidLeaseOrBatchFailsConfiguration() {
        assertThatThrownBy(() -> new SourceTranscriptionSubmissionService(jdbc, storage, port, manager, 0, 300, 180))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceTranscriptionSubmissionService(jdbc, storage, port, manager, 4, 180, 180))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mediaParseCommitsQueueAndStageTogetherBeforeAnyWorkerCall() {
        jdbc.update("update source_snapshot set processing_stage='EXTRACTING'");
        var messaging = mock(SourceMessagingMode.class);
        when(messaging.isAsyncEnabled()).thenReturn(true);
        var parse = new SourceParseService(jdbc, mock(DocumentChunker.class), mock(SourceDocumentTextExtractor.class),
                new ObjectMapper(), storage, messaging,
                new NoteWeaveProperties(null, null, null, null,
                        new NoteWeaveProperties.Elasticsearch(false, "localhost", 9200, "http", "test"), null),
                mock(TaskCommandPort.class), manager, mock(AuditActorProvider.class),
                mock(SourceCatalogVersionService.class), mock(ApplicationEventPublisher.class));
        parse.setTranscriptionSubmissions(service);
        assertThat(parse.parseAndIndexAsyncIfProcessable("ws", "src", "snap")).isTrue();
        assertThat(status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select processing_stage from source_snapshot", String.class)).isEqualTo("TRANSCRIBING");
        verifyNoInteractions(storage, port);
    }

    @Test
    void enqueueFailureRollsBackStageChange() {
        jdbc.update("update source_snapshot set processing_stage='EXTRACTING'");
        var messaging = mock(SourceMessagingMode.class);
        when(messaging.isAsyncEnabled()).thenReturn(true);
        var parse = new SourceParseService(jdbc, mock(DocumentChunker.class), mock(SourceDocumentTextExtractor.class),
                new ObjectMapper(), storage, messaging,
                new NoteWeaveProperties(null, null, null, null,
                        new NoteWeaveProperties.Elasticsearch(false, "localhost", 9200, "http", "test"), null),
                mock(TaskCommandPort.class), manager, mock(AuditActorProvider.class),
                mock(SourceCatalogVersionService.class), mock(ApplicationEventPublisher.class));
        parse.setTranscriptionSubmissions(service);
        jdbc.update("delete from task");
        assertThatThrownBy(() -> parse.parseAndIndexAsyncIfProcessable("ws", "src", "snap"))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("select processing_stage from source_snapshot", String.class)).isEqualTo("EXTRACTING");
        assertThat(jdbc.queryForObject("select count(*) from source_transcription_submission", Integer.class)).isZero();
    }
}

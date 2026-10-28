package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import com.noteweave.source.SourceFrameService;
import com.noteweave.worker.WorkerSourceScopeItemResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ArtifactTaskFrameServiceTest {
    private JdbcTemplate jdbc;
    private ArtifactJobService jobs;
    private SourceFrameService frames;
    private ArtifactTaskFrameService service;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table source(id varchar, workspace_id varchar, status varchar)");
        jdbc.execute("create table source_snapshot(id varchar, source_id varchar, version_no int, parse_status varchar)");
        jdbc.execute("create table source_media_frame(workspace_id varchar, source_id varchar, source_snapshot_id varchar, frame_id varchar)");
        jdbc.update("insert into source values ('source', 'workspace', 'READY'), ('foreign', 'elsewhere', 'READY')");
        jdbc.update("insert into source_snapshot values ('snapshot', 'source', 1, 'PARSED'), ('other', 'foreign', 1, 'PARSED')");
        jdbc.update("insert into source_media_frame values ('workspace', 'source', 'snapshot', 'frame'), ('elsewhere', 'foreign', 'other', 'foreign-frame')");
        jobs = mock(ArtifactJobService.class);
        frames = mock(SourceFrameService.class);
        when(jobs.getWorkerInput("task")).thenReturn(input("FULL"));
        when(frames.readFrame("workspace", "source", "frame")).thenReturn(new SourceFrameService.FrameImage(new byte[]{1, 2}, "image/png"));
        service = new ArtifactTaskFrameService(jobs, jdbc, frames);
    }

    private ArtifactWorkerInputResponse input(String replay) {
        var source = new WorkerSourceScopeItemResponse("source", "视频", "", "", "snapshot", "", "", "", "VIDEO", "", Map.of());
        return new ArtifactWorkerInputResponse("task", "workspace", "artifact", "input", "digest", replay,
                List.of(source), List.of(), null, null, null, null);
    }

    @AfterEach
    void close() { jdbc.execute("shutdown"); }

    @Test
    void readsFrozenFrameAndPreservesMediaType() {
        var guard = mock(ArtifactWorkerDeliveryGuard.class);
        var response = new ArtifactTaskFrameController(service, guard).readFrame("task", "frame", "token");
        verify(guard).requireDelivery("task", "token");
        assertThat(response.getBody()).containsExactly(1, 2);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(jobs, times(2)).getWorkerInput("task");
    }

    @Test
    void rejectsForeignOrMissingFrameBeforeReadingStorage() {
        assertThatThrownBy(() -> service.readFrame("task", "foreign-frame")).hasMessageContaining("冻结资料");
        assertThatThrownBy(() -> service.readFrame("task", "missing")).hasMessageContaining("冻结资料");
        verifyNoInteractions(frames);
    }

    @Test
    void rejectsNewSnapshotAndDeletedSource() {
        jdbc.update("insert into source_snapshot values ('new', 'source', 2, 'PARSED')");
        assertThatThrownBy(() -> service.readFrame("task", "frame")).hasMessageContaining("不可读取");
        jdbc.update("delete from source_snapshot where id = 'new'");
        jdbc.update("update source set status = 'DELETED' where id = 'source'");
        assertThatThrownBy(() -> service.readFrame("task", "frame")).hasMessageContaining("不可读取");
        verifyNoInteractions(frames);
    }

    @Test
    void rejectsUnreplayableInput() {
        when(jobs.getWorkerInput("task")).thenReturn(input("PARTIAL"));
        assertThatThrownBy(() -> service.readFrame("task", "frame")).hasMessageContaining("不可完整重放");
        verifyNoInteractions(frames);
    }

    @Test
    void staleDeliveryNeverReadsTaskOrStorage() {
        var dispatcher = mock(DurableOutboxDispatcher.class);
        var controller = new ArtifactTaskFrameController(service, new ArtifactWorkerDeliveryGuard(dispatcher, "production", true));
        assertThatThrownBy(() -> controller.readFrame("task", "frame", "")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> controller.readFrame("task", "frame", "stale")).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(jobs, frames);
    }
}

package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.noteweave.common.BusinessException;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import com.noteweave.retrieval.qa.ScopedPassageSearch;
import com.noteweave.worker.WorkerSourceScopeItemResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ArtifactTaskRetrievalServiceTest {
    private JdbcTemplate jdbc;
    private ArtifactJobService jobs;
    private ScopedPassageSearch search;
    private ArtifactTaskRetrievalService service;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table source(id varchar, workspace_id varchar, status varchar)");
        jdbc.execute("create table source_snapshot(id varchar, source_id varchar, parse_status varchar, index_status varchar)");
        jdbc.execute("create table source_chunk(id varchar, workspace_id varchar, source_id varchar, source_snapshot_id varchar, chunk_no int, location_info varchar, content clob)");
        jdbc.update("insert into source values ('source', 'workspace', 'READY'), ('other', 'elsewhere', 'READY')");
        jdbc.update("insert into source_snapshot values ('frozen', 'source', 'PARSED', 'DISABLED'), ('latest', 'source', 'PARSED', 'INDEXED'), ('other-snap', 'other', 'PARSED', 'INDEXED')");
        jdbc.update("insert into source_chunk values ('good', 'workspace', 'source', 'frozen', 9, 'page 9', '冻结数据库原文'), ('new', 'workspace', 'source', 'latest', 0, 'page 1', '新版本原文'), ('foreign', 'elsewhere', 'other', 'other-snap', 0, '', '其他工作区内容')");
        jobs = mock(ArtifactJobService.class);
        search = mock(ScopedPassageSearch.class);
        when(jobs.getWorkerInput("task")).thenReturn(input("FULL", List.of(source())));
        service = new ArtifactTaskRetrievalService(jobs, search, jdbc);
    }

    @AfterEach
    void close() { jdbc.execute("shutdown"); }

    private WorkerSourceScopeItemResponse source() {
        return new WorkerSourceScopeItemResponse("source", "冻结标题", "", "", "frozen", "", "", "", "DOCUMENT_TEXT", "", Map.of());
    }

    private ArtifactWorkerInputResponse input(String replay, List<WorkerSourceScopeItemResponse> sources) {
        return new ArtifactWorkerInputResponse("task", "workspace", "artifact", "input", "digest", replay,
                sources, List.of(), null, null, null, null);
    }

    private ScopedPassageSearch.Passage candidate(String id, String source, String snapshot) {
        return new ScopedPassageSearch.Passage(id, source, snapshot, 0, "索引旧标题", "索引旧位置", "索引中不可信正文", 0.8);
    }

    private void results(ScopedPassageSearch.Passage... passages) {
        when(search.search(anyString(), any(), anyString(), anyInt())).thenReturn(new ScopedPassageSearch.Result(List.of(passages), false, List.of()));
    }

    @Test
    void candidatesAreScopedAndHydratedFromFrozenDatabaseInsteadOfIndexText() {
        results(candidate("foreign", "other", "other-snap"), candidate("new", "source", "latest"),
                candidate("good", "source", "frozen"), candidate("good", "source", "frozen"));
        var result = service.retrieve("task", "章节查询", 6);
        assertThat(result.passages()).hasSize(1);
        var passage = result.passages().get(0);
        assertThat(passage.content()).isEqualTo("冻结数据库原文");
        assertThat(passage.title()).isEqualTo("冻结标题");
        assertThat(passage.location()).isEqualTo("page 9");
        assertThat(passage.chunkNo()).isEqualTo(9);
        assertThat(result.degradationReasons()).contains("ARTIFACT_RETRIEVAL_FROZEN_SCOPE_MISMATCH");
        verify(search).search("workspace", Set.of("source"), "章节查询", 24);
        verify(jobs, times(2)).getWorkerInput("task");
    }

    @Test
    void forgedChunkIdentityCannotReadAnUnrelatedDatabaseRow() {
        results(candidate("foreign", "source", "frozen"));
        assertThat(service.retrieve("task", "查询", 3).passages()).isEmpty();
    }

    @Test
    void emptyScopeDoesNotSearchEntireWorkspace() {
        when(jobs.getWorkerInput("task")).thenReturn(input("FULL", List.of()));
        assertThat(service.retrieve("task", "查询", 3).passages()).isEmpty();
        verifyNoInteractions(search);
    }

    @Test
    void revokedOrUnreplayableTaskStopsBeforeCallingProvider() {
        when(jobs.getWorkerInput("task")).thenThrow(new BusinessException("ARTIFACT_SOURCE_REVOKED", "冻结资料已撤权"));
        assertThatThrownBy(() -> service.retrieve("task", "查询", 3)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(search);
        reset(jobs);
        when(jobs.getWorkerInput("task")).thenReturn(input("PARTIAL", List.of(source())));
        assertThatThrownBy(() -> service.retrieve("task", "查询", 3)).hasMessageContaining("不可完整重放");
        verifyNoInteractions(search);
    }

    @Test
    void providerFailureReturnsSafeDegradationWithoutConnectionDetails() {
        when(search.search(anyString(), any(), anyString(), anyInt())).thenThrow(new IllegalStateException("secret connection string"));
        var result = service.retrieve("task", "查询", 3);
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradationReasons()).containsExactly("ARTIFACT_RETRIEVAL_PROVIDER_UNAVAILABLE");
    }

    @Test
    void oversizedPassageDoesNotConsumeOtherPassageBudget() {
        jdbc.update("update source_chunk set content = ? where id = 'good'", "字".repeat(30_000));
        results(candidate("good", "source", "frozen"));
        var result = service.retrieve("task", "查询", 3);
        assertThat(result.passages()).isEmpty();
        assertThat(result.degradationReasons()).contains("ARTIFACT_RETRIEVAL_BYTE_BUDGET_REACHED");
    }

    @Test
    void retrievalValidatesQueryAndLimitBeforeReadingTask() {
        assertThatThrownBy(() -> service.retrieve("task", " ", 3)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.retrieve("task", "字".repeat(2001), 3)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.retrieve("task", "查询", 21)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(jobs, search);
    }

    @Test
    void staleOrMissingDeliveryCannotReachRetrievalService() {
        var dispatcher = mock(DurableOutboxDispatcher.class);
        var retrieval = mock(ArtifactTaskRetrievalService.class);
        var controller = new ArtifactTaskRetrievalController(retrieval,
                new ArtifactWorkerDeliveryGuard(dispatcher, "production", true));
        assertThatThrownBy(() -> controller.retrieve("task", new ArtifactTaskRetrievalController.Request("查询", 3), ""))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.retrieve("task", new ArtifactTaskRetrievalController.Request("查询", 3), "stale"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(retrieval);
    }
}

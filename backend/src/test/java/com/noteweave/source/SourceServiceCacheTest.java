package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.security.AuditActorProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.context.ApplicationEventPublisher;

class SourceServiceCacheTest {

    private JdbcTemplate jdbcTemplate;
    private SourceCatalogCache cache;
    private SourceService service;
    private SourceWikiCommandPort wikiCommandPort;
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:source-service-cache-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table workspace(
                    id varchar(36) primary key,
                    source_catalog_version bigint not null default 1
                )
                """);
        jdbcTemplate.update("insert into workspace(id) values ('workspace-1')");
        cache = mock(SourceCatalogCache.class);
        wikiCommandPort = mock(SourceWikiCommandPort.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new SourceService(
                jdbcTemplate,
                mock(WorkspaceAccessGuard.class),
                wikiCommandPort,
                mock(AuditActorProvider.class),
                cache,
                new SourceCatalogVersionService(jdbcTemplate),
                new SimpleMeterRegistry(),
                mock(com.noteweave.task.TaskCommandPort.class),
                eventPublisher,
                mock(com.noteweave.conversation.RunReplayRedactionService.class));
    }

    @Test
    void cacheHitShouldNotRequireSourceTableScan() {
        List<SourceResponse> cached = List.of(source("cached-source"));
        when(cache.get("workspace-1", 1)).thenReturn(Optional.of(cached));

        assertThat(service.listSources("workspace-1")).isEqualTo(cached);
    }

    @Test
    void cacheMissShouldLoadDatabaseAndPopulateVersionedSnapshot() {
        jdbcTemplate.execute("""
                create table source(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    title varchar(160) not null,
                    source_type varchar(32) not null,
                    status varchar(32) not null,
                    parse_status varchar(32) not null,
                    index_status varchar(32) not null,
                    generated_by varchar(80),
                    generated_ref_id varchar(36),
                    updated_at timestamp not null
                )
                """);
        jdbcTemplate.update("""
                insert into source(
                    id, workspace_id, title, source_type, status, parse_status, index_status, updated_at
                ) values ('source-1', 'workspace-1', 'Source', 'USER_UPLOAD',
                          'READY', 'PARSED', 'INDEXED', current_timestamp)
                """);
        when(cache.get("workspace-1", 1)).thenReturn(Optional.empty());

        List<SourceResponse> loaded = service.listSources("workspace-1");

        assertThat(loaded).extracting(SourceResponse::sourceId).containsExactly("source-1");
        verify(cache).put(eq("workspace-1"), eq(1L), anyList());
    }

    @Test
    void deletingLastFileReferenceShouldPublishPostCommitCleanupEvent() {
        jdbcTemplate.execute("""
                create table file_object(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    object_key varchar(500) not null,
                    ref_count int not null
                )
                """);
        jdbcTemplate.execute("""
                create table source(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    file_object_id varchar(36) not null,
                    title varchar(160) not null,
                    status varchar(32) not null,
                    parse_status varchar(32) not null,
                    index_status varchar(32) not null,
                    updated_by varchar(80),
                    updated_at timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table source_snapshot(
                    id varchar(36) primary key,
                    source_id varchar(36) not null,
                    version_no int not null,
                    object_key varchar(500) not null,
                    parse_status varchar(32) not null,
                    index_status varchar(32) not null
                )
                """);
        jdbcTemplate.execute("""
                create table task(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    target_type varchar(32),
                    target_id varchar(36),
                    task_type varchar(32),
                    task_status varchar(32)
                )
                """);
        jdbcTemplate.update("insert into file_object values ('file-1', 'workspace-1', 'workspace/workspace-1/file.txt', 1)");
        jdbcTemplate.update("""
                insert into source values (
                    'source-1', 'workspace-1', 'file-1', 'Source',
                    'READY', 'PARSED', 'INDEXED', 'SYSTEM:TEST', current_timestamp)
                """);
        jdbcTemplate.update("""
                insert into source_snapshot values (
                    'snapshot-1', 'source-1', 1, 'workspace/workspace-1/source/source-1/snapshot/1/original/file.txt',
                    'PARSED', 'INDEXED')
                """);
        when(wikiCommandPort.requestSourceRetract("workspace-1", "source-1", "Source"))
                .thenReturn("wiki-task-1");

        DeleteSourceResponse deleted = service.deleteSource("workspace-1", "source-1");

        assertThat(deleted.status()).isEqualTo("DELETED");
        assertThat(jdbcTemplate.queryForObject("select ref_count from file_object where id = 'file-1'", Integer.class))
                .isZero();
        verify(eventPublisher).publishEvent(new SourceDeletedEvent(
                "workspace-1", "source-1", List.of(
                        "workspace/workspace-1/source/source-1/snapshot/1/original/file.txt",
                        "workspace/workspace-1/file.txt")));
    }

    private SourceResponse source(String sourceId) {
        return new SourceResponse(
                sourceId, "Source", "USER_UPLOAD", "READY", "PARSED", "INDEXED",
                "", "", Instant.parse("2026-07-14T10:00:00Z"));
    }
}

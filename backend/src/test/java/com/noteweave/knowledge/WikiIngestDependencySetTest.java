package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * F6-03：Wiki 编译输入、正文模板与 citation 依赖来自同一冻结集合。
 * 依赖覆盖正文使用的每个片段，并补上只有来源级摘要的资料来源；
 * 编译只读当前 INDEXED 快照的已投影片段。
 */
class WikiIngestDependencySetTest {

    private static final String WS = "ws-1";

    private JdbcTemplate jdbc;
    private WikiIngestService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:wiki-dependency-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table citation(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    source_id varchar(36),
                    source_snapshot_id varchar(36),
                    source_chunk_id varchar(36),
                    title varchar(300),
                    quote_text varchar(2000),
                    page_no int,
                    location_info varchar(1000)
                )
                """);
        jdbc.execute("""
                create table source(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    status varchar(32) not null,
                    index_status varchar(32) not null,
                    title varchar(300),
                    summary varchar(1000),
                    tags_json varchar(1000),
                    source_type varchar(64),
                    updated_at timestamp default current_timestamp
                )
                """);
        jdbc.execute("""
                create table source_snapshot(
                    id varchar(36) primary key,
                    source_id varchar(36) not null,
                    version_no int not null,
                    index_status varchar(32) not null
                )
                """);
        jdbc.execute("""
                create table source_chunk(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    source_id varchar(36) not null,
                    source_snapshot_id varchar(36) not null,
                    chunk_no int not null,
                    heading varchar(300),
                    content clob,
                    location_info varchar(300),
                    projection_status varchar(32) not null
                )
                """);
        service = new WikiIngestService(
                jdbc, null, null, null, null, null, new ObjectMapper(), null, null, null);
    }

    @Test
    void everyFrozenChunkGetsItsOwnCitationWithBoundSnapshot() {
        seedSource("source-a", "snap-a", 6);

        List<WikiIngestService.ChunkForWiki> chunks = service.loadChunksForSources(
                WS, List.of("source-a"), 5);
        List<String> citationIds = service.createCitations(WS, chunks);

        assertThat(chunks).hasSize(5);
        assertThat(citationIds).hasSize(5);
        assertThat(jdbc.queryForObject("select count(*) from citation where workspace_id = ?", Integer.class, WS))
                .isEqualTo(5);
        assertThat(jdbc.queryForList("select source_snapshot_id from citation", String.class))
                .containsOnly("snap-a");
    }

    @Test
    void sourceLevelSummaryWithoutChunkStillGetsAProvenanceCitation() {
        seedSource("source-a", "snap-a", 2);
        seedSource("source-b", "snap-b", 2);
        List<WikiIngestService.SourceForWiki> sources = List.of(
                new WikiIngestService.SourceForWiki("source-a", "A", "TEXT", "summary a", "[]"),
                new WikiIngestService.SourceForWiki("source-b", "B", "TEXT", "summary b", "[]"));
        List<WikiIngestService.ChunkForWiki> chunks = service.loadChunksForSources(
                WS, List.of("source-a"), 2);

        List<WikiIngestService.ChunkForWiki> covered = service.withSourceCoverage(WS, sources, chunks);
        List<String> citationIds = service.createCitations(WS, covered);

        assertThat(covered).extracting(WikiIngestService.ChunkForWiki::sourceId)
                .contains("source-a", "source-b");
        assertThat(citationIds).hasSize(covered.size());
        assertThat(jdbc.queryForList("select distinct source_id from citation", String.class))
                .containsExactlyInAnyOrder("source-a", "source-b");
    }

    @Test
    void compilationIgnoresOldSnapshotAndUnprojectedChunks() {
        seedSource("source-current", "snap-current", 2);
        jdbc.update("""
                insert into source(id, workspace_id, status, index_status, title)
                values ('source-stale', ?, 'READY', 'INDEXED', 'stale')
                """, WS);
        jdbc.update("insert into source_snapshot(id, source_id, version_no, index_status) values ('snap-old', 'source-stale', 1, 'INDEXED')");
        jdbc.update("insert into source_snapshot(id, source_id, version_no, index_status) values ('snap-new', 'source-stale', 2, 'INDEXED')");
        jdbc.update("""
                insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, chunk_no,
                    heading, content, location_info, projection_status)
                values ('chunk-old', ?, 'source-stale', 'snap-old', 1, '', '旧快照正文', '', 'PROJECTED')
                """, WS);
        jdbc.update("""
                insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, chunk_no,
                    heading, content, location_info, projection_status)
                values ('chunk-unprojected', ?, 'source-current', 'snap-current', 1, '', '未投影正文', '', 'PENDING')
                """, WS);

        List<WikiIngestService.ChunkForWiki> chunks = service.loadChunksForSources(
                WS, List.of("source-stale", "source-current"), 10);

        assertThat(chunks).extracting(WikiIngestService.ChunkForWiki::chunkId)
                .doesNotContain("chunk-old", "chunk-unprojected");
    }

    private void seedSource(String sourceId, String snapshotId, int chunkCount) {
        jdbc.update("""
                insert into source(id, workspace_id, status, index_status, title)
                values (?, ?, 'READY', 'INDEXED', ?)
                """, sourceId, WS, sourceId);
        jdbc.update("""
                insert into source_snapshot(id, source_id, version_no, index_status)
                values (?, ?, 1, 'INDEXED')
                """, snapshotId, sourceId);
        for (int i = 1; i <= chunkCount; i++) {
            jdbc.update("""
                    insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, chunk_no,
                        heading, content, location_info, projection_status)
                    values (?, ?, ?, ?, ?, '', ?, '', 'PROJECTED')
                    """, sourceId + "-chunk-" + i, WS, sourceId, snapshotId, i, "正文" + i);
        }
    }
}

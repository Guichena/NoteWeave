package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class NoteRecallRepositoryQueryTest {
    @Test
    void candidatePoolShouldNotBeRestrictedToRecentFortySources() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NoteRecallRepository repository = new NoteRecallRepository(jdbc);

        repository.findCurrentSources("workspace");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(org.springframework.jdbc.core.RowMapper.class), any(Object.class));
        assertThat(sql.getValue().toLowerCase()).doesNotContain("limit 40");
        assertThat(sql.getValue()).contains("ss.version_no", "s.index_status = 'INDEXED'");
    }

    @Test
    void sampleTextComesFromTheLowestChunkNumber() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:note-recall-sample-" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table source (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    title varchar(300) not null,
                    source_type varchar(64) not null,
                    status varchar(32) not null,
                    index_status varchar(32) not null,
                    updated_at timestamp not null,
                    generated_by varchar(64),
                    generated_ref_id varchar(128),
                    summary varchar(1000),
                    tags_json longtext,
                    metadata_json longtext
                )
                """);
        jdbc.execute("""
                create table source_snapshot (
                    id varchar(36) primary key,
                    source_id varchar(36) not null,
                    version_no int not null,
                    index_status varchar(32) not null
                )
                """);
        jdbc.execute("""
                create table source_chunk (
                    id varchar(36) primary key,
                    source_id varchar(36) not null,
                    source_snapshot_id varchar(36) not null,
                    chunk_no int not null,
                    content longtext not null
                )
                """);
        jdbc.execute("""
                create table source_window (
                    id varchar(36) primary key,
                    source_chunk_id varchar(36) not null
                )
                """);
        jdbc.update("""
                insert into source(id, workspace_id, title, source_type, status, index_status, updated_at,
                    generated_by, generated_ref_id, summary, tags_json, metadata_json)
                values ('s1', 'ws', 'Title', 'PDF', 'READY', 'INDEXED', current_timestamp, '', '', '', '[]', '{}')
                """);
        jdbc.update("""
                insert into source_snapshot(id, source_id, version_no, index_status)
                values ('ss1', 's1', 1, 'INDEXED')
                """);
        jdbc.update("""
                insert into source_chunk(id, source_id, source_snapshot_id, chunk_no, content)
                values ('c1', 's1', 'ss1', 1, 'Apple is the later chunk')
                """);
        jdbc.update("""
                insert into source_chunk(id, source_id, source_snapshot_id, chunk_no, content)
                values ('c0', 's1', 'ss1', 0, 'Zebra is the earliest chunk')
                """);

        var sources = new NoteRecallRepository(jdbc).findCurrentSourcesByIds("ws", List.of("s1"));

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).sampleText()).startsWith("Zebra");
    }
}

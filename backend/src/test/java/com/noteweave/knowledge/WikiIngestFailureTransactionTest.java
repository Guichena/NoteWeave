package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.source.SourceMessagingMode;
import com.noteweave.task.TaskCommandPort;
import com.noteweave.task.TaskService;
import com.noteweave.workspace.WorkspaceQueryPort;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringJUnitConfig(WikiIngestFailureTransactionTest.Config.class)
class WikiIngestFailureTransactionTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkspaceQueryPort workspaceQueryPort;

    @Autowired
    private TaskCommandPort taskCommandPort;

    @Autowired
    private WikiIngestService wikiIngestService;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("drop table if exists task_event");
        jdbcTemplate.execute("drop table if exists task");
        jdbcTemplate.execute("drop table if exists source");
        jdbcTemplate.execute("""
                create table task (
                    id varchar(64) primary key,
                    workspace_id varchar(64) not null,
                    task_type varchar(64) not null,
                    task_status varchar(32) not null,
                    target_type varchar(64),
                    target_id varchar(64),
                    progress_phase varchar(128),
                    progress_message varchar(512),
                    result_ref varchar(512),
                    error_message varchar(1024),
                    created_by varchar(80) not null,
                    updated_by varchar(80) not null,
                    created_at timestamp default current_timestamp,
                    updated_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table task_event (
                    id varchar(64) primary key,
                    task_id varchar(64) not null,
                    event_type varchar(64) not null,
                    message varchar(512),
                    payload_json clob,
                    created_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table source (
                    id varchar(64) primary key,
                    workspace_id varchar(64) not null,
                    title varchar(255),
                    source_type varchar(64),
                    summary clob,
                    tags_json clob,
                    status varchar(32)
                )
                """);
        when(workspaceQueryPort.isWikiEnabled("workspace-1")).thenReturn(true);
    }

    @Test
    void ingestRollbackShouldStillPersistFailedTerminalState() {
        String taskId = taskCommandPort.createTask(
                "workspace-1", "WIKI_INGEST", "SOURCE", "missing-source",
                "QUEUED", "queued");

        wikiIngestService.runSourceIngestNow(taskId, "workspace-1", "missing-source");

        assertThat(jdbcTemplate.queryForObject(
                "select task_status from task where id = ?", String.class, taskId))
                .isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "select progress_phase from task where id = ?", String.class, taskId))
                .isEqualTo("WIKI_INDEXED_FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from task_event where task_id = ? and event_type = 'TASK_FAILED'",
                Integer.class, taskId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from task_event where task_id = ? and event_type = 'TASK_COMPLETED'",
                Integer.class, taskId)).isZero();
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:wiki-ingest-failure;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        WorkspaceQueryPort workspaceQueryPort() {
            return mock(WorkspaceQueryPort.class);
        }

        @Bean
        KnowledgeQueryService knowledgeQueryService() {
            return mock(KnowledgeQueryService.class);
        }

        @Bean
        KnowledgeCommandService knowledgeCommandService() {
            return mock(KnowledgeCommandService.class);
        }

        @Bean
        KnowledgeGovernanceService knowledgeGovernanceService() {
            return mock(KnowledgeGovernanceService.class);
        }

        @Bean
        SourceMessagingMode sourceMessagingMode() {
            return mock(SourceMessagingMode.class);
        }

        @Bean
        TaskCommandPort taskCommandPort(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
            return new TaskService(jdbcTemplate, objectMapper);
        }

        @Bean
        WikiIngestTransactionExecutor wikiIngestTransactionExecutor() {
            return new WikiIngestTransactionExecutor();
        }

        @Bean
        WikiIngestService wikiIngestService(
                JdbcTemplate jdbcTemplate,
                WorkspaceQueryPort workspaceQueryPort,
                KnowledgeQueryService knowledgeQueryService,
                KnowledgeCommandService knowledgeCommandService,
                KnowledgeGovernanceService knowledgeGovernanceService,
                TaskCommandPort taskCommandPort,
                ObjectMapper objectMapper,
                SourceMessagingMode sourceMessagingMode,
                WikiIngestTransactionExecutor transactionExecutor
        ) {
            return new WikiIngestService(
                    jdbcTemplate,
                    workspaceQueryPort,
                    knowledgeQueryService,
                    knowledgeCommandService,
                    knowledgeGovernanceService,
                    taskCommandPort,
                    objectMapper,
                    sourceMessagingMode,
                    transactionExecutor
            );
        }
    }
}

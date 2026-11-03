package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 历史独立研究迁入对话：不删除运行、不改状态，重复调用幂等。 */
class ResearchStandaloneMigrationServiceTest {

    private JdbcTemplate jdbc;
    private ResearchStandaloneMigrationService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:research-standalone-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        service = new ResearchStandaloneMigrationService(jdbc);
        jdbc.update("insert into workspace(id,owner_id,name,status) values ('w','local-user','测试','ACTIVE')");
        jdbc.update("""
                insert into task(id,workspace_id,task_type,task_status,target_type,target_id)
                values ('task-1','w','RESEARCH_RUN','COMPLETED','RESEARCH_RUN','run-1')
                """);
        jdbc.update("""
                insert into research_run(id,workspace_id,task_id,question,profile_key,source_scope_json,
                    status,final_report_markdown)
                values ('run-1','w','task-1','比较 AWS 和 Azure','default','[]',
                    'COMPLETED_VERIFIED','## 报告\nAWS 更合适')
                """);
    }

    @Test
    void attachesStandaloneRunWithoutDeletingOrRerunningIt() {
        var first = service.migrateOne("w", "run-1");
        assertThat(first.migrated()).isEqualTo(1);
        assertThat(first.conversationIds()).hasSize(1);

        String conversationId = jdbc.queryForObject(
                "select conversation_id from research_run where id = 'run-1'", String.class);
        assertThat(conversationId).isEqualTo(first.conversationIds().get(0));
        assertThat(jdbc.queryForObject("select status from research_run where id = 'run-1'", String.class))
                .isEqualTo("COMPLETED_VERIFIED");
        assertThat(jdbc.queryForObject("""
                select content from conversation_message
                where conversation_id = ? and role = 'USER'
                """, String.class, conversationId)).contains("AWS");
        assertThat(jdbc.queryForObject("""
                select content from conversation_message
                where conversation_id = ? and role = 'ASSISTANT'
                """, String.class, conversationId)).contains("报告");

        var second = service.migrateWorkspace("w");
        assertThat(second.migrated()).isZero();
        assertThat(second.skipped()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from research_run", Integer.class)).isEqualTo(1);
    }
}

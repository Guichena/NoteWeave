package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.research.ResearchProjectionService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** F3-03：完成消息的结论卡由结构化投影生成，并同步保存投影 JSON。 */
class ConversationResearchProjectionCardTest {

    @Test
    void completedReportWritesStructuralCardAndProjectionJson() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:research-card-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("insert into workspace(id,owner_id,name,status) values ('w','local-user','测试','ACTIVE')");
        jdbc.update("""
                insert into conversation(id,workspace_id,title,conversation_type,status)
                values ('c','w','测试','QA','ACTIVE')
                """);
        jdbc.update("""
                insert into conversation_message(id,conversation_id,workspace_id,message_seq,role,
                    content,context_status)
                values ('m1','c','w',2,'ASSISTANT','研究进行中','PENDING')
                """);
        jdbc.update("""
                insert into task(id,workspace_id,task_type,task_status)
                values ('task-1','w','DEEP_RESEARCH','COMPLETED')
                """);
        jdbc.update("""
                insert into research_run(id,workspace_id,task_id,question,profile_key,
                    source_scope_json,status,final_report_title,final_report_markdown,answer_message_id)
                values ('run-1','w','task-1','问题','default','[]',
                    'COMPLETED_VERIFIED','结论卡标题','## 执行摘要\n结构化摘要\n\n## 正文\n细节','m1')
                """);
        jdbc.update("""
                insert into research_row(id,research_run_id,row_key,row_status)
                values ('row-1','run-1','对象A','DONE')
                """);
        jdbc.update("""
                insert into research_column(id,research_run_id,column_key,label,plan_revision)
                values ('col-1','run-1','dimension','维度一',1)
                """);
        jdbc.update("""
                insert into research_cell(id,research_run_id,research_row_id,cell_key,column_key,
                    candidate_value,cell_status,evidence_refs_json)
                values ('cell-1','run-1','row-1','cell','dimension','已核实结论','VERIFIED','["e1"]')
                """);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ConversationResearchProjectionService service = new ConversationResearchProjectionService(
                jdbc, mock(ConversationSegmentBuildService.class),
                new ResearchProjectionService(jdbc, mapper));

        service.projectCompletedReport("w", "c", "m1", "run-1", "结论卡标题", "## 执行摘要\n结构化摘要");

        String card = jdbc.queryForObject("select content from conversation_message where id = 'm1'", String.class);
        String projection = jdbc.queryForObject(
                "select research_projection_json from conversation_message where id = 'm1'", String.class);
        assertThat(card)
                .contains("研究报告已完成")
                .contains("结构化摘要")
                .contains("需求覆盖")
                .doesNotContain("细节");
        assertThat(projection).contains("research-projection-v1", "已核实结论", "VERIFIED");
        assertThat(jdbc.queryForObject("select context_status from conversation_message where id = 'm1'",
                String.class)).isEqualTo("CURRENT");
    }
}

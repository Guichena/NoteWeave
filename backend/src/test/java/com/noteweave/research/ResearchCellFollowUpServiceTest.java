package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** F4-03：对象/维度定位、歧义和未核实单元格的边界。 */
class ResearchCellFollowUpServiceTest {

    private JdbcTemplate jdbc;
    private ResearchCellFollowUpService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:cell-followup-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("insert into workspace(id,owner_id,name,status) values ('w','local-user','测试','ACTIVE')");
        jdbc.update("""
                insert into conversation(id,workspace_id,title,conversation_type,status)
                values ('c','w','测试','DEEP_RESEARCH','ACTIVE')
                """);
        jdbc.update("""
                insert into conversation_message(id,conversation_id,workspace_id,message_seq,role,
                    content,context_status)
                values ('m1','c','w',2,'ASSISTANT','研究报告','CURRENT')
                """);
        jdbc.update("""
                insert into task(id,workspace_id,task_type,task_status)
                values ('task-1','w','DEEP_RESEARCH','COMPLETED')
                """);
        jdbc.update("""
                insert into research_run(id,workspace_id,task_id,question,profile_key,
                    source_scope_json,status,final_report_title,answer_message_id)
                values ('run-1','w','task-1','对比部署','default','[]',
                    'COMPLETED_VERIFIED','部署对比','m1')
                """);
        jdbc.update("""
                insert into research_row(id,research_run_id,row_key,source_title,row_status)
                values ('row-aws','run-1','AWS','Amazon Web Services','DONE')
                """);
        jdbc.update("""
                insert into research_row(id,research_run_id,row_key,source_title,row_status)
                values ('row-azure','run-1','Azure','Microsoft Azure','DONE')
                """);
        jdbc.update("""
                insert into research_column(id,research_run_id,column_key,label,plan_revision)
                values ('col-deploy','run-1','deployment','部署方式',1)
                """);
        jdbc.update("""
                insert into research_cell(id,research_run_id,research_row_id,cell_key,column_key,
                    candidate_value,cell_status,evidence_refs_json)
                values ('cell-aws','run-1','row-aws','AWS|deployment','deployment',
                    'AWS 使用托管容器','VERIFIED','["e-aws"]')
                """);
        jdbc.update("""
                insert into research_cell(id,research_run_id,research_row_id,cell_key,column_key,
                    candidate_value,cell_status,evidence_refs_json)
                values ('cell-azure','run-1','row-azure','Azure|deployment','deployment',
                    'Azure 使用应用服务','CONFLICTED','["e-azure"]')
                """);
        service = new ResearchCellFollowUpService(jdbc);
    }

    @Test
    void locatesVerifiedCellAndKeepsEvidenceOnlyForVerifiedStatus() {
        List<ResearchCellFollowUpService.Match> matches = service.match(
                "w", "c", "AWS 的部署方式是什么", 10);

        assertThat(matches).isNotEmpty();
        assertThat(matches).first().satisfies(match -> {
            assertThat(match.cellId()).isEqualTo("cell-aws");
            assertThat(match.objectName()).isEqualTo("Amazon Web Services");
            assertThat(match.dimensionName()).isEqualTo("部署方式");
            assertThat(match.status()).isEqualTo("VERIFIED");
            assertThat(match.evidenceIds()).containsExactly("e-aws");
            assertThat(match.ambiguous()).isFalse();
        });
    }

    @Test
    void conflictingCellIsUnverifiedEvenWhenQuestionAlsoMatchedIt() {
        List<ResearchCellFollowUpService.Match> matches = service.match(
                "w", "c", "Azure 的部署方式", 10);

        assertThat(matches).filteredOn(match -> "cell-azure".equals(match.cellId()))
                .singleElement().satisfies(match -> {
                    assertThat(match.status()).isEqualTo("UNVERIFIED");
                    assertThat(match.evidenceIds()).isEmpty();
                });
    }

    @Test
    void twoObjectsAtSameDimensionAreMarkedAmbiguous() {
        List<ResearchCellFollowUpService.Match> matches = service.match(
                "w", "c", "对比 AWS 和 Azure 的部署方式", 10);

        assertThat(matches).hasSize(2);
        assertThat(matches).allSatisfy(match -> assertThat(match.ambiguous()).isTrue());
    }

    @Test
    void pronounOnlyQuestionDoesNotGuessACell() {
        assertThat(service.match("w", "c", "第二点为什么重要", 10)).isEmpty();
    }
}

package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.context.ResearchProjection;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** F3-03：P0/P1/P2 分级只暴露允许字段，未核实单元格不能携带已核实证据。 */
class ResearchProjectionServiceTest {

    @Test
    void projectionLevelsExposeOnlyAllowedFields() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:research-projection-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("insert into workspace(id,owner_id,name,status) values ('w','local-user','测试','ACTIVE')");
        jdbc.update("""
                insert into task(id,workspace_id,task_type,task_status)
                values ('task-1','w','DEEP_RESEARCH','COMPLETED')
                """);
        jdbc.update("""
                insert into research_run(id,workspace_id,task_id,question,profile_key,
                    source_scope_json,status,final_report_title,final_report_markdown)
                values ('run-1','w','task-1','AWS 和 Azure 的部署差异','default','[]',
                    'COMPLETED_VERIFIED','部署对比','## 执行摘要\nAWS 更适合当前团队。\n\n## 正文\n细节')
                """);
        jdbc.update("""
                insert into research_row(id,research_run_id,row_key,row_status)
                values ('row-1','run-1','AWS','DONE')
                """);
        jdbc.update("""
                insert into research_column(id,research_run_id,column_key,label,plan_revision)
                values ('col-1','run-1','deployment','部署方式',1)
                """);
        jdbc.update("""
                insert into research_cell(id,research_run_id,research_row_id,cell_key,column_key,
                    candidate_value,cell_status,evidence_refs_json)
                values ('cell-1','run-1','row-1','AWS|deployment','deployment',
                    '托管容器','VERIFIED','["evidence-1"]')
                """);
        jdbc.update("""
                insert into research_cell(id,research_run_id,research_row_id,cell_key,column_key,
                    candidate_value,cell_status,evidence_refs_json)
                values ('cell-2','run-1','row-1','AWS|conflict','deployment',
                    '两条来源冲突','CONFLICTED','["evidence-2"]')
                """);
        ResearchProjectionService service = new ResearchProjectionService(jdbc, new ObjectMapper());

        ResearchProjection p2 = service.build("run-1", ResearchProjection.LEVEL_P2);
        assertThat(p2.status()).isEqualTo("COMPLETED");
        assertThat(p2.executiveSummary()).contains("AWS 更适合");
        assertThat(p2.cells()).hasSize(2);
        assertThat(p2.cells()).filteredOn(cell -> "VERIFIED".equals(cell.status()))
                .singleElement().satisfies(cell -> {
                    assertThat(cell.evidenceIds()).containsExactly("evidence-1");
                    assertThat(cell.conclusion()).isEqualTo("托管容器");
                });
        assertThat(p2.cells()).filteredOn(cell -> "UNVERIFIED".equals(cell.status()))
                .singleElement().satisfies(cell -> assertThat(cell.evidenceIds()).isEmpty());
        assertThat(p2.requirements()).singleElement().satisfies(requirement -> {
            assertThat(requirement.coverage()).isEqualTo("PARTIAL");
            assertThat(requirement.label()).isEqualTo("部署方式");
        });

        ResearchProjection p1 = service.build("run-1", ResearchProjection.LEVEL_P1);
        assertThat(p1.cells()).isEmpty();
        assertThat(p1.requirements()).isNotEmpty();
        assertThat(p1.degradationReasons())
                .contains(ResearchProjection.REASON_LEVEL_RESTRICTS_CELLS);

        ResearchProjection p0 = service.build("run-1", ResearchProjection.LEVEL_P0);
        assertThat(p0.executiveSummary()).isEmpty();
        assertThat(p0.requirements()).isEmpty();
        assertThat(p0.cells()).isEmpty();
        assertThat(p0.title()).isEqualTo("部署对比");
        assertThat(p0.degradationReasons())
                .contains(ResearchProjection.REASON_LEVEL_RESTRICTS_P0_FIELDS);
    }

    @Test
    void reportWithoutStructureIsExplicitlyMarkedAsUpstreamMissing() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:research-projection-empty-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("insert into workspace(id,owner_id,name,status) values ('w','local-user','测试','ACTIVE')");
        jdbc.update("""
                insert into task(id,workspace_id,task_type,task_status)
                values ('task-1','w','DEEP_RESEARCH','COMPLETED')
                """);
        jdbc.update("""
                insert into research_run(id,workspace_id,task_id,question,profile_key,
                    source_scope_json,status,final_report_title)
                values ('run-1','w','task-1','历史问题','default','[]',
                    'COMPLETED','历史报告')
                """);
        ResearchProjectionService service = new ResearchProjectionService(jdbc, new ObjectMapper());

        ResearchProjection p1 = service.build("run-1", ResearchProjection.LEVEL_P1);

        assertThat(p1.executiveSummary()).isEmpty();
        assertThat(p1.requirements()).isEmpty();
        assertThat(p1.degradationReasons())
                .contains(ResearchProjection.REASON_UPSTREAM_SUMMARY_EMPTY,
                        ResearchProjection.REASON_UPSTREAM_REQUIREMENTS_EMPTY);
    }
}

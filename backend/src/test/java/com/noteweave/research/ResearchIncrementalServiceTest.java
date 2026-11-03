package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 第七期：局部补查只重开指定格，保留旧格；输入变更走保守的 STALE 重新资格检查。 */
class ResearchIncrementalServiceTest {

    private JdbcTemplate jdbc;
    private ResearchIncrementalService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:research-incremental-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        service = new ResearchIncrementalService(jdbc, new ObjectMapper());
        jdbc.update("insert into workspace(id,owner_id,name,status) values ('w','local-user','测试','ACTIVE')");
        jdbc.update("""
                insert into task(id,workspace_id,task_type,task_status,target_type,target_id)
                values ('task-1','w','RESEARCH_RUN','COMPLETED','RESEARCH_RUN','run-1')
                """);
        jdbc.update("""
                insert into research_run(id,workspace_id,task_id,question,profile_key,source_scope_json,
                    status,agent_execution_mode)
                values ('run-1','w','task-1','对比部署','default','[]','COMPLETED_VERIFIED','INCREMENTAL_V1')
                """);
        jdbc.update("""
                insert into research_row(id,research_run_id,row_key,source_title,row_status)
                values ('row-1','run-1','AWS','AWS','DONE')
                """);
        jdbc.update("""
                insert into research_column(id,research_run_id,column_key,label,plan_revision)
                values ('col-1','run-1','deployment','部署方式',1)
                """);
        jdbc.update("""
                insert into research_cell(id,research_run_id,research_row_id,cell_key,column_key,
                    candidate_value,cell_status,evidence_refs_json,cell_version)
                values ('cell-1','run-1','row-1','AWS:deployment','deployment',
                    '托管容器','VERIFIED','["evidence-1"]',1)
                """);
        jdbc.update("""
                insert into source_evidence(id,research_run_id,evidence_key,quote_text)
                values ('evidence-1','run-1','evidence-1','托管容器')
                """);
        jdbc.update("""
                insert into research_cell_evidence(id,research_run_id,research_cell_id,source_evidence_id,evidence_key)
                values ('ce-1','run-1','cell-1','evidence-1','evidence-1')
                """);
    }

    @Test
    void reopensOnlyRequestedCellAndExtendsMatrix() {
        var receipt = service.reopen("w", "run-1", new ResearchIncrementalService.Request(
                List.of("AWS:deployment"),
                List.of(new ResearchIncrementalService.NewRow("GCP", "GCP")),
                List.of(new ResearchIncrementalService.NewColumn("cost", "成本", "", "SHORT_TEXT")),
                false, List.of(), "补查 GCP 与成本"));

        assertThat(receipt.reopenedCells()).isEqualTo(1);
        assertThat(receipt.addedRows()).isEqualTo(1);
        assertThat(receipt.addedColumns()).isEqualTo(1);
        assertThat(receipt.totalCells()).isEqualTo(4);
        assertThat(jdbc.queryForObject("select status from research_run where id = 'run-1'", String.class))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("""
                select cell_status from research_cell where id = 'cell-1'
                """, String.class)).isEqualTo("GAP");
        assertThat(jdbc.queryForObject("""
                select candidate_value from research_cell where id = 'cell-1'
                """, String.class)).isNull();
        assertThat(jdbc.queryForObject("""
                select cell_version from research_cell where id = 'cell-1'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from research_cell where research_run_id = 'run-1' and cell_status = 'GAP'
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                select count(*) from research_cell_evidence where research_run_id = 'run-1'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from research_trace where research_run_id = 'run-1'
                    and trace_type = 'INCREMENTAL_REOPEN'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void inputChangeStalesOnlyCellsThatCiteTheChangedSource() {
        jdbc.update("update source_evidence set source_id = 'src-changed' where id = 'evidence-1'");
        jdbc.update("""
                insert into research_cell(id,research_run_id,research_row_id,cell_key,column_key,
                    candidate_value,cell_status,cell_version)
                values ('cell-2','run-1','row-1','AWS:cost','cost','按量','VERIFIED',1)
                """);
        jdbc.update("""
                insert into source_evidence(id,research_run_id,evidence_key,source_id,quote_text)
                values ('evidence-2','run-1','evidence-2','src-keep','按量计费')
                """);
        jdbc.update("""
                insert into research_cell_evidence(id,research_run_id,research_cell_id,source_evidence_id,evidence_key)
                values ('ce-2','run-1','cell-2','evidence-2','evidence-2')
                """);

        var receipt = service.reopen("w", "run-1", new ResearchIncrementalService.Request(
                List.of(), List.of(), List.of(), true, List.of("src-changed"), "资料更新"));

        assertThat(receipt.staleCells()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select cell_status from research_cell where id = 'cell-1'", String.class))
                .isEqualTo("STALE");
        assertThat(jdbc.queryForObject("select cell_status from research_cell where id = 'cell-2'", String.class))
                .isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("""
                select count(*) from research_cell_evidence where research_cell_id = 'cell-1'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from research_cell_evidence where research_cell_id = 'cell-2'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsUnfinishedRunAndUnknownCell() {
        jdbc.update("update research_run set status = 'RUNNING' where id = 'run-1'");
        assertThatThrownBy(() -> service.reopen("w", "run-1", new ResearchIncrementalService.Request(
                List.of("AWS:deployment"), List.of(), List.of(), false, List.of(), null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_INCREMENTAL_NOT_COMPLETED"));

        jdbc.update("update research_run set status = 'COMPLETED_VERIFIED' where id = 'run-1'");
        assertThatThrownBy(() -> service.reopen("w", "run-1", new ResearchIncrementalService.Request(
                List.of("missing:cell"), List.of(), List.of(), false, List.of(), null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_INCREMENTAL_CELL_NOT_FOUND"));
        assertThatThrownBy(() -> service.reopen("w", "run-1", new ResearchIncrementalService.Request(
                List.of(), List.of(), List.of(), true, List.of(), "没有资料")))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("RESEARCH_INCREMENTAL_SOURCE_SCOPE_REQUIRED"));
    }
}

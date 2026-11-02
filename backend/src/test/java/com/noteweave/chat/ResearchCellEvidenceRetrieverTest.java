package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.ResearchReportEvidence;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.research.ResearchCellFollowUpService;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** F4-03：只有已核实、非歧义、研究已完成的单元格才进入证据通道。 */
class ResearchCellEvidenceRetrieverTest {

    private JdbcTemplate jdbc;
    private ResearchCellEvidenceRetriever retriever;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:cell-evidence-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("insert into workspace(id,owner_id,name,status) values ('w','local-user','测试','ACTIVE')");
        jdbc.update("""
                insert into conversation(id,workspace_id,title,conversation_type,status)
                values ('c','w','测试','DEEP_RESEARCH','ACTIVE')
                """);
        jdbc.update("""
                insert into conversation_message(id,conversation_id,workspace_id,message_seq,role,content,context_status)
                values ('m1','c','w',2,'ASSISTANT','研究报告','CURRENT'),
                       ('m2','c','w',3,'USER','追问','CURRENT')
                """);
        jdbc.update("""
                insert into task(id,workspace_id,task_type,task_status)
                values ('task-1','w','DEEP_RESEARCH','COMPLETED')
                """);
        jdbc.update("""
                insert into research_run(id,workspace_id,task_id,question,profile_key,source_scope_json,
                    status,final_report_title,answer_message_id)
                values ('run-1','w','task-1','对比部署','default','[]','COMPLETED','部署对比','m1')
                """);
        jdbc.update("""
                insert into research_row(id,research_run_id,row_key,source_title,row_status)
                values ('row-aws','run-1','AWS','Amazon Web Services','DONE')
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
        retriever = new ResearchCellEvidenceRetriever(jdbc, new ResearchCellFollowUpService(jdbc));
    }

    @Test
    void verifiedCompletedCellYieldsResearchEvidence() {
        var result = retriever.retrieve(context("AWS 的部署方式是什么"), null, step());

        assertThat(result.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.kind()).isEqualTo(ResearchReportEvidence.KIND);
            assertThat(evidence.passageId()).isEqualTo("e-aws");
            assertThat(evidence.accessScope()).isEqualTo("workspace-research:run-1");
            assertThat(evidence.evidenceId()).isEqualTo("research-evidence:run-1:e-aws");
            assertThat(evidence.excerpt()).contains("托管容器");
            assertThat(evidence.metadata()).containsEntry("research_cell_id", "cell-aws");
        });
    }

    @Test
    void pronounOnlyQuestionYieldsNoCellEvidence() {
        assertThat(retriever.retrieve(context("它怎么样"), null, step()).evidence()).isEmpty();
    }

    private AnswerContext context(String query) {
        return new AnswerContext("w", "c", "m2", query, Set.of(), Map.of(), Instant.now(), Set.of("run-1"));
    }

    private RetrievalPlan.Step step() {
        return new RetrievalPlan.Step(ResearchCellEvidenceRetriever.CHANNEL, 8, 1.0d, Map.of());
    }
}

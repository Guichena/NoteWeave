package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.AnswerRunService;
import com.noteweave.security.AuditActorProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** F1：真实迁移及真实事务验证删除后的引用和运行副本，禁止用读接口隐藏替代物理清理。 */
class F1LifecycleRegressionTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    ConversationMessageDeletionService deletion;
    AnswerRunService runs;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:f1-life-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.update("insert into workspace(id, owner_id, name, status) values ('w','local-user','测试','ACTIVE')");
        jdbc.update("insert into conversation(id, workspace_id, title, conversation_type, status) values ('conv','w','测试','QA','ACTIVE')");
        jdbc.update("insert into conversation_message(id, conversation_id, workspace_id, message_seq, role, content) values ('q','conv','w',1,'USER','问题'),('a','conv','w',2,'ASSISTANT','草稿'),('other','conv','w',3,'ASSISTANT','保留回答')");
        jdbc.update("insert into answer_run(id,workspace_id,conversation_id,mode,status,query_message_id,answer_message_id,assistant_request_id,stream_owner,stream_lease_until,created_by,updated_by) values ('run','w','conv','QA','GENERATING','q','a','request','owner',timestampadd(second,150,current_timestamp),'test','test')");
        jdbc.update("insert into message_revision(id,workspace_id,answer_run_id,message_id,revision_no,status,content,created_by) values ('revision','w','run','a',1,'STREAMING','私密草稿','test')");
        jdbc.update("insert into citation(id,workspace_id,title,quote_text,owner_message_id) values ('citation','w','标题','保留引用','a')");
        jdbc.update("insert into message_citation(id,message_id,citation_id,sort_order) values ('mc','a','citation',0)");
        var actor = mock(AuditActorProvider.class);
        when(actor.currentOrSystem("ANSWER")).thenReturn("test");
        runs = new AnswerRunService(jdbc, actor, new SimpleMeterRegistry(), new ObjectMapper());
        deletion = new ConversationMessageDeletionService(jdbc, mock(ConversationService.class),
                new RunReplayRedactionService(jdbc, new ObjectMapper()));
    }

    @Test
    void deletingOwnerPreservesHistoricalCitationSharedWithAnotherMessage() {
        jdbc.update("insert into message_citation(id,message_id,citation_id,sort_order) values ('shared','other','citation',0)");
        tx.executeWithoutResult(s -> deletion.delete("w", "conv", "a"));
        assertThat(jdbc.queryForObject("select quote_text from citation where id='citation'", String.class))
                .isEqualTo("保留引用");
        assertThat(jdbc.queryForObject("select count(*) from message_citation where message_id='a'", Integer.class)).isZero();
    }

    @Test
    void deletingAssistantErasesRevisionAndPreventsLateCompletion() {
        tx.executeWithoutResult(s -> deletion.delete("w", "conv", "a"));
        var outcome = tx.execute(s -> runs.complete("w", "run", "owner", "迟到正文", 1));
        assertThat(outcome).isNotEqualTo(AnswerRunService.CompletionOutcome.COMPLETED);
        assertThat(jdbc.queryForObject("select content from message_revision where id='revision'", String.class)).isEmpty();
        assertThat(jdbc.queryForObject("select content from conversation_message where id='a'", String.class)).isEmpty();
    }

    @Test
    void deletingQueryPreventsLateAssistantCompletion() {
        tx.executeWithoutResult(s -> deletion.delete("w", "conv", "q"));
        var outcome = tx.execute(s -> runs.complete("w", "run", "owner", "迟到正文", 1));
        assertThat(outcome).isNotEqualTo(AnswerRunService.CompletionOutcome.COMPLETED);
        assertThat(jdbc.queryForObject("select content from message_revision where id='revision'", String.class)).isEmpty();
    }

    @Test
    void deletingMessageErasesManifestAndCitationEventsPhysically() {
        jdbc.update("insert into answer_run_evidence_manifest(id,workspace_id,answer_run_id,rank_no,evidence_id,evidence_kind,title,excerpt,content_hash,character_cost) values ('manifest','w','run',1,'e','PASSAGE','私密标题','私密摘录',?,4)", "a".repeat(64));
        runs.persistCitationEvents("w", "run", java.util.List.of("私密引用"));
        tx.executeWithoutResult(s -> deletion.delete("w", "conv", "a"));
        assertThat(jdbc.queryForObject("select excerpt from answer_run_evidence_manifest where id='manifest'", String.class)).isEmpty();
        assertThat(jdbc.queryForList("select payload_json from answer_event where answer_run_id='run'", String.class))
                .noneMatch(v -> v != null && v.contains("私密"));
    }
}

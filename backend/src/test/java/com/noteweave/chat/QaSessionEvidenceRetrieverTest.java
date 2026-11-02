package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.EvidenceRetrievalResult;
import com.noteweave.answer.strategy.RetrievalPlan;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** F4-02：QA 会话证据按规范身份去重、只取有效回合与当前仍有效的来源。 */
class QaSessionEvidenceRetrieverTest {

    private JdbcTemplate jdbc;
    private QaSessionEvidenceRetriever retriever;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:qa-session-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table conversation_message(
                    id varchar(36) primary key, workspace_id varchar(36), conversation_id varchar(36),
                    message_seq int, context_status varchar(32))
                """);
        jdbc.execute("""
                create table citation(
                    id varchar(36) primary key, workspace_id varchar(36), source_id varchar(36),
                    source_snapshot_id varchar(36), source_chunk_id varchar(36), title varchar(300),
                    quote_text longtext, location_info varchar(300), created_at timestamp default current_timestamp)
                """);
        jdbc.execute("create table message_citation(message_id varchar(36), citation_id varchar(36))");
        jdbc.execute("create table source(id varchar(36) primary key, status varchar(32))");
        jdbc.update("insert into source values ('s1','ACTIVE'),('s2','REVOKED')");
        jdbc.update("""
                insert into conversation_message values
                ('m1','w','c',1,'CURRENT'),
                ('m2','w','c',2,'CURRENT'),
                ('m3','w','c',3,'DELETED'),
                ('m4','w','c',4,'CURRENT')
                """);
        jdbc.update("""
                insert into citation(id,workspace_id,source_id,source_snapshot_id,source_chunk_id,title,quote_text)
                values
                ('c1','w','s1','snap-1','k1','资料一','同一分块的引用'),
                ('c2','w','s1','snap-1','k1','资料一','同一分块的引用（重复回合）'),
                ('c3','w','s2','snap-2','k2','已撤销资料','撤销来源的引用'),
                ('c4','w','s1','snap-1','k9','删除回合','删除回合的引用')
                """);
        jdbc.update("""
                insert into message_citation values
                ('m1','c1'),('m2','c2'),('m3','c4'),('m4','c3')
                """);
        retriever = new QaSessionEvidenceRetriever(jdbc);
    }

    @Test
    void returnsOnlyCurrentEligibleCitationsDedupedByCanonicalIdentity() {
        EvidenceRetrievalResult result = retriever.retrieve(context("m4"), null, step());

        assertThat(result.evidence()).hasSize(1);
        var evidence = result.evidence().get(0);
        assertThat(evidence.sourceId()).isEqualTo("s1");
        assertThat(evidence.sourceSnapshotId()).isEqualTo("snap-1");
        assertThat(evidence.passageId()).isEqualTo("k1");
        assertThat(evidence.metadata()).containsEntry("session_citation", "true")
                .containsEntry("match_reason", "SESSION_CITATION");
        assertThat(evidence.accessScope()).isEqualTo("workspace-source:s1");
        assertThat(result.measurements()).containsEntry("session_candidates", 2L)
                .containsEntry("session_deduped", 1L);
    }

    @Test
    void withoutCurrentMessageReturnsNothing() {
        assertThat(retriever.retrieve(context(null), null, step()).evidence()).isEmpty();
    }

    private AnswerContext context(String messageId) {
        return new AnswerContext("w", "c", messageId, "追问", Set.of(), Map.of(), Instant.now(), Set.of());
    }

    private RetrievalPlan.Step step() {
        return new RetrievalPlan.Step(QaSessionEvidenceRetriever.CHANNEL, 8, 0.9d, Map.of());
    }
}

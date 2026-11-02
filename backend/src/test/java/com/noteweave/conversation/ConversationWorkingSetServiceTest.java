package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.context.ConversationWorkingSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** F4-01：工作集按有效回合派生，删除回合不带走仍被其他回合引用的资料。 */
class ConversationWorkingSetServiceTest {

    private JdbcTemplate jdbc;
    private ConversationWorkingSetService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:working-set-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table conversation_message(
                    id varchar(36) primary key, workspace_id varchar(36), conversation_id varchar(36),
                    message_seq int, role varchar(16), context_status varchar(32))
                """);
        jdbc.execute("""
                create table citation(
                    id varchar(36) primary key, workspace_id varchar(36), source_id varchar(36),
                    source_snapshot_id varchar(36), research_run_id varchar(36))
                """);
        jdbc.execute("""
                create table message_citation(message_id varchar(36), citation_id varchar(36))
                """);
        jdbc.update("""
                insert into conversation_message values
                ('m1','w','c',1,'USER','CURRENT'),
                ('m2','w','c',2,'ASSISTANT','CURRENT'),
                ('m3','w','c',3,'ASSISTANT','DELETED')
                """);
        jdbc.update("""
                insert into citation values
                ('c1','w','source-a','snap-a1',null),
                ('c2','w','source-b','snap-b1','run-1'),
                ('c3','w',null,null,null),
                ('c4','w','source-c','snap-c1',null)
                """);
        jdbc.update("""
                insert into message_citation values
                ('m2','c1'),('m2','c2'),('m3','c3'),('m3','c4')
                """);
        service = new ConversationWorkingSetService(jdbc);
    }

    @Test
    void deletedTurnStopsContributingButSharedSourceRemains() {
        ConversationWorkingSet set = service.derive("w", "c", 3);

        assertThat(set.entries())
                .filteredOn(entry -> entry.kind() == ConversationWorkingSet.EntryKind.SOURCE)
                .extracting(ConversationWorkingSet.Entry::refId)
                .containsExactly("source-a", "source-b");
        assertThat(set.entries())
                .filteredOn(entry -> "source-a".equals(entry.refId()))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.contributingMessageIds()).containsExactly("m2");
                    assertThat(entry.lastSeq()).isEqualTo(2);
                    assertThat(entry.weight()).isEqualTo(0.92d);
                    assertThat(entry.versionId()).isEqualTo("snap-a1");
                });
        assertThat(set.exclusions())
                .filteredOn(exclusion -> exclusion.kind() == ConversationWorkingSet.EntryKind.SOURCE)
                .extracting(ConversationWorkingSet.Exclusion::refId)
                .contains("source-c");
        assertThat(set.entries())
                .filteredOn(entry -> entry.kind() == ConversationWorkingSet.EntryKind.RESEARCH_RUN)
                .extracting(ConversationWorkingSet.Entry::refId)
                .containsExactly("run-1");
    }

    @Test
    void weightDecaysWithTurnDistanceAndCitedEvidenceIsBoundedToValidTurns() {
        ConversationWorkingSet set = service.derive("w", "c", 10);

        assertThat(set.entries())
                .filteredOn(entry -> "source-a".equals(entry.refId()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.weight()).isEqualTo(0.36d));
        assertThat(set.entries())
                .filteredOn(entry -> entry.kind() == ConversationWorkingSet.EntryKind.CITED_EVIDENCE)
                .extracting(ConversationWorkingSet.Entry::refId)
                .containsExactly("c1", "c2");
        assertThat(set.count(ConversationWorkingSet.EntryKind.KNOWLEDGE_PAGE)).isZero();
        assertThat(set.derivationVersion()).isEqualTo(ConversationWorkingSetService.DERIVATION_VERSION);
    }
}

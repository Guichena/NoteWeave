package com.noteweave.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** F2-04：会话前提的独立语义、同主体同作用域覆盖、幂等与删除撤销。 */
class ConversationPremiseTest {

    private final PremiseResolver resolver = new PremiseResolver();

    @Test
    void correctionSupersedesTheSameSubjectAndScope() {
        ConversationPremise mysql = premise("premise-mysql", "db.version", "MySQL", 1,
                ConversationPremise.PremiseScope.CONVERSATION, "");
        PremiseOp correct = op(PremiseOp.Action.CORRECT, PremiseOp.Relation.CORRECTION,
                "db.version", "PostgreSQL", ConversationPremise.PremiseScope.CONVERSATION, "",
                "m2", 2, 1);

        var resolution = resolver.resolve(new PremiseResolver.Input(
                "workspace", "conversation", List.of(mysql), List.of(correct)));

        assertThat(resolution.inserted()).singleElement()
                .satisfies(item -> assertThat(item.premiseText()).isEqualTo("PostgreSQL"));
        assertThat(resolution.updated()).singleElement()
                .satisfies(item -> {
                    assertThat(item.premiseId()).isEqualTo("premise-mysql");
                    assertThat(item.status()).isEqualTo(ConversationPremise.STATUS_SUPERSEDED);
                });
    }

    @Test
    void differentSubjectsAndThisTurnScopeCoexist() {
        PremiseOp budget = op(PremiseOp.Action.ASSERT, PremiseOp.Relation.ASSUMPTION,
                "team.budget", "100 万", ConversationPremise.PremiseScope.CONVERSATION, "",
                "m1", 1, 0);
        PremiseOp size = op(PremiseOp.Action.ASSERT, PremiseOp.Relation.ASSUMPTION,
                "team.size", "8 人", ConversationPremise.PremiseScope.THIS_TURN, "",
                "m1", 1, 1);

        var resolution = resolver.resolve(new PremiseResolver.Input(
                "workspace", "conversation", List.of(), List.of(budget, size)));

        assertThat(resolution.state()).hasSize(2);
        assertThat(resolution.state()).extracting(ConversationPremise::subjectKey)
                .containsExactlyInAnyOrder("team.budget", "team.size");
    }

    @Test
    void crossTopicCorrectionDoesNotWithdrawTheOldPremise() {
        ConversationPremise topicA = premise("premise-a", "db.version", "MySQL", 2,
                ConversationPremise.PremiseScope.CURRENT_TOPIC, "topic-a");
        PremiseOp correct = op(PremiseOp.Action.CORRECT, PremiseOp.Relation.CORRECTION,
                "db.version", "PostgreSQL", ConversationPremise.PremiseScope.CURRENT_TOPIC,
                "topic-b", "m4", 4, 0);

        var resolution = resolver.resolve(new PremiseResolver.Input(
                "workspace", "conversation", List.of(topicA), List.of(correct)));

        assertThat(resolution.state())
                .anyMatch(item -> item.premiseId().equals("premise-a")
                        && item.status().equals(ConversationPremise.STATUS_ACTIVE));
        assertThat(resolution.rejections())
                .anyMatch(rejection -> PremiseResolver.REASON_NO_RELIABLE_TARGET.equals(rejection.reason()));
    }

    @Test
    void retractionWithoutReliableTargetIsRejectedAndChangesNothing() {
        PremiseOp retract = op(PremiseOp.Action.RETRACT, PremiseOp.Relation.CORRECTION,
                "db.version", "", ConversationPremise.PremiseScope.CONVERSATION, "",
                "m3", 3, 0);

        var resolution = resolver.resolve(new PremiseResolver.Input(
                "workspace", "conversation", List.of(), List.of(retract)));

        assertThat(resolution.inserted()).isEmpty();
        assertThat(resolution.state()).isEmpty();
        assertThat(resolution.rejections())
                .anyMatch(rejection -> PremiseResolver.REASON_NO_RELIABLE_TARGET.equals(rejection.reason()));
    }

    @Test
    void replayingTheSameSourceMessageIsIdempotent() {
        PremiseOp op = op(PremiseOp.Action.ASSERT, PremiseOp.Relation.ASSUMPTION,
                "db.version", "MySQL", ConversationPremise.PremiseScope.CONVERSATION, "",
                "m1", 1, 0);
        var first = resolver.resolve(new PremiseResolver.Input(
                "workspace", "conversation", List.of(), List.of(op)));
        var second = resolver.resolve(new PremiseResolver.Input(
                "workspace", "conversation", first.state(), List.of(op)));

        assertThat(second.inserted()).isEmpty();
        assertThat(second.updated()).isEmpty();
        assertThat(second.rejections())
                .anyMatch(rejection -> PremiseResolver.REASON_ALREADY_APPLIED.equals(rejection.reason()));
    }

    @Test
    void servicePersistsEffectivePremisesAndRevokesDeletedSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:premise-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("insert into workspace(id,owner_id,name,status) values ('w','local-user','测试','ACTIVE')");
        jdbc.update("""
                insert into conversation(id,workspace_id,title,conversation_type,status)
                values ('c','w','测试','QA','ACTIVE')
                """);
        ConversationPremiseService service = new ConversationPremiseService(jdbc);

        service.apply("w", "c", List.of(op(PremiseOp.Action.ASSERT, PremiseOp.Relation.ASSUMPTION,
                "db.version", "MySQL", ConversationPremise.PremiseScope.CONVERSATION, "",
                "m1", 1, 0)));

        assertThat(service.effective("w", "c", 3)).singleElement()
                .satisfies(item -> assertThat(item.renderAsCondition()).contains("MySQL", "未经资料核实"));
        assertThat(service.effective("w", "c", 0)).isEmpty();

        assertThat(service.revokeBySourceMessage("w", "m1")).isEqualTo(1);
        assertThat(service.effective("w", "c", 3)).isEmpty();
        assertThat(jdbc.queryForObject("select premise_text from conversation_premise", String.class))
                .isEmpty();
    }

    private ConversationPremise premise(String id, String subjectKey, String value, int seq,
                                        ConversationPremise.PremiseScope scope, String topicId) {
        return new ConversationPremise(id, "workspace", "conversation", subjectKey, value,
                "m" + seq, seq, scope, topicId, seq, null, ConversationPremise.STATUS_ACTIVE, "",
                "premise-extraction-v1");
    }

    private PremiseOp op(PremiseOp.Action action, PremiseOp.Relation relation,
                         String subjectKey, String value,
                         ConversationPremise.PremiseScope scope, String topicId,
                         String sourceMessageId, int sourceSeq, int index) {
        return new PremiseOp(action, relation, subjectKey, value, scope, topicId, "",
                sourceMessageId, sourceSeq, "premise-extraction-v1", 0.9, index);
    }
}

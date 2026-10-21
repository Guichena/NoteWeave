package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.conversation.ContextProjectionV2;
import com.noteweave.conversation.ContextV2ShadowSnapshotService;
import com.noteweave.conversation.ConversationConstraintProjectionV2Service;
import com.noteweave.conversation.ConversationContextCompilerV2Service;
import com.noteweave.conversation.ConversationTopicProjectionV2Service;
import com.noteweave.conversation.ConversationTopicSummaryV2Service;
import com.noteweave.conversation.TopicBoundaryReviewService;
import com.noteweave.conversation.constraint.ConstraintIds;
import com.noteweave.conversation.constraint.ConstraintOp;
import com.noteweave.conversation.constraint.ConstraintOpsExtractor;
import com.noteweave.conversation.constraint.ConstraintOpsPrompt;
import com.noteweave.conversation.constraint.ConstraintPolarity;
import com.noteweave.conversation.constraint.ConstraintScope;
import com.noteweave.conversation.constraint.ConstraintSlot;
import com.noteweave.memory.MemoryRuntime;
import com.noteweave.memory.MemoryRuntimePack;
import com.noteweave.security.CurrentUserProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 极性端到端：stub 模型给 ops → 真实合并器 → 落 {@code conversation_constraint_v2}（含 {@code polarity} 列）
 * → 真实编译器按库读回 → 回答提示词渲染成禁止表述。
 * <p>
 * 这一条替代了原先「手工把 projection 文本写成『不要使用表格』再断言渲染结果」的自证测试：
 * 只要极性没有落库、编译器没有读回、渲染没有按极性分支，断言就会失败。
 * 不启动 Spring：内存 H2 + 最小表结构，模型是脚本化的 stub。
 */
class ConstraintPolarityAnswerPromptEndToEndTest {

    private static final String WORKSPACE = "ws-polarity";
    private static final String CONVERSATION = "conv-polarity";
    private static final String ACTOR = "actor-polarity";
    private static final double MIN_CONFIDENCE = 0.55;

    private JdbcTemplate jdbc;
    private StubExtractor extractor;
    private ConversationTopicProjectionV2Service topics;
    private ConversationContextCompilerV2Service compiler;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:constraint-polarity-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        extractor = new StubExtractor();

        ConversationTopicSummaryV2Service summaries = mock(ConversationTopicSummaryV2Service.class);
        when(summaries.ready(anyString(), anyString(), anyInt())).thenReturn(List.of());
        MemoryRuntime memory = mock(MemoryRuntime.class);
        when(memory.recall(any())).thenReturn(MemoryRuntimePack.empty());
        CurrentUserProvider users = mock(CurrentUserProvider.class);
        when(users.requireUserId()).thenReturn(ACTOR);

        topics = new ConversationTopicProjectionV2Service(jdbc, summaries, null,
                mock(TopicBoundaryReviewService.class),
                new ConversationConstraintProjectionV2Service(extractor, MIN_CONFIDENCE),
                new DataSourceTransactionManager(dataSource));
        compiler = new ConversationContextCompilerV2Service(jdbc, mock(WorkspaceAccessGuard.class),
                users, memory, summaries);

        jdbc.update("insert into conversation(id, workspace_id) values (?, ?)", CONVERSATION, WORKSPACE);
    }

    /**
     * 「以后都用英文，不要用表格。」：一条 REQUIRE、一条 FORBID。提示词必须把后者渲染成禁止表述，
     * 而不是一条普通的「不要使用表格」要求。
     */
    @Test
    void forbidRowReachesTheAnswerPromptAsAProhibitedInstruction() {
        userMessage("m1", 1, "以后都用英文，不要用表格。");
        extractor.ops.put("m1", List.of(
                op(ConstraintSlot.LANGUAGE, "英文", ConstraintPolarity.REQUIRE),
                op(ConstraintSlot.TABLE, "format.table", ConstraintPolarity.FORBID)));

        topics.refresh(WORKSPACE, CONVERSATION);

        // 1) 落库：极性是独立列，不是从正文反推的。
        assertThat(polarityOf(ConstraintIds.of("m1", "table", 0))).isEqualTo("FORBID");
        assertThat(polarityOf(ConstraintIds.of("m1", "language", 0))).isEqualTo("REQUIRE");
        assertThat(textOf(ConstraintIds.of("m1", "table", 0))).isEqualTo("不要使用表格");

        // 2) 编译器按库读回极性。
        ContextProjectionV2 projection = compile();
        assertThat(polarityByText(projection))
                .containsEntry("英文", ConstraintPolarity.REQUIRE)
                .containsEntry("不要使用表格", ConstraintPolarity.FORBID);

        // 3) 回答提示词（ChatService 拼进回答属性的那一段）：FORBID 行带「禁止：」前缀，REQUIRE 行保持原样。
        String prompt = answerPrompt(projection);
        assertThat(prompt)
                .contains("## 当前有效的用户约束（冻结会话）")
                .contains("- 英文")
                .contains("- 禁止：不要使用表格")
                .doesNotContain("- 不要使用表格")
                .doesNotContain("format.table");
    }

    /** 正向要求不受影响：没有 FORBID 行时提示词与加列前完全一致。 */
    @Test
    void requireRowKeepsTheOriginalRendering() {
        userMessage("m1", 1, "以后都用英文。");
        extractor.ops.put("m1", List.of(op(ConstraintSlot.LANGUAGE, "英文", ConstraintPolarity.REQUIRE)));

        topics.refresh(WORKSPACE, CONVERSATION);
        String prompt = answerPrompt(compile());

        assertThat(prompt)
                .contains("## 当前有效的用户约束（冻结会话）")
                .contains("- 英文")
                .doesNotContain("禁止");
    }

    private ContextProjectionV2 compile() {
        return compiler.compile(WORKSPACE, ACTOR, CONVERSATION, 1, "继续这个话题", "QA", 12_000);
    }

    /** 回答提示词里承载冻结约束的那一段：与 ChatService.buildAnswerDraft 用的是同一入口。 */
    private static String answerPrompt(ContextProjectionV2 projection) {
        return ChatService.renderChatControlSection(null,
                new ContextV2ShadowSnapshotService.FrozenAnswer("snapshot", "snapshot-sha", projection));
    }

    private static Map<String, ConstraintPolarity> polarityByText(ContextProjectionV2 projection) {
        Map<String, ConstraintPolarity> byText = new HashMap<>();
        for (ContextProjectionV2.UserConstraint constraint : projection.constraints()) {
            byText.put(constraint.text(), constraint.polarity());
        }
        return byText;
    }

    private static ConstraintOp op(ConstraintSlot slot, String value, ConstraintPolarity polarity) {
        return new ConstraintOp(ConstraintOp.Op.ADD, slot, value, ConstraintScope.CONVERSATION,
                null, 0.9, polarity);
    }

    private String polarityOf(String constraintId) {
        return jdbc.queryForObject("""
                select polarity from conversation_constraint_v2 where id = ?
                """, String.class, constraintId);
    }

    private String textOf(String constraintId) {
        return jdbc.queryForObject("""
                select constraint_text from conversation_constraint_v2 where id = ?
                """, String.class, constraintId);
    }

    private void userMessage(String id, int seq, String text) {
        jdbc.update("""
                insert into conversation_message(id, conversation_id, workspace_id, message_seq,
                    role, content, context_status)
                values (?, ?, ?, ?, 'USER', ?, 'CURRENT')
                """, id, CONVERSATION, WORKSPACE, seq, text);
    }

    private void createSchema() {
        jdbc.execute("create table conversation (id varchar(36) primary key, workspace_id varchar(36) not null)");
        jdbc.execute("""
                create table conversation_message (
                    id varchar(36) primary key,
                    conversation_id varchar(36) not null,
                    workspace_id varchar(36) not null,
                    message_seq int not null,
                    role varchar(32) not null,
                    content longtext,
                    content_hash char(64),
                    context_status varchar(24) default 'CURRENT'
                )
                """);
        jdbc.execute("""
                create table conversation_topic_v2 (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    conversation_id varchar(36) not null,
                    anchor_message_id varchar(36) not null,
                    anchor_digest char(64) not null,
                    status varchar(24) not null,
                    rule_version varchar(64) not null
                )
                """);
        jdbc.execute("""
                create table conversation_topic_segment_v2 (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    conversation_id varchar(36) not null,
                    topic_id varchar(36) not null,
                    start_seq int not null,
                    end_seq int not null,
                    decision_status varchar(24) not null,
                    decision_reason varchar(80),
                    rule_version varchar(64),
                    source_digest char(64),
                    degradation_reason varchar(80),
                    boundary_review varchar(24),
                    lock_version int not null default 0,
                    updated_at timestamp not null default current_timestamp
                )
                """);
        // 与 V170 之后的生产表一致：极性独立成列，旧行缺省 REQUIRE。
        jdbc.execute("""
                create table conversation_constraint_v2 (
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    conversation_id varchar(36) not null,
                    source_message_id varchar(36) not null,
                    kind varchar(32) not null,
                    scope varchar(64) not null,
                    constraint_text longtext not null,
                    valid_from_seq int not null,
                    invalid_after_seq int,
                    status varchar(24) not null,
                    superseded_by varchar(36),
                    polarity varchar(16) not null default 'REQUIRE',
                    rule_version varchar(64) not null
                )
                """);
        jdbc.execute("""
                create table conversation_constraint_state_v2 (
                    conversation_id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    processed_seq int not null default 0,
                    steps_json longtext,
                    rule_version varchar(64) not null,
                    lock_version int not null default 0,
                    updated_at timestamp not null default current_timestamp
                )
                """);
    }

    /** 脚本化抽取器：按 messageId 给出预置 ops；没有预置就当作抽取不可用。 */
    private static final class StubExtractor implements ConstraintOpsExtractor {

        final Map<String, List<ConstraintOp>> ops = new HashMap<>();

        @Override
        public Result extract(Request request) {
            List<ConstraintOp> resolved = ops.get(request.messageId());
            if (resolved == null) {
                return new Result.Unusable("NO_STUB");
            }
            return new Result.Ok(resolved, ConstraintOpsPrompt.PROMPT_VERSION);
        }
    }
}

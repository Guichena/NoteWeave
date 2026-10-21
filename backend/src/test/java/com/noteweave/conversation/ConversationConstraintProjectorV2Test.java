package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationConstraintProjectorV2Test {
    private final ConversationConstraintProjectorV2 projector = new ConversationConstraintProjectorV2();

    @Test
    void explicitCorrectionRevokesOldLanguageRuleWithinTopic() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("n1", 1, "USER", "这份报告用英文。"),
                message("n2", 2, "ASSISTANT", "好的，我会用英文。"),
                message("n3", 3, "USER", "更正：不要英文，改用中文。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).status()).isEqualTo("REVOKED");
        assertThat(constraints.get(0).invalidAfterSeq()).isEqualTo(3);
        assertThat(constraints.get(0).supersededBy()).isEqualTo(constraints.get(1).id());
        assertThat(constraints.get(1).status()).isEqualTo("ACTIVE");
        assertThat(constraints.get(1).text()).isEqualTo("中文");
    }

    @Test
    void crossTopicPreferenceIsExplicitAndVagueReferenceCannotBecomeActive() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("f1", 1, "USER", "以后解释技术问题先给结论。"),
                message("f2", 2, "USER", "解释索引选择。"),
                message("f3", 3, "USER", "现在解释 TCP 重传。"),
                message("f4", 4, "USER", "还是用那个格式。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).kind()).isEqualTo("FORMAT");
        // 带"以后"的要求在整个会话内生效，上下文规划时不受话题切换影响
        assertThat(constraints.get(0).scope()).isEqualTo("CONVERSATION");
        assertThat(constraints.get(0).text()).isEqualTo("解释技术问题先给结论");
        assertThat(constraints.get(0).status()).isEqualTo("ACTIVE");
        assertThat(constraints.get(1).status()).isEqualTo("UNRESOLVED");
        assertThat(constraints.get(1).text()).isEmpty();
    }

    @Test
    void commonPhrasingsOfFormatLanguageAndNegationAreRecognized() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("g1", 1, "USER", "介绍一下 Redis 的持久化方式，用表格对比 RDB 和 AOF。"),
                message("g2", 2, "USER", "每次回答都控制在300字以内。"),
                message("g3", 3, "USER", "接下来请用英文回答。"),
                message("g4", 4, "USER", "不要再贴大段配置文件了。"),
                message("g5", 5, "USER", "Redis 的过期键是怎么删除的？")));

        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::kind)
                .containsExactly("FORMAT", "FORMAT", "LANGUAGE", "NEGATION");
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::text)
                .containsExactly("用表格对比 RDB 和 AOF", "回答都控制在300字以内", "英文", "不要再贴大段配置文件了");
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::scope)
                .containsExactly("CURRENT_TOPIC", "CONVERSATION", "CURRENT_TOPIC", "CURRENT_TOPIC");
    }

    @Test
    void negatedLanguageIsNotTakenAsTheRequestedLanguage() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("h1", 1, "USER", "不要用英文，用中文写。")));

        assertThat(constraints).singleElement()
                .satisfies(constraint -> assertThat(constraint.text()).isEqualTo("中文"));
    }

    @Test
    void negatedLanguagePrefixesAreNotTakenAsTheRequestedLanguage() {
        // 否定前缀不能只有"不要/别/不"："不是用英文""禁止使用英文"同样是在拒绝英文
        for (String text : List.of("不是用英文。", "不要用英文。", "不用英文。", "禁止使用英文。",
                "避免用英文回答。", "别用英文。", "不要再用英文。", "不要总是用英文。")) {
            List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                    message("x1", 1, "USER", text)));

            assertThat(constraints).as(text).singleElement().satisfies(constraint -> {
                assertThat(constraint.kind()).as(text).isEqualTo("NEGATION");
                assertThat(constraint.status()).isEqualTo("ACTIVE");
                assertThat(constraint.text()).as(text).isEqualTo(text.substring(0, text.length() - 1));
            });
        }
    }

    @Test
    void negationBetweenPrefixAndCueStillLeavesTheRequestPositive() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("w1", 1, "USER", "不要忘记用英文。")));

        assertThat(constraints).singleElement().satisfies(constraint -> {
            assertThat(constraint.kind()).isEqualTo("LANGUAGE");
            assertThat(constraint.text()).isEqualTo("英文");
        });
    }

    @Test
    void negatedLanguageInOneClauseDoesNotCancelPositiveLanguageInAnother() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("y1", 1, "USER", "不是用英文，用中文写。")));

        assertThat(constraints).singleElement().satisfies(constraint -> {
            assertThat(constraint.kind()).isEqualTo("LANGUAGE");
            assertThat(constraint.text()).isEqualTo("中文");
        });
    }

    @Test
    void negationTakesPrecedenceOverFormatCueInTheSameClause() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("z1", 1, "USER", "不要用表格。"),
                message("z2", 2, "USER", "禁止使用表格对比。")));

        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::kind)
                .containsExactly("NEGATION", "NEGATION");
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::text)
                .containsExactly("不要用表格", "禁止使用表格对比");
    }

    @Test
    void plainNegationPhrasesInConversationDoNotBecomeConstraints() {
        // 评审反例：只表示语气的"不是/不用"没有指向语言/格式/长度目标，不能投影成约束
        for (String text : List.of("这不是我要的。", "不用了。", "不是这个意思。")) {
            assertThat(projector.project(List.of(message("c1", 1, "USER", text)))).as(text).isEmpty();
        }
    }

    @Test
    void plainNegationPhrasesDoNotRevokeOrReplaceEarlierConstraint() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("c1", 1, "USER", "以后用英文。"),
                message("c2", 2, "USER", "这不是我要的。"),
                message("c3", 3, "USER", "不用了。"),
                message("c4", 4, "USER", "不是这个意思。")));

        assertThat(constraints).singleElement().satisfies(constraint -> {
            assertThat(constraint.kind()).isEqualTo("LANGUAGE");
            assertThat(constraint.text()).isEqualTo("英文");
            assertThat(constraint.status()).isEqualTo("ACTIVE");
            assertThat(constraint.invalidAfterSeq()).isNull();
            assertThat(constraint.supersededBy()).isNull();
        });
    }

    @Test
    void negationStillCountsWhenItPointsAtLanguageFormatOrLength() {
        // 正例：否定词后面跟着语言/格式/长度目标，仍然投影成 NEGATION
        for (String text : List.of("不要用英文。", "不是用英文。", "不用表格。", "别太长。", "不要写大段。")) {
            assertThat(projector.project(List.of(message("n1", 1, "USER", text))))
                    .as(text).singleElement().satisfies(constraint -> {
                        assertThat(constraint.kind()).as(text).isEqualTo("NEGATION");
                        assertThat(constraint.status()).isEqualTo("ACTIVE");
                        assertThat(constraint.text()).as(text).isEqualTo(text.substring(0, text.length() - 1));
                    });
        }
    }

    @Test
    void negationWordsEmbeddedInLongerWordsAreNotNegations() {
        // "别人/差别"里的"别"不是否定词："别人用英文写的"是陈述，不能读成"别用英文"
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("b1", 1, "USER", "别人用英文写的。"),
                message("b2", 2, "USER", "差别在于中文和英文的时态。")));

        assertThat(constraints).singleElement().satisfies(constraint -> {
            assertThat(constraint.kind()).isEqualTo("LANGUAGE");
            assertThat(constraint.text()).isEqualTo("英文");
        });
    }

    @Test
    void laterValueOfSameKindRevokesEarlierOneWithoutCorrectionKeyword() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("k1", 1, "USER", "这份报告用英文。"),
                message("k2", 2, "ASSISTANT", "好的，我会用英文。"),
                message("k3", 3, "USER", "用中文。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).kind()).isEqualTo("LANGUAGE");
        assertThat(constraints.get(0).scope()).isEqualTo("CURRENT_TOPIC");
        assertThat(constraints.get(0).status()).isEqualTo("REVOKED");
        assertThat(constraints.get(0).invalidAfterSeq()).isEqualTo(3);
        assertThat(constraints.get(0).supersededBy()).isEqualTo(constraints.get(1).id());
        assertThat(constraints.get(1).kind()).isEqualTo("LANGUAGE");
        assertThat(constraints.get(1).text()).isEqualTo("中文");
        assertThat(constraints.get(1).status()).isEqualTo("ACTIVE");
        assertThat(constraints.get(1).invalidAfterSeq()).isNull();
        assertThat(constraints.get(1).supersededBy()).isNull();
    }

    @Test
    void complementaryConversationWideFormatPreferencesCoexist() {
        // 撤回按语义槽位而不是按 kind："先给结论"和"用表格"是两个互补的格式维度，
        // 后来的表格偏好不能把先前的结论偏好作废。
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("p1", 1, "USER", "以后解释技术问题先给结论。"),
                message("p2", 2, "USER", "以后用表格对比。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).scope()).isEqualTo("CONVERSATION");
        assertThat(constraints.get(0).text()).isEqualTo("解释技术问题先给结论");
        assertThat(constraints.get(0).status()).isEqualTo("ACTIVE");
        assertThat(constraints.get(0).invalidAfterSeq()).isNull();
        assertThat(constraints.get(1).scope()).isEqualTo("CONVERSATION");
        assertThat(constraints.get(1).text()).isEqualTo("用表格对比");
        assertThat(constraints.get(1).status()).isEqualTo("ACTIVE");
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::supersededBy)
                .containsOnlyNulls();
    }

    @Test
    void complementaryFormatSlotsStayActiveTogether() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("m1", 1, "USER", "以后先给结论。"),
                message("m2", 2, "USER", "以后用表格对比。"),
                message("m3", 3, "USER", "每次回答不超过300字。")));

        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::kind)
                .containsExactly("FORMAT", "FORMAT", "FORMAT");
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::scope)
                .containsOnly("CONVERSATION");
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::status)
                .containsOnly("ACTIVE");
    }

    @Test
    void negatedFormatRevokesEarlierPositiveFormatAcrossKinds() {
        // 评审反例：旧「用表格」是 FORMAT，新「不要用表格」是 NEGATION，kind 不同也要撤回
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("t1", 1, "USER", "介绍一下 Redis 的持久化方式，用表格对比 RDB 和 AOF。"),
                message("t2", 2, "USER", "不要再用表格。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).kind()).isEqualTo("FORMAT");
        assertThat(constraints.get(0).text()).isEqualTo("用表格对比 RDB 和 AOF");
        assertThat(constraints.get(0).status()).isEqualTo("REVOKED");
        assertThat(constraints.get(0).invalidAfterSeq()).isEqualTo(2);
        assertThat(constraints.get(0).supersededBy()).isEqualTo(constraints.get(1).id());
        assertThat(constraints.get(1).kind()).isEqualTo("NEGATION");
        assertThat(constraints.get(1).text()).isEqualTo("不要再用表格");
        assertThat(constraints.get(1).status()).isEqualTo("ACTIVE");
    }

    @Test
    void negatedLanguageRevokesEarlierConversationWideLanguage() {
        // 评审反例：旧「以后用英文」是 LANGUAGE:CONVERSATION，新「不要用英文」是 NEGATION，
        // 不撤回会让提示词同时出现「英文」和「不要用英文」
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("v1", 1, "USER", "以后用英文。"),
                message("v2", 2, "USER", "不要用英文。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).scope()).isEqualTo("CONVERSATION");
        assertThat(constraints.get(0).status()).isEqualTo("REVOKED");
        assertThat(constraints.get(0).invalidAfterSeq()).isEqualTo(2);
        assertThat(constraints.get(1).kind()).isEqualTo("NEGATION");
        assertThat(constraints.get(1).status()).isEqualTo("ACTIVE");
    }

    @Test
    void explicitCorrectionRevokesConversationWideLanguageFromTopicScope() {
        // 评审反例：旧「以后用英文」=LANGUAGE:CONVERSATION，新「更正：这份报告改用中文」
        // =LANGUAGE:CURRENT_TOPIC，scope 不同，但有显式更正措辞，旧约束必须撤回
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("x1", 1, "USER", "以后用英文。"),
                message("x2", 2, "USER", "更正：这份报告改用中文。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).scope()).isEqualTo("CONVERSATION");
        assertThat(constraints.get(0).status()).isEqualTo("REVOKED");
        assertThat(constraints.get(0).invalidAfterSeq()).isEqualTo(2);
        assertThat(constraints.get(0).supersededBy()).isEqualTo(constraints.get(1).id());
        assertThat(constraints.get(1).scope()).isEqualTo("CURRENT_TOPIC");
        assertThat(constraints.get(1).text()).isEqualTo("中文");
        assertThat(constraints.get(1).status()).isEqualTo("ACTIVE");
    }

    @Test
    void negatingOneLanguageDoesNotRevokeAnotherLanguagePreference() {
        // 槽位相同但取值不同：「不要用中文」不是对「以后用英文」的更正
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("q1", 1, "USER", "以后用英文。"),
                message("q2", 2, "USER", "不要用中文。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).text()).isEqualTo("英文");
        assertThat(constraints.get(0).status()).isEqualTo("ACTIVE");
        assertThat(constraints.get(1).kind()).isEqualTo("NEGATION");
        assertThat(constraints.get(1).status()).isEqualTo("ACTIVE");
    }

    @Test
    void negationWithoutCueDoesNotRevokeUnrelatedFormat() {
        // 没有指向具体线索的否定要求不占任何已有槽位
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("r1", 1, "USER", "先给结论。"),
                message("r2", 2, "USER", "不要再贴大段配置文件了。")));

        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::kind)
                .containsExactly("FORMAT", "NEGATION");
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::status)
                .containsOnly("ACTIVE");
    }

    @Test
    void correctionOnlyReachesTheSameSlot() {
        // 「更正」只作废同一槽位的旧取值，不牵连其它格式维度
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("y1", 1, "USER", "以后先给结论。"),
                message("y2", 2, "USER", "更正：改用分点列出。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).text()).isEqualTo("先给结论");
        assertThat(constraints.get(0).status()).isEqualTo("ACTIVE");
        assertThat(constraints.get(1).text()).isEqualTo("改用分点列出");
        assertThat(constraints.get(1).status()).isEqualTo("ACTIVE");
    }

    @Test
    void sameSlotInSameTopicSupersedesWithoutCorrectionKeyword() {
        // 同一长度维度里的新取值仍然直接顶掉旧取值，不需要关键词
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("z1", 1, "USER", "解释 Redis 过期键删除策略，回答简短一点。"),
                message("z2", 2, "USER", "继续，Redis 过期键删除再详细一点。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).status()).isEqualTo("REVOKED");
        assertThat(constraints.get(0).invalidAfterSeq()).isEqualTo(2);
        assertThat(constraints.get(1).status()).isEqualTo("ACTIVE");
    }

    @Test
    void constraintsOfDifferentKindOrScopeInTheSameTopicAllStayActive() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("d1", 1, "USER", "每次回答都控制在300字以内。"),
                message("d2", 2, "USER", "不要再贴大段配置文件了。"),
                message("d3", 3, "USER", "先给结论。")));

        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::kind)
                .containsExactly("FORMAT", "NEGATION", "FORMAT");
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::scope)
                .containsExactly("CONVERSATION", "CURRENT_TOPIC", "CURRENT_TOPIC");
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::status)
                .containsOnly("ACTIVE");
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::invalidAfterSeq)
                .containsOnlyNulls();
    }

    @Test
    void sameKindInDifferentTopicsIsNotRevoked() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("t1", 1, "USER", "用表格对比 RDB 和 AOF 的持久化差异。"),
                message("t2", 2, "USER", "换个话题，用表格列出 Redis 过期键的删除策略。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::scope)
                .containsExactly("CURRENT_TOPIC", "CURRENT_TOPIC");
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::status)
                .containsOnly("ACTIVE");
        assertThat(constraints.get(1).invalidAfterSeq()).isNull();
    }

    @Test
    void loneConstraintIsNotSupersededByItselfAndReprojectionIsStable() {
        List<TopicSegmenterV2.Message> messages = List.of(message("s1", 1, "USER", "这份报告用英文。"));

        List<ConversationConstraintProjectorV2.Constraint> first = projector.project(messages);
        assertThat(first).singleElement().satisfies(constraint -> {
            assertThat(constraint.status()).isEqualTo("ACTIVE");
            assertThat(constraint.invalidAfterSeq()).isNull();
            assertThat(constraint.supersededBy()).isNull();
        });
        // 投影会随每一轮重算，同样的输入必须给出同样的结果
        assertThat(projector.project(messages)).isEqualTo(first);
    }

    @Test
    void incidentalNegationWordDoesNotNarrowConversationWideLanguagePreference() {
        // 评审 M5-M15 反例：整句里的「不是」只是语气，同槽位同取值的重申必须保留会话级 scope
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("n1", 1, "USER", "以后用英文。"),
                message("n2", 2, "USER", "这不是重点，继续用英文。")));

        assertThat(constraints).singleElement().satisfies(constraint -> {
            assertThat(constraint.kind()).isEqualTo("LANGUAGE");
            assertThat(constraint.text()).isEqualTo("英文");
            assertThat(constraint.scope()).isEqualTo("CONVERSATION");
            assertThat(constraint.validFromSeq()).isEqualTo(1);
            assertThat(constraint.status()).isEqualTo("ACTIVE");
            assertThat(constraint.invalidAfterSeq()).isNull();
            assertThat(constraint.supersededBy()).isNull();
        });
    }

    @Test
    void incidentalNegationWordWithADifferentValueIsNotACorrection() {
        // 「不是」不在更正词表里：换一个取值但只说本话题时，不能撤回会话级偏好
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("o1", 1, "USER", "以后用英文。"),
                message("o2", 2, "USER", "这不是重点，用中文解释一下。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).scope()).isEqualTo("CONVERSATION");
        assertThat(constraints.get(0).text()).isEqualTo("英文");
        assertThat(constraints.get(0).status()).isEqualTo("ACTIVE");
        assertThat(constraints.get(0).invalidAfterSeq()).isNull();
        assertThat(constraints.get(1).scope()).isEqualTo("CURRENT_TOPIC");
        assertThat(constraints.get(1).text()).isEqualTo("中文");
        assertThat(constraints.get(1).status()).isEqualTo("ACTIVE");
    }

    @Test
    void repeatingTheSameValueInTheSameScopeIsIdempotent() {
        // 同值同 scope 的重申是确认：不新建重复约束，也不把旧约束标成 REVOKED
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("i1", 1, "USER", "以后用英文。"),
                message("i2", 2, "USER", "以后也继续用英文。")));

        assertThat(constraints).singleElement().satisfies(constraint -> {
            assertThat(constraint.sourceMessageId()).isEqualTo("i1");
            assertThat(constraint.scope()).isEqualTo("CONVERSATION");
            assertThat(constraint.status()).isEqualTo("ACTIVE");
            assertThat(constraint.invalidAfterSeq()).isNull();
            assertThat(constraint.supersededBy()).isNull();
        });
    }

    @Test
    void differentValueCorrectionStillNarrowsConversationWidePreference() {
        // 取值不同且有显式更正措辞时，会话级偏好照旧被顶掉
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("j1", 1, "USER", "以后用英文。"),
                message("j2", 2, "USER", "改用中文。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).scope()).isEqualTo("CONVERSATION");
        assertThat(constraints.get(0).status()).isEqualTo("REVOKED");
        assertThat(constraints.get(0).invalidAfterSeq()).isEqualTo(2);
        assertThat(constraints.get(0).supersededBy()).isEqualTo(constraints.get(1).id());
        assertThat(constraints.get(1).scope()).isEqualTo("CURRENT_TOPIC");
        assertThat(constraints.get(1).text()).isEqualTo("中文");
        assertThat(constraints.get(1).status()).isEqualTo("ACTIVE");
    }

    @Test
    void explicitTopicOnlyValueDoesNotRevokeConversationWidePreference() {
        // 明说只在本话题里换取值：会话级偏好继续生效，新取值只是当前话题的补充
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("k1", 1, "USER", "以后用英文。"),
                message("k2", 2, "USER", "这次回答只用中文。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).scope()).isEqualTo("CONVERSATION");
        assertThat(constraints.get(0).text()).isEqualTo("英文");
        assertThat(constraints.get(0).status()).isEqualTo("ACTIVE");
        assertThat(constraints.get(1).scope()).isEqualTo("CURRENT_TOPIC");
        assertThat(constraints.get(1).text()).isEqualTo("中文");
        assertThat(constraints.get(1).status()).isEqualTo("ACTIVE");
    }

    @Test
    void constraintShapedNegationWithTheSameValueStillCountsAsCorrection() {
        // 「不用」从整句更正词表里拿掉后，构成约束形态的否定（"不用英文"）仍然是更正
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("l1", 1, "USER", "以后用英文。"),
                message("l2", 2, "USER", "不用英文。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).status()).isEqualTo("REVOKED");
        assertThat(constraints.get(0).invalidAfterSeq()).isEqualTo(2);
        assertThat(constraints.get(0).supersededBy()).isEqualTo(constraints.get(1).id());
        assertThat(constraints.get(1).kind()).isEqualTo("NEGATION");
        assertThat(constraints.get(1).text()).isEqualTo("不用英文");
        assertThat(constraints.get(1).status()).isEqualTo("ACTIVE");
    }

    @Test
    void buXuIsANegationPrefixSoTheCueIsNotReadAsAPositiveFormat() {
        // 「不许」也是否定词：不能把"不许用表格"读成正向的 format.table
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("u1", 1, "USER", "以后用表格对比。"),
                message("u2", 2, "USER", "不许用表格。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).status()).isEqualTo("REVOKED");
        assertThat(constraints.get(0).invalidAfterSeq()).isEqualTo(2);
        assertThat(constraints.get(1).kind()).isEqualTo("NEGATION");
        assertThat(constraints.get(1).text()).isEqualTo("不许用表格");
        assertThat(constraints.get(1).status()).isEqualTo("ACTIVE");
    }

    private TopicSegmenterV2.Message message(String id, int seq, String role, String text) {
        return new TopicSegmenterV2.Message(id, seq, role, text);
    }
}

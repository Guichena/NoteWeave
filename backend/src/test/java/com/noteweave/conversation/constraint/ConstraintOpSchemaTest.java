package com.noteweave.conversation.constraint;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConstraintOpSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void validArrayIsParsed() {
        ConstraintOpSchema.ParseResult result = ConstraintOpSchema.parse(MAPPER, """
                [{"op":"ADD","slot":"table","value":"format.table","scope":"conversation","confidence":0.9},
                 {"op":"REVOKE","targetId":"constraint-abc"}]
                """);

        assertThat(result.ok()).isTrue();
        assertThat(result.ops()).hasSize(2);
        ConstraintOp add = result.ops().get(0);
        assertThat(add.op()).isEqualTo(ConstraintOp.Op.ADD);
        assertThat(add.slot()).isEqualTo(ConstraintSlot.TABLE);
        assertThat(add.value()).isEqualTo("format.table");
        assertThat(add.scope()).isEqualTo(ConstraintScope.CONVERSATION);
        assertThat(add.confidence()).isEqualTo(0.9);
        ConstraintOp revoke = result.ops().get(1);
        assertThat(revoke.op()).isEqualTo(ConstraintOp.Op.REVOKE);
        assertThat(revoke.slot()).isNull();
        assertThat(revoke.targetId()).isEqualTo("constraint-abc");
    }

    @Test
    void polarityIsParsedAndDefaultsToRequire() {
        ConstraintOpSchema.ParseResult result = ConstraintOpSchema.parse(MAPPER, """
                [{"op":"ADD","slot":"table","value":"format.table","polarity":"FORBID"},
                 {"op":"ADD","slot":"bullets","value":"format.bullets","polarity":"require"},
                 {"op":"ADD","slot":"conclusion"}]
                """);

        assertThat(result.ok()).isTrue();
        assertThat(result.ops()).extracting(ConstraintOp::polarity).containsExactly(
                ConstraintPolarity.FORBID, ConstraintPolarity.REQUIRE, ConstraintPolarity.REQUIRE);
        assertThat(result.ops().get(0).forbids()).isTrue();
        // 「不要表格」按提示词就是 FORBID + 被否定对象的槽位，不是正向 table。
        assertThat(result.ops().get(0).slot()).isEqualTo(ConstraintSlot.TABLE);
    }

    @Test
    void unknownPolarityRejectsTheWholeArray() {
        ConstraintOpSchema.ParseResult result = ConstraintOpSchema.parse(MAPPER,
                "[{\"op\":\"ADD\",\"slot\":\"table\",\"polarity\":\"NEVER\"}]");

        assertThat(result.ok()).isFalse();
        assertThat(result.ops()).isEmpty();
        assertThat(result.failure()).isEqualTo(ConstraintOpSchema.UNKNOWN_POLARITY);
    }

    @Test
    void confidenceDefaultsAndUndeclaredScopeStaysNull() {
        ConstraintOpSchema.ParseResult result = ConstraintOpSchema.parse(MAPPER,
                "[{\"op\":\"ADD\",\"slot\":\"language\",\"value\":\"中文\"}]");

        assertThat(result.ok()).isTrue();
        assertThat(result.ops().get(0).confidence()).isEqualTo(ConstraintOp.DEFAULT_CONFIDENCE);
        // 未声明 scope 不等于「当前话题」：合并器要按「不降级」处理。
        assertThat(result.ops().get(0).scope()).isNull();
    }

    @Test
    void jsonFenceAndSurroundingProseAreStripped() {
        ConstraintOpSchema.ParseResult result = ConstraintOpSchema.parse(MAPPER, """
                好的，结果如下：
                ```json
                [{"op":"NOOP"}]
                ```
                以上。
                """);

        assertThat(result.ok()).isTrue();
        assertThat(result.ops()).hasSize(1);
        assertThat(result.ops().get(0).op()).isEqualTo(ConstraintOp.Op.NOOP);
    }

    @Test
    void emptyArrayIsAccepted() {
        ConstraintOpSchema.ParseResult result = ConstraintOpSchema.parse(MAPPER, "[]");

        assertThat(result.ok()).isTrue();
        assertThat(result.ops()).isEmpty();
        assertThat(result.failure()).isNull();
    }

    @Test
    void opIsCaseInsensitive() {
        ConstraintOpSchema.ParseResult result = ConstraintOpSchema.parse(MAPPER,
                "[{\"op\":\"add\",\"slot\":\"LANGUAGE\"},{\"op\":\"NoOp\"}]");

        assertThat(result.ok()).isTrue();
        assertThat(result.ops()).extracting(ConstraintOp::op)
                .containsExactly(ConstraintOp.Op.ADD, ConstraintOp.Op.NOOP);
        assertThat(result.ops().get(0).slot()).isEqualTo(ConstraintSlot.LANGUAGE);
    }

    @Test
    void unknownOpRejectsTheWholeArray() {
        ConstraintOpSchema.ParseResult result = ConstraintOpSchema.parse(MAPPER, """
                [{"op":"ADD","slot":"table"},
                 {"op":"REPLACE","slot":"table"}]
                """);

        assertThat(result.ok()).isFalse();
        assertThat(result.ops()).isEmpty();
        assertThat(result.failure()).isEqualTo(ConstraintOpSchema.UNKNOWN_OP);
    }

    @Test
    void unknownSlotIsRejected() {
        ConstraintOpSchema.ParseResult result = ConstraintOpSchema.parse(MAPPER,
                "[{\"op\":\"ADD\",\"slot\":\"answer.language\"}]");

        assertThat(result.ok()).isFalse();
        assertThat(result.failure()).isEqualTo(ConstraintOpSchema.UNKNOWN_SLOT);
    }

    @Test
    void unknownScopeIsRejected() {
        ConstraintOpSchema.ParseResult result = ConstraintOpSchema.parse(MAPPER,
                "[{\"op\":\"ADD\",\"slot\":\"table\",\"scope\":\"forever\"}]");

        assertThat(result.ok()).isFalse();
        assertThat(result.failure()).isEqualTo(ConstraintOpSchema.UNKNOWN_SCOPE);
    }

    @Test
    void addAndUpdateRequireSlot() {
        assertThat(ConstraintOpSchema.parse(MAPPER, "[{\"op\":\"ADD\",\"value\":\"中文\"}]").failure())
                .isEqualTo(ConstraintOpSchema.MISSING_SLOT);
        assertThat(ConstraintOpSchema.parse(MAPPER, "[{\"op\":\"UPDATE\",\"targetId\":\"constraint-1\"}]").failure())
                .isEqualTo(ConstraintOpSchema.MISSING_SLOT);
    }

    @Test
    void revokeRequiresTargetIdOrSlot() {
        assertThat(ConstraintOpSchema.parse(MAPPER, "[{\"op\":\"REVOKE\"}]").failure())
                .isEqualTo(ConstraintOpSchema.MISSING_TARGET);

        ConstraintOpSchema.ParseResult bySlot = ConstraintOpSchema.parse(MAPPER,
                "[{\"op\":\"REVOKE\",\"slot\":\"table\"}]");
        assertThat(bySlot.ok()).isTrue();
        assertThat(bySlot.ops().get(0).slot()).isEqualTo(ConstraintSlot.TABLE);
    }

    @Test
    void languageForbidWithoutValueInvalidatesTheWholeArray() {
        // 「不要用中文」缺 value：合并器写不出可渲染的禁止项，只能丢掉这条要求。
        // 不能当成「模型认为没有约束」放过——那会让水位越过这条消息，要求永久丢失。
        ConstraintOpSchema.ParseResult add = ConstraintOpSchema.parse(MAPPER,
                "[{\"op\":\"ADD\",\"slot\":\"language\",\"polarity\":\"forbid\",\"confidence\":0.9}]");
        assertThat(add.ok()).isFalse();
        assertThat(add.ops()).isEmpty();
        assertThat(add.failure()).isEqualTo(ConstraintOpSchema.MISSING_FORBID_VALUE);

        assertThat(ConstraintOpSchema.parse(MAPPER,
                "[{\"op\":\"UPDATE\",\"targetId\":\"constraint-1\",\"slot\":\"language\",\"polarity\":\"forbid\"}]")
                .failure())
                .isEqualTo(ConstraintOpSchema.MISSING_FORBID_VALUE);

        // 同批里的合法 op 一起作废：一半的 ops 比没有 ops 更危险。
        ConstraintOpSchema.ParseResult mixed = ConstraintOpSchema.parse(MAPPER, """
                [{"op":"ADD","slot":"language","polarity":"forbid"},
                 {"op":"ADD","slot":"table","value":"format.table","confidence":0.9}]
                """);
        assertThat(mixed.ok()).isFalse();
        assertThat(mixed.ops()).isEmpty();
    }

    @Test
    void languageForbidWithValueAndLanguageRevokeWithoutValueStayValid() {
        ConstraintOpSchema.ParseResult forbid = ConstraintOpSchema.parse(MAPPER,
                "[{\"op\":\"ADD\",\"slot\":\"language\",\"value\":\"中文\",\"polarity\":\"forbid\"}]");
        assertThat(forbid.ok()).isTrue();
        assertThat(forbid.ops().get(0).value()).isEqualTo("中文");
        assertThat(forbid.ops().get(0).forbids()).isTrue();

        // REVOKE 不落新行，没有 value 仍然合法：撤回整个语言槽位。
        assertThat(ConstraintOpSchema.parse(MAPPER, "[{\"op\":\"REVOKE\",\"slot\":\"language\"}]").ok()).isTrue();
    }

    @Test
    void confidenceOutsideTheUnitIntervalIsRejected() {
        assertThat(ConstraintOpSchema.parse(MAPPER,
                "[{\"op\":\"ADD\",\"slot\":\"table\",\"confidence\":1.5}]").failure())
                .isEqualTo(ConstraintOpSchema.INVALID_CONFIDENCE);
        assertThat(ConstraintOpSchema.parse(MAPPER,
                "[{\"op\":\"ADD\",\"slot\":\"table\",\"confidence\":-0.1}]").failure())
                .isEqualTo(ConstraintOpSchema.INVALID_CONFIDENCE);
        assertThat(ConstraintOpSchema.parse(MAPPER,
                "[{\"op\":\"ADD\",\"slot\":\"table\",\"confidence\":\"0.9\"}]").failure())
                .isEqualTo(ConstraintOpSchema.INVALID_CONFIDENCE);
    }

    @Test
    void nonJsonAndTruncatedJsonAreRejected() {
        assertThat(ConstraintOpSchema.parse(MAPPER, "这次没有约束").failure()).isEqualTo(ConstraintOpSchema.NOT_JSON);
        assertThat(ConstraintOpSchema.parse(MAPPER, "  ").failure()).isEqualTo(ConstraintOpSchema.NOT_JSON);
        assertThat(ConstraintOpSchema.parse(MAPPER, "[{\"op\":\"ADD\",\"slot\":\"table\"").failure())
                .isEqualTo(ConstraintOpSchema.NOT_JSON);
        assertThat(ConstraintOpSchema.parse(MAPPER, "[{\"op\":\"ADD\",]").failure())
                .isEqualTo(ConstraintOpSchema.NOT_JSON);
    }

    @Test
    void nonObjectItemIsRejected() {
        ConstraintOpSchema.ParseResult result = ConstraintOpSchema.parse(MAPPER, "[\"ADD\"]");

        assertThat(result.ok()).isFalse();
        assertThat(result.failure()).isEqualTo(ConstraintOpSchema.ITEM_NOT_OBJECT);
    }

    @Test
    void moreThanEightOpsAreRejected() {
        String tooMany = "[" + "{\"op\":\"NOOP\"},".repeat(8) + "{\"op\":\"NOOP\"}]";

        assertThat(ConstraintOpSchema.parse(MAPPER, tooMany).failure())
                .isEqualTo(ConstraintOpSchema.TOO_MANY_OPS);
        assertThat(ConstraintOpSchema.parse(MAPPER, "[" + "{\"op\":\"NOOP\"},".repeat(7) + "{\"op\":\"NOOP\"}]").ok())
                .isTrue();
    }

    @Test
    void unknownFieldsAreIgnored() {
        ConstraintOpSchema.ParseResult result = ConstraintOpSchema.parse(MAPPER,
                "[{\"op\":\"ADD\",\"slot\":\"table\",\"reason\":\"用户要求对比\",\"confidence\":0.8,\"extra\":[1,2]}]");

        assertThat(result.ok()).isTrue();
        assertThat(result.ops()).hasSize(1);
    }

    @Test
    void valueLongerThanTheCapIsRejected() {
        String longValue = "中".repeat(ConstraintOp.MAX_VALUE_CHARS + 1);

        assertThat(ConstraintOpSchema.parse(MAPPER,
                "[{\"op\":\"ADD\",\"slot\":\"language\",\"value\":\"" + longValue + "\"}]").failure())
                .isEqualTo(ConstraintOpSchema.VALUE_TOO_LONG);
        assertThat(ConstraintOpSchema.parse(MAPPER,
                "[{\"op\":\"ADD\",\"slot\":\"language\",\"value\":\"" + "中".repeat(ConstraintOp.MAX_VALUE_CHARS) + "\"}]")
                .ok()).isTrue();
    }

    @Test
    void activeConstraintListIsNotRequiredToValidateOps() {
        ConstraintOpSchema.ParseResult result = ConstraintOpSchema.parse(MAPPER,
                "[{\"op\":\"UPDATE\",\"slot\":\"detail\",\"value\":\"简短\",\"scope\":\"current-topic\","
                        + "\"targetId\":\"constraint-x\",\"confidence\":0.6}]");

        assertThat(result.ok()).isTrue();
        assertThat(List.copyOf(result.ops())).hasSize(1);
        assertThat(result.ops().get(0).targetId()).isEqualTo("constraint-x");
    }
}

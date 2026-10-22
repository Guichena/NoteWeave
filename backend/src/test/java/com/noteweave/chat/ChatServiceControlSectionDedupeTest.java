package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.conversation.ContextProjectionV2;
import com.noteweave.conversation.ContextV2ShadowSnapshotService;
import com.noteweave.memory.MemoryControlPackResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatServiceControlSectionDedupeTest {

    @Test
    void rendersSharedUserPreferenceOnlyOnceAndKeepsFrozenWording() throws Exception {
        MemoryControlPackResponse pack = pack(List.of("请用中文回答",
                "回答控制在三段以内"), List.of(), List.of(), List.of(), List.of(), List.of());
        var frozen = frozen("请用中文回答。");

        String rendered = ChatService.renderChatControlSection(pack, frozen);

        assertThat(rendered).containsOnlyOnce("请用中文回答");
        assertThat(rendered)
                .contains("## 当前有效的用户约束（冻结会话）")
                .contains("- 请用中文回答。")
                .doesNotContain("- 风格约束：请用中文回答；")
                .contains("- 风格约束：回答控制在三段以内")
                .contains("## 表达控制");
    }

    @Test
    void dropsControlSectionWhenEveryControlValueRepeatsAFrozenConstraint() throws Exception {
        MemoryControlPackResponse pack = pack(List.of("请用中文回答"),
                List.of("请用中文回答"), List.of(), List.of(), List.of(), List.of());
        var frozen = frozen("请用中文回答");

        String rendered = ChatService.renderChatControlSection(pack, frozen);

        assertThat(rendered).doesNotContain("## 表达控制");
        assertThat(rendered).containsOnlyOnce("请用中文回答");
        assertThat(rendered).contains("- 请用中文回答");
    }

    @Test
    void deduplicatesRepeatedControlValuesAcrossDifferentControlFields() throws Exception {
        MemoryControlPackResponse pack = pack(List.of("回答控制在三段以内"),
                List.of("回答控制在三段以内"), List.of("回答控制在三段以内"),
                List.of("不使用网络流行语"), List.of("先给结论"), List.of("不使用网络流行语"));
        var frozen = frozen("不使用网络流行语！");

        String rendered = ChatService.renderChatControlSection(pack, frozen);

        assertThat(rendered).containsOnlyOnce("回答控制在三段以内");
        assertThat(rendered).containsOnlyOnce("不使用网络流行语");
        assertThat(rendered)
                .contains("- 风格约束：回答控制在三段以内")
                .contains("- 交互策略：先给结论")
                .doesNotContain("- 结构约束：")
                .doesNotContain("- 术语口径：")
                .doesNotContain("- 复核清单：");
    }

    @Test
    void keepsControlSectionIntactWhenNoFrozenConstraintsExist() {
        MemoryControlPackResponse pack = pack(List.of("请用中文回答"), List.of(), List.of(),
                List.of(), List.of(), List.of());

        String rendered = ChatService.renderChatControlSection(pack, null);

        assertThat(rendered).contains("- 风格约束：请用中文回答");
        assertThat(rendered).doesNotContain("当前有效的用户约束");
    }

    @Test
    void ignoresBlankControlValuesAndPunctuationDifferencesWhenDeduplicating() throws Exception {
        MemoryControlPackResponse pack = pack(List.of("  ", "请 用中文 回答"),
                List.of(), List.of(), List.of(), List.of(), List.of());
        var frozen = frozen("请用中文回答");

        assertThat(ChatService.normalizeControlText("请 用中文，回答！")).isEqualTo("请用中文回答");
        assertThat(ChatService.renderChatControlSection(pack, frozen))
                .containsOnlyOnce("请用中文回答");
    }

    @Test
    void rendersEvidencePolicyLineWhenPackCarriesEvidencePolicy() {
        MemoryControlPackResponse pack = packWithEvidencePolicy(
                List.of("仅使用近 90 天内的资料", "无来源时明确说明"),
                List.of("请用中文回答"),
                List.of(), List.of(), List.of(), List.of(), List.of());

        String rendered = ChatService.renderChatControlSection(pack, null);

        assertThat(rendered)
                .contains("## 表达控制")
                .contains("- 证据口径：仅使用近 90 天内的资料；无来源时明确说明");
    }

    @Test
    void omitsEvidencePolicyLineWhenPackCarriesNoEvidencePolicy() {
        MemoryControlPackResponse pack = pack(List.of("请用中文回答"), List.of(), List.of(),
                List.of(), List.of(), List.of());

        String rendered = ChatService.renderChatControlSection(pack, null);

        assertThat(rendered).contains("- 风格约束：请用中文回答");
        assertThat(rendered).doesNotContain("- 证据口径：");
    }

    @Test
    void keepsControlSectionWhenOnlyEvidencePolicyIsPresent() {
        MemoryControlPackResponse pack = packWithEvidencePolicy(
                List.of("引用需可追溯"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        String rendered = ChatService.renderChatControlSection(pack, null);

        assertThat(rendered)
                .contains("## 表达控制")
                .contains("- 证据口径：引用需可追溯");
    }

    @Test
    void dropsEvidencePolicyLineWhenItRepeatsAFrozenConstraint() throws Exception {
        MemoryControlPackResponse pack = packWithEvidencePolicy(
                List.of("仅使用近 90 天内的资料"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var frozen = frozen("仅使用近 90 天内的资料");

        String rendered = ChatService.renderChatControlSection(pack, frozen);

        assertThat(rendered).doesNotContain("## 表达控制");
        assertThat(rendered).containsOnlyOnce("仅使用近 90 天内的资料");
    }

    @Test
    void skipsFrozenConstraintBlockWhenTheConversationWindowAlreadyCarriesIt() throws Exception {
        MemoryControlPackResponse pack = pack(List.of("请用中文回答", "回答控制在三段以内"),
                List.of(), List.of(), List.of(), List.of(), List.of());
        var frozen = frozen("请用中文回答。");

        String rendered = ChatService.renderChatControlSection(pack, frozen, false);

        // 窗口里已经有「当前有效的用户约束：- 请用中文回答。」，控制区不再重复整块约束
        assertThat(rendered).doesNotContain("当前有效的用户约束");
        // 与冻结约束重复的控制项仍然被去重，剩下的控制项照常输出
        assertThat(rendered).doesNotContain("请用中文回答");
        assertThat(rendered)
                .contains("## 表达控制")
                .contains("- 风格约束：回答控制在三段以内");
    }

    @Test
    void rendersFrozenConstraintBlockByDefaultWhenNoWindowIsApplied() throws Exception {
        MemoryControlPackResponse pack = pack(List.of("请用中文回答", "回答控制在三段以内"),
                List.of(), List.of(), List.of(), List.of(), List.of());
        var frozen = frozen("请用中文回答。");

        String rendered = ChatService.renderChatControlSection(pack, frozen, true);

        assertThat(rendered)
                .contains("## 当前有效的用户约束（冻结会话）")
                .containsOnlyOnce("请用中文回答");
    }

    private static MemoryControlPackResponse pack(
            List<String> styleConstraints,
            List<String> structureConstraints,
            List<String> terminologyPolicy,
            List<String> forbiddenPatterns,
            List<String> interactionPolicy,
            List<String> reviewChecklist
    ) {
        return packWithEvidencePolicy(List.of(), styleConstraints, structureConstraints, terminologyPolicy,
                forbiddenPatterns, interactionPolicy, reviewChecklist);
    }

    private static MemoryControlPackResponse packWithEvidencePolicy(
            List<String> evidencePolicy,
            List<String> styleConstraints,
            List<String> structureConstraints,
            List<String> terminologyPolicy,
            List<String> forbiddenPatterns,
            List<String> interactionPolicy,
            List<String> reviewChecklist
    ) {
        return new MemoryControlPackResponse(
                "answer_control",
                "message-1",
                "",
                styleConstraints,
                structureConstraints,
                terminologyPolicy,
                forbiddenPatterns,
                evidencePolicy,
                interactionPolicy,
                reviewChecklist,
                List.of("memory-1"),
                List.of(),
                null
        );
    }

    private static ContextV2ShadowSnapshotService.FrozenAnswer frozen(String constraintText)
            throws Exception {
        var projection = new ContextProjectionV2(
                "context-projection-v2",
                "context-window-v2-test",
                "workspace",
                "actor",
                "conversation",
                2,
                "第二种写入顺序呢？",
                List.of(),
                List.of(),
                List.of(new ContextProjectionV2.UserConstraint("constraint", "message-prev", "USER",
                        "FORMAT", "CONVERSATION", constraintText, 1, null, "ACTIVE")),
                List.of(),
                List.of(),
                4096,
                128,
                List.of(),
                "FULL"
        );
        return new ContextV2ShadowSnapshotService.FrozenAnswer("snapshot", sha("snapshot"), projection);
    }

    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}

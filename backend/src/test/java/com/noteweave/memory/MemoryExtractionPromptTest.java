package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 记忆抽取提示词的单元测试：只读常量，不启动 Spring、不调用模型。
 * <p>
 * 锁住 live 评测暴露的两个失败点：
 * <ul>
 *   <li>{@code remember-short-answers-is-a-preference-not-feedback}：
 *       「记住：以后回答不要太长」是面向以后的长期偏好，feedback 必须是 false，不能被当成对上一轮的差评；</li>
 *   <li>{@code variable-name-too-long-is-not-user-feedback}：
 *       评论代码/资料本身（变量名太长）既不是偏好也不是反馈，输出 []。</li>
 * </ul>
 * 注意：第一行被 {@code MemoryExtractionModelStub} 用来识别抽取调用，这里也一并锁住。
 */
class MemoryExtractionPromptTest {

    private static final Pattern EXAMPLE_SLOT = Pattern.compile("\"slot\":\"([^\"]+)\"");

    @Test
    void promptStillStartsWithTheHeadTheStubUsesToDetectExtractionCalls() {
        assertThat(MemoryConversationExtractionService.SYSTEM_PROMPT)
                .startsWith(MemoryExtractionModelStub.EXTRACTION_PROMPT_HEAD);
    }

    @Test
    void durableWordingIsTaughtAsPreferenceWithFeedbackFalse() {
        String prompt = MemoryConversationExtractionService.SYSTEM_PROMPT;

        // 「面向以后的指令不算反馈」要有明确定义，不能只在 feedback 例子列「太长了」
        assertThat(prompt).contains("面向以后的指令不算反馈");
        assertThat(prompt).contains("feedback 也是 false");
        // 长期措辞的 few-shot 必须示范 feedback=false
        assertThat(prompt).contains("\"feedback\":false");
    }

    @Test
    void commentingOnCodeOrContentIsTaughtAsAnEmptyResult() {
        String prompt = MemoryConversationExtractionService.SYSTEM_PROMPT;

        // 变量名/字段名/命名风格属于资料本身，不是对回答的反馈
        assertThat(prompt).contains("变量名");
        assertThat(prompt).contains("不是对助手回答的反馈");
        // 示范输出空数组
        assertThat(prompt).contains("→ []");
    }

    @Test
    void realFeedbackIsStillTaughtWithFeedbackTrue() {
        String prompt = MemoryConversationExtractionService.SYSTEM_PROMPT;

        // 真差评的语义不能被收紧掉：「先评价上一条回答，再提要求」才算 feedback
        assertThat(prompt).contains("先说");
        assertThat(prompt).contains("\"feedback\":true");
    }

    @Test
    void examplesOnlyUseDeclaredSlots() {
        List<String> slots = new ArrayList<>();
        Matcher matcher = EXAMPLE_SLOT.matcher(MemoryConversationExtractionService.SYSTEM_PROMPT);
        while (matcher.find()) {
            slots.add(matcher.group(1));
        }

        assertThat(slots).as("few-shot 至少要示范一条偏好").isNotEmpty();
        assertThat(slots).allSatisfy(slot -> assertThat(MemorySlots.ALLOWED_SLOTS).contains(slot));
    }
}

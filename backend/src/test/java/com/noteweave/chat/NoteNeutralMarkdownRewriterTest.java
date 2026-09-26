package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class NoteNeutralMarkdownRewriterTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final NoteNeutralMarkdownRewriter rewriter =
            new NoteNeutralMarkdownRewriter(true, meterRegistry);

    @Test
    void shouldExtractDirectAnswerAndDropProcessCards() {
        String answer = """
                ## 直接回答
                Note 链路先定位资料，再打开原文窗口抽取可引用结论。

                ## 资料定位
                【候选资料】
                - 《阶段3》：metadata

                ## 深读窗口
                window body
                """;

        NoteNeutralMarkdownRewriter.RewriteResult result = rewriter.rewrite("Note 知识点", answer);

        assertThat(result.mode()).isEqualTo("template");
        assertThat(result.content()).startsWith("# Note 知识点");
        assertThat(result.content()).contains("先定位资料");
        assertThat(result.content()).doesNotContain("资料定位");
        assertThat(result.content()).doesNotContain("深读窗口");
        assertThat(result.content()).doesNotContain("候选资料");
    }

    @Test
    void shouldFallbackToTemplateWhenLlmDisabled() {
        ChatLlmClient disabled = new ChatLlmClient() {
            @Override
            public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                     java.util.function.Consumer<String> onToken) {
                throw new IllegalStateException("should not call");
            }

            @Override
            public boolean isEnabled() {
                return false;
            }
        };

        NoteNeutralMarkdownRewriter.RewriteResult result = rewriter.polishWithLlm(
                disabled,
                "标题",
                "## 直接回答\n结论正文\n\n## 资料定位\n过程"
        );

        assertThat(result.mode()).isEqualTo("template");
        assertThat(result.fallbackReason()).isEqualTo("NOTE_POLISH_LLM_UNAVAILABLE");
        assertThat(meterRegistry.counter(
                "noteweave.note.polish.fallback", "reason", "NOTE_POLISH_LLM_UNAVAILABLE").count())
                .isEqualTo(1.0);
        assertThat(result.content()).contains("结论正文");
        assertThat(result.content()).doesNotContain("资料定位");
    }

    @Test
    void shouldFailClosedWhenLlmFailsAndFallbackIsDisabled() {
        ChatLlmClient failing = new ChatLlmClient() {
            @Override
            public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                     java.util.function.Consumer<String> onToken) {
                throw new IllegalStateException("provider unavailable");
            }

            @Override
            public boolean isEnabled() {
                return true;
            }
        };
        NoteNeutralMarkdownRewriter failClosed =
                new NoteNeutralMarkdownRewriter(false, new SimpleMeterRegistry());

        assertThatThrownBy(() -> failClosed.polishWithLlm(failing, "title", "long enough source content"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo("NOTE_POLISH_LLM_FAILED");
                    assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
    }
}

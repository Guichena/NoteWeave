package com.noteweave.preference;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.memory.MemoryControlPackResponse;
import org.junit.jupiter.api.Test;

/** F5-02：控制包结构化参数编译与不支持项显式保留。 */
class PreferenceParameterCompilerTest {

    @Test
    void compilesSupportedPreferencesIntoPromptParameters() {
        MemoryControlPackResponse pack = pack(
                java.util.List.of("用中文回答", "控制在300字以内", "尽量简短"),
                java.util.List.of("用表格列出", "先给结论"),
                java.util.List.of("术语甲"),
                java.util.List.of("不要标注来源"),
                java.util.List.of("标注来源", "只用工作台资料"),
                java.util.List.of());

        PreferenceParameterCompiler.Compiled compiled = PreferenceParameterCompiler.compile(pack);

        assertThat(compiled.promptLine())
                .contains("language=中文")
                .contains("max_chars=300")
                .contains("detail=short")
                .contains("table")
                .contains("conclusion_first")
                .contains("citations=false")
                .contains("terminology=术语甲")
                .contains("source_policy=workspace_only");
        assertThat(compiled.consumed())
                .contains("用中文回答", "不要标注来源", "术语甲");
        assertThat(compiled.unsupported()).isEmpty();
    }

    @Test
    void unsupportedInteractionPolicyIsReportedNotSilentlyDropped() {
        MemoryControlPackResponse pack = pack(
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of("先寒暄两句再回答"));

        PreferenceParameterCompiler.Compiled compiled = PreferenceParameterCompiler.compile(pack);

        assertThat(compiled.promptLine()).isEmpty();
        assertThat(compiled.unsupported()).singleElement()
                .satisfies(item -> assertThat(item.reason()).isEqualTo("UNSUPPORTED_TARGET"));
    }

    @Test
    void nullPackIsEmpty() {
        assertThat(PreferenceParameterCompiler.compile(null).promptLine()).isEmpty();
    }

    private MemoryControlPackResponse pack(
            java.util.List<String> style,
            java.util.List<String> structure,
            java.util.List<String> terminology,
            java.util.List<String> forbidden,
            java.util.List<String> evidence,
            java.util.List<String> interaction
    ) {
        return new MemoryControlPackResponse(
                "CHAT", "chat", "NOTE", style, structure, terminology, forbidden,
                evidence, interaction, java.util.List.of(), java.util.List.of(),
                java.util.List.of(), null);
    }
}

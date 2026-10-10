package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.chat.ChatLlmClient;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class WikiConceptSuggesterTest {

    @Test
    void fileNameFragmentsTimestampsTypeTagsAndSentencesNeverBecomeConceptPages() {
        assertThat(WikiConceptSuggester.filter(List.of(
                "00", "00:16", "user_upload", "wav", "PDF", "今天的周会讨论产物生成模块", "第一，纪要先给结论",
                "混合检索", "RRF 融合", "混合检索", "a")))
                .containsExactly("混合检索", "RRF 融合");
    }

    @Test
    void llmSuggestionsAreFilteredAndRulesAreTheFallback() {
        WikiConceptSuggester withModel = new WikiConceptSuggester(new FixedLlm(
                "概念如下：[\"缓存一致性\", \"延迟双删\", \"2026\", \"binlog 订阅\", \"cache-consistency.md\"]"));
        assertThat(withModel.suggest("cache-consistency.md", "缓存方案", "[]", "正文"))
                .containsExactly("缓存一致性", "延迟双删", "binlog 订阅");

        WikiConceptSuggester noUsableOutput = new WikiConceptSuggester(new FixedLlm("没有合适的概念"));
        assertThat(noUsableOutput.suggest("Retrieval Design.md", "", "[\"RAG\",\"user_upload\"]", ""))
                .containsExactly("RAG", "Retrieval", "Design");
    }

    @Test
    void existingConceptTitlesAreOfferedForReuse() {
        assertThat(WikiConceptSuggester.prompt("a.md", "", "正文", List.of("binlog 订阅", "延迟双删")))
                .contains("工作台已有的概念页：binlog 订阅、延迟双删")
                .contains("直接使用上面的原标题");
        assertThat(WikiConceptSuggester.prompt("a.md", "", "正文", List.of())).doesNotContain("工作台已有的概念页");
    }

    private record FixedLlm(String output) implements ChatLlmClient {
        @Override
        public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                 Consumer<String> onToken) {
            return output;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }
}

package com.noteweave.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.chat.ChatLlmClient;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** F2-04：前提抽取的结构校验与第三方陈述过滤。 */
class LlmConversationPremiseExtractorTest {

    @Test
    void parsesUserAssertionsAndSkipsThirdPartyStatements() {
        FakeLlm llm = new FakeLlm("""
                {"premises":[
                  {"action":"assert","relation":"assumption","subject_key":"team.stack",
                   "value":"用 Java 17","scope":"conversation","confidence":0.9},
                  {"action":"assert","relation":"third-party","subject_key":"someone.stack",
                   "value":"用 Kotlin","scope":"conversation","confidence":0.9}
                ]}
                """);
        LlmConversationPremiseExtractor extractor = new LlmConversationPremiseExtractor(llm);

        var operations = extractor.extract("w", "c", "m1", 3, "我们团队用 Java 17");

        assertThat(operations).singleElement().satisfies(operation -> {
            assertThat(operation.action()).isEqualTo(PremiseOp.Action.ASSERT);
            assertThat(operation.subjectKey()).isEqualTo("team.stack");
            assertThat(operation.value()).isEqualTo("用 Java 17");
            assertThat(operation.scope()).isEqualTo(ConversationPremise.PremiseScope.CONVERSATION);
            assertThat(operation.sourceMessageId()).isEqualTo("m1");
            assertThat(operation.sourceSeq()).isEqualTo(3);
            assertThat(operation.premiseId()).startsWith("premise-");
        });
    }

    @Test
    void malformedOutputYieldsNoOperations() {
        LlmConversationPremiseExtractor extractor =
                new LlmConversationPremiseExtractor(new FakeLlm("不是 JSON"));

        assertThat(extractor.extract("w", "c", "m1", 1, "问题")).isEmpty();
    }

    @Test
    void disabledModelYieldsNoOperations() {
        FakeLlm llm = new FakeLlm("{\"premises\":[]}");
        llm.enabled = false;

        assertThat(new LlmConversationPremiseExtractor(llm)
                .extract("w", "c", "m1", 1, "问题")).isEmpty();
    }

    private static final class FakeLlm implements ChatLlmClient {
        private final String output;
        private boolean enabled = true;

        private FakeLlm(String output) {
            this.output = output;
        }

        @Override
        public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                 Consumer<String> onToken) {
            return output;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }
    }
}

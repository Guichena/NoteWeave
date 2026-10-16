package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.chat.ChatLlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class MemoryFeedbackAspectJudgeTest {

    @Test
    void rulesClassifyLengthLanguageAndTheFixedContentReasons() {
        ScriptedClient client = new ScriptedClient();

        assertThat(MemoryFeedbackAspectJudge.judge("太长了", client).aspect()).isEqualTo("LENGTH");
        assertThat(MemoryFeedbackAspectJudge.judge("别用英文", client).aspect()).isEqualTo("LANGUAGE");
        assertThat(MemoryFeedbackAspectJudge.judge("请用表格", client).aspect()).isEqualTo("FORMAT");
        assertThat(MemoryFeedbackAspectJudge.judge("语气太硬", client).aspect()).isEqualTo("STYLE");
        assertThat(MemoryFeedbackAspectJudge.judge("内容不准确", client).aspect()).isEqualTo("CONTENT");
        assertThat(MemoryFeedbackAspectJudge.judge("没有回答问题", client).aspect()).isEqualTo("CONTENT");
        assertThat(MemoryFeedbackAspectJudge.judge("引用不支持结论", client).aspect()).isEqualTo("CONTENT");
        assertThat(MemoryFeedbackAspectJudge.judge("资料里有但没找到", client).aspect()).isEqualTo("CONTENT");
        assertThat(MemoryFeedbackAspectJudge.judge("  ", client).aspect()).isEqualTo("UNKNOWN");
        assertThat(MemoryFeedbackAspectJudge.judge("  ", client).degradationReason()).isNull();
        assertThat(client.calls).isZero();
    }

    @Test
    void invalidModelOutputIsRetriedOnce() {
        ScriptedClient client = new ScriptedClient();
        client.outputs.add("不是 JSON");
        client.outputs.add("{\"aspect\":\"LANGUAGE\"}");

        MemoryFeedbackAspectJudge.Judgment judgment = MemoryFeedbackAspectJudge.judge("这段没说到点上", client);

        assertThat(judgment.aspect()).isEqualTo("LANGUAGE");
        assertThat(judgment.degradationReason()).isNull();
        assertThat(client.calls).isEqualTo(2);
        assertThat(client.prompts.get(1)).contains("不合规");
    }

    @Test
    void unavailableModelIsUnknownRatherThanAPassedJudgment() {
        ScriptedClient client = new ScriptedClient();
        client.enabled = false;

        MemoryFeedbackAspectJudge.Judgment judgment = MemoryFeedbackAspectJudge.judge("这段没说到点上", client);

        assertThat(judgment.aspect()).isEqualTo("UNKNOWN");
        assertThat(judgment.degradationReason()).isEqualTo(MemoryFeedbackAspectJudge.ASPECT_NOT_RUN);
        assertThat(client.calls).isZero();
    }

    private static final class ScriptedClient implements ChatLlmClient {
        private final List<String> outputs = new ArrayList<>();
        private final List<String> prompts = new ArrayList<>();
        private int calls;
        private boolean enabled = true;

        @Override
        public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                  Consumer<String> onToken) {
            calls++;
            prompts.add(userPrompt);
            return outputs.isEmpty() ? "" : outputs.remove(0);
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }
    }
}

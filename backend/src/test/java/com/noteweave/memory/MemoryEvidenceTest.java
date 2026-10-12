package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class MemoryEvidenceTest {

    @Test
    void priorsAndSessionWeightsFollowTheSourceBand() {
        MemoryEvidence.Posterior explicit = MemoryEvidence.prior(MemoryEvidence.Band.EXPLICIT);
        MemoryEvidence.Posterior inferred = MemoryEvidence.prior(MemoryEvidence.Band.INFERRED);
        MemoryEvidence.Posterior model = MemoryEvidence.prior(MemoryEvidence.Band.MODEL);

        assertThat(explicit.mean()).isEqualTo(0.8);
        assertThat(explicit.variance()).isCloseTo(4.0 / 150.0, within(0.0000001));
        assertThat(inferred.mean()).isEqualTo(0.5);
        assertThat(model.mean()).isCloseTo(1.0 / 3.0, within(0.0000001));
        assertThat(model.variance()).isGreaterThan(0.04);
        assertThat(MemoryEvidence.weight(MemoryEvidence.Band.EXPLICIT, 1)).isEqualTo(0.5);
        assertThat(MemoryEvidence.bandOf("CONVERSATION_FEEDBACK", true)).isEqualTo(MemoryEvidence.Band.INFERRED);
        assertThat(MemoryEvidence.bandOf("CONVERSATION_FEEDBACK", false)).isEqualTo(MemoryEvidence.Band.EXPLICIT);
    }

    @Test
    void classifierRetriesOnceAndThenGivesUp() {
        AtomicInteger calls = new AtomicInteger();
        ChatLlmClient client = client(calls, "maybe", "yes");
        assertThat(MemoryExtractionClassifier.decide(client, "太长了")).isEqualTo("YES");
        assertThat(calls.get()).isEqualTo(2);

        AtomicInteger failures = new AtomicInteger();
        ChatLlmClient broken = client(failures, "maybe", "still no");
        assertThat(MemoryExtractionClassifier.decide(broken, "今天天气怎么样")).isEqualTo("UNAVAILABLE");
        assertThat(MemoryExtractionClassifier.label("No.")).isEqualTo("NO");
    }

    @Test
    void wordingJudgeAcceptsOnlyBooleanJson() {
        ObjectMapper mapper = new ObjectMapper();
        assertThat(MemoryWordingJudge.parse(mapper, "说明 {\"more_specific\": true}")).isTrue();
        assertThat(MemoryWordingJudge.parse(mapper, "{\"more_specific\": false}")).isFalse();
        assertThat(MemoryWordingJudge.parse(mapper, "不是")).isNull();
        assertThat(MemoryConsolidationService.parseMerge("{\"merge\": true}")).isTrue();
        assertThat(MemoryConsolidationService.parseMerge("{\"merge\": false}")).isFalse();
    }

    private static ChatLlmClient client(AtomicInteger calls, String first, String second) {
        return new ChatLlmClient() {
            @Override
            public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                     Consumer<String> onToken) {
                return calls.incrementAndGet() == 1 ? first : second;
            }

            @Override
            public boolean isEnabled() {
                return true;
            }
        };
    }
}

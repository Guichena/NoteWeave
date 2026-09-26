package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationTurnPayloadCodecTest {

    private final ConversationTurnPayloadCodec codec =
            new ConversationTurnPayloadCodec(new ObjectMapper());

    @Test
    void codecShouldKeepTurnHashAndFrozenPayloadStable() {
        SubmitTurnCommand command = new SubmitTurnCommand(
                "conversation-1",
                "original question",
                "NOTE",
                "request-1",
                List.of("source-1"),
                "head-1",
                "EXPLICIT",
                List.of("WORKSPACE", "REPORT"),
                List.of("report-1")
        );

        assertThat(codec.requestHash(command)).isEqualTo(codec.requestHash(command));
        String preparation = codec.preparationJson(command, "head-1", 7);
        SubmitTurnCommand restored = codec.readFrozenCommand("conversation-1", preparation);

        assertThat(restored.content()).isEqualTo(command.content());
        assertThat(restored.requestedTurnMode()).isEqualTo(command.requestedTurnMode());
        assertThat(restored.sourceScope()).containsExactly("source-1");
        assertThat(restored.retrievalStrategy()).isEqualTo("EXPLICIT");
        assertThat(restored.retrievalChannels()).containsExactly("WORKSPACE", "REPORT");
        assertThat(restored.groundingRefs()).containsExactly("report-1");
    }

    @Test
    void codecShouldRoundTripTurnReceipt() {
        TurnReceipt receipt = new TurnReceipt(
                "submission-1",
                "ANSWER",
                "message-1",
                "assistant-1",
                "request-1",
                "/stream",
                "run-1",
                null,
                "/events",
                true,
                List.of("NOTE_SOURCE_RECALL_UNAVAILABLE"),
                false
        );

        assertThat(codec.readReceipt(codec.writeReceipt(receipt))).isEqualTo(receipt);
    }
}

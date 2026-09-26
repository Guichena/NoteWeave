package com.noteweave.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AnswerRunServiceInvariantTest {

    @Test
    void completionShouldRejectBlankFinalContent() {
        assertThatThrownBy(() -> AnswerRunService.requireNonBlankFinalContent(" \n "))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo("ANSWER_LLM_EMPTY_RESPONSE");
                    assertThat(error.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                });
    }

    @Test
    void completionShouldRejectMissingStreamingMessageRevision() {
        assertThatThrownBy(() -> AnswerRunService.requireMessageRevisionFinalization(0))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo("ANSWER_MESSAGE_REVISION_INVALID");
                    assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                });
    }
}

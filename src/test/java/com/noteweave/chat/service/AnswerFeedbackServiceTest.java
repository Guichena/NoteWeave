package com.noteweave.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.noteweave.chat.dto.AnswerFeedbackRequest;
import com.noteweave.chat.model.AnswerFeedback;
import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.repository.AnswerFeedbackRepository;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.permission.service.ResourceAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnswerFeedbackServiceTest {

    @Mock
    private ChatSessionService chatSessionService;

    @Mock
    private AnswerFeedbackRepository answerFeedbackRepository;

    @Mock
    private ResourceAccessService resourceAccessService;

    @InjectMocks
    private AnswerFeedbackService answerFeedbackService;

    @Test
    void shouldNormalizeLegacyHelpfulRatingToUp() {
        ChatMessage message = new ChatMessage();
        message.setId(20L);
        message.setSessionId(30L);
        ChatSession session = new ChatSession();
        session.setId(30L);
        session.setSpaceId(40L);

        given(chatSessionService.getRequiredMessage(10L, 20L)).willReturn(message);
        given(chatSessionService.getRequiredActiveSession(30L)).willReturn(session);
        given(answerFeedbackRepository.findByUserIdAndMessageId(10L, 20L)).willReturn(java.util.Optional.empty());
        given(answerFeedbackRepository.save(any(AnswerFeedback.class))).willAnswer(invocation -> {
            AnswerFeedback feedback = invocation.getArgument(0);
            feedback.setId(50L);
            return feedback;
        });

        AnswerFeedbackRequest request = new AnswerFeedbackRequest();
        request.setRating("helpful");
        request.setReason("helpful");
        request.setComment(" grounded ");

        var response = answerFeedbackService.submit(10L, 20L, request);

        ArgumentCaptor<AnswerFeedback> captor = ArgumentCaptor.forClass(AnswerFeedback.class);
        verify(answerFeedbackRepository).save(captor.capture());
        assertThat(captor.getValue().getRating()).isEqualTo("UP");
        assertThat(captor.getValue().getReason()).isEqualTo("HELPFUL");
        assertThat(response.getRating()).isEqualTo("UP");
        assertThat(response.getReason()).isEqualTo("HELPFUL");
        assertThat(response.getComment()).isEqualTo("grounded");
    }

    @Test
    void shouldRejectUnsupportedRating() {
        ChatMessage message = new ChatMessage();
        message.setId(20L);
        message.setSessionId(30L);
        ChatSession session = new ChatSession();
        session.setId(30L);
        session.setSpaceId(40L);

        given(chatSessionService.getRequiredMessage(10L, 20L)).willReturn(message);
        given(chatSessionService.getRequiredActiveSession(30L)).willReturn(session);

        AnswerFeedbackRequest request = new AnswerFeedbackRequest();
        request.setRating("maybe");
        request.setReason("HELPFUL");

        assertThatThrownBy(() -> answerFeedbackService.submit(10L, 20L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}

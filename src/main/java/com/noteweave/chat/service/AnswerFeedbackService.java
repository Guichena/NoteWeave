package com.noteweave.chat.service;

import com.noteweave.chat.dto.AnswerFeedbackRequest;
import com.noteweave.chat.dto.AnswerFeedbackResponse;
import com.noteweave.chat.model.AnswerFeedback;
import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.repository.AnswerFeedbackRepository;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.permission.service.ResourceAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AnswerFeedbackService {

    private final ChatSessionService chatSessionService;
    private final AnswerFeedbackRepository answerFeedbackRepository;
    private final ResourceAccessService resourceAccessService;

    @Transactional
    public AnswerFeedbackResponse submit(Long userId, Long messageId, AnswerFeedbackRequest request) {
        ChatMessage message = chatSessionService.getRequiredMessage(userId, messageId);
        ChatSession session = chatSessionService.getRequiredActiveSession(message.getSessionId());
        resourceAccessService.requireViewSpace(userId, session.getSpaceId());

        AnswerFeedback feedback = answerFeedbackRepository.findByUserIdAndMessageId(userId, messageId)
                .orElseGet(AnswerFeedback::new);
        feedback.setUserId(userId);
        feedback.setSpaceId(session.getSpaceId());
        feedback.setSessionId(session.getId());
        feedback.setMessageId(messageId);
        feedback.setRating(request.getRating().trim());
        feedback.setReason(normalize(request.getReason()));
        feedback.setComment(normalize(request.getComment()));
        return toResponse(answerFeedbackRepository.save(feedback));
    }

    private AnswerFeedbackResponse toResponse(AnswerFeedback feedback) {
        return AnswerFeedbackResponse.builder()
                .id(feedback.getId())
                .messageId(feedback.getMessageId())
                .rating(feedback.getRating())
                .reason(feedback.getReason())
                .comment(feedback.getComment())
                .createdAt(feedback.getCreatedAt())
                .updatedAt(feedback.getUpdatedAt())
                .build();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}

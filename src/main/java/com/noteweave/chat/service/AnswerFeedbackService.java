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
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AnswerFeedbackService {

    private static final Set<String> ALLOWED_RATINGS = Set.of("UP", "DOWN", "NEUTRAL");
    private static final Set<String> ALLOWED_REASONS = Set.of(
            "HELPFUL",
            "NOT_GROUNDED",
            "WRONG_CITATION",
            "MISSING_CONTEXT",
            "TOO_VERBOSE",
            "TOO_SHORT",
            "OTHER"
    );

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
        feedback.setRating(normalizeRating(request.getRating()));
        feedback.setReason(normalizeReason(request.getReason()));
        feedback.setComment(normalize(request.getComment()));
        return toResponse(answerFeedbackRepository.save(feedback));
    }

    @Transactional(readOnly = true)
    public AnswerFeedbackResponse get(Long userId, Long messageId) {
        ChatMessage message = chatSessionService.getRequiredMessage(userId, messageId);
        ChatSession session = chatSessionService.getRequiredActiveSession(message.getSessionId());
        resourceAccessService.requireViewSpace(userId, session.getSpaceId());
        return answerFeedbackRepository.findByUserIdAndMessageId(userId, messageId)
                .map(this::toResponse)
                .orElse(null);
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

    private String normalizeRating(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "rating: must not be blank");
        }
        String canonical = switch (normalized.toUpperCase(Locale.ROOT)) {
            case "UP", "HELPFUL", "THUMBS_UP" -> "UP";
            case "DOWN", "UNHELPFUL", "NOT_HELPFUL", "THUMBS_DOWN" -> "DOWN";
            case "NEUTRAL" -> "NEUTRAL";
            default -> null;
        };
        if (canonical == null || !ALLOWED_RATINGS.contains(canonical)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "rating: unsupported value");
        }
        return canonical;
    }

    private String normalizeReason(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        String canonical = normalized.toUpperCase(Locale.ROOT);
        if (!ALLOWED_REASONS.contains(canonical)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "reason: unsupported value");
        }
        return canonical;
    }
}

package com.noteweave.chat.repository;

import com.noteweave.chat.model.AnswerFeedback;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnswerFeedbackRepository extends JpaRepository<AnswerFeedback, Long> {

    Optional<AnswerFeedback> findByUserIdAndMessageId(Long userId, Long messageId);
}

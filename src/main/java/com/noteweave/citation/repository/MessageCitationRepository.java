package com.noteweave.citation.repository;

import com.noteweave.citation.model.MessageCitation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageCitationRepository extends JpaRepository<MessageCitation, Long> {

    List<MessageCitation> findByMessageId(Long messageId);

    Optional<MessageCitation> findByMessageIdAndCitationId(Long messageId, Long citationId);
}

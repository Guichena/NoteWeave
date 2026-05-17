package com.noteweave.citation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.noteweave.citation.model.Citation;
import com.noteweave.citation.model.MessageCitation;
import com.noteweave.citation.repository.CitationRepository;
import com.noteweave.citation.repository.MessageCitationRepository;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.storage.config.StorageProperties;
import com.noteweave.storage.service.FileStorageService;
import com.noteweave.team.rag.evidence.EvidenceItem;
import com.noteweave.team.rag.evidence.EvidenceSource;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CitationServiceTest {

    @Mock
    private CitationRepository citationRepository;

    @Mock
    private MessageCitationRepository messageCitationRepository;

    @Mock
    private ResourceAccessService resourceAccessService;

    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private CitationService citationService;

    @Test
    void shouldReturnDeduplicatedCitationsAndBackfillTraceIdOnExistingRelation() {
        StorageProperties storageProperties = new StorageProperties(
                new StorageProperties.Minio("http://localhost", "a", "b", "dev-bucket", "test-bucket"),
                new StorageProperties.Paths("target/test-storage", "dev-prefix", "test-prefix", null)
        );
        citationService = new CitationService(
                citationRepository,
                messageCitationRepository,
                resourceAccessService,
                fileStorageService,
                storageProperties
        );

        EvidenceItem evidenceItem = new EvidenceItem(
                1,
                "DOCUMENT",
                11L,
                11L,
                "Runbook",
                1,
                3,
                "Rollback should be rehearsed before release.",
                0.91d,
                List.of(
                        new EvidenceSource(101L, 3, 1, 0, 42, "Rollback should be rehearsed before release.", "1"),
                        new EvidenceSource(101L, 3, 1, 0, 42, "Rollback should be rehearsed before release.", "1")
                )
        );

        Citation citation = new Citation();
        citation.setId(201L);
        citation.setSpaceId(9L);
        citation.setSourceType("DOCUMENT");
        citation.setSourceId(11L);
        citation.setChunkId(101L);
        citation.setTitle("Runbook");
        citation.setQuoteText("Rollback should be rehearsed before release.");
        given(citationRepository.findBySpaceIdAndSourceTypeAndSourceIdAndChunkId(9L, "DOCUMENT", 11L, 101L))
                .willReturn(Optional.of(citation));
        given(citationRepository.save(any(Citation.class))).willAnswer(invocation -> invocation.getArgument(0));

        MessageCitation relation = new MessageCitation();
        relation.setId(301L);
        relation.setMessageId(501L);
        relation.setCitationId(201L);
        relation.setRetrievalTraceId(null);
        given(messageCitationRepository.findByMessageIdAndCitationId(501L, 201L)).willReturn(Optional.of(relation));
        given(messageCitationRepository.save(any(MessageCitation.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(fileStorageService.devBucket()).willReturn("dev-bucket");
        doNothing().when(fileStorageService).putObject(any(String.class), any(String.class), any(InputStream.class), any(Long.class), any(String.class));

        var responses = citationService.saveForAssistantMessage(501L, 9L, List.of(evidenceItem), 701L);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getId()).isEqualTo(201L);
        ArgumentCaptor<MessageCitation> captor = ArgumentCaptor.forClass(MessageCitation.class);
        verify(messageCitationRepository).save(captor.capture());
        assertThat(captor.getValue().getRetrievalTraceId()).isEqualTo(701L);
        verify(citationRepository, times(2)).save(any(Citation.class));
    }
}

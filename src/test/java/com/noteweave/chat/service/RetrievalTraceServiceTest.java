package com.noteweave.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.noteweave.chat.dto.RetrievalTraceItemCreateRequest;
import com.noteweave.chat.model.RetrievalTraceItem;
import com.noteweave.chat.repository.RetrievalTraceItemRepository;
import com.noteweave.chat.repository.RetrievalTraceRepository;
import com.noteweave.permission.service.ResourceAccessService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetrievalTraceServiceTest {

    @Mock
    private RetrievalTraceRepository retrievalTraceRepository;

    @Mock
    private RetrievalTraceItemRepository retrievalTraceItemRepository;

    @Mock
    private ResourceAccessService resourceAccessService;

    @InjectMocks
    private RetrievalTraceService retrievalTraceService;

    @Test
    void shouldDeduplicateRepeatedTraceItemsWithinSingleWrite() {
        given(retrievalTraceItemRepository.findByTraceIdOrderByRankNoAscIdAsc(1L)).willReturn(List.of());
        given(retrievalTraceItemRepository.save(any(RetrievalTraceItem.class))).willAnswer(invocation -> invocation.getArgument(0));

        RetrievalTraceItemCreateRequest request = RetrievalTraceItemCreateRequest.builder()
                .sourceType("DOCUMENT_CHUNK")
                .sourceId(10L)
                .documentId(20L)
                .chunkId(30L)
                .score(0.91d)
                .rank(1)
                .selectedAsEvidence(true)
                .metadataJson("{\"rrf\":1}")
                .build();

        retrievalTraceService.addItems(1L, List.of(request, request));

        verify(retrievalTraceItemRepository, times(1)).save(any(RetrievalTraceItem.class));
    }

    @Test
    void shouldMergeDuplicateTraceItemIntoExistingRowInsteadOfInsertingAgain() {
        RetrievalTraceItem existing = new RetrievalTraceItem();
        existing.setId(100L);
        existing.setTraceId(1L);
        existing.setSourceType("DOCUMENT_CHUNK");
        existing.setSourceId(10L);
        existing.setDocumentId(20L);
        existing.setChunkId(30L);
        existing.setRankNo(1);
        existing.setSelectedAsEvidence(false);
        existing.setMetadataJson(null);
        given(retrievalTraceItemRepository.findByTraceIdOrderByRankNoAscIdAsc(1L)).willReturn(List.of(existing));
        given(retrievalTraceItemRepository.save(any(RetrievalTraceItem.class))).willAnswer(invocation -> invocation.getArgument(0));

        RetrievalTraceItemCreateRequest request = RetrievalTraceItemCreateRequest.builder()
                .sourceType("DOCUMENT_CHUNK")
                .sourceId(10L)
                .documentId(20L)
                .chunkId(30L)
                .score(0.77d)
                .rank(1)
                .selectedAsEvidence(true)
                .metadataJson("{\"trace\":\"kept\"}")
                .build();

        retrievalTraceService.addItems(1L, List.of(request));

        ArgumentCaptor<RetrievalTraceItem> captor = ArgumentCaptor.forClass(RetrievalTraceItem.class);
        verify(retrievalTraceItemRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(100L);
        assertThat(captor.getValue().isSelectedAsEvidence()).isTrue();
        assertThat(captor.getValue().getScore()).isEqualTo(0.77d);
        assertThat(captor.getValue().getMetadataJson()).isEqualTo("{\"trace\":\"kept\"}");
    }
}

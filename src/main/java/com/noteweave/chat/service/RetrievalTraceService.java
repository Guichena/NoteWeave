package com.noteweave.chat.service;

import com.noteweave.chat.dto.RetrievalTraceCreateRequest;
import com.noteweave.chat.dto.RetrievalTraceDetailResponse;
import com.noteweave.chat.dto.RetrievalTraceItemCreateRequest;
import com.noteweave.chat.dto.RetrievalTraceItemResponse;
import com.noteweave.chat.model.RetrievalTrace;
import com.noteweave.chat.model.RetrievalTraceItem;
import com.noteweave.chat.repository.RetrievalTraceItemRepository;
import com.noteweave.chat.repository.RetrievalTraceRepository;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.permission.service.ResourceAccessService;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RetrievalTraceService {

    private final RetrievalTraceRepository retrievalTraceRepository;
    private final RetrievalTraceItemRepository retrievalTraceItemRepository;
    private final ResourceAccessService resourceAccessService;

    @Transactional
    public Long createTrace(RetrievalTraceCreateRequest request) {
        RetrievalTrace trace = new RetrievalTrace();
        trace.setUserId(request.userId());
        trace.setSpaceId(request.spaceId());
        trace.setSessionId(request.sessionId());
        trace.setMessageId(request.messageId());
        trace.setTaskId(request.taskId());
        trace.setScene(request.scene() == null ? null : request.scene().trim().toUpperCase());
        trace.setQueryText(request.queryText());
        trace.setRetrieverType(request.retrieverType());
        trace.setTopK(request.topK());
        trace.setLatencyMs(request.latencyMs());
        trace.setRetrievedChunkCount(request.retrievedChunkCount() == null ? 0 : request.retrievedChunkCount());
        trace.setRetrievalMode(request.retrievalMode());
        trace.setBm25Count(request.bm25Count());
        trace.setVectorCount(request.vectorCount());
        trace.setFusionCount(request.fusionCount());
        trace.setFallbackUsed(request.fallbackUsed());
        trace.setTraceJson(request.traceJson());
        return retrievalTraceRepository.save(trace).getId();
    }

    @Transactional
    public void addItems(Long traceId, List<RetrievalTraceItemCreateRequest> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<RetrievalTraceItem> existingItems = retrievalTraceItemRepository.findByTraceIdOrderByRankNoAscIdAsc(traceId);
        LinkedHashMap<String, RetrievalTraceItem> existingBySignature = new LinkedHashMap<>();
        for (RetrievalTraceItem existing : existingItems) {
            existingBySignature.put(signature(
                    existing.getSourceType(),
                    existing.getSourceId(),
                    existing.getDocumentId(),
                    existing.getChunkId(),
                    existing.getWikiPageId(),
                    existing.getRankNo()
            ), existing);
        }
        LinkedHashMap<String, RetrievalTraceItemCreateRequest> uniqueRequests = new LinkedHashMap<>();
        for (RetrievalTraceItemCreateRequest itemRequest : items) {
            uniqueRequests.putIfAbsent(signature(
                    itemRequest.sourceType(),
                    itemRequest.sourceId(),
                    itemRequest.documentId(),
                    itemRequest.chunkId(),
                    itemRequest.wikiPageId(),
                    itemRequest.rank()
            ), itemRequest);
        }
        for (RetrievalTraceItemCreateRequest itemRequest : uniqueRequests.values()) {
            String signature = signature(
                    itemRequest.sourceType(),
                    itemRequest.sourceId(),
                    itemRequest.documentId(),
                    itemRequest.chunkId(),
                    itemRequest.wikiPageId(),
                    itemRequest.rank()
            );
            RetrievalTraceItem existing = existingBySignature.get(signature);
            if (existing != null) {
                boolean changed = false;
                if (!existing.isSelectedAsEvidence() && itemRequest.selectedAsEvidence()) {
                    existing.setSelectedAsEvidence(true);
                    changed = true;
                }
                if (existing.getScore() == null && itemRequest.score() != null) {
                    existing.setScore(itemRequest.score());
                    changed = true;
                }
                if ((existing.getMetadataJson() == null || existing.getMetadataJson().isBlank())
                        && itemRequest.metadataJson() != null
                        && !itemRequest.metadataJson().isBlank()) {
                    existing.setMetadataJson(itemRequest.metadataJson());
                    changed = true;
                }
                if (changed) {
                    retrievalTraceItemRepository.save(existing);
                }
                continue;
            }
            RetrievalTraceItem item = new RetrievalTraceItem();
            item.setTraceId(traceId);
            item.setSourceType(itemRequest.sourceType());
            item.setSourceId(itemRequest.sourceId());
            item.setDocumentId(itemRequest.documentId());
            item.setChunkId(itemRequest.chunkId());
            item.setWikiPageId(itemRequest.wikiPageId());
            item.setScore(itemRequest.score());
            item.setRankNo(itemRequest.rank());
            item.setSelectedAsEvidence(itemRequest.selectedAsEvidence());
            item.setMetadataJson(itemRequest.metadataJson());
            RetrievalTraceItem saved = retrievalTraceItemRepository.save(item);
            existingBySignature.put(signature, saved);
        }
    }

    @Transactional
    public void markSelectedEvidence(Long traceId, List<Long> selectedItemIds) {
        if (selectedItemIds == null || selectedItemIds.isEmpty()) {
            return;
        }
        for (RetrievalTraceItem item : retrievalTraceItemRepository.findByTraceIdOrderByRankNoAscIdAsc(traceId)) {
            if (selectedItemIds.contains(item.getId())) {
                item.setSelectedAsEvidence(true);
                retrievalTraceItemRepository.save(item);
            }
        }
    }

    @Transactional(readOnly = true)
    public RetrievalTraceDetailResponse get(Long userId, Long traceId) {
        RetrievalTrace trace = retrievalTraceRepository.findById(traceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Retrieval trace not found"));
        resourceAccessService.requireAdminOrManageSpace(userId, trace.getSpaceId());
        List<RetrievalTraceItemResponse> items = retrievalTraceItemRepository.findByTraceIdOrderByRankNoAscIdAsc(traceId).stream()
                .map(item -> RetrievalTraceItemResponse.builder()
                        .id(item.getId())
                        .sourceType(item.getSourceType())
                        .sourceId(item.getSourceId())
                        .documentId(item.getDocumentId())
                        .chunkId(item.getChunkId())
                        .wikiPageId(item.getWikiPageId())
                        .score(item.getScore())
                        .rank(item.getRankNo())
                        .selectedAsEvidence(item.isSelectedAsEvidence())
                        .metadataJson(item.getMetadataJson())
                        .createdAt(item.getCreatedAt())
                        .build())
                .toList();
        return RetrievalTraceDetailResponse.builder()
                .id(trace.getId())
                .userId(trace.getUserId())
                .spaceId(trace.getSpaceId())
                .sessionId(trace.getSessionId())
                .messageId(trace.getMessageId())
                .taskId(trace.getTaskId())
                .scene(trace.getScene())
                .queryText(trace.getQueryText())
                .retrieverType(trace.getRetrieverType())
                .topK(trace.getTopK())
                .latencyMs(trace.getLatencyMs())
                .retrievedChunkCount(trace.getRetrievedChunkCount())
                .retrievalMode(trace.getRetrievalMode())
                .bm25Count(trace.getBm25Count())
                .vectorCount(trace.getVectorCount())
                .fusionCount(trace.getFusionCount())
                .fallbackUsed(trace.isFallbackUsed())
                .traceJson(trace.getTraceJson())
                .createdAt(trace.getCreatedAt())
                .items(items)
                .build();
    }

    private String signature(String sourceType, Long sourceId, Long documentId, Long chunkId, Long wikiPageId, Integer rank) {
        return String.valueOf(sourceType) + "|"
                + String.valueOf(sourceId) + "|"
                + String.valueOf(documentId) + "|"
                + String.valueOf(chunkId) + "|"
                + String.valueOf(wikiPageId) + "|"
                + String.valueOf(rank);
    }
}

package com.noteweave.personal.card.service;

import com.noteweave.citation.model.Citation;
import com.noteweave.citation.repository.CitationRepository;
import com.noteweave.personal.card.dto.CardCitationResponse;
import com.noteweave.personal.card.model.ArticleCard;
import com.noteweave.personal.card.model.ArticleCardCitation;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.model.ConceptCardCitation;
import com.noteweave.personal.card.model.SynthesisCardCitation;
import com.noteweave.personal.card.repository.ArticleCardCitationRepository;
import com.noteweave.personal.card.repository.ConceptCardCitationRepository;
import com.noteweave.personal.card.repository.SynthesisCardCitationRepository;
import com.noteweave.personal.compiler.dto.EvidenceQuoteDraft;
import com.noteweave.personal.compiler.service.EvidenceBacktraceService;
import com.noteweave.personal.source.model.Source;
import com.noteweave.storage.config.StorageProperties;
import com.noteweave.storage.service.FileStorageService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PersonalCardCitationService {

    private final CitationRepository citationRepository;
    private final ArticleCardCitationRepository articleCardCitationRepository;
    private final ConceptCardCitationRepository conceptCardCitationRepository;
    private final SynthesisCardCitationRepository synthesisCardCitationRepository;
    private final EvidenceBacktraceService evidenceBacktraceService;
    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;

    @Transactional
    public List<Citation> replaceArticleCitations(ArticleCard articleCard, Source source, List<EvidenceQuoteDraft> evidenceQuotes) {
        articleCardCitationRepository.deleteByArticleCardId(articleCard.getId());
        List<Citation> citations = new ArrayList<>();
        for (EvidenceQuoteDraft evidenceQuote : evidenceQuotes) {
            Citation citation = saveSourceCitation(articleCard.getSpaceId(), source, evidenceQuote.quote(), articleCard.getTitle());
            ArticleCardCitation relation = new ArticleCardCitation();
            relation.setArticleCardId(articleCard.getId());
            relation.setCitationId(citation.getId());
            articleCardCitationRepository.save(relation);
            citations.add(citation);
        }
        return citations;
    }

    @Transactional
    public Citation addConceptCitation(ConceptCard conceptCard, Source source, EvidenceQuoteDraft evidenceQuote) {
        Citation citation = saveSourceCitation(conceptCard.getSpaceId(), source, evidenceQuote.quote(), conceptCard.getName());
        if (conceptCardCitationRepository.findByConceptCardIdAndCitationIdAndRelationType(conceptCard.getId(), citation.getId(), "EVIDENCE").isEmpty()) {
            ConceptCardCitation relation = new ConceptCardCitation();
            relation.setConceptCardId(conceptCard.getId());
            relation.setCitationId(citation.getId());
            conceptCardCitationRepository.save(relation);
        }
        return citation;
    }

    @Transactional(readOnly = true)
    public List<CardCitationResponse> listArticleCitations(Long articleCardId) {
        return articleCardCitationRepository.findByArticleCardIdOrderByIdAsc(articleCardId).stream()
                .map(ArticleCardCitation::getCitationId)
                .map(citationRepository::findById)
                .flatMap(java.util.Optional::stream)
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CardCitationResponse> listConceptCitations(Long conceptCardId) {
        return conceptCardCitationRepository.findByConceptCardIdOrderByIdAsc(conceptCardId).stream()
                .map(ConceptCardCitation::getCitationId)
                .map(citationRepository::findById)
                .flatMap(java.util.Optional::stream)
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void mergeSynthesisCitations(Long synthesisCardId, List<Long> citationIds) {
        for (Long citationId : citationIds) {
            synthesisCardCitationRepository.findBySynthesisCardIdAndCitationIdAndRelationType(synthesisCardId, citationId, "EVIDENCE")
                    .orElseGet(() -> {
                        SynthesisCardCitation relation = new SynthesisCardCitation();
                        relation.setSynthesisCardId(synthesisCardId);
                        relation.setCitationId(citationId);
                        return synthesisCardCitationRepository.save(relation);
                    });
        }
    }

    @Transactional(readOnly = true)
    public List<CardCitationResponse> listSynthesisCitations(Long synthesisCardId) {
        return synthesisCardCitationRepository.findBySynthesisCardIdOrderByIdAsc(synthesisCardId).stream()
                .map(SynthesisCardCitation::getCitationId)
                .map(citationRepository::findById)
                .flatMap(java.util.Optional::stream)
                .map(this::toResponse)
                .toList();
    }

    private Citation saveSourceCitation(Long spaceId, Source source, String quote, String title) {
        String normalizedQuote = quote == null ? "" : quote.trim();
        String quoteHash = sha256(normalizedQuote);
        Citation citation = citationRepository.findBySpaceIdAndSourceTypeAndSourceIdAndQuoteHash(spaceId, "SOURCE", source.getId(), quoteHash)
                .orElseGet(Citation::new);

        EvidenceBacktraceService.EvidenceBacktrace backtrace = evidenceBacktraceService.backtrace(source, normalizedQuote);
        citation.setSpaceId(spaceId);
        citation.setSourceType("SOURCE");
        citation.setSourceId(source.getId());
        citation.setChunkId(null);
        citation.setPageNo(1);
        citation.setStartOffset(backtrace.startOffset());
        citation.setEndOffset(backtrace.endOffset());
        citation.setTitle(title);
        citation.setQuoteText(normalizedQuote);
        citation.setQuoteHash(quoteHash);
        citation.setLocationInfo(backtrace.exists()
                ? "offset " + backtrace.startOffset() + "-" + backtrace.endOffset()
                : "source text match missing");
        citation.setSourceVersion(backtrace.sourceVersion());
        Citation saved = citationRepository.save(citation);
        String snapshotObjectKey = storeSnapshot(saved.getId(), normalizedQuote);
        if (!snapshotObjectKey.equals(saved.getSnapshotObjectKey())) {
            saved.setSnapshotObjectKey(snapshotObjectKey);
            saved = citationRepository.save(saved);
        }
        return saved;
    }

    private CardCitationResponse toResponse(Citation citation) {
        return CardCitationResponse.builder()
                .id(citation.getId())
                .sourceType(citation.getSourceType())
                .sourceId(citation.getSourceId())
                .chunkId(citation.getChunkId())
                .title(citation.getTitle())
                .quoteText(citation.getQuoteText())
                .locationInfo(citation.getLocationInfo())
                .pageNo(citation.getPageNo())
                .startOffset(citation.getStartOffset())
                .endOffset(citation.getEndOffset())
                .quoteHash(citation.getQuoteHash())
                .snapshotObjectKey(citation.getSnapshotObjectKey())
                .sourceVersion(citation.getSourceVersion())
                .createdAt(citation.getCreatedAt())
                .build();
    }

    private String storeSnapshot(Long citationId, String quoteText) {
        String objectKey = resolveObjectPrefix() + "/citations/" + citationId + "/snapshot.txt";
        byte[] bytes = (quoteText == null ? "" : quoteText).getBytes(StandardCharsets.UTF_8);
        fileStorageService.putObject(currentBucket(), objectKey, new ByteArrayInputStream(bytes), bytes.length, "text/plain; charset=utf-8");
        return objectKey;
    }

    private String currentBucket() {
        if (normalizeTestRunId(storageProperties.paths().testRunId()) != null) {
            return fileStorageService.testBucket();
        }
        return fileStorageService.devBucket();
    }

    private String resolveObjectPrefix() {
        String configuredTestRunId = normalizeTestRunId(storageProperties.paths().testRunId());
        if (configuredTestRunId != null) {
            return storageProperties.paths().testObjectPrefix() + "/" + configuredTestRunId;
        }
        return storageProperties.paths().devObjectPrefix();
    }

    private String normalizeTestRunId(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash citation quote", ex);
        }
    }
}

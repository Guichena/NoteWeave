package com.noteweave.personal.distillation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.artifact.dto.ArtifactCardRelationResponse;
import com.noteweave.artifact.dto.DistillArtifactRequest;
import com.noteweave.artifact.dto.DistillArtifactResponse;
import com.noteweave.artifact.model.Artifact;
import com.noteweave.artifact.model.ArtifactCardRelation;
import com.noteweave.artifact.model.ArtifactCardRelationType;
import com.noteweave.artifact.model.ArtifactCardType;
import com.noteweave.artifact.model.ArtifactCitation;
import com.noteweave.artifact.model.ArtifactDistillationProposal;
import com.noteweave.artifact.model.ArtifactDistillationProposalStatus;
import com.noteweave.artifact.model.ArtifactSource;
import com.noteweave.artifact.model.ArtifactSourceType;
import com.noteweave.artifact.model.ArtifactStatus;
import com.noteweave.artifact.model.ArtifactVersion;
import com.noteweave.artifact.repository.ArtifactCardRelationRepository;
import com.noteweave.artifact.repository.ArtifactCitationRepository;
import com.noteweave.artifact.repository.ArtifactDistillationProposalRepository;
import com.noteweave.artifact.repository.ArtifactRepository;
import com.noteweave.artifact.repository.ArtifactSourceRepository;
import com.noteweave.artifact.repository.ArtifactVersionRepository;
import com.noteweave.artifact.service.ArtifactService;
import com.noteweave.citation.model.Citation;
import com.noteweave.citation.repository.CitationRepository;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.card.dto.SynthesisCardResponse;
import com.noteweave.personal.card.model.PersonalCardStatus;
import com.noteweave.personal.card.model.SynthesisCard;
import com.noteweave.personal.card.model.SynthesisConceptRelation;
import com.noteweave.personal.card.repository.SynthesisCardRepository;
import com.noteweave.personal.card.repository.SynthesisConceptRelationRepository;
import com.noteweave.personal.card.service.SynthesisCardService;
import com.noteweave.personal.common.PersonalSpaceService;
import com.noteweave.personal.project.service.ResearchProjectService;
import com.noteweave.space.model.Space;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PersonalArtifactDistillationService {

    private static final String SYNTHESIS_RELATION_EVIDENCE = "Linked from artifact source concept context.";

    private final ArtifactService artifactService;
    private final ArtifactRepository artifactRepository;
    private final ArtifactVersionRepository artifactVersionRepository;
    private final ArtifactSourceRepository artifactSourceRepository;
    private final ArtifactCitationRepository artifactCitationRepository;
    private final ArtifactCardRelationRepository artifactCardRelationRepository;
    private final ArtifactDistillationProposalRepository artifactDistillationProposalRepository;
    private final CitationRepository citationRepository;
    private final SynthesisCardRepository synthesisCardRepository;
    private final SynthesisConceptRelationRepository synthesisConceptRelationRepository;
    private final com.noteweave.personal.card.service.PersonalCardCitationService personalCardCitationService;
    private final SynthesisCardService synthesisCardService;
    private final ResearchProjectService researchProjectService;
    private final PersonalSpaceService personalSpaceService;
    private final ObjectMapper objectMapper;

    @Transactional
    public DistillArtifactResponse distill(Long userId, Long artifactId, DistillArtifactRequest request) {
        ArtifactCardType cardType = resolveCardType(request);
        return Boolean.TRUE.equals(request.getConfirm())
                ? confirmProposal(userId, artifactId, request.getProposalId(), cardType)
                : createProposal(userId, artifactId, cardType);
    }

    @Transactional(readOnly = true)
    public List<ArtifactCardRelationResponse> listCardRelations(Long userId, Long artifactId) {
        getRequiredPersonalArtifact(userId, artifactId);
        return artifactCardRelationRepository.findByArtifactIdOrderByIdAsc(artifactId).stream()
                .map(this::toRelationResponse)
                .toList();
    }

    private DistillArtifactResponse createProposal(Long userId, Long artifactId, ArtifactCardType cardType) {
        Artifact artifact = getRequiredPersonalArtifact(userId, artifactId);
        ArtifactVersion latestVersion = getRequiredLatestVersion(artifactId);
        researchProjectService.getRequiredActiveProject(userId, artifact.getResearchProjectId());

        ArtifactDistillationProposal proposal = artifactDistillationProposalRepository
                .findFirstByArtifactIdAndArtifactVersionIdAndUserIdAndCardTypeAndProposalStatusOrderByIdDesc(
                        artifactId,
                        latestVersion.getId(),
                        userId,
                        cardType,
                        ArtifactDistillationProposalStatus.PENDING
                )
                .orElseGet(ArtifactDistillationProposal::new);

        DistillationDraft draft = buildDraft(latestVersion, artifactId);
        proposal.setArtifactId(artifactId);
        proposal.setArtifactVersionId(latestVersion.getId());
        proposal.setUserId(userId);
        proposal.setResearchProjectId(artifact.getResearchProjectId());
        proposal.setCardType(cardType);
        proposal.setProposalStatus(ArtifactDistillationProposalStatus.PENDING);
        proposal.setTitle(draft.title());
        proposal.setSummary(draft.summary());
        proposal.setInsightsJson(writeJson(draft.insights()));
        proposal.setEvidenceQuotesJson(writeJson(draft.evidenceQuotes()));
        ArtifactDistillationProposal saved = artifactDistillationProposalRepository.save(proposal);
        return toProposalResponse(saved, false, null);
    }

    private DistillArtifactResponse confirmProposal(Long userId, Long artifactId, Long proposalId, ArtifactCardType cardType) {
        if (proposalId == null) {
            throw new BusinessException(ErrorCode.ARTIFACT_DISTILLATION_CONFIRMATION_REQUIRED, "proposalId is required for confirmation");
        }
        Artifact artifact = artifactService.getRequiredWritableArtifact(userId, artifactId);
        requirePersonalArtifact(userId, artifact);
        researchProjectService.getRequiredActiveProject(userId, artifact.getResearchProjectId());
        ArtifactVersion latestVersion = getRequiredLatestVersion(artifactId);

        ArtifactDistillationProposal proposal = artifactDistillationProposalRepository.findByIdAndArtifactIdAndUserId(proposalId, artifactId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ARTIFACT_DISTILLATION_PROPOSAL_NOT_FOUND));
        if (proposal.getCardType() != cardType) {
            throw new BusinessException(ErrorCode.ARTIFACT_DISTILLATION_UNSUPPORTED, "Only synthesis distillation is supported");
        }
        if (proposal.getProposalStatus() == ArtifactDistillationProposalStatus.CONFIRMED && proposal.getConfirmedSynthesisCardId() != null) {
            return confirmedResponse(proposal, synthesisCardService.getRequiredCard(userId, proposal.getConfirmedSynthesisCardId()));
        }
        if (!latestVersion.getId().equals(proposal.getArtifactVersionId())) {
            proposal.setProposalStatus(ArtifactDistillationProposalStatus.STALE);
            artifactDistillationProposalRepository.save(proposal);
            throw new BusinessException(ErrorCode.ARTIFACT_DISTILLATION_PROPOSAL_STALE, "Artifact has a newer version; create a fresh proposal");
        }

        ArtifactCardRelation existingRelation = artifactCardRelationRepository
                .findByArtifactIdAndArtifactVersionIdAndCardTypeAndRelationType(
                        artifactId,
                        latestVersion.getId(),
                        ArtifactCardType.SYNTHESIS,
                        ArtifactCardRelationType.SUMMARIZED_INTO
                )
                .orElse(null);
        if (existingRelation != null) {
            proposal.setProposalStatus(ArtifactDistillationProposalStatus.CONFIRMED);
            proposal.setConfirmedSynthesisCardId(existingRelation.getCardId());
            proposal.setConfirmedAt(LocalDateTime.now());
            artifactDistillationProposalRepository.save(proposal);
            return confirmedResponse(proposal, synthesisCardService.getRequiredCard(userId, existingRelation.getCardId()));
        }

        SynthesisCard synthesisCard = new SynthesisCard();
        synthesisCard.setSpaceId(artifact.getSpaceId());
        synthesisCard.setResearchProjectId(artifact.getResearchProjectId());
        synthesisCard.setSourceArtifactId(artifact.getId());
        synthesisCard.setSourceArtifactVersionId(latestVersion.getId());
        synthesisCard.setTitle(proposal.getTitle());
        synthesisCard.setSummary(proposal.getSummary());
        synthesisCard.setInsightsJson(proposal.getInsightsJson());
        synthesisCard.setEvidenceQuotesJson(proposal.getEvidenceQuotesJson());
        synthesisCard.setCardStatus(PersonalCardStatus.READY);
        synthesisCard.setCreatedBy(userId);
        SynthesisCard savedCard = synthesisCardRepository.save(synthesisCard);

        mergeCitationRelations(savedCard.getId(), artifactId);
        mergeConceptRelations(savedCard.getId(), artifactId);

        ArtifactCardRelation relation = new ArtifactCardRelation();
        relation.setArtifactId(artifactId);
        relation.setArtifactVersionId(latestVersion.getId());
        relation.setCardType(ArtifactCardType.SYNTHESIS);
        relation.setCardId(savedCard.getId());
        relation.setRelationType(ArtifactCardRelationType.SUMMARIZED_INTO);
        artifactCardRelationRepository.save(relation);

        artifact.setStatus(ArtifactStatus.DISTILLED_TO_PERSONAL_WIKI);
        artifactRepository.save(artifact);

        proposal.setProposalStatus(ArtifactDistillationProposalStatus.CONFIRMED);
        proposal.setConfirmedSynthesisCardId(savedCard.getId());
        proposal.setConfirmedAt(LocalDateTime.now());
        artifactDistillationProposalRepository.save(proposal);
        return confirmedResponse(proposal, savedCard);
    }

    private void mergeCitationRelations(Long synthesisCardId, Long artifactId) {
        List<Long> citationIds = artifactCitationRepository.findByArtifactIdOrderByIdAsc(artifactId).stream()
                .map(ArtifactCitation::getCitationId)
                .toList();
        if (citationIds.isEmpty()) {
            throw new BusinessException(ErrorCode.EVIDENCE_BACKTRACE_FAILED, "Artifact has no traceable citations for synthesis distillation");
        }
        personalCardCitationService.mergeSynthesisCitations(synthesisCardId, citationIds);
    }

    private void mergeConceptRelations(Long synthesisCardId, Long artifactId) {
        for (ArtifactSource source : artifactSourceRepository.findByArtifactIdOrderByIdAsc(artifactId)) {
            if (source.getSourceType() != ArtifactSourceType.CONCEPT_CARD) {
                continue;
            }
            synthesisConceptRelationRepository.findBySynthesisCardIdAndConceptCardIdAndRelationType(
                            synthesisCardId,
                            source.getSourceId(),
                            "RELATED"
                    )
                    .orElseGet(() -> {
                        SynthesisConceptRelation relation = new SynthesisConceptRelation();
                        relation.setSynthesisCardId(synthesisCardId);
                        relation.setConceptCardId(source.getSourceId());
                        relation.setRelationType("RELATED");
                        relation.setEvidence(SYNTHESIS_RELATION_EVIDENCE);
                        return synthesisConceptRelationRepository.save(relation);
                    });
        }
    }

    private Artifact getRequiredPersonalArtifact(Long userId, Long artifactId) {
        Artifact artifact = artifactService.getRequiredReadableArtifact(userId, artifactId);
        requirePersonalArtifact(userId, artifact);
        return artifact;
    }

    private void requirePersonalArtifact(Long userId, Artifact artifact) {
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        if (!personalSpace.getId().equals(artifact.getSpaceId()) || artifact.getResearchProjectId() == null) {
            throw new BusinessException(ErrorCode.ARTIFACT_DISTILLATION_UNSUPPORTED, "Only personal research artifacts can be distilled to synthesis cards");
        }
    }

    private ArtifactCardType resolveCardType(DistillArtifactRequest request) {
        try {
            ArtifactCardType cardType = ArtifactCardType.valueOf(request.getCardType().trim().toUpperCase());
            if (cardType != ArtifactCardType.SYNTHESIS) {
                throw new BusinessException(ErrorCode.ARTIFACT_DISTILLATION_UNSUPPORTED, "MVP only supports synthesis cards");
            }
            return cardType;
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.ARTIFACT_DISTILLATION_UNSUPPORTED, "Unsupported card type");
        }
    }

    private ArtifactVersion getRequiredLatestVersion(Long artifactId) {
        return artifactVersionRepository.findTopByArtifactIdOrderByVersionNoDesc(artifactId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ARTIFACT_DISTILLATION_UNSUPPORTED, "Artifact has no saved version"));
    }

    private DistillationDraft buildDraft(ArtifactVersion version, Long artifactId) {
        String title = normalizeTitle(version.getTitle());
        String summary = buildSummary(version.getContent(), title);
        List<String> insights = buildInsights(version.getContent(), summary);
        List<Map<String, Object>> evidenceQuotes = buildEvidenceQuotes(artifactId);
        return new DistillationDraft(title, summary, insights, evidenceQuotes);
    }

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "Synthesis Card";
        }
        return title.trim();
    }

    private String buildSummary(String content, String fallbackTitle) {
        if (content == null || content.isBlank()) {
            return fallbackTitle;
        }
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-") || trimmed.startsWith("*")) {
                continue;
            }
            return truncate(trimmed, 280);
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.isEmpty() ? fallbackTitle : truncate(normalized, 280);
    }

    private List<String> buildInsights(String content, String fallbackSummary) {
        LinkedHashSet<String> insights = new LinkedHashSet<>();
        if (content != null) {
            for (String rawLine : content.split("\\R")) {
                String line = rawLine.trim();
                if (line.startsWith("##")) {
                    insights.add(cleanInsight(line.substring(2)));
                } else if (line.startsWith("-")) {
                    insights.add(cleanInsight(line.substring(1)));
                } else if (line.startsWith("*")) {
                    insights.add(cleanInsight(line.substring(1)));
                }
                if (insights.size() >= 6) {
                    break;
                }
            }
        }
        if (insights.isEmpty() && fallbackSummary != null && !fallbackSummary.isBlank()) {
            insights.add(fallbackSummary.trim());
        }
        return insights.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> truncate(item.trim(), 200))
                .toList();
    }

    private List<Map<String, Object>> buildEvidenceQuotes(Long artifactId) {
        List<Map<String, Object>> evidenceQuotes = new ArrayList<>();
        for (ArtifactCitation relation : artifactCitationRepository.findByArtifactIdOrderByIdAsc(artifactId)) {
            Citation citation = citationRepository.findById(relation.getCitationId()).orElse(null);
            if (citation == null) {
                continue;
            }
            evidenceQuotes.add(Map.of(
                    "citationId", citation.getId(),
                    "sourceType", citation.getSourceType(),
                    "sourceId", citation.getSourceId(),
                    "quoteText", citation.getQuoteText(),
                    "title", citation.getTitle()
            ));
        }
        return evidenceQuotes;
    }

    private String cleanInsight(String raw) {
        return raw == null ? "" : raw.replaceFirst("^[:\\-\\s]+", "").trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to write distillation draft json", ex);
        }
    }

    private DistillArtifactResponse toProposalResponse(
            ArtifactDistillationProposal proposal,
            boolean confirmed,
            SynthesisCardResponse synthesisCard
    ) {
        return DistillArtifactResponse.builder()
                .proposalId(proposal.getId())
                .cardType(proposal.getCardType().name())
                .artifactId(proposal.getArtifactId())
                .artifactVersionId(proposal.getArtifactVersionId())
                .title(proposal.getTitle())
                .summary(proposal.getSummary())
                .insights(readStringList(proposal.getInsightsJson()))
                .evidenceQuotes(readMapList(proposal.getEvidenceQuotesJson()))
                .confirmed(confirmed)
                .synthesisCard(synthesisCard)
                .build();
    }

    private DistillArtifactResponse confirmedResponse(ArtifactDistillationProposal proposal, SynthesisCard synthesisCard) {
        return toProposalResponse(proposal, true, synthesisCardService.toResponse(synthesisCard));
    }

    private ArtifactCardRelationResponse toRelationResponse(ArtifactCardRelation relation) {
        String cardTitle = null;
        if (relation.getCardType() == ArtifactCardType.SYNTHESIS) {
            cardTitle = synthesisCardRepository.findById(relation.getCardId()).map(SynthesisCard::getTitle).orElse(null);
        }
        return ArtifactCardRelationResponse.builder()
                .id(relation.getId())
                .artifactId(relation.getArtifactId())
                .artifactVersionId(relation.getArtifactVersionId())
                .cardType(relation.getCardType().name())
                .cardId(relation.getCardId())
                .relationType(relation.getRelationType().name())
                .cardTitle(cardTitle)
                .createdAt(relation.getCreatedAt())
                .build();
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to read proposal insights json", ex);
        }
    }

    private List<Map<String, Object>> readMapList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to read proposal evidence json", ex);
        }
    }

    private record DistillationDraft(
            String title,
            String summary,
            List<String> insights,
            List<Map<String, Object>> evidenceQuotes
    ) {
    }
}

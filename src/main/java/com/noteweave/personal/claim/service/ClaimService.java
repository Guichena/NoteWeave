package com.noteweave.personal.claim.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.claim.dto.ClaimConceptLink;
import com.noteweave.personal.claim.dto.ClaimConceptLinkResponse;
import com.noteweave.personal.claim.dto.ClaimResponse;
import com.noteweave.personal.claim.dto.CreateClaimRequest;
import com.noteweave.personal.claim.dto.UpdateClaimRequest;
import com.noteweave.personal.claim.model.Claim;
import com.noteweave.personal.claim.model.ClaimCitation;
import com.noteweave.personal.claim.model.ClaimConceptRelation;
import com.noteweave.personal.claim.repository.ClaimCitationRepository;
import com.noteweave.personal.claim.repository.ClaimConceptRelationRepository;
import com.noteweave.personal.claim.repository.ClaimRepository;
import com.noteweave.personal.question.model.ResearchQuestion;
import com.noteweave.personal.question.service.ResearchQuestionService;
import com.noteweave.space.model.Space;
import com.noteweave.personal.common.PersonalSpaceService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ClaimService {

    private static final Set<String> CONCEPT_RELATION_TYPES = Set.of("SUPPORTS", "CONTRADICTS", "RELATED");
    private static final BigDecimal DEFAULT_CONFIDENCE = BigDecimal.valueOf(0.5d);

    private final ClaimRepository claimRepository;
    private final ClaimConceptRelationRepository claimConceptRelationRepository;
    private final ClaimCitationRepository claimCitationRepository;
    private final ConceptCardRepository conceptCardRepository;
    private final ResearchQuestionService researchQuestionService;
    private final com.noteweave.personal.question.repository.ResearchQuestionRepository researchQuestionRepository;
    private final PersonalSpaceService personalSpaceService;
    private final ClaimIndexService claimIndexService;

    @Transactional
    public ClaimResponse create(Long userId, CreateClaimRequest request) {
        ResearchQuestion question = researchQuestionService.getRequiredQuestion(userId, request.getResearchQuestionId());
        Claim claim = new Claim();
        claim.setSpaceId(question.getSpaceId());
        claim.setUserId(userId);
        claim.setResearchProjectId(question.getResearchProjectId());
        claim.setResearchQuestionId(question.getId());
        claim.setStatement(request.getStatement().trim());
        claim.setClaimType(request.getClaimType());
        claim.setStance(request.getStance());
        claim.setConfidence(normalizeConfidence(request.getConfidence()));
        claim.setRationale(normalizeOptional(request.getRationale()));
        claim.setSupersedesClaimId(resolveSupersedes(userId, request.getSupersedesClaimId(), question.getId()));
        Claim saved = claimRepository.save(claim);

        replaceConceptLinks(saved, question.getResearchProjectId(), question.getSpaceId(), request.getConceptLinks());
        replaceCitations(saved, request.getCitationIds());
        claimIndexService.syncClaim(saved);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<ClaimResponse> listByQuestion(Long userId, Long researchQuestionId) {
        // Validate ownership of the parent question before exposing its claims.
        researchQuestionService.getRequiredQuestion(userId, researchQuestionId);
        return claimRepository.findByResearchQuestionIdAndDeletedAtIsNullOrderByUpdatedAtDesc(researchQuestionId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ClaimResponse get(Long userId, Long claimId) {
        return toResponse(getRequiredClaim(userId, claimId));
    }

    /**
     * Summarize the *current effective* judgments for a research question, for injection into the
     * answer context. Claims that have been superseded by a newer claim are dropped, so the model
     * only ever sees the latest conclusion / open issue per line of reasoning.
     *
     * <p>This is a trusted read used by the memory-load path: the questionId is derived from a
     * session already verified to belong to the current user, so no separate ownership check is
     * performed here (and we deliberately avoid throwing from the context-assembly hot path).
     */
    @Transactional(readOnly = true)
    public List<String> summarizeCurrentForQuestion(Long researchQuestionId, int limit) {
        if (researchQuestionId == null) {
            return List.of();
        }
        List<Claim> claims = claimRepository.findByResearchQuestionIdAndDeletedAtIsNullOrderByUpdatedAtDesc(researchQuestionId);
        if (claims.isEmpty()) {
            return List.of();
        }
        Set<Long> superseded = claims.stream()
                .map(Claim::getSupersedesClaimId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return claims.stream()
                .filter(claim -> !superseded.contains(claim.getId()))
                .limit(limit)
                .map(this::formatForContext)
                .toList();
    }

    /**
     * Recall the user's prior judgments from *other* research questions that are textually relevant
     * to the current query. This is what lets "what did I already conclude about X" surface across
     * the whole research history, not just the question this session is bound to.
     *
     * <p>Deliberately DB- + in-memory-ranked (not ES): personal claims are small in number, and a
     * keyword-overlap score keeps this offline-testable while still being useful. Claims belonging
     * to {@code excludeQuestionId} are skipped (those already enter context via
     * {@link #summarizeCurrentForQuestion}). Superseded claims are dropped.
     */
    @Transactional(readOnly = true)
    public List<String> recallRelevantAcrossQuestions(Long userId, String queryText, Long excludeQuestionId, int limit) {
        if (userId == null || queryText == null || queryText.isBlank() || limit <= 0) {
            return List.of();
        }
        Set<String> queryTerms = tokenize(queryText);
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        List<Claim> claims = claimRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(userId);
        if (claims.isEmpty()) {
            return List.of();
        }
        Set<Long> superseded = claims.stream()
                .map(Claim::getSupersedesClaimId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return claims.stream()
                .filter(claim -> !superseded.contains(claim.getId()))
                .filter(claim -> excludeQuestionId == null || !excludeQuestionId.equals(claim.getResearchQuestionId()))
                .map(claim -> Map.entry(claim, overlapScore(queryTerms, claim)))
                .filter(entry -> entry.getValue() > 0)
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(limit)
                .map(entry -> formatForContext(entry.getKey()))
                .toList();
    }

    private int overlapScore(Set<String> queryTerms, Claim claim) {
        Set<String> claimTerms = tokenize(claim.getStatement() + " "
                + (claim.getRationale() == null ? "" : claim.getRationale()));
        if (claimTerms.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String term : queryTerms) {
            if (claimTerms.contains(term)) {
                score++;
            }
        }
        return score;
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String raw : text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{Alnum}\\p{IsHan}]+")) {
            if (raw.length() >= 2) {
                terms.add(raw);
            }
        }
        return terms;
    }

    private String formatForContext(Claim claim) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(claim.getClaimType()).append('/').append(claim.getStance())
                .append(", confidence ").append(claim.getConfidence().stripTrailingZeros().toPlainString())
                .append("] ").append(claim.getStatement());
        if (claim.getRationale() != null && !claim.getRationale().isBlank()) {
            sb.append(" — ").append(claim.getRationale());
        }
        return sb.toString();
    }

    @Transactional
    public ClaimResponse update(Long userId, Long claimId, UpdateClaimRequest request) {
        Claim claim = getRequiredClaimForWrite(userId, claimId);
        claim.setStatement(request.getStatement().trim());
        claim.setClaimType(request.getClaimType());
        claim.setStance(request.getStance());
        claim.setConfidence(normalizeConfidence(request.getConfidence()));
        claim.setRationale(normalizeOptional(request.getRationale()));
        claim.setSupersedesClaimId(resolveSupersedes(userId, request.getSupersedesClaimId(), claim.getResearchQuestionId()));
        Claim saved = claimRepository.save(claim);

        if (request.getConceptLinks() != null) {
            replaceConceptLinks(saved, saved.getResearchProjectId(), saved.getSpaceId(), request.getConceptLinks());
        }
        if (request.getCitationIds() != null) {
            replaceCitations(saved, request.getCitationIds());
        }
        claimIndexService.syncClaim(saved);
        return toResponse(saved);
    }

    @Transactional
    public void archive(Long userId, Long claimId) {
        Claim claim = getRequiredClaimForWrite(userId, claimId);
        claim.setDeletedAt(LocalDateTime.now());
        claim.setDeletedBy(userId);
        claimRepository.save(claim);
        claimIndexService.deleteClaim(claim.getId());
    }

    @Transactional(readOnly = true)
    public Claim getRequiredClaim(Long userId, Long claimId) {
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        return claimRepository.findByIdAndSpaceIdAndDeletedAtIsNull(claimId, personalSpace.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CLAIM_NOT_FOUND));
    }

    @Transactional
    public Claim getRequiredClaimForWrite(Long userId, Long claimId) {
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        Claim claim = claimRepository.findByIdForUpdate(claimId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLAIM_NOT_FOUND));
        if (claim.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.CLAIM_NOT_FOUND);
        }
        if (!claim.getSpaceId().equals(personalSpace.getId())) {
            throw new BusinessException(ErrorCode.CLAIM_ACCESS_DENIED, "No permission to access this claim");
        }
        return claim;
    }

    private Long resolveSupersedes(Long userId, Long supersedesClaimId, Long researchQuestionId) {
        if (supersedesClaimId == null) {
            return null;
        }
        Claim superseded = getRequiredClaim(userId, supersedesClaimId);
        if (!superseded.getResearchQuestionId().equals(researchQuestionId)) {
            throw new BusinessException(ErrorCode.CLAIM_ACCESS_DENIED,
                    "superseded claim belongs to a different research question");
        }
        return superseded.getId();
    }

    private void replaceConceptLinks(Claim claim, Long researchProjectId, Long spaceId, List<ClaimConceptLink> links) {
        claimConceptRelationRepository.deleteByClaimId(claim.getId());
        if (links == null || links.isEmpty()) {
            return;
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (ClaimConceptLink link : links) {
            Long conceptCardId = link.getConceptCardId();
            if (conceptCardId == null || !seen.add(conceptCardId)) {
                continue;
            }
            ConceptCard concept = conceptCardRepository.findByIdAndSpaceId(conceptCardId, spaceId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONCEPT_CARD_NOT_FOUND));
            if (!concept.getResearchProjectId().equals(researchProjectId)) {
                // A claim can only bind concepts from its own project; cross-project reuse happens
                // at the concept level, not by linking foreign concepts into this conclusion.
                throw new BusinessException(ErrorCode.CONCEPT_CARD_NOT_FOUND,
                        "concept card is outside the claim's research project");
            }
            ClaimConceptRelation relation = new ClaimConceptRelation();
            relation.setClaimId(claim.getId());
            relation.setConceptCardId(conceptCardId);
            relation.setRelationType(normalizeConceptRelationType(link.getRelationType()));
            relation.setEvidence(normalizeOptional(link.getEvidence()));
            claimConceptRelationRepository.save(relation);
        }
    }

    private void replaceCitations(Claim claim, List<Long> citationIds) {
        claimCitationRepository.deleteByClaimId(claim.getId());
        if (citationIds == null || citationIds.isEmpty()) {
            return;
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (Long citationId : citationIds) {
            if (citationId == null || !seen.add(citationId)) {
                continue;
            }
            ClaimCitation citation = new ClaimCitation();
            citation.setClaimId(claim.getId());
            citation.setCitationId(citationId);
            citation.setRelationType("EVIDENCE");
            claimCitationRepository.save(citation);
        }
    }

    private ClaimResponse toResponse(Claim claim) {
        List<ClaimConceptRelation> relations = claimConceptRelationRepository.findByClaimId(claim.getId());
        Map<Long, String> conceptNames = loadConceptNames(relations.stream()
                .map(ClaimConceptRelation::getConceptCardId)
                .collect(Collectors.toSet()));
        List<ClaimConceptLinkResponse> conceptLinks = relations.stream()
                .map(relation -> ClaimConceptLinkResponse.builder()
                        .conceptCardId(relation.getConceptCardId())
                        .conceptName(conceptNames.get(relation.getConceptCardId()))
                        .relationType(relation.getRelationType())
                        .evidence(relation.getEvidence())
                        .build())
                .toList();
        List<Long> citationIds = claimCitationRepository.findByClaimIdOrderByIdAsc(claim.getId()).stream()
                .map(ClaimCitation::getCitationId)
                .toList();
        return ClaimResponse.builder()
                .id(claim.getId())
                .spaceId(claim.getSpaceId())
                .userId(claim.getUserId())
                .researchProjectId(claim.getResearchProjectId())
                .researchQuestionId(claim.getResearchQuestionId())
                .statement(claim.getStatement())
                .claimType(claim.getClaimType())
                .stance(claim.getStance())
                .confidence(claim.getConfidence())
                .rationale(claim.getRationale())
                .supersedesClaimId(claim.getSupersedesClaimId())
                .cardStatus(claim.getCardStatus())
                .conceptLinks(conceptLinks)
                .citationIds(citationIds)
                .createdAt(claim.getCreatedAt())
                .updatedAt(claim.getUpdatedAt())
                .build();
    }

    private Map<Long, String> loadConceptNames(Collection<Long> conceptCardIds) {
        if (conceptCardIds.isEmpty()) {
            return Map.of();
        }
        return conceptCardRepository.findByIdIn(conceptCardIds).stream()
                .collect(Collectors.toMap(ConceptCard::getId, ConceptCard::getName, (left, right) -> left));
    }

    private String normalizeConceptRelationType(String relationType) {
        if (relationType == null || relationType.isBlank()) {
            return "RELATED";
        }
        String normalized = relationType.trim().toUpperCase(java.util.Locale.ROOT);
        return CONCEPT_RELATION_TYPES.contains(normalized) ? normalized : "RELATED";
    }

    private BigDecimal normalizeConfidence(BigDecimal confidence) {
        if (confidence == null) {
            return DEFAULT_CONFIDENCE;
        }
        if (confidence.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (confidence.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return confidence;
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}

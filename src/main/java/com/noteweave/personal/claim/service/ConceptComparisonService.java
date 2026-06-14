package com.noteweave.personal.claim.service;

import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.service.ConceptCardService;
import com.noteweave.personal.claim.dto.ConceptComparisonClaimView;
import com.noteweave.personal.claim.dto.ConceptComparisonQuestionGroup;
import com.noteweave.personal.claim.dto.ConceptComparisonResponse;
import com.noteweave.personal.claim.model.Claim;
import com.noteweave.personal.claim.model.ClaimConceptRelation;
import com.noteweave.personal.claim.model.ClaimStance;
import com.noteweave.personal.claim.repository.ClaimConceptRelationRepository;
import com.noteweave.personal.claim.repository.ClaimRepository;
import com.noteweave.personal.question.model.ResearchQuestion;
import com.noteweave.personal.question.repository.ResearchQuestionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cross-question comparison: how a single concept is used across different research questions.
 *
 * <p>This is the standout capability of a research-question-centric wiki versus plain notes — a
 * shared concept can <em>support</em> one question's conclusion while <em>contradicting</em>
 * another's, and that tension is exactly what we want to surface rather than flatten away.
 */
@Service
@RequiredArgsConstructor
public class ConceptComparisonService {

    private final ConceptCardService conceptCardService;
    private final ClaimConceptRelationRepository claimConceptRelationRepository;
    private final ClaimRepository claimRepository;
    private final ResearchQuestionRepository researchQuestionRepository;

    @Transactional(readOnly = true)
    public ConceptComparisonResponse compareAcrossQuestions(Long userId, Long conceptCardId) {
        // Reuse the concept card's full ownership/project validation.
        ConceptCard concept = conceptCardService.getRequiredCard(userId, conceptCardId);

        List<ClaimConceptRelation> relations = claimConceptRelationRepository.findByConceptCardId(conceptCardId);
        if (relations.isEmpty()) {
            return emptyResponse(concept);
        }

        Map<Long, ClaimConceptRelation> relationByClaimId = relations.stream()
                .collect(Collectors.toMap(
                        ClaimConceptRelation::getClaimId,
                        r -> r,
                        (left, right) -> left,
                        LinkedHashMap::new));

        List<Claim> claims = claimRepository.findByIdInAndDeletedAtIsNull(relationByClaimId.keySet());
        if (claims.isEmpty()) {
            return emptyResponse(concept);
        }

        Map<Long, String> questionTitles = loadQuestionTitles(claims);

        // Group claims by their owning research question, preserving first-seen order.
        Map<Long, List<Claim>> claimsByQuestion = claims.stream()
                .collect(Collectors.groupingBy(Claim::getResearchQuestionId, LinkedHashMap::new, Collectors.toList()));

        List<ConceptComparisonQuestionGroup> groups = new ArrayList<>();
        for (Map.Entry<Long, List<Claim>> entry : claimsByQuestion.entrySet()) {
            List<ConceptComparisonClaimView> views = entry.getValue().stream()
                    .map(claim -> toClaimView(claim, relationByClaimId.get(claim.getId())))
                    .toList();
            groups.add(ConceptComparisonQuestionGroup.builder()
                    .researchQuestionId(entry.getKey())
                    .researchQuestionTitle(questionTitles.get(entry.getKey()))
                    .claims(views)
                    .build());
        }

        return ConceptComparisonResponse.builder()
                .conceptCardId(concept.getId())
                .conceptName(concept.getName())
                .researchProjectId(concept.getResearchProjectId())
                .questionCount(groups.size())
                .claimCount(claims.size())
                .hasConflict(detectConflict(claims, relationByClaimId))
                .questionGroups(groups)
                .build();
    }

    private ConceptComparisonResponse emptyResponse(ConceptCard concept) {
        return ConceptComparisonResponse.builder()
                .conceptCardId(concept.getId())
                .conceptName(concept.getName())
                .researchProjectId(concept.getResearchProjectId())
                .questionCount(0)
                .claimCount(0)
                .hasConflict(false)
                .questionGroups(List.of())
                .build();
    }

    private Map<Long, String> loadQuestionTitles(List<Claim> claims) {
        Set<Long> questionIds = claims.stream()
                .map(Claim::getResearchQuestionId)
                .collect(Collectors.toSet());
        if (questionIds.isEmpty()) {
            return Map.of();
        }
        return researchQuestionRepository.findByIdInAndDeletedAtIsNull(questionIds).stream()
                .collect(Collectors.toMap(ResearchQuestion::getId, ResearchQuestion::getTitle, (left, right) -> left));
    }

    private ConceptComparisonClaimView toClaimView(Claim claim, ClaimConceptRelation relation) {
        return ConceptComparisonClaimView.builder()
                .claimId(claim.getId())
                .statement(claim.getStatement())
                .claimType(claim.getClaimType())
                .stance(claim.getStance())
                .confidence(claim.getConfidence())
                .relationType(relation == null ? null : relation.getRelationType())
                .relationEvidence(relation == null ? null : relation.getEvidence())
                .build();
    }

    /**
     * A concept is "in conflict" when its referencing claims disagree: either by stance
     * (SUPPORTED vs REFUTED) or by how they link to the concept (SUPPORTS vs CONTRADICTS).
     */
    private boolean detectConflict(List<Claim> claims, Map<Long, ClaimConceptRelation> relationByClaimId) {
        boolean supported = claims.stream().anyMatch(c -> c.getStance() == ClaimStance.SUPPORTED);
        boolean refuted = claims.stream().anyMatch(c -> c.getStance() == ClaimStance.REFUTED);
        if (supported && refuted) {
            return true;
        }
        boolean linkSupports = relationByClaimId.values().stream()
                .anyMatch(r -> "SUPPORTS".equals(r.getRelationType()));
        boolean linkContradicts = relationByClaimId.values().stream()
                .anyMatch(r -> "CONTRADICTS".equals(r.getRelationType()));
        return linkSupports && linkContradicts;
    }
}

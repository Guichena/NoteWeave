package com.noteweave.personal.question.service;

import com.noteweave.memory.model.SessionSummary;
import com.noteweave.memory.service.SessionSummaryService;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.claim.model.Claim;
import com.noteweave.personal.claim.model.ClaimCitation;
import com.noteweave.personal.claim.model.ClaimConceptRelation;
import com.noteweave.personal.claim.model.ClaimType;
import com.noteweave.personal.claim.repository.ClaimCitationRepository;
import com.noteweave.personal.claim.repository.ClaimConceptRelationRepository;
import com.noteweave.personal.claim.repository.ClaimRepository;
import com.noteweave.personal.question.dto.ResearchQuestionOverviewResponse;
import com.noteweave.personal.question.dto.ResearchQuestionResponse;
import com.noteweave.personal.question.dto.ResearchQuestionWorkspaceClaimResponse;
import com.noteweave.personal.question.dto.ResearchQuestionWorkspaceConceptResponse;
import com.noteweave.personal.question.dto.ResearchQuestionWorkspaceResponse;
import com.noteweave.personal.question.dto.ResearchQuestionWorkspaceSessionResponse;
import com.noteweave.personal.question.model.ResearchQuestion;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ResearchQuestionWorkspaceService {

    private final ResearchQuestionService researchQuestionService;
    private final ResearchQuestionOverviewService researchQuestionOverviewService;
    private final ClaimRepository claimRepository;
    private final ClaimConceptRelationRepository claimConceptRelationRepository;
    private final ClaimCitationRepository claimCitationRepository;
    private final ConceptCardRepository conceptCardRepository;
    private final SessionSummaryService sessionSummaryService;

    @Transactional(readOnly = true)
    public ResearchQuestionWorkspaceResponse getWorkspace(Long userId, Long questionId) {
        ResearchQuestion question = researchQuestionService.getRequiredQuestion(userId, questionId);
        List<Claim> currentClaims = filterCurrentClaims(
                claimRepository.findByResearchQuestionIdAndDeletedAtIsNullOrderByUpdatedAtDesc(questionId));
        Map<Long, List<ClaimConceptRelation>> relationsByClaimId = loadConceptRelations(currentClaims);
        Map<Long, List<ClaimCitation>> citationsByClaimId = loadCitationRelations(currentClaims);
        Map<Long, String> conceptNamesById = loadConceptNames(relationsByClaimId.values());
        List<ResearchQuestionWorkspaceClaimResponse> claimResponses = buildClaimResponses(currentClaims, relationsByClaimId, citationsByClaimId, conceptNamesById);
        List<ResearchQuestionWorkspaceConceptResponse> relatedConcepts = buildConceptResponses(relationsByClaimId, conceptNamesById);
        List<ResearchQuestionWorkspaceConceptResponse> conflictingConcepts = relatedConcepts.stream()
                .filter(ResearchQuestionWorkspaceConceptResponse::conflicting)
                .toList();
        List<ResearchQuestionWorkspaceSessionResponse> recentSessions = sessionSummaryService.retrieveByQuestion(userId, questionId).stream()
                .map(this::toSessionResponse)
                .toList();
        ResearchQuestionOverviewResponse latestOverview = researchQuestionOverviewService.getLatest(userId, questionId);
        String nextStep = resolveNextStep(question, latestOverview, claimResponses);

        return ResearchQuestionWorkspaceResponse.builder()
                .question(toQuestionResponse(question))
                .currentClaims(claimResponses)
                .openIssues(claimResponses.stream().filter(claim -> claim.claimType() == ClaimType.OPEN_ISSUE).toList())
                .relatedConcepts(relatedConcepts)
                .conflictingConcepts(conflictingConcepts)
                .recentSessions(recentSessions)
                .latestOverview(latestOverview)
                .nextStep(nextStep)
                .build();
    }

    private List<Claim> filterCurrentClaims(List<Claim> claims) {
        if (claims.isEmpty()) {
            return List.of();
        }
        Set<Long> superseded = claims.stream()
                .map(Claim::getSupersedesClaimId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return claims.stream()
                .filter(claim -> !superseded.contains(claim.getId()))
                .toList();
    }

    private Map<Long, List<ClaimConceptRelation>> loadConceptRelations(List<Claim> claims) {
        if (claims.isEmpty()) {
            return Map.of();
        }
        return claimConceptRelationRepository.findByClaimIdInOrderByClaimIdAscIdAsc(claimIds(claims)).stream()
                .collect(Collectors.groupingBy(ClaimConceptRelation::getClaimId, LinkedHashMap::new, Collectors.toList()));
    }

    private Map<Long, List<ClaimCitation>> loadCitationRelations(List<Claim> claims) {
        if (claims.isEmpty()) {
            return Map.of();
        }
        return claimCitationRepository.findByClaimIdInOrderByClaimIdAscIdAsc(claimIds(claims)).stream()
                .collect(Collectors.groupingBy(ClaimCitation::getClaimId, LinkedHashMap::new, Collectors.toList()));
    }

    private Map<Long, String> loadConceptNames(Collection<List<ClaimConceptRelation>> relationGroups) {
        LinkedHashSet<Long> conceptIds = new LinkedHashSet<>();
        for (List<ClaimConceptRelation> relations : relationGroups) {
            for (ClaimConceptRelation relation : relations) {
                conceptIds.add(relation.getConceptCardId());
            }
        }
        if (conceptIds.isEmpty()) {
            return Map.of();
        }
        return conceptCardRepository.findByIdIn(conceptIds).stream()
                .collect(Collectors.toMap(ConceptCard::getId, ConceptCard::getName, (left, right) -> left, LinkedHashMap::new));
    }

    private List<ResearchQuestionWorkspaceClaimResponse> buildClaimResponses(
            List<Claim> claims,
            Map<Long, List<ClaimConceptRelation>> relationsByClaimId,
            Map<Long, List<ClaimCitation>> citationsByClaimId,
            Map<Long, String> conceptNamesById
    ) {
        return claims.stream()
                .map(claim -> ResearchQuestionWorkspaceClaimResponse.builder()
                        .claimId(claim.getId())
                        .statement(claim.getStatement())
                        .claimType(claim.getClaimType())
                        .stance(claim.getStance())
                        .confidence(claim.getConfidence())
                        .rationale(claim.getRationale())
                        .cardStatus(claim.getCardStatus())
                        .conceptNames(relationsByClaimId.getOrDefault(claim.getId(), List.of()).stream()
                                .map(ClaimConceptRelation::getConceptCardId)
                                .map(conceptNamesById::get)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList())
                        .citationIds(citationsByClaimId.getOrDefault(claim.getId(), List.of()).stream()
                                .map(ClaimCitation::getCitationId)
                                .distinct()
                                .toList())
                        .build())
                .toList();
    }

    private List<ResearchQuestionWorkspaceConceptResponse> buildConceptResponses(
            Map<Long, List<ClaimConceptRelation>> relationsByClaimId,
            Map<Long, String> conceptNamesById
    ) {
        Map<Long, List<ClaimConceptRelation>> relationsByConcept = new LinkedHashMap<>();
        for (List<ClaimConceptRelation> relations : relationsByClaimId.values()) {
            for (ClaimConceptRelation relation : relations) {
                relationsByConcept.computeIfAbsent(relation.getConceptCardId(), ignored -> new ArrayList<>()).add(relation);
            }
        }
        return relationsByConcept.entrySet().stream()
                .map(entry -> {
                    List<String> relationTypes = entry.getValue().stream()
                            .map(ClaimConceptRelation::getRelationType)
                            .filter(Objects::nonNull)
                            .map(String::trim)
                            .map(String::toUpperCase)
                            .distinct()
                            .toList();
                    return ResearchQuestionWorkspaceConceptResponse.builder()
                            .conceptCardId(entry.getKey())
                            .conceptName(conceptNamesById.getOrDefault(entry.getKey(), "未命名概念"))
                            .relationTypes(relationTypes)
                            .claimCount((int) entry.getValue().stream().map(ClaimConceptRelation::getClaimId).distinct().count())
                            .conflicting(relationTypes.contains("SUPPORTS") && relationTypes.contains("CONTRADICTS"))
                            .build();
                })
                .sorted(Comparator.comparing(ResearchQuestionWorkspaceConceptResponse::claimCount).reversed())
                .toList();
    }

    private ResearchQuestionWorkspaceSessionResponse toSessionResponse(SessionSummary summary) {
        return ResearchQuestionWorkspaceSessionResponse.builder()
                .sessionSummaryId(summary.getId())
                .sessionId(summary.getSessionId())
                .topic(summary.getTopic())
                .summary(summary.getSummary())
                .updatedAt(summary.getUpdatedAt())
                .build();
    }

    private String resolveNextStep(
            ResearchQuestion question,
            ResearchQuestionOverviewResponse latestOverview,
            List<ResearchQuestionWorkspaceClaimResponse> currentClaims
    ) {
        if (latestOverview != null && latestOverview.nextSteps() != null && !latestOverview.nextSteps().isEmpty()) {
            return latestOverview.nextSteps().get(0);
        }
        if (question.getNextStep() != null && !question.getNextStep().isBlank()) {
            return question.getNextStep();
        }
        if (currentClaims.isEmpty()) {
            return "先沉淀当前问题下的关键判断、开放问题与证据。";
        }
        return "补充能改变当前结论置信度的新证据，并生成最新综述。";
    }

    private ResearchQuestionResponse toQuestionResponse(ResearchQuestion question) {
        return ResearchQuestionResponse.builder()
                .id(question.getId())
                .spaceId(question.getSpaceId())
                .userId(question.getUserId())
                .researchProjectId(question.getResearchProjectId())
                .title(question.getTitle())
                .questionType(question.getQuestionType())
                .status(question.getStatus())
                .currentHypothesis(question.getCurrentHypothesis())
                .currentAnswer(question.getCurrentAnswer())
                .nextStep(question.getNextStep())
                .scopeNote(question.getScopeNote())
                .createdAt(question.getCreatedAt())
                .updatedAt(question.getUpdatedAt())
                .build();
    }

    private List<Long> claimIds(List<Claim> claims) {
        return claims.stream().map(Claim::getId).toList();
    }
}

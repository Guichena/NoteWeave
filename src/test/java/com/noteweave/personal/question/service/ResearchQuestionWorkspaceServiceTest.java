package com.noteweave.personal.question.service;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.noteweave.memory.model.SessionSummary;
import com.noteweave.memory.service.SessionSummaryService;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.model.PersonalCardStatus;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.claim.model.Claim;
import com.noteweave.personal.claim.model.ClaimConceptRelation;
import com.noteweave.personal.claim.model.ClaimStance;
import com.noteweave.personal.claim.model.ClaimType;
import com.noteweave.personal.claim.repository.ClaimCitationRepository;
import com.noteweave.personal.claim.repository.ClaimConceptRelationRepository;
import com.noteweave.personal.claim.repository.ClaimRepository;
import com.noteweave.personal.question.dto.ResearchQuestionOverviewResponse;
import com.noteweave.personal.question.model.ResearchQuestion;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResearchQuestionWorkspaceServiceTest {

    @Mock
    private ResearchQuestionService researchQuestionService;

    @Mock
    private ResearchQuestionOverviewService researchQuestionOverviewService;

    @Mock
    private ClaimRepository claimRepository;

    @Mock
    private ClaimConceptRelationRepository claimConceptRelationRepository;

    @Mock
    private ClaimCitationRepository claimCitationRepository;

    @Mock
    private ConceptCardRepository conceptCardRepository;

    @Mock
    private SessionSummaryService sessionSummaryService;

    private ResearchQuestionWorkspaceService service;

    @BeforeEach
    void setUp() {
        service = new ResearchQuestionWorkspaceService(
                researchQuestionService,
                researchQuestionOverviewService,
                claimRepository,
                claimConceptRelationRepository,
                claimCitationRepository,
                conceptCardRepository,
                sessionSummaryService
        );
    }

    @Test
    void shouldReturnGuidedWorkspaceWhenQuestionHasNoClaims() {
        ResearchQuestion question = question(99L, "How should we start?");
        given(researchQuestionService.getRequiredQuestion(7L, 99L)).willReturn(question);
        given(claimRepository.findByResearchQuestionIdAndDeletedAtIsNullOrderByUpdatedAtDesc(99L)).willReturn(List.of());
        given(sessionSummaryService.retrieveByQuestion(7L, 99L)).willReturn(List.of());
        given(researchQuestionOverviewService.getLatest(7L, 99L)).willReturn(emptyOverview(99L, "How should we start?"));

        var response = service.getWorkspace(7L, 99L);

        assertThat(response.currentClaims()).isEmpty();
        assertThat(response.relatedConcepts()).isEmpty();
        assertThat(response.nextStep()).contains("关键判断");
    }

    @Test
    void shouldSurfaceConflictingConceptsAndOpenIssues() {
        ResearchQuestion question = question(100L, "Is GraphRAG worth it?");
        Claim support = claim(1L, ClaimType.CONCLUSION, ClaimStance.SUPPORTED, "Worth it for multi-hop.");
        Claim refute = claim(2L, ClaimType.CONCLUSION, ClaimStance.REFUTED, "Too heavy for MVP.");
        Claim open = claim(3L, ClaimType.OPEN_ISSUE, ClaimStance.UNCERTAIN, "What is the smallest viable index?");
        support.setCardStatus(PersonalCardStatus.READY);
        refute.setCardStatus(PersonalCardStatus.PENDING_REVIEW);
        open.setCardStatus(PersonalCardStatus.PENDING_REVIEW);

        given(researchQuestionService.getRequiredQuestion(7L, 100L)).willReturn(question);
        given(claimRepository.findByResearchQuestionIdAndDeletedAtIsNullOrderByUpdatedAtDesc(100L))
                .willReturn(List.of(support, refute, open));
        given(claimConceptRelationRepository.findByClaimIdInOrderByClaimIdAscIdAsc(List.of(1L, 2L, 3L)))
                .willReturn(List.of(
                        relation(1L, 8L, "SUPPORTS"),
                        relation(2L, 8L, "CONTRADICTS"),
                        relation(3L, 8L, "RELATED")
                ));
        given(claimCitationRepository.findByClaimIdInOrderByClaimIdAscIdAsc(List.of(1L, 2L, 3L))).willReturn(List.of());
        given(conceptCardRepository.findByIdIn(anyCollection())).willReturn(List.of(concept(8L, "GraphRAG")));
        given(sessionSummaryService.retrieveByQuestion(7L, 100L)).willReturn(List.of(summary(15L, 500L, "session note")));
        given(researchQuestionOverviewService.getLatest(7L, 100L)).willReturn(emptyOverview(100L, "Is GraphRAG worth it?"));

        var response = service.getWorkspace(7L, 100L);

        assertThat(response.currentClaims()).hasSize(3);
        assertThat(response.openIssues()).hasSize(1);
        assertThat(response.conflictingConcepts()).hasSize(1);
        assertThat(response.conflictingConcepts().get(0).conceptName()).isEqualTo("GraphRAG");
        assertThat(response.recentSessions()).hasSize(1);
    }

    @Test
    void shouldPreferLatestOverviewNextStepWhenAvailable() {
        ResearchQuestion question = question(101L, "How should we package the wiki?");
        question.setNextStep("fallback next step");
        given(researchQuestionService.getRequiredQuestion(7L, 101L)).willReturn(question);
        given(claimRepository.findByResearchQuestionIdAndDeletedAtIsNullOrderByUpdatedAtDesc(101L)).willReturn(List.of());
        given(sessionSummaryService.retrieveByQuestion(7L, 101L)).willReturn(List.of());
        given(researchQuestionOverviewService.getLatest(7L, 101L)).willReturn(
                ResearchQuestionOverviewResponse.builder()
                        .researchQuestionId(101L)
                        .title("How should we package the wiki?")
                        .nextSteps(List.of("Generate a fresh overview after adding more claims."))
                        .generated(true)
                        .build()
        );

        var response = service.getWorkspace(7L, 101L);

        assertThat(response.nextStep()).isEqualTo("Generate a fresh overview after adding more claims.");
    }

    private ResearchQuestion question(Long id, String title) {
        ResearchQuestion question = new ResearchQuestion();
        question.setId(id);
        question.setSpaceId(1L);
        question.setUserId(7L);
        question.setResearchProjectId(9L);
        question.setTitle(title);
        question.setCreatedAt(LocalDateTime.now());
        question.setUpdatedAt(LocalDateTime.now());
        return question;
    }

    private Claim claim(Long id, ClaimType claimType, ClaimStance stance, String statement) {
        Claim claim = new Claim();
        claim.setId(id);
        claim.setClaimType(claimType);
        claim.setStance(stance);
        claim.setStatement(statement);
        claim.setConfidence(BigDecimal.valueOf(0.7d));
        return claim;
    }

    private ClaimConceptRelation relation(Long claimId, Long conceptCardId, String relationType) {
        ClaimConceptRelation relation = new ClaimConceptRelation();
        relation.setClaimId(claimId);
        relation.setConceptCardId(conceptCardId);
        relation.setRelationType(relationType);
        return relation;
    }

    private ConceptCard concept(Long id, String name) {
        ConceptCard card = new ConceptCard();
        card.setId(id);
        card.setName(name);
        return card;
    }

    private SessionSummary summary(Long id, Long sessionId, String summaryText) {
        SessionSummary summary = new SessionSummary();
        summary.setId(id);
        summary.setSessionId(sessionId);
        summary.setSummary(summaryText);
        summary.setUpdatedAt(LocalDateTime.now());
        return summary;
    }

    private ResearchQuestionOverviewResponse emptyOverview(Long questionId, String title) {
        return ResearchQuestionOverviewResponse.builder()
                .researchQuestionId(questionId)
                .title(title)
                .nextSteps(List.of())
                .generated(false)
                .build();
    }
}

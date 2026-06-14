package com.noteweave.personal.claim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.noteweave.personal.claim.dto.CreateClaimRequest;
import com.noteweave.personal.claim.model.ClaimConceptRelation;
import com.noteweave.personal.claim.model.Claim;
import com.noteweave.personal.claim.model.ClaimStance;
import com.noteweave.personal.claim.model.ClaimType;
import com.noteweave.personal.claim.repository.ClaimCitationRepository;
import com.noteweave.personal.claim.repository.ClaimConceptRelationRepository;
import com.noteweave.personal.claim.repository.ClaimRepository;
import com.noteweave.personal.question.model.ResearchQuestion;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.common.PersonalSpaceService;
import com.noteweave.personal.question.service.ResearchQuestionService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClaimServiceTest {

    @Mock
    private ClaimRepository claimRepository;

    @Mock
    private ClaimConceptRelationRepository claimConceptRelationRepository;

    @Mock
    private ClaimCitationRepository claimCitationRepository;

    @Mock
    private ConceptCardRepository conceptCardRepository;

    @Mock
    private ResearchQuestionService researchQuestionService;

    @Mock
    private com.noteweave.personal.question.repository.ResearchQuestionRepository researchQuestionRepository;

    @Mock
    private PersonalSpaceService personalSpaceService;

    @Mock
    private ClaimIndexService claimIndexService;

    private ClaimService claimService;

    @BeforeEach
    void setUp() {
        claimService = new ClaimService(
                claimRepository,
                claimConceptRelationRepository,
                claimCitationRepository,
                conceptCardRepository,
                researchQuestionService,
                researchQuestionRepository,
                personalSpaceService,
                claimIndexService
        );
    }

    @Test
    void shouldDropSupersededClaimsWhenSummarizingCurrentJudgments() {
        Claim oldClaim = claim(10L, "Rollback is optional.", null);
        Claim newClaim = claim(11L, "Rollback rehearsal is mandatory before release.", 10L);
        given(claimRepository.findByResearchQuestionIdAndDeletedAtIsNullOrderByUpdatedAtDesc(99L))
                .willReturn(List.of(newClaim, oldClaim));

        List<String> summaries = claimService.summarizeCurrentForQuestion(99L, 6);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0)).contains("Rollback rehearsal is mandatory before release.");
        assertThat(summaries.get(0)).doesNotContain("Rollback is optional.");
    }

    @Test
    void shouldReturnEmptyForNullQuestion() {
        assertThat(claimService.summarizeCurrentForQuestion(null, 6)).isEmpty();
    }

    @Test
    void shouldRecallTextuallyRelevantClaimsFromOtherQuestions() {
        Claim relevant = questionClaim(30L, 200L, "GraphRAG indexing cost is too high for an MVP.");
        Claim unrelated = questionClaim(31L, 201L, "Redis cache eviction policy should be LRU.");
        given(claimRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(7L))
                .willReturn(List.of(relevant, unrelated));

        List<String> recalled = claimService.recallRelevantAcrossQuestions(7L, "Is GraphRAG indexing worth it?", 999L, 4);

        assertThat(recalled).hasSize(1);
        assertThat(recalled.get(0)).contains("GraphRAG indexing cost is too high");
    }

    @Test
    void shouldExcludeClaimsFromTheCurrentQuestionWhenRecalling() {
        Claim sameQuestion = questionClaim(40L, 500L, "GraphRAG indexing cost is too high for an MVP.");
        given(claimRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(7L))
                .willReturn(List.of(sameQuestion));

        List<String> recalled = claimService.recallRelevantAcrossQuestions(7L, "GraphRAG indexing", 500L, 4);

        assertThat(recalled).isEmpty();
    }

    @Test
    void shouldReturnEmptyRecallForBlankQuery() {
        assertThat(claimService.recallRelevantAcrossQuestions(7L, "  ", null, 4)).isEmpty();
    }

    @Test
    void shouldSyncIndexAfterCreatingClaim() {
        ResearchQuestion question = new ResearchQuestion();
        question.setId(88L);
        question.setSpaceId(1L);
        question.setUserId(7L);
        question.setResearchProjectId(9L);
        CreateClaimRequest request = new CreateClaimRequest();
        request.setResearchQuestionId(88L);
        request.setStatement("GraphRAG is too heavy for the MVP.");
        request.setClaimType(ClaimType.CONCLUSION);
        request.setStance(ClaimStance.SUPPORTED);
        request.setConfidence(BigDecimal.valueOf(0.8d));

        given(researchQuestionService.getRequiredQuestion(7L, 88L)).willReturn(question);
        given(claimRepository.save(org.mockito.ArgumentMatchers.any(Claim.class))).willAnswer(invocation -> {
            Claim claim = invocation.getArgument(0);
            claim.setId(501L);
            return claim;
        });

        claimService.create(7L, request);

        verify(claimIndexService).syncClaim(org.mockito.ArgumentMatchers.any(Claim.class));
    }

    private Claim claim(Long id, String statement, Long supersedesClaimId) {
        Claim claim = new Claim();
        claim.setId(id);
        claim.setStatement(statement);
        claim.setClaimType(ClaimType.CONCLUSION);
        claim.setStance(ClaimStance.SUPPORTED);
        claim.setConfidence(BigDecimal.valueOf(0.8d));
        claim.setSupersedesClaimId(supersedesClaimId);
        return claim;
    }

    private Claim questionClaim(Long id, Long questionId, String statement) {
        Claim claim = claim(id, statement, null);
        claim.setResearchQuestionId(questionId);
        return claim;
    }
}

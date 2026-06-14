package com.noteweave.personal.claim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.service.ConceptCardService;
import com.noteweave.personal.claim.dto.ConceptComparisonResponse;
import com.noteweave.personal.claim.model.Claim;
import com.noteweave.personal.claim.model.ClaimConceptRelation;
import com.noteweave.personal.claim.model.ClaimStance;
import com.noteweave.personal.claim.model.ClaimType;
import com.noteweave.personal.claim.repository.ClaimConceptRelationRepository;
import com.noteweave.personal.claim.repository.ClaimRepository;
import com.noteweave.personal.question.model.ResearchQuestion;
import com.noteweave.personal.question.repository.ResearchQuestionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConceptComparisonServiceTest {

    @Mock
    private ConceptCardService conceptCardService;

    @Mock
    private ClaimConceptRelationRepository claimConceptRelationRepository;

    @Mock
    private ClaimRepository claimRepository;

    @Mock
    private ResearchQuestionRepository researchQuestionRepository;

    private ConceptComparisonService service;

    @BeforeEach
    void setUp() {
        service = new ConceptComparisonService(
                conceptCardService,
                claimConceptRelationRepository,
                claimRepository,
                researchQuestionRepository
        );
    }

    @Test
    void shouldGroupClaimsByQuestionAndDetectConflictingStances() {
        ConceptCard concept = concept(5L, "GraphRAG", 1L);
        given(conceptCardService.getRequiredCard(7L, 5L)).willReturn(concept);

        // Two questions reference the same concept with opposing conclusions.
        Claim supportClaim = claim(100L, 20L, "Worth doing for multi-hop navigation.", ClaimStance.SUPPORTED);
        Claim refuteClaim = claim(101L, 21L, "Too heavy for an MVP.", ClaimStance.REFUTED);
        given(claimConceptRelationRepository.findByConceptCardId(5L))
                .willReturn(List.of(relation(100L, "SUPPORTS"), relation(101L, "CONTRADICTS")));
        given(claimRepository.findByIdInAndDeletedAtIsNull(Set.of(100L, 101L)))
                .willReturn(List.of(supportClaim, refuteClaim));
        given(researchQuestionRepository.findByIdInAndDeletedAtIsNull(Set.of(20L, 21L)))
                .willReturn(List.of(question(20L, "Is GraphRAG worth it?"), question(21L, "GraphRAG for MVP?")));

        ConceptComparisonResponse response = service.compareAcrossQuestions(7L, 5L);

        assertThat(response.getConceptName()).isEqualTo("GraphRAG");
        assertThat(response.getQuestionCount()).isEqualTo(2);
        assertThat(response.getClaimCount()).isEqualTo(2);
        assertThat(response.isHasConflict()).isTrue();
        assertThat(response.getQuestionGroups())
                .extracting("researchQuestionTitle")
                .containsExactlyInAnyOrder("Is GraphRAG worth it?", "GraphRAG for MVP?");
    }

    @Test
    void shouldReturnEmptyWhenConceptHasNoClaims() {
        ConceptCard concept = concept(5L, "GraphRAG", 1L);
        given(conceptCardService.getRequiredCard(7L, 5L)).willReturn(concept);
        given(claimConceptRelationRepository.findByConceptCardId(5L)).willReturn(List.of());

        ConceptComparisonResponse response = service.compareAcrossQuestions(7L, 5L);

        assertThat(response.getQuestionCount()).isZero();
        assertThat(response.getClaimCount()).isZero();
        assertThat(response.isHasConflict()).isFalse();
        assertThat(response.getQuestionGroups()).isEmpty();
    }

    private ConceptCard concept(Long id, String name, Long projectId) {
        ConceptCard card = new ConceptCard();
        card.setId(id);
        card.setName(name);
        card.setResearchProjectId(projectId);
        return card;
    }

    private Claim claim(Long id, Long questionId, String statement, ClaimStance stance) {
        Claim claim = new Claim();
        claim.setId(id);
        claim.setResearchQuestionId(questionId);
        claim.setStatement(statement);
        claim.setClaimType(ClaimType.CONCLUSION);
        claim.setStance(stance);
        claim.setConfidence(BigDecimal.valueOf(0.7d));
        return claim;
    }

    private ClaimConceptRelation relation(Long claimId, String relationType) {
        ClaimConceptRelation relation = new ClaimConceptRelation();
        relation.setClaimId(claimId);
        relation.setConceptCardId(5L);
        relation.setRelationType(relationType);
        return relation;
    }

    private ResearchQuestion question(Long id, String title) {
        ResearchQuestion question = new ResearchQuestion();
        question.setId(id);
        question.setTitle(title);
        return question;
    }
}

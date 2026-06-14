package com.noteweave.personal.question.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.citation.repository.CitationRepository;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.claim.model.Claim;
import com.noteweave.personal.claim.model.ClaimCitation;
import com.noteweave.personal.claim.model.ClaimConceptRelation;
import com.noteweave.personal.claim.model.ClaimStance;
import com.noteweave.personal.claim.model.ClaimType;
import com.noteweave.personal.claim.repository.ClaimCitationRepository;
import com.noteweave.personal.claim.repository.ClaimConceptRelationRepository;
import com.noteweave.personal.claim.repository.ClaimRepository;
import com.noteweave.personal.question.model.ResearchQuestion;
import com.noteweave.personal.question.model.ResearchQuestionOverview;
import com.noteweave.personal.question.repository.ResearchQuestionOverviewRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResearchQuestionOverviewServiceTest {

    @Mock
    private ResearchQuestionService researchQuestionService;

    @Mock
    private ResearchQuestionOverviewRepository researchQuestionOverviewRepository;

    @Mock
    private ClaimRepository claimRepository;

    @Mock
    private ClaimConceptRelationRepository claimConceptRelationRepository;

    @Mock
    private ClaimCitationRepository claimCitationRepository;

    @Mock
    private ConceptCardRepository conceptCardRepository;

    @Mock
    private CitationRepository citationRepository;

    private ResearchQuestionOverviewService service;

    @BeforeEach
    void setUp() {
        service = new ResearchQuestionOverviewService(
                researchQuestionService,
                researchQuestionOverviewRepository,
                claimRepository,
                claimConceptRelationRepository,
                claimCitationRepository,
                conceptCardRepository,
                citationRepository,
                new ObjectMapper()
        );
        given(researchQuestionOverviewRepository.save(any(ResearchQuestionOverview.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void shouldFilterSupersededClaimsOutOfOverview() {
        ResearchQuestion question = question(99L, "Should we ship GraphRAG?");
        Claim oldClaim = claim(10L, ClaimType.CONCLUSION, ClaimStance.SUPPORTED, "GraphRAG rollout can be optional.", null);
        Claim newClaim = claim(11L, ClaimType.CONCLUSION, ClaimStance.SUPPORTED, "GraphRAG rollout needs a rehearsal run.", 10L);

        given(researchQuestionService.getRequiredQuestion(7L, 99L)).willReturn(question);
        given(claimRepository.findByResearchQuestionIdAndDeletedAtIsNullOrderByUpdatedAtDesc(99L))
                .willReturn(List.of(newClaim, oldClaim));
        given(claimConceptRelationRepository.findByClaimIdInOrderByClaimIdAscIdAsc(List.of(11L))).willReturn(List.of());
        given(claimCitationRepository.findByClaimIdInOrderByClaimIdAscIdAsc(List.of(11L))).willReturn(List.of());

        var response = service.generate(7L, 99L);

        assertThat(response.keyClaims()).hasSize(1);
        assertThat(response.keyClaims().get(0).statement()).contains("rehearsal run");
        assertThat(response.markdown()).contains("GraphRAG rollout needs a rehearsal run.");
        assertThat(response.markdown()).doesNotContain("GraphRAG rollout can be optional.");
        assertThat(response.generatedFromSnapshot().claimIds()).containsExactly(11L);
    }

    @Test
    void shouldPlaceOpenIssuesIntoDedicatedSection() {
        ResearchQuestion question = question(100L, "What blocks the personal wiki MVP?");
        Claim openIssue = claim(20L, ClaimType.OPEN_ISSUE, ClaimStance.UNCERTAIN, "We still need a stable overview page.", null);

        given(researchQuestionService.getRequiredQuestion(7L, 100L)).willReturn(question);
        given(claimRepository.findByResearchQuestionIdAndDeletedAtIsNullOrderByUpdatedAtDesc(100L))
                .willReturn(List.of(openIssue));
        given(claimConceptRelationRepository.findByClaimIdInOrderByClaimIdAscIdAsc(List.of(20L))).willReturn(List.of());
        given(claimCitationRepository.findByClaimIdInOrderByClaimIdAscIdAsc(List.of(20L))).willReturn(List.of());

        var response = service.generate(7L, 100L);

        assertThat(response.openIssues()).containsExactly("We still need a stable overview page.");
        assertThat(response.keyClaims()).isEmpty();
        assertThat(response.markdown()).contains("## 未解决问题");
        assertThat(response.markdown()).contains("We still need a stable overview page.");
    }

    @Test
    void shouldMarkConflictsWhenConceptRelationsOpposeEachOther() {
        ResearchQuestion question = question(101L, "Is GraphRAG worth it?");
        Claim support = claim(30L, ClaimType.CONCLUSION, ClaimStance.SUPPORTED, "GraphRAG helps multi-hop navigation.", null);
        Claim refute = claim(31L, ClaimType.CONCLUSION, ClaimStance.REFUTED, "GraphRAG is too heavy for the MVP.", null);
        ClaimConceptRelation supportRelation = relation(30L, 5L, "SUPPORTS");
        ClaimConceptRelation contradictRelation = relation(31L, 5L, "CONTRADICTS");

        given(researchQuestionService.getRequiredQuestion(7L, 101L)).willReturn(question);
        given(claimRepository.findByResearchQuestionIdAndDeletedAtIsNullOrderByUpdatedAtDesc(101L))
                .willReturn(List.of(support, refute));
        given(claimConceptRelationRepository.findByClaimIdInOrderByClaimIdAscIdAsc(List.of(30L, 31L)))
                .willReturn(List.of(supportRelation, contradictRelation));
        given(conceptCardRepository.findByIdIn(anyCollection())).willReturn(List.of(concept(5L, "GraphRAG")));
        given(claimCitationRepository.findByClaimIdInOrderByClaimIdAscIdAsc(List.of(30L, 31L))).willReturn(List.of());

        var response = service.generate(7L, 101L);

        assertThat(response.conflicts()).anySatisfy(conflict -> assertThat(conflict).contains("GraphRAG"));
        assertThat(response.markdown()).contains("## 争议与冲突");
        assertThat(response.relatedConcepts()).containsExactly("GraphRAG");
    }

    @Test
    void shouldReturnGuidanceWhenNoClaimsHaveBeenRecordedYet() {
        ResearchQuestion question = question(102L, "How should we bootstrap the wiki?");

        given(researchQuestionService.getRequiredQuestion(7L, 102L)).willReturn(question);
        given(claimRepository.findByResearchQuestionIdAndDeletedAtIsNullOrderByUpdatedAtDesc(102L))
                .willReturn(List.of());

        var response = service.generate(7L, 102L);

        assertThat(response.keyClaims()).isEmpty();
        assertThat(response.generated()).isTrue();
        assertThat(response.markdown()).contains("暂无关键判断");
        assertThat(response.nextSteps()).isNotEmpty();
    }

    private ResearchQuestion question(Long id, String title) {
        ResearchQuestion question = new ResearchQuestion();
        question.setId(id);
        question.setSpaceId(1L);
        question.setUserId(7L);
        question.setResearchProjectId(9L);
        question.setTitle(title);
        return question;
    }

    private Claim claim(Long id, ClaimType type, ClaimStance stance, String statement, Long supersedesClaimId) {
        Claim claim = new Claim();
        claim.setId(id);
        claim.setResearchQuestionId(99L);
        claim.setStatement(statement);
        claim.setClaimType(type);
        claim.setStance(stance);
        claim.setConfidence(BigDecimal.valueOf(0.8d));
        claim.setSupersedesClaimId(supersedesClaimId);
        return claim;
    }

    private ClaimConceptRelation relation(Long claimId, Long conceptCardId, String relationType) {
        ClaimConceptRelation relation = new ClaimConceptRelation();
        relation.setClaimId(claimId);
        relation.setConceptCardId(conceptCardId);
        relation.setRelationType(relationType);
        return relation;
    }

    private com.noteweave.personal.card.model.ConceptCard concept(Long id, String name) {
        com.noteweave.personal.card.model.ConceptCard card = new com.noteweave.personal.card.model.ConceptCard();
        card.setId(id);
        card.setName(name);
        return card;
    }
}

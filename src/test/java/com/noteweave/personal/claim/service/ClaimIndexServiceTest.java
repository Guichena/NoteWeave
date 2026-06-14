package com.noteweave.personal.claim.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.noteweave.embedding.service.EmbeddingClient;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.claim.model.Claim;
import com.noteweave.personal.claim.model.ClaimStance;
import com.noteweave.personal.claim.model.ClaimType;
import com.noteweave.personal.claim.repository.ClaimConceptRelationRepository;
import com.noteweave.personal.question.model.ResearchQuestion;
import com.noteweave.personal.question.repository.ResearchQuestionRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClaimIndexServiceTest {

    @Mock
    private SearchIndexClaimSupport searchIndexClaimSupport;

    @Mock
    private ClaimConceptRelationRepository claimConceptRelationRepository;

    @Mock
    private ConceptCardRepository conceptCardRepository;

    @Mock
    private ResearchQuestionRepository researchQuestionRepository;

    @Mock
    private EmbeddingClient embeddingClient;

    private ClaimIndexService claimIndexService;

    @BeforeEach
    void setUp() {
        claimIndexService = new ClaimIndexService(
                searchIndexClaimSupport,
                claimConceptRelationRepository,
                conceptCardRepository,
                researchQuestionRepository,
                embeddingClient
        );
    }

    @Test
    void shouldIndexClaimWithConceptNamesAndEmbedding() {
        Claim claim = new Claim();
        claim.setId(123L);
        claim.setSpaceId(1L);
        claim.setUserId(7L);
        claim.setResearchProjectId(9L);
        claim.setResearchQuestionId(88L);
        claim.setStatement("GraphRAG is too heavy for the MVP.");
        claim.setRationale("The retrieval quality gain does not justify the cost.");
        claim.setClaimType(ClaimType.CONCLUSION);
        claim.setStance(ClaimStance.SUPPORTED);
        claim.setConfidence(BigDecimal.valueOf(0.82d));

        ResearchQuestion question = new ResearchQuestion();
        question.setId(88L);
        question.setTitle("Should we use GraphRAG?");
        ConceptCard concept = new ConceptCard();
        concept.setId(5L);
        concept.setName("GraphRAG");
        com.noteweave.personal.claim.model.ClaimConceptRelation relation = new com.noteweave.personal.claim.model.ClaimConceptRelation();
        relation.setClaimId(123L);
        relation.setConceptCardId(5L);

        given(researchQuestionRepository.findById(88L)).willReturn(java.util.Optional.of(question));
        given(claimConceptRelationRepository.findByClaimId(123L)).willReturn(List.of(relation));
        given(conceptCardRepository.findByIdIn(java.util.Set.of(5L))).willReturn(List.of(concept));
        given(embeddingClient.embedTexts(any())).willReturn(List.of(new float[]{0.1f, 0.2f}));

        claimIndexService.syncClaim(claim);

        verify(searchIndexClaimSupport).index(any(SearchIndexClaimSupport.ClaimIndexDocument.class));
    }

    @Test
    void shouldDeleteClaimFromIndex() {
        claimIndexService.deleteClaim(123L);

        verify(searchIndexClaimSupport).deleteByClaimId(123L);
    }
}

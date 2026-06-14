package com.noteweave.personal.entity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.service.ConceptCardService;
import com.noteweave.personal.claim.model.Claim;
import com.noteweave.personal.claim.service.ClaimService;
import com.noteweave.personal.common.PersonalSpaceService;
import com.noteweave.personal.entity.dto.CreateEntityCardRequest;
import com.noteweave.personal.entity.dto.LinkEntityRelationRequest;
import com.noteweave.personal.entity.model.EntityCard;
import com.noteweave.personal.entity.model.EntityType;
import com.noteweave.personal.entity.repository.ClaimEntityRelationRepository;
import com.noteweave.personal.entity.repository.ConceptEntityRelationRepository;
import com.noteweave.personal.entity.repository.EntityCardRepository;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.service.ResearchProjectService;
import com.noteweave.space.model.Space;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EntityCardServiceTest {

    @Mock
    private EntityCardRepository entityCardRepository;

    @Mock
    private ConceptEntityRelationRepository conceptEntityRelationRepository;

    @Mock
    private ClaimEntityRelationRepository claimEntityRelationRepository;

    @Mock
    private ResearchProjectService researchProjectService;

    @Mock
    private PersonalSpaceService personalSpaceService;

    @Mock
    private ConceptCardService conceptCardService;

    @Mock
    private ClaimService claimService;

    private EntityCardService service;

    @BeforeEach
    void setUp() {
        service = new EntityCardService(
                entityCardRepository,
                conceptEntityRelationRepository,
                claimEntityRelationRepository,
                researchProjectService,
                personalSpaceService,
                conceptCardService,
                claimService,
                new ObjectMapper()
        );
    }

    @Test
    void shouldMergeAliasIntoExistingEntityCard() {
        Space space = new Space();
        space.setId(1L);
        ResearchProject project = new ResearchProject();
        project.setId(9L);
        project.setSpaceId(1L);
        EntityCard existing = new EntityCard();
        existing.setId(50L);
        existing.setSpaceId(1L);
        existing.setResearchProjectId(9L);
        existing.setCanonicalName("OpenAI");
        existing.setNormalizedName("openai");
        existing.setEntityType(EntityType.COMPANY);
        existing.setAliasesJson("[\"Open AI\"]");
        existing.setConfidence(BigDecimal.valueOf(0.6d));

        CreateEntityCardRequest request = new CreateEntityCardRequest();
        request.setResearchProjectId(9L);
        request.setCanonicalName("Open AI");
        request.setEntityType(EntityType.COMPANY);
        request.setAliases(List.of("OpenAI Inc."));
        request.setExternalRefs(List.of("https://openai.com"));
        request.setConfidence(BigDecimal.valueOf(0.8d));

        given(personalSpaceService.getRequiredPersonalSpace(7L)).willReturn(space);
        given(researchProjectService.getRequiredActiveProjectForWrite(7L, 9L)).willReturn(project);
        given(entityCardRepository.findByResearchProjectIdAndSpaceIdOrderByUpdatedAtDesc(9L, 1L)).willReturn(List.of(existing));
        given(entityCardRepository.save(any(EntityCard.class))).willAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(7L, request);

        assertThat(response.id()).isEqualTo(50L);
        assertThat(response.aliases()).contains("OpenAI Inc.");
        assertThat(response.externalRefs()).contains("https://openai.com");
        assertThat(response.confidence()).isEqualByComparingTo("0.8");
    }

    @Test
    void shouldCreateConceptAndClaimLinksAgainstSameEntity() {
        ConceptCard conceptCard = new ConceptCard();
        conceptCard.setId(5L);
        conceptCard.setResearchProjectId(9L);
        Claim claim = new Claim();
        claim.setId(6L);
        claim.setResearchProjectId(9L);
        EntityCard entity = new EntityCard();
        entity.setId(7L);
        entity.setSpaceId(1L);
        entity.setResearchProjectId(9L);

        LinkEntityRelationRequest request = new LinkEntityRelationRequest();
        request.setEntityCardId(7L);
        request.setRelationType("RELATED");
        request.setEvidence("same entity reused");

        given(conceptCardService.getRequiredCard(7L, 5L)).willReturn(conceptCard);
        given(claimService.getRequiredClaim(7L, 6L)).willReturn(claim);
        given(personalSpaceService.getRequiredPersonalSpace(7L)).willReturn(space(1L));
        given(entityCardRepository.findByIdForUpdate(7L)).willReturn(Optional.of(entity));
        given(researchProjectService.getRequiredActiveProjectForWrite(7L, 9L)).willReturn(project(9L, 1L));

        service.linkConcept(7L, 5L, request);
        service.linkClaim(7L, 6L, request);

        verify(conceptEntityRelationRepository).save(any());
        verify(claimEntityRelationRepository).save(any());
    }

    private Space space(Long id) {
        Space space = new Space();
        space.setId(id);
        return space;
    }

    private ResearchProject project(Long id, Long spaceId) {
        ResearchProject project = new ResearchProject();
        project.setId(id);
        project.setSpaceId(spaceId);
        return project;
    }
}

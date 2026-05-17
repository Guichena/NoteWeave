package com.noteweave.personal.methodology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.noteweave.artifact.model.ArtifactType;
import com.noteweave.personal.common.PersonalSpaceService;
import com.noteweave.personal.methodology.model.MethodologyCard;
import com.noteweave.personal.methodology.model.MethodologyCardSource;
import com.noteweave.personal.methodology.model.MethodologyCardStatus;
import com.noteweave.personal.methodology.repository.MethodologyCardRepository;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.service.ResearchProjectService;
import com.noteweave.space.model.Space;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MethodologyMatcherTest {

    @Mock
    private MethodologyCardRepository methodologyCardRepository;

    @Mock
    private ResearchProjectService researchProjectService;

    @Mock
    private PersonalSpaceService personalSpaceService;

    private MethodologyMatcher methodologyMatcher;

    @BeforeEach
    void setUp() {
        methodologyMatcher = new MethodologyMatcher(
                methodologyCardRepository,
                researchProjectService,
                personalSpaceService
        );
    }

    @Test
    void shouldPreferExactProblemTypeOverGenericPreset() {
        given(researchProjectService.getRequiredActiveProject(11L, 101L)).willReturn(project(101L));
        given(personalSpaceService.getRequiredPersonalSpace(11L)).willReturn(personalSpace(201L));
        given(methodologyCardRepository.findByResearchProjectIdAndSpaceIdAndStatusOrderByUpdatedAtDesc(101L, 201L, MethodologyCardStatus.ACTIVE))
                .willReturn(List.of());
        given(methodologyCardRepository.findBySpaceIdAndResearchProjectIdIsNullAndStatusOrderByUpdatedAtDesc(201L, MethodologyCardStatus.ACTIVE))
                .willReturn(List.of());
        given(methodologyCardRepository.findByCardSourceAndStatusOrderByUpdatedAtDesc(MethodologyCardSource.PRESET, MethodologyCardStatus.ACTIVE))
                .willReturn(List.of(
                        preset("General Structured Writing Methodology", "General research synthesis", "GENERAL"),
                        preset("Research Report Methodology", "Research report", "REPORT")
                ));

        Optional<MethodologyCard> matched = methodologyMatcher.match(11L, 101L, ArtifactType.REPORT, Map.of("topic", "RAG report"));

        assertThat(matched)
                .isPresent()
                .get()
                .extracting(MethodologyCard::getName)
                .isEqualTo("Research Report Methodology");
    }

    @Test
    void shouldUseSceneSignalToBreakTiesWithinSameProblemType() {
        given(researchProjectService.getRequiredActiveProject(12L, 102L)).willReturn(project(102L));
        given(personalSpaceService.getRequiredPersonalSpace(12L)).willReturn(personalSpace(202L));
        given(methodologyCardRepository.findByResearchProjectIdAndSpaceIdAndStatusOrderByUpdatedAtDesc(102L, 202L, MethodologyCardStatus.ACTIVE))
                .willReturn(List.of());
        given(methodologyCardRepository.findBySpaceIdAndResearchProjectIdIsNullAndStatusOrderByUpdatedAtDesc(202L, MethodologyCardStatus.ACTIVE))
                .willReturn(List.of());
        given(methodologyCardRepository.findByCardSourceAndStatusOrderByUpdatedAtDesc(MethodologyCardSource.PRESET, MethodologyCardStatus.ACTIVE))
                .willReturn(List.of(
                        preset("Work Prep STAR Methodology", "behavioral interview", "WORK_PREP"),
                        preset("Work Prep Deep Dive Methodology", "system design interview", "WORK_PREP")
                ));

        Optional<MethodologyCard> matched = methodologyMatcher.match(
                12L,
                102L,
                ArtifactType.WORK_PREP,
                Map.of("scenario", "Need a system design interview prep outline")
        );

        assertThat(matched)
                .isPresent()
                .get()
                .extracting(MethodologyCard::getName)
                .isEqualTo("Work Prep Deep Dive Methodology");
    }

    @Test
    void shouldFallbackToGeneralPresetWhenNoSpecificProblemTypeExists() {
        given(researchProjectService.getRequiredActiveProject(13L, 103L)).willReturn(project(103L));
        given(personalSpaceService.getRequiredPersonalSpace(13L)).willReturn(personalSpace(203L));
        given(methodologyCardRepository.findByResearchProjectIdAndSpaceIdAndStatusOrderByUpdatedAtDesc(103L, 203L, MethodologyCardStatus.ACTIVE))
                .willReturn(List.of());
        given(methodologyCardRepository.findBySpaceIdAndResearchProjectIdIsNullAndStatusOrderByUpdatedAtDesc(203L, MethodologyCardStatus.ACTIVE))
                .willReturn(List.of());
        given(methodologyCardRepository.findByCardSourceAndStatusOrderByUpdatedAtDesc(MethodologyCardSource.PRESET, MethodologyCardStatus.ACTIVE))
                .willReturn(List.of(
                        preset("General Structured Writing Methodology", "General research synthesis", "GENERAL")
                ));

        Optional<MethodologyCard> matched = methodologyMatcher.match(13L, 103L, ArtifactType.BRIEFING, Map.of("topic", "Release notes"));

        assertThat(matched)
                .isPresent()
                .get()
                .extracting(MethodologyCard::getName)
                .isEqualTo("General Structured Writing Methodology");
    }

    @Test
    void shouldReturnEmptyWhenNoMethodologyCandidatesExist() {
        given(researchProjectService.getRequiredActiveProject(14L, 104L)).willReturn(project(104L));
        given(personalSpaceService.getRequiredPersonalSpace(14L)).willReturn(personalSpace(204L));
        given(methodologyCardRepository.findByResearchProjectIdAndSpaceIdAndStatusOrderByUpdatedAtDesc(104L, 204L, MethodologyCardStatus.ACTIVE))
                .willReturn(List.of());
        given(methodologyCardRepository.findBySpaceIdAndResearchProjectIdIsNullAndStatusOrderByUpdatedAtDesc(204L, MethodologyCardStatus.ACTIVE))
                .willReturn(List.of());
        given(methodologyCardRepository.findByCardSourceAndStatusOrderByUpdatedAtDesc(MethodologyCardSource.PRESET, MethodologyCardStatus.ACTIVE))
                .willReturn(List.of());

        Optional<MethodologyCard> matched = methodologyMatcher.match(14L, 104L, ArtifactType.FAQ, Map.of());

        assertThat(matched).isEmpty();
    }

    private MethodologyCard preset(String name, String scene, String problemType) {
        MethodologyCard card = new MethodologyCard();
        card.setSpaceId(MethodologySeedService.SYSTEM_PRESET_SPACE_ID);
        card.setName(name);
        card.setScene(scene);
        card.setProblemType(problemType);
        card.setCardSource(MethodologyCardSource.PRESET);
        card.setStatus(MethodologyCardStatus.ACTIVE);
        return card;
    }

    private ResearchProject project(Long id) {
        ResearchProject project = new ResearchProject();
        project.setId(id);
        project.setSpaceId(999L);
        project.setUserId(1L);
        return project;
    }

    private Space personalSpace(Long id) {
        Space space = new Space();
        space.setId(id);
        return space;
    }
}

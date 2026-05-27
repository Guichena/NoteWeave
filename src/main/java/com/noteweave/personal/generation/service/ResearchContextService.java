package com.noteweave.personal.generation.service;

import com.noteweave.artifact.model.ArtifactType;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.card.repository.ArticleCardRepository;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.card.repository.SynthesisCardRepository;
import com.noteweave.personal.methodology.MethodologyMatcher;
import com.noteweave.personal.methodology.model.MethodologyCard;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.service.ResearchProjectService;
import com.noteweave.studio.service.StudioMcpToolRegistry;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ResearchContextService {

    private final ResearchProjectService researchProjectService;
    private final ArticleCardRepository articleCardRepository;
    private final ConceptCardRepository conceptCardRepository;
    private final SynthesisCardRepository synthesisCardRepository;
    private final MethodologyMatcher methodologyMatcher;
    private final StudioMcpToolRegistry studioMcpToolRegistry;

    public ResearchGenerationContext load(Long userId, Long researchProjectId, ArtifactType artifactType, Map<String, Object> params) {
        ResearchProject project = researchProjectService.getRequiredActiveProject(userId, researchProjectId);
        List<com.noteweave.personal.card.model.ArticleCard> articleCards =
                articleCardRepository.findByResearchProjectIdAndSpaceIdOrderByUpdatedAtDesc(project.getId(), project.getSpaceId());
        List<com.noteweave.personal.card.model.ConceptCard> conceptCards =
                conceptCardRepository.findByResearchProjectIdAndSpaceIdOrderByUpdatedAtDesc(project.getId(), project.getSpaceId());
        List<com.noteweave.personal.card.model.SynthesisCard> synthesisCards =
                synthesisCardRepository.findByResearchProjectIdAndSpaceIdOrderByUpdatedAtDesc(project.getId(), project.getSpaceId());
        if (articleCards.isEmpty() && conceptCards.isEmpty() && synthesisCards.isEmpty()) {
            if (!hasMcpTool(params)) {
                throw new BusinessException(ErrorCode.RESEARCH_CONTEXT_EMPTY, "No compiled personal wiki cards available for generation");
            }
        }
        MethodologyCard methodologyCard = methodologyMatcher.match(userId, project.getId(), artifactType, params).orElse(null);
        return new ResearchGenerationContext(project, articleCards, conceptCards, synthesisCards, methodologyCard);
    }

    private boolean hasMcpTool(Map<String, Object> params) {
        return studioMcpToolRegistry.hasConfiguredTool(params);
    }
}

package com.noteweave.personal.generation.service;

import com.noteweave.personal.card.model.ArticleCard;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.model.SynthesisCard;
import com.noteweave.personal.methodology.model.MethodologyCard;
import com.noteweave.personal.project.model.ResearchProject;
import java.util.List;

public record ResearchGenerationContext(
        ResearchProject researchProject,
        List<ArticleCard> articleCards,
        List<ConceptCard> conceptCards,
        List<SynthesisCard> synthesisCards,
        MethodologyCard methodologyCard
) {
}

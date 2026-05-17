package com.noteweave.personal.generation.service;

import com.noteweave.citation.model.Citation;
import com.noteweave.citation.repository.CitationRepository;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.card.model.ArticleCard;
import com.noteweave.personal.card.model.ArticleCardCitation;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.model.ConceptCardCitation;
import com.noteweave.personal.card.repository.ArticleCardCitationRepository;
import com.noteweave.personal.card.repository.ConceptCardCitationRepository;
import com.noteweave.personal.source.model.Source;
import com.noteweave.personal.source.repository.SourceRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PersonalEvidenceService {

    private final ArticleCardCitationRepository articleCardCitationRepository;
    private final ConceptCardCitationRepository conceptCardCitationRepository;
    private final CitationRepository citationRepository;
    private final SourceRepository sourceRepository;

    public List<PersonalEvidenceItem> buildEvidence(ResearchGenerationContext context) {
        LinkedHashMap<Long, Citation> citationsById = new LinkedHashMap<>();
        for (ArticleCard articleCard : context.articleCards()) {
            for (ArticleCardCitation relation : articleCardCitationRepository.findByArticleCardIdOrderByIdAsc(articleCard.getId())) {
                citationRepository.findById(relation.getCitationId()).ifPresent(citation -> citationsById.putIfAbsent(citation.getId(), citation));
            }
        }
        for (ConceptCard conceptCard : context.conceptCards()) {
            for (ConceptCardCitation relation : conceptCardCitationRepository.findByConceptCardIdOrderByIdAsc(conceptCard.getId())) {
                citationRepository.findById(relation.getCitationId()).ifPresent(citation -> citationsById.putIfAbsent(citation.getId(), citation));
            }
        }
        if (citationsById.isEmpty()) {
            throw new BusinessException(ErrorCode.PERSONAL_GENERATION_FAILED, "No traceable evidence available for personal generation");
        }

        Map<Long, Source> sourcesById = new LinkedHashMap<>();
        for (Citation citation : citationsById.values()) {
            if (!"SOURCE".equalsIgnoreCase(citation.getSourceType())) {
                continue;
            }
            Long sourceId = citation.getSourceId();
            if (sourceId == null || sourcesById.containsKey(sourceId)) {
                continue;
            }
            sourceRepository.findById(sourceId)
                    .filter(source -> source.getDeletedAt() == null
                            && source.getResearchProjectId().equals(context.researchProject().getId())
                            && source.getSpaceId().equals(context.researchProject().getSpaceId()))
                    .ifPresent(source -> sourcesById.put(sourceId, source));
        }
        if (sourcesById.isEmpty()) {
            throw new BusinessException(ErrorCode.PERSONAL_GENERATION_FAILED, "No source-backed evidence available for personal generation");
        }

        List<PersonalEvidenceItem> evidenceItems = new ArrayList<>();
        for (Citation citation : citationsById.values()) {
            Source source = "SOURCE".equalsIgnoreCase(citation.getSourceType()) ? sourcesById.get(citation.getSourceId()) : null;
            evidenceItems.add(new PersonalEvidenceItem(citation, source));
        }
        return evidenceItems;
    }
}

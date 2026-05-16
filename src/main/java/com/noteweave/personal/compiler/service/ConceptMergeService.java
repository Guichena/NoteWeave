package com.noteweave.personal.compiler.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.card.model.ArticleConceptRelation;
import com.noteweave.personal.card.model.ConceptAlias;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.model.ConceptCardCitation;
import com.noteweave.personal.card.model.ConceptRelation;
import com.noteweave.personal.card.model.PersonalCardStatus;
import com.noteweave.citation.model.Citation;
import com.noteweave.citation.repository.CitationRepository;
import com.noteweave.personal.card.repository.ArticleConceptRelationRepository;
import com.noteweave.personal.card.repository.ConceptAliasRepository;
import com.noteweave.personal.card.repository.ConceptCardCitationRepository;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.card.repository.ConceptRelationRepository;
import com.noteweave.personal.compiler.dto.ConceptDraft;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConceptMergeService {

    private final ConceptCardRepository conceptCardRepository;
    private final ConceptAliasRepository conceptAliasRepository;
    private final ConceptRelationRepository conceptRelationRepository;
    private final ArticleConceptRelationRepository articleConceptRelationRepository;
    private final ConceptCardCitationRepository conceptCardCitationRepository;
    private final CitationRepository citationRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public MergeOutcome createOrMerge(Long spaceId, Long projectId, ConceptDraft candidate) {
        String normalizedName = normalizeName(candidate.name());
        ConceptCard existing = conceptCardRepository.findByResearchProjectIdAndNormalizedName(projectId, normalizedName)
                .orElseGet(() -> findByAlias(projectId, normalizedName));
        if (existing != null) {
            mergeInto(existing, candidate);
            attachAliases(existing, candidate.aliases());
            return new MergeOutcome(conceptCardRepository.save(existing), true);
        }

        ConceptCard conceptCard = new ConceptCard();
        conceptCard.setSpaceId(spaceId);
        conceptCard.setResearchProjectId(projectId);
        conceptCard.setName(normalizeDisplayName(candidate.name()));
        conceptCard.setNormalizedName(normalizedName);
        conceptCard.setDefinition(normalizeOptional(candidate.definition()));
        conceptCard.setExplanation(normalizeOptional(candidate.explanation()));
        conceptCard.setUseCasesJson(null);
        conceptCard.setCommonMisunderstandingsJson(null);
        conceptCard.setEvidenceQuotesJson(null);
        conceptCard.setConfidence(BigDecimal.valueOf(candidate.confidence()));
        conceptCard.setCardStatus(PersonalCardStatus.READY);
        conceptCard = conceptCardRepository.save(conceptCard);
        attachAliases(conceptCard, candidate.aliases());
        return new MergeOutcome(conceptCard, false);
    }

    @Transactional
    public void mergeConcepts(ConceptCard target, List<ConceptCard> sources) {
        for (ConceptCard source : sources) {
            if (!target.getResearchProjectId().equals(source.getResearchProjectId())) {
                throw new BusinessException(ErrorCode.CONCEPT_MERGE_INVALID, "Concept merge must stay inside one research project");
            }
            if (target.getId().equals(source.getId())) {
                continue;
            }
            mergeConceptContent(target, source);
            conceptAliasRepository.findByConceptCardIdOrderByAliasAsc(source.getId())
                    .forEach(alias -> saveAlias(target.getId(), alias.getAlias()));

            conceptCardCitationRepository.findByConceptCardIdOrderByIdAsc(source.getId())
                    .forEach(relation -> moveCitation(target.getId(), relation));

            articleConceptRelationRepository.findByConceptCardIdOrderByIdAsc(source.getId())
                    .forEach(relation -> moveArticleRelation(target.getId(), relation));

            conceptRelationRepository.findBySourceConceptIdInOrTargetConceptIdIn(List.of(source.getId()), List.of(source.getId()))
                    .forEach(relation -> moveConceptRelation(target.getId(), source.getId(), relation));

            conceptAliasRepository.deleteAll(conceptAliasRepository.findByConceptCardIdOrderByAliasAsc(source.getId()));
            conceptCardRepository.delete(source);
        }
        conceptCardRepository.save(target);
    }

    public String normalizeName(String raw) {
        String normalized = normalizeOptional(raw);
        if (normalized == null) {
            return "";
        }
        String folded = Normalizer.normalize(normalized, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        return folded.trim();
    }

    public void attachAliases(ConceptCard conceptCard, List<String> aliases) {
        Set<String> values = new LinkedHashSet<>();
        if (aliases != null) {
            values.addAll(aliases);
        }
        for (String alias : values) {
            saveAlias(conceptCard.getId(), alias);
        }
    }

    private void mergeInto(ConceptCard target, ConceptDraft candidate) {
        if (isBlank(target.getDefinition())) {
            target.setDefinition(normalizeOptional(candidate.definition()));
        }
        if (isBlank(target.getExplanation())) {
            target.setExplanation(normalizeOptional(candidate.explanation()));
        }
        target.setUseCasesJson(mergeStringArrayJson(target.getUseCasesJson(), candidate.useCases()));
        target.setCommonMisunderstandingsJson(mergeStringArrayJson(target.getCommonMisunderstandingsJson(), candidate.commonMisunderstandings()));
        double mergedConfidence = Math.max(target.getConfidence().doubleValue(), candidate.confidence());
        target.setConfidence(BigDecimal.valueOf(mergedConfidence));
    }

    private void mergeConceptContent(ConceptCard target, ConceptCard source) {
        if (isBlank(target.getDefinition())) {
            target.setDefinition(normalizeOptional(source.getDefinition()));
        }
        if (isBlank(target.getExplanation())) {
            target.setExplanation(normalizeOptional(source.getExplanation()));
        }
        target.setUseCasesJson(mergeStringArrayJson(target.getUseCasesJson(), readStringList(source.getUseCasesJson())));
        target.setCommonMisunderstandingsJson(mergeStringArrayJson(
                target.getCommonMisunderstandingsJson(),
                readStringList(source.getCommonMisunderstandingsJson())
        ));
        target.setEvidenceQuotesJson(mergeEvidenceJson(target.getEvidenceQuotesJson(), source.getEvidenceQuotesJson()));
        double mergedConfidence = Math.max(
                target.getConfidence() == null ? 0.0d : target.getConfidence().doubleValue(),
                source.getConfidence() == null ? 0.0d : source.getConfidence().doubleValue()
        );
        target.setConfidence(BigDecimal.valueOf(mergedConfidence));
        conceptCardCitationRepository.findByConceptCardIdOrderByIdAsc(source.getId()).stream()
                .map(ConceptCardCitation::getCitationId)
                .map(citationRepository::findById)
                .flatMap(java.util.Optional::stream)
                .forEach(citation -> appendEvidenceFromCitation(target, citation));
    }

    private void appendEvidenceFromCitation(ConceptCard target, Citation citation) {
        if (citation == null || isBlank(citation.getQuoteText())) {
            return;
        }
        List<Map<String, Object>> merged = readEvidenceList(target.getEvidenceQuotesJson());
        boolean exists = merged.stream().anyMatch(item ->
                citation.getQuoteText().equals(item.get("quote"))
                        && citation.getSourceId() != null
                        && citation.getSourceId().equals(item.get("sourceId"))
        );
        if (exists) {
            return;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("quote", citation.getQuoteText());
        evidence.put("sourceId", citation.getSourceId());
        evidence.put("articleCardId", null);
        evidence.put("backtraceVerified", citation.getStartOffset() != null && citation.getEndOffset() != null);
        evidence.put("startOffset", citation.getStartOffset());
        evidence.put("endOffset", citation.getEndOffset());
        evidence.put("sourceVersion", citation.getSourceVersion());
        merged.add(evidence);
        target.setEvidenceQuotesJson(writeJson(merged));
    }

    private String mergeStringArrayJson(String existingJson, List<String> incomingValues) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.addAll(readStringList(existingJson));
        if (incomingValues != null) {
            incomingValues.stream()
                    .map(this::normalizeOptional)
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(merged::add);
        }
        return merged.isEmpty() ? null : writeJson(new ArrayList<>(merged));
    }

    private String mergeEvidenceJson(String existingJson, String incomingJson) {
        List<Map<String, Object>> merged = readEvidenceList(existingJson);
        for (Map<String, Object> candidate : readEvidenceList(incomingJson)) {
            Object quote = candidate.get("quote");
            Object sourceId = candidate.get("sourceId");
            boolean exists = merged.stream().anyMatch(item ->
                    java.util.Objects.equals(item.get("quote"), quote)
                            && java.util.Objects.equals(item.get("sourceId"), sourceId)
            );
            if (!exists) {
                merged.add(candidate);
            }
        }
        return merged.isEmpty() ? null : writeJson(merged);
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to read concept list json", ex);
        }
    }

    private List<Map<String, Object>> readEvidenceList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to read concept evidence json", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to write concept merge json", ex);
        }
    }

    private void saveAlias(Long conceptCardId, String alias) {
        String normalizedAlias = normalizeName(alias);
        if (normalizedAlias.isBlank()) {
            return;
        }
        boolean exists = conceptAliasRepository.findByConceptCardIdOrderByAliasAsc(conceptCardId).stream()
                .anyMatch(existing -> normalizedAlias.equals(existing.getNormalizedAlias()));
        if (exists) {
            return;
        }
        ConceptAlias conceptAlias = new ConceptAlias();
        conceptAlias.setConceptCardId(conceptCardId);
        conceptAlias.setAlias(normalizeDisplayName(alias));
        conceptAlias.setNormalizedAlias(normalizedAlias);
        conceptAliasRepository.save(conceptAlias);
    }

    private ConceptCard findByAlias(Long projectId, String normalizedAlias) {
        return conceptAliasRepository.findByResearchProjectIdAndNormalizedAlias(projectId, normalizedAlias)
                .flatMap(alias -> conceptCardRepository.findById(alias.getConceptCardId()))
                .orElse(null);
    }

    private void moveCitation(Long targetConceptId, ConceptCardCitation relation) {
        if (conceptCardCitationRepository.findByConceptCardIdAndCitationIdAndRelationType(targetConceptId, relation.getCitationId(), relation.getRelationType()).isPresent()) {
            conceptCardCitationRepository.delete(relation);
            return;
        }
        relation.setConceptCardId(targetConceptId);
        conceptCardCitationRepository.save(relation);
    }

    private void moveArticleRelation(Long targetConceptId, ArticleConceptRelation relation) {
        ArticleConceptRelation existing = articleConceptRelationRepository.findByArticleCardIdAndConceptCardId(relation.getArticleCardId(), targetConceptId)
                .orElse(null);
        if (existing != null) {
            existing.setRelevanceScore(existing.getRelevanceScore().max(relation.getRelevanceScore()));
            if (isBlank(existing.getEvidence())) {
                existing.setEvidence(relation.getEvidence());
            }
            articleConceptRelationRepository.save(existing);
            articleConceptRelationRepository.delete(relation);
            return;
        }
        relation.setConceptCardId(targetConceptId);
        articleConceptRelationRepository.save(relation);
    }

    private void moveConceptRelation(Long targetConceptId, Long sourceConceptId, ConceptRelation relation) {
        Long newSourceId = relation.getSourceConceptId().equals(sourceConceptId) ? targetConceptId : relation.getSourceConceptId();
        Long newTargetId = relation.getTargetConceptId().equals(sourceConceptId) ? targetConceptId : relation.getTargetConceptId();
        if (newSourceId.equals(newTargetId)) {
            conceptRelationRepository.delete(relation);
            return;
        }
        ConceptRelation existing = conceptRelationRepository.findBySourceConceptIdAndTargetConceptIdAndRelationType(newSourceId, newTargetId, relation.getRelationType())
                .orElse(null);
        if (existing != null) {
            if (isBlank(existing.getDescription())) {
                existing.setDescription(relation.getDescription());
                conceptRelationRepository.save(existing);
            }
            conceptRelationRepository.delete(relation);
            return;
        }
        relation.setSourceConceptId(newSourceId);
        relation.setTargetConceptId(newTargetId);
        conceptRelationRepository.save(relation);
    }

    private String normalizeDisplayName(String value) {
        String normalized = normalizeOptional(value);
        return normalized == null ? "" : normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record MergeOutcome(ConceptCard conceptCard, boolean merged) {
    }
}

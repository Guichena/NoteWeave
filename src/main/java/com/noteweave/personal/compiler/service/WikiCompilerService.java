package com.noteweave.personal.compiler.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.llm.dto.LlmCallContext;
import com.noteweave.llm.dto.LlmMessage;
import com.noteweave.llm.dto.LlmOptions;
import com.noteweave.llm.dto.LlmResponse;
import com.noteweave.llm.service.ObservedLlmGateway;
import com.noteweave.personal.card.dto.RelatedConceptResponse;
import com.noteweave.personal.card.model.ArticleCard;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.model.ConceptRelation;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.card.repository.ConceptRelationRepository;
import com.noteweave.personal.card.service.ArticleCardService;
import com.noteweave.personal.card.service.PersonalCardCitationService;
import com.noteweave.personal.compiler.dto.ArticleCardDraft;
import com.noteweave.personal.compiler.dto.CompileSourceResponse;
import com.noteweave.personal.compiler.dto.ConceptDraft;
import com.noteweave.personal.compiler.dto.ConceptExtractionDraft;
import com.noteweave.personal.compiler.dto.ConceptRelationDraft;
import com.noteweave.personal.compiler.dto.EvidenceQuoteDraft;
import com.noteweave.personal.compiler.prompt.WikiCompilerPromptBuilder;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.model.ResearchProjectCompileStatus;
import com.noteweave.personal.project.model.ResearchProjectStatus;
import com.noteweave.personal.project.repository.ResearchProjectRepository;
import com.noteweave.personal.project.service.ResearchProjectCompileStatusService;
import com.noteweave.personal.source.model.Source;
import com.noteweave.personal.source.model.SourceCompileStatus;
import com.noteweave.personal.source.model.SourceImportStatus;
import com.noteweave.personal.source.repository.SourceRepository;
import com.noteweave.personal.source.service.SourceService;
import com.noteweave.task.dto.TaskResponse;
import com.noteweave.task.model.Task;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.model.TaskType;
import com.noteweave.task.repository.TaskRepository;
import com.noteweave.task.service.TaskCreateCommand;
import com.noteweave.task.service.TaskService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class WikiCompilerService {

    private static final String SOURCE_TARGET_TYPE = "SOURCE";

    private final SourceService sourceService;
    private final SourceRepository sourceRepository;
    private final ResearchProjectRepository researchProjectRepository;
    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final ObservedLlmGateway observedLlmGateway;
    private final ObjectMapper objectMapper;
    private final WikiCompilerPromptBuilder promptBuilder;
    private final EvidenceBacktraceService evidenceBacktraceService;
    private final ConceptMergeService conceptMergeService;
    private final ArticleCardService articleCardService;
    private final PersonalCardCitationService personalCardCitationService;
    private final ConceptCardRepository conceptCardRepository;
    private final ConceptRelationRepository conceptRelationRepository;
    private final ResearchProjectCompileStatusService researchProjectCompileStatusService;
    private final PlatformTransactionManager transactionManager;

    @Transactional
    public CompileSourceResponse compileSource(Long userId, Long sourceId) {
        Source source = sourceService.getRequiredSourceForWrite(userId, sourceId);
        if (source.getImportStatus() != SourceImportStatus.READY) {
            throw new BusinessException(ErrorCode.SOURCE_NOT_READY, "Source import is not ready");
        }
        evidenceBacktraceService.loadReadableText(source);

        Task activeTask = taskRepository.findTopByTaskTypeAndTargetTypeAndTargetIdAndTaskStatusInOrderByCreatedAtDesc(
                        TaskType.SOURCE_COMPILE,
                        SOURCE_TARGET_TYPE,
                        source.getId(),
                        java.util.EnumSet.of(TaskStatus.PENDING, TaskStatus.RUNNING)
                )
                .orElse(null);
        if (activeTask != null) {
            return CompileSourceResponse.builder()
                    .sourceId(source.getId())
                    .taskId(activeTask.getId())
                    .taskStatus(activeTask.getTaskStatus())
                    .compileStatus(source.getCompileStatus())
                    .build();
        }

        source.setCompileStatus(SourceCompileStatus.PENDING);
        source.setErrorMessage(null);
        sourceRepository.save(source);
        researchProjectRepository.findByIdForUpdate(source.getResearchProjectId()).ifPresent(project -> {
            project.setCompileStatus(ResearchProjectCompileStatus.COMPILING);
            researchProjectRepository.save(project);
        });

        long attempt = taskRepository.countByTaskTypeAndTargetTypeAndTargetId(TaskType.SOURCE_COMPILE, SOURCE_TARGET_TYPE, source.getId()) + 1;
        Map<String, Object> input = new HashMap<>();
        input.put("sourceId", source.getId());
        input.put("researchProjectId", source.getResearchProjectId());
        input.put("spaceId", source.getSpaceId());
        input.put("title", source.getTitle());

        TaskResponse createdTask = taskService.createTask(TaskCreateCommand.builder()
                        .userId(userId)
                        .spaceId(source.getSpaceId())
                        .researchProjectId(source.getResearchProjectId())
                        .taskType(TaskType.SOURCE_COMPILE)
                        .targetType(SOURCE_TARGET_TYPE)
                        .targetId(source.getId())
                        .idempotencyKey("SOURCE_COMPILE:" + source.getId() + ":attempt:" + attempt)
                        .input(input)
                        .build());

        return CompileSourceResponse.builder()
                .sourceId(source.getId())
                .taskId(createdTask.getId())
                .taskStatus(createdTask.getTaskStatus())
                .compileStatus(source.getCompileStatus())
                .build();
    }

    public CompileTaskResult executeCompileTask(Task task) {
        try {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            return template.execute(status -> {
                Source current = sourceRepository.findById(task.getTargetId()).orElse(null);
                if (current == null || current.getDeletedAt() != null) {
                    return skipped(current == null ? null : current.getId(), "SOURCE_MISSING");
                }
                ResearchProject project = researchProjectRepository.findByIdForUpdate(current.getResearchProjectId()).orElse(null);
                if (project == null || project.getDeletedAt() != null || project.getStatus() != ResearchProjectStatus.ACTIVE) {
                    return skipped(current.getId(), "RESEARCH_PROJECT_INACTIVE");
                }

                Source source = sourceRepository.findByIdForUpdate(task.getTargetId()).orElse(null);
                if (source == null || source.getDeletedAt() != null) {
                    return skipped(current.getId(), "SOURCE_DELETED");
                }

                source.setCompileStatus(SourceCompileStatus.COMPILING);
                source.setErrorMessage(null);
                sourceRepository.save(source);
                project.setCompileStatus(ResearchProjectCompileStatus.COMPILING);
                researchProjectRepository.save(project);

                ensureSourceStillCompilable(source);
                EvidenceBacktraceService.SourceTextSnapshot textSnapshot = evidenceBacktraceService.loadReadableText(source);
                String text = textSnapshot.text();

                ArticleCardDraft articleDraft = requestArticleDraft(task, source, text);
                List<Map<String, Object>> articleEvidenceCache = buildEvidenceCache(source, articleDraft.evidenceQuotes());
                ArticleCard articleCard = articleCardService.createOrUpdateFromSource(project, source.getId(), articleDraft, articleEvidenceCache);
                personalCardCitationService.replaceArticleCitations(articleCard, source, articleDraft.evidenceQuotes());

                ConceptExtractionDraft extractionDraft = requestConceptDraft(task, source, articleCard, text);
                Map<String, ConceptCard> conceptsByNormalizedName = new LinkedHashMap<>();
                int createdConceptCount = 0;
                int mergedConceptCount = 0;
                List<RelatedConceptResponse> relatedConcepts = new ArrayList<>();

                for (ConceptDraft candidate : extractionDraft.concepts()) {
                    ConceptMergeService.MergeOutcome outcome = conceptMergeService.createOrMerge(project.getSpaceId(), project.getId(), candidate);
                    ConceptCard conceptCard = hydrateConceptCard(outcome.conceptCard(), articleCard.getId(), source, candidate);
                    conceptCard = conceptCardRepository.save(conceptCard);
                    personalCardCitationService.addConceptCitation(conceptCard, source, candidate.evidence());
                    if (outcome.merged()) {
                        mergedConceptCount++;
                    } else {
                        createdConceptCount++;
                    }
                    conceptsByNormalizedName.put(conceptMergeService.normalizeName(candidate.name()), conceptCard);
                    relatedConcepts.add(RelatedConceptResponse.builder()
                            .conceptCardId(conceptCard.getId())
                            .name(conceptCard.getName())
                            .relevanceScore(conceptCard.getConfidence().doubleValue())
                            .evidence(candidate.evidence().quote())
                            .build());
                }
                articleCardService.replaceRelatedConcepts(articleCard, relatedConcepts);
                persistConceptRelations(project.getId(), conceptsByNormalizedName, extractionDraft.relations());

                source.setCompileStatus(SourceCompileStatus.READY);
                source.setErrorMessage(null);
                sourceRepository.save(source);
                researchProjectCompileStatusService.refresh(project.getId());

                Map<String, Object> output = new LinkedHashMap<>();
                output.put("sourceId", source.getId());
                output.put("articleCardId", articleCard.getId());
                output.put("conceptCount", conceptsByNormalizedName.size());
                output.put("createdConceptCount", createdConceptCount);
                output.put("mergedConceptCount", mergedConceptCount);
                return success(articleCard.getId(), output);
            });
        } catch (Exception ex) {
            markCompileFailed(task.getTargetId(), task.getResearchProjectId(), safeMessage(ex));
            throw ex;
        }
    }

    private ArticleCardDraft requestArticleDraft(Task task, Source source, String text) {
        String prompt = promptBuilder.buildArticlePrompt(source, text);
        for (int attempt = 0; attempt < 2; attempt++) {
            LlmResponse response = observedLlmGateway.chat(
                    LlmCallContext.builder()
                            .userId(task.getUserId())
                            .spaceId(task.getSpaceId())
                            .taskId(task.getId())
                            .scene("SOURCE_COMPILE")
                            .messages(List.of(new LlmMessage("user", prompt)))
                            .build(),
                    LlmOptions.builder().temperature(0.2d).maxTokens(2000).build()
            ).response();
            try {
                JsonNode root = objectMapper.readTree(response.content());
                return new ArticleCardDraft(
                        textOrDefault(root, "title", source.getTitle()),
                        textOrDefault(root, "summary", ""),
                        readStringArray(root.path("keyPoints")),
                        readStringArray(root.path("tags")),
                        readEvidenceQuotes(root.path("evidenceQuotes"))
                );
            } catch (Exception ex) {
                if (attempt == 1) {
                    throw new BusinessException(ErrorCode.LLM_JSON_PARSE_FAILED, "Failed to parse article card json: " + ex.getMessage());
                }
            }
        }
        throw new BusinessException(ErrorCode.LLM_JSON_PARSE_FAILED, "Failed to parse article card json");
    }

    private ConceptExtractionDraft requestConceptDraft(Task task, Source source, ArticleCard articleCard, String text) {
        String prompt = promptBuilder.buildConceptPrompt(source, articleCard, text);
        for (int attempt = 0; attempt < 2; attempt++) {
            LlmResponse response = observedLlmGateway.chat(
                    LlmCallContext.builder()
                            .userId(task.getUserId())
                            .spaceId(task.getSpaceId())
                            .taskId(task.getId())
                            .scene("SOURCE_COMPILE")
                            .messages(List.of(new LlmMessage("user", prompt)))
                            .build(),
                    LlmOptions.builder().temperature(0.2d).maxTokens(2000).build()
            ).response();
            try {
                JsonNode root = objectMapper.readTree(response.content());
                List<ConceptDraft> concepts = new ArrayList<>();
                for (JsonNode node : iterable(root.path("concepts"))) {
                    JsonNode evidence = node.path("evidence");
                    concepts.add(new ConceptDraft(
                            textOrDefault(node, "name", ""),
                            readStringArray(node.path("aliases")),
                            textOrDefault(node, "definition", ""),
                            textOrDefault(node, "explanation", ""),
                            readStringArray(node.path("useCases")),
                            readStringArray(node.path("commonMisunderstandings")),
                            new EvidenceQuoteDraft(
                                    textOrDefault(evidence, "quote", ""),
                                    longOrDefault(evidence, "sourceId", source.getId()),
                                    "Concept evidence",
                                    Map.of()
                            ),
                            node.path("confidence").asDouble(0.0d)
                    ));
                }
                List<ConceptRelationDraft> relations = new ArrayList<>();
                for (JsonNode node : iterable(root.path("relations"))) {
                    relations.add(new ConceptRelationDraft(
                            textOrDefault(node, "sourceName", ""),
                            textOrDefault(node, "targetName", ""),
                            textOrDefault(node, "relationType", "RELATED"),
                            textOrDefault(node, "description", "")
                    ));
                }
                return new ConceptExtractionDraft(concepts, relations);
            } catch (Exception ex) {
                if (attempt == 1) {
                    throw new BusinessException(ErrorCode.LLM_JSON_PARSE_FAILED, "Failed to parse concept json: " + ex.getMessage());
                }
            }
        }
        throw new BusinessException(ErrorCode.LLM_JSON_PARSE_FAILED, "Failed to parse concept json");
    }

    private ConceptCard hydrateConceptCard(ConceptCard conceptCard, Long articleCardId, Source source, ConceptDraft candidate) {
        if (isBlank(conceptCard.getDefinition())) {
            conceptCard.setDefinition(normalizeOptional(candidate.definition()));
        }
        if (isBlank(conceptCard.getExplanation())) {
            conceptCard.setExplanation(normalizeOptional(candidate.explanation()));
        }
        conceptCard.setUseCasesJson(mergeStringArrayJson(conceptCard.getUseCasesJson(), candidate.useCases()));
        conceptCard.setCommonMisunderstandingsJson(mergeStringArrayJson(
                conceptCard.getCommonMisunderstandingsJson(),
                candidate.commonMisunderstandings()
        ));
        double adjustedConfidence = candidate.confidence();
        EvidenceBacktraceService.EvidenceBacktrace backtrace = evidenceBacktraceService.backtrace(source, candidate.evidence().quote());
        if (!backtrace.exists()) {
            adjustedConfidence = Math.min(adjustedConfidence, 0.49d);
        }
        conceptCard.setConfidence(BigDecimal.valueOf(Math.max(conceptCard.getConfidence().doubleValue(), adjustedConfidence)));

        List<Map<String, Object>> existingEvidence = readEvidenceCache(conceptCard.getEvidenceQuotesJson());
        Map<String, Object> newEvidence = new LinkedHashMap<>();
        newEvidence.put("quote", candidate.evidence().quote());
        newEvidence.put("sourceId", candidate.evidence().sourceId());
        newEvidence.put("articleCardId", articleCardId);
        newEvidence.put("backtraceVerified", backtrace.exists());
        newEvidence.put("startOffset", backtrace.startOffset());
        newEvidence.put("endOffset", backtrace.endOffset());
        newEvidence.put("sourceVersion", backtrace.sourceVersion());
        boolean exists = existingEvidence.stream().anyMatch(item ->
                java.util.Objects.equals(candidate.evidence().quote(), item.get("quote"))
                        && java.util.Objects.equals(candidate.evidence().sourceId(), item.get("sourceId"))
                        && java.util.Objects.equals(articleCardId, item.get("articleCardId"))
        );
        if (!exists) {
            existingEvidence.add(newEvidence);
        }
        conceptCard.setEvidenceQuotesJson(writeJson(existingEvidence));
        conceptMergeService.attachAliases(conceptCard, candidate.aliases());
        return conceptCard;
    }

    private void persistConceptRelations(Long projectId, Map<String, ConceptCard> conceptsByNormalizedName, List<ConceptRelationDraft> relationDrafts) {
        for (ConceptRelationDraft relationDraft : relationDrafts) {
            ConceptCard source = conceptsByNormalizedName.get(conceptMergeService.normalizeName(relationDraft.sourceName()));
            ConceptCard target = conceptsByNormalizedName.get(conceptMergeService.normalizeName(relationDraft.targetName()));
            if (source == null || target == null || source.getId().equals(target.getId())) {
                continue;
            }
            ConceptRelation relation = conceptRelationRepository.findBySourceConceptIdAndTargetConceptIdAndRelationType(
                            source.getId(),
                            target.getId(),
                            relationDraft.relationType()
                    )
                    .orElseGet(ConceptRelation::new);
            relation.setResearchProjectId(projectId);
            relation.setSourceConceptId(source.getId());
            relation.setTargetConceptId(target.getId());
            relation.setRelationType(relationDraft.relationType());
            relation.setDescription(normalizeOptional(relationDraft.description()));
            conceptRelationRepository.save(relation);
        }
    }

    private void ensureSourceStillCompilable(Source source) {
        if (source.getImportStatus() != SourceImportStatus.READY) {
            throw new BusinessException(ErrorCode.SOURCE_NOT_READY, "Source import is not ready");
        }
        evidenceBacktraceService.loadReadableText(source);
    }

    private void markCompileFailed(Long sourceId, Long projectId, String errorMessage) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> {
            Source source = sourceRepository.findByIdForUpdate(sourceId).orElse(null);
            if (source == null || source.getDeletedAt() != null) {
                return;
            }
            source.setCompileStatus(SourceCompileStatus.FAILED);
            source.setErrorMessage(errorMessage);
            sourceRepository.save(source);
            researchProjectCompileStatusService.refresh(projectId);
        });
    }

    private List<Map<String, Object>> buildEvidenceCache(Source source, List<EvidenceQuoteDraft> evidenceQuotes) {
        List<Map<String, Object>> cache = new ArrayList<>();
        for (EvidenceQuoteDraft evidenceQuote : evidenceQuotes) {
            EvidenceBacktraceService.EvidenceBacktrace backtrace = evidenceBacktraceService.backtrace(source, evidenceQuote.quote());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("quote", evidenceQuote.quote());
            item.put("sourceId", evidenceQuote.sourceId());
            item.put("reason", evidenceQuote.reason());
            item.put("backtraceVerified", backtrace.exists());
            item.put("startOffset", backtrace.startOffset());
            item.put("endOffset", backtrace.endOffset());
            item.put("sourceVersion", backtrace.sourceVersion());
            cache.add(item);
        }
        return cache;
    }

    private List<Map<String, Object>> readEvidenceCache(String json) {
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

    private List<String> readStringArray(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : iterable(node)) {
            String value = item.asText("").trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private List<EvidenceQuoteDraft> readEvidenceQuotes(JsonNode node) {
        List<EvidenceQuoteDraft> evidenceQuotes = new ArrayList<>();
        for (JsonNode item : iterable(node)) {
            evidenceQuotes.add(new EvidenceQuoteDraft(
                    textOrDefault(item, "quote", ""),
                    longOrDefault(item, "sourceId", null),
                    textOrDefault(item, "reason", ""),
                    Map.of()
            ));
        }
        return evidenceQuotes;
    }

    private List<JsonNode> iterable(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> items = new ArrayList<>();
        node.elements().forEachRemaining(items::add);
        return items;
    }

    private String textOrDefault(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private Long longOrDefault(JsonNode node, String field, Long defaultValue) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        return value.asLong(defaultValue == null ? 0L : defaultValue);
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to write compiler json", ex);
        }
    }

    private String mergeStringArrayJson(String existingJson, List<String> incomingValues) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        merged.addAll(readStringList(existingJson));
        if (incomingValues != null) {
            incomingValues.stream()
                    .map(this::normalizeOptional)
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(merged::add);
        }
        return merged.isEmpty() ? null : writeJson(new ArrayList<>(merged));
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to read compiler list json", ex);
        }
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

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private CompileTaskResult success(Long articleCardId, Map<String, Object> output) {
        return new CompileTaskResult(articleCardId, output);
    }

    private CompileTaskResult skipped(Long sourceId, String reason) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("sourceId", sourceId);
        output.put("skipped", true);
        output.put("reason", reason);
        return new CompileTaskResult(null, output);
    }

    public record CompileTaskResult(Long articleCardId, Map<String, Object> output) {
    }
}

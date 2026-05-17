package com.noteweave.rageval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.dto.RetrievalTraceCreateRequest;
import com.noteweave.chat.dto.RetrievalTraceDetailResponse;
import com.noteweave.chat.dto.RetrievalTraceItemCreateRequest;
import com.noteweave.chat.service.RetrievalTraceService;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.llm.dto.LlmCallContext;
import com.noteweave.llm.dto.LlmOptions;
import com.noteweave.llm.service.ObservedLlmGateway;
import com.noteweave.memory.service.PromptMemoryContext;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.prompt.service.PromptVersionService;
import com.noteweave.rageval.dto.RagEvalCaseResponse;
import com.noteweave.rageval.dto.RagEvalResultResponse;
import com.noteweave.rageval.dto.RagEvalRunResponse;
import com.noteweave.rageval.dto.StartRagEvalRunRequest;
import com.noteweave.rageval.dto.UpsertRagEvalCaseRequest;
import com.noteweave.rageval.model.RagEvalCase;
import com.noteweave.rageval.model.RagEvalResult;
import com.noteweave.rageval.model.RagEvalRun;
import com.noteweave.rageval.repository.RagEvalCaseRepository;
import com.noteweave.rageval.repository.RagEvalResultRepository;
import com.noteweave.rageval.repository.RagEvalRunRepository;
import com.noteweave.task.dto.TaskResponse;
import com.noteweave.task.model.TaskType;
import com.noteweave.task.service.TaskCreateCommand;
import com.noteweave.task.service.TaskService;
import com.noteweave.team.kb.model.KnowledgeBaseStatus;
import com.noteweave.team.kb.repository.KnowledgeBaseRepository;
import com.noteweave.team.rag.config.RagProperties;
import com.noteweave.team.rag.evidence.EvidenceItem;
import com.noteweave.team.rag.evidence.EvidenceOptions;
import com.noteweave.team.rag.evidence.EvidencePostProcessor;
import com.noteweave.team.rag.prompt.PromptMessages;
import com.noteweave.team.rag.prompt.TeamRagPromptBuilder;
import com.noteweave.team.rag.retriever.HybridRetriever;
import com.noteweave.team.rag.retriever.RetrievalHit;
import com.noteweave.team.rag.retriever.TeamRetrievalQuery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RagEvaluationService {

    private static final String RUN_STATUS_PENDING = "PENDING";
    private static final String RUN_STATUS_RUNNING = "RUNNING";
    private static final String RUN_STATUS_SUCCESS = "SUCCESS";
    private static final String RUN_STATUS_FAILED = "FAILED";
    private static final String SCENE_RAG_EVAL = "RAG_EVAL";
    private static final String TARGET_TYPE = "RAG_EVAL_RUN";

    private final RagEvalCaseRepository ragEvalCaseRepository;
    private final RagEvalRunRepository ragEvalRunRepository;
    private final RagEvalResultRepository ragEvalResultRepository;
    private final ResourceAccessService resourceAccessService;
    private final TaskService taskService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final HybridRetriever hybridRetriever;
    private final EvidencePostProcessor evidencePostProcessor;
    private final TeamRagPromptBuilder teamRagPromptBuilder;
    private final RetrievalTraceService retrievalTraceService;
    private final ObservedLlmGateway observedLlmGateway;
    private final PromptVersionService promptVersionService;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<RagEvalCaseResponse> listCases(Long userId, Long spaceId) {
        resourceAccessService.requireAdminOrManageSpace(userId, spaceId);
        return ragEvalCaseRepository.findBySpaceIdOrderByUpdatedAtDescIdDesc(spaceId).stream()
                .map(this::toCaseResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Long findCaseSpaceId(Long userId, Long caseId) {
        RagEvalCase evalCase = ragEvalCaseRepository.findById(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "RAG eval case not found"));
        resourceAccessService.requireAdminOrManageSpace(userId, evalCase.getSpaceId());
        return evalCase.getSpaceId();
    }

    @Transactional
    public RagEvalCaseResponse upsertCase(Long userId, Long spaceId, Long caseId, UpsertRagEvalCaseRequest request) {
        resourceAccessService.requireAdminOrManageSpace(userId, spaceId);
        RagEvalCase evalCase = caseId == null
                ? new RagEvalCase()
                : ragEvalCaseRepository.findById(caseId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "RAG eval case not found"));
        if (evalCase.getId() != null && !spaceId.equals(evalCase.getSpaceId())) {
            throw new BusinessException(ErrorCode.SPACE_ACCESS_DENIED, "RAG eval case is outside current space");
        }
        evalCase.setSpaceId(spaceId);
        evalCase.setName(request.getName().trim());
        evalCase.setQueryText(request.getQueryText().trim());
        evalCase.setExpectedAnswer(normalize(request.getExpectedAnswer()));
        evalCase.setExpectedSourceJson(normalize(request.getExpectedSourceJson()));
        evalCase.setTagsJson(normalize(request.getTagsJson()));
        evalCase.setEnabled(request.getEnabled() == null || request.getEnabled());
        if (evalCase.getCreatedBy() == null) {
            evalCase.setCreatedBy(userId);
        }
        return toCaseResponse(ragEvalCaseRepository.save(evalCase));
    }

    @Transactional
    public RagEvalRunResponse startRun(Long userId, Long spaceId, StartRagEvalRunRequest request) {
        resourceAccessService.requireAdminOrManageSpace(userId, spaceId);
        List<RagEvalCase> cases = ragEvalCaseRepository.findBySpaceIdAndEnabledTrueOrderByIdAsc(spaceId);
        RagEvalRun run = new RagEvalRun();
        run.setSpaceId(spaceId);
        run.setName(request.getName().trim());
        run.setStatus(RUN_STATUS_PENDING);
        run.setCaseCount(cases.size());
        run.setStartedBy(userId);
        run = ragEvalRunRepository.save(run);

        TaskResponse task = taskService.createTask(TaskCreateCommand.builder()
                .userId(userId)
                .spaceId(spaceId)
                .taskType(TaskType.RAG_EVAL_RUN)
                .targetType(TARGET_TYPE)
                .targetId(run.getId())
                .idempotencyKey("RAG_EVAL_RUN:" + run.getId())
                .input(new RagEvalTaskInput(run.getId()))
                .build());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("taskId", task.getId());
        summary.put("enabledCaseCount", cases.size());
        run.setSummaryJson(writeJson(summary));
        return toRunResponse(ragEvalRunRepository.save(run));
    }

    @Transactional
    public void executeRun(Long runId, Long taskId) {
        RagEvalRun run = ragEvalRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "RAG eval run not found"));
        run.setStatus(RUN_STATUS_RUNNING);
        run.setStartedAt(LocalDateTime.now());
        ragEvalRunRepository.save(run);

        List<RagEvalCase> cases = ragEvalCaseRepository.findBySpaceIdAndEnabledTrueOrderByIdAsc(run.getSpaceId());
        List<RagEvalResult> savedResults = new ArrayList<>();
        int successCount = 0;
        int errorCount = 0;
        long totalLatency = 0L;
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int totalTokens = 0;

        for (RagEvalCase evalCase : cases) {
            RagEvalResult result = new RagEvalResult();
            result.setRunId(run.getId());
            result.setCaseId(evalCase.getId());
            try {
                EvaluationOutcome outcome = evaluateCase(run, evalCase, taskId);
                result.setRetrievalTraceId(outcome.retrievalTraceId());
                result.setLlmCallLogId(outcome.llmCallLogId());
                result.setRecallAtK(scale(outcome.recallAtK()));
                result.setMrr(scale(outcome.mrr()));
                result.setCitationCoverage(scale(outcome.citationCoverage()));
                result.setLatencyMs(outcome.latencyMs());
                result.setAnswerSnapshot(outcome.answer());
                savedResults.add(ragEvalResultRepository.save(result));
                successCount++;
                totalLatency += outcome.latencyMs();
                totalInputTokens += outcome.inputTokens();
                totalOutputTokens += outcome.outputTokens();
                totalTokens += outcome.totalTokens();
            } catch (Exception ex) {
                result.setErrorMessage(trim(ex.getMessage(), 4000));
                savedResults.add(ragEvalResultRepository.save(result));
                errorCount++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("taskId", taskId);
        summary.put("caseCount", cases.size());
        summary.put("successCount", successCount);
        summary.put("errorCount", errorCount);
        summary.put("successRate", rate(successCount, cases.size()));
        summary.put("errorRate", rate(errorCount, cases.size()));
        summary.put("avgLatencyMs", cases.isEmpty() ? 0 : totalLatency / Math.max(successCount, 1));
        summary.put("inputTokens", totalInputTokens);
        summary.put("outputTokens", totalOutputTokens);
        summary.put("totalTokens", totalTokens);
        summary.put("avgRecallAtK", average(savedResults.stream().map(RagEvalResult::getRecallAtK).toList()));
        summary.put("avgMrr", average(savedResults.stream().map(RagEvalResult::getMrr).toList()));
        summary.put("avgCitationCoverage", average(savedResults.stream().map(RagEvalResult::getCitationCoverage).toList()));

        run.setStatus(errorCount == 0 ? RUN_STATUS_SUCCESS : RUN_STATUS_FAILED);
        run.setFinishedAt(LocalDateTime.now());
        run.setSummaryJson(writeJson(summary));
        ragEvalRunRepository.save(run);
    }

    @Transactional(readOnly = true)
    public RagEvalRunResponse getRun(Long userId, Long runId) {
        RagEvalRun run = ragEvalRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "RAG eval run not found"));
        resourceAccessService.requireAdminOrManageSpace(userId, run.getSpaceId());
        return toRunResponse(run);
    }

    @Transactional(readOnly = true)
    public List<RagEvalResultResponse> listResults(Long userId, Long runId) {
        RagEvalRun run = ragEvalRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "RAG eval run not found"));
        resourceAccessService.requireAdminOrManageSpace(userId, run.getSpaceId());
        return ragEvalResultRepository.findByRunIdOrderByIdAsc(runId).stream()
                .map(this::toResultResponse)
                .toList();
    }

    private EvaluationOutcome evaluateCase(RagEvalRun run, RagEvalCase evalCase, Long taskId) {
        Instant retrievalStart = Instant.now();
        HybridRetriever.HybridRetrievalResult retrieval = hybridRetriever.retrieve(
                new TeamRetrievalQuery(
                        run.getStartedBy(),
                        run.getSpaceId(),
                        knowledgeBaseRepository.findBySpaceIdAndStatus(run.getSpaceId(), KnowledgeBaseStatus.ACTIVE).stream()
                                .map(com.noteweave.team.kb.model.KnowledgeBase::getId)
                                .toList(),
                        evalCase.getQueryText(),
                        ragProperties.retrieval().topK(),
                        true
                ),
                ragProperties.retrieval().mode()
        );
        List<EvidenceItem> evidenceItems = evidencePostProcessor.process(
                retrieval.fusedHits().stream().map(teamHit -> toRetrievedChunk(teamHit)).toList(),
                EvidenceOptions.builder()
                        .maxEvidencePerDocument(ragProperties.retrieval().perDocumentLimit())
                        .mergeAdjacentChunks(true)
                        .maxMergedChars(ragProperties.retrieval().maxMergedChars())
                        .finalTopK(ragProperties.retrieval().topK())
                        .maxContextChars(ragProperties.retrieval().contextMaxChars())
                        .minScore(ragProperties.retrieval().minScore())
                        .build()
        );
        long retrievalLatency = Math.max(1L, Duration.between(retrievalStart, Instant.now()).toMillis());
        Long traceId = retrievalTraceService.createTrace(RetrievalTraceCreateRequest.builder()
                .userId(run.getStartedBy())
                .spaceId(run.getSpaceId())
                .taskId(taskId)
                .scene(SCENE_RAG_EVAL)
                .queryText(evalCase.getQueryText())
                .retrieverType("HYBRID")
                .topK(ragProperties.retrieval().topK())
                .latencyMs(retrievalLatency)
                .retrievedChunkCount(retrieval.fusedHits().size())
                .retrievalMode(retrieval.retrievalMode().name())
                .bm25Count(retrieval.bm25Count())
                .vectorCount(retrieval.vectorCount())
                .fusionCount(retrieval.fusionCount())
                .fallbackUsed(retrieval.fallbackUsed())
                .traceJson(retrieval.traceJson())
                .build());
        retrievalTraceService.addItems(traceId, buildTraceItems(retrieval.fusedHits(), evidenceItems));

        String answer;
        long llmLogId = 0L;
        int inputTokens = 0;
        int outputTokens = 0;
        int totalTokens = 0;
        long latencyMs = retrievalLatency;
        if (evidenceItems.isEmpty()) {
            answer = ragProperties.prompt().noResultText();
        } else {
            PromptMessages prompt = teamRagPromptBuilder.build(evalCase.getQueryText(), evidenceItems, List.of(), PromptMemoryContext.empty());
            ObservedLlmGateway.ObservedLlmResult observed = observedLlmGateway.chat(
                    LlmCallContext.builder()
                            .userId(run.getStartedBy())
                            .spaceId(run.getSpaceId())
                            .taskId(taskId)
                            .scene(SCENE_RAG_EVAL)
                            .promptVersionId(promptVersionService.findActiveEntity("TEAM_RAG_CHAT").map(com.noteweave.prompt.model.PromptVersion::getId).orElse(null))
                            .messages(prompt.messages().stream().map(message -> new com.noteweave.llm.dto.LlmMessage(message.role(), message.content())).toList())
                            .build(),
                    LlmOptions.builder().temperature(0.3d).maxTokens(2000).build()
            );
            answer = observed.response().content();
            llmLogId = observed.logId();
            inputTokens = observed.response().inputTokens();
            outputTokens = observed.response().outputTokens();
            totalTokens = inputTokens + outputTokens;
            latencyMs += observed.response().latencyMs();
        }

        MatchMetrics metrics = matchMetrics(evalCase.getExpectedSourceJson(), retrievalTraceService.get(run.getStartedBy(), traceId));
        double citationCoverage = answer.contains("[SOURCE#") ? 1.0d : 0.0d;
        return new EvaluationOutcome(traceId, llmLogId == 0L ? null : llmLogId, answer, metrics.recallAtK(), metrics.mrr(), citationCoverage, latencyMs, inputTokens, outputTokens, totalTokens);
    }

    private List<RetrievalTraceItemCreateRequest> buildTraceItems(List<RetrievalHit> hits, List<EvidenceItem> evidenceItems) {
        List<RetrievalTraceItemCreateRequest> items = new ArrayList<>();
        for (int index = 0; index < hits.size(); index++) {
            RetrievalHit hit = hits.get(index);
            boolean selected = evidenceItems.stream().anyMatch(item -> item.sources().stream().anyMatch(source -> source.chunkId().equals(hit.chunkId())));
            items.add(RetrievalTraceItemCreateRequest.builder()
                    .sourceType(hit.metadata() == null ? "DOCUMENT_CHUNK" : String.valueOf(hit.metadata().getOrDefault("sourceType", "DOCUMENT_CHUNK")))
                    .sourceId(hit.metadata() == null ? hit.documentId() : longValue(hit.metadata().get("sourceId")))
                    .documentId(hit.documentId())
                    .chunkId(hit.chunkId())
                    .wikiPageId(hit.metadata() != null && hit.metadata().containsKey("publishedVersionId") ? longValue(hit.metadata().get("sourceId")) : null)
                    .score(hit.score())
                    .rank(index + 1)
                    .selectedAsEvidence(selected)
                    .metadataJson(writeJson(hit.metadata()))
                    .build());
        }
        return items;
    }

    private com.noteweave.team.rag.retriever.RetrievedChunk toRetrievedChunk(RetrievalHit hit) {
        Integer indexVersion = hit.metadata() == null ? null : (Integer) hit.metadata().get("indexVersion");
        return new com.noteweave.team.rag.retriever.RetrievedChunk(
                hit.chunkId(),
                hit.documentId(),
                hit.knowledgeBaseId(),
                hit.spaceId(),
                hit.metadata() == null ? "DOCUMENT" : String.valueOf(hit.metadata().getOrDefault("sourceType", "DOCUMENT")),
                hit.metadata() == null ? hit.documentId() : longValue(hit.metadata().getOrDefault("sourceId", hit.documentId())),
                indexVersion,
                hit.chunkIndex(),
                hit.documentTitle(),
                hit.content(),
                hit.score(),
                1,
                null,
                null,
                indexVersion == null ? "unknown" : String.valueOf(indexVersion)
        );
    }

    private MatchMetrics matchMetrics(String expectedSourceJson, RetrievalTraceDetailResponse detail) {
        if (expectedSourceJson == null || expectedSourceJson.isBlank()) {
            return new MatchMetrics(0.0d, 0.0d);
        }
        try {
            JsonNode root = objectMapper.readTree(expectedSourceJson);
            List<ExpectedSource> expectedSources = parseExpectedSources(root);
            if (expectedSources.isEmpty()) {
                return new MatchMetrics(0.0d, 0.0d);
            }
            java.util.Set<Integer> matchedExpectedIndexes = new java.util.HashSet<>();
            double mrr = 0.0d;
            for (int index = 0; index < detail.items().size(); index++) {
                var item = detail.items().get(index);
                for (int expectedIndex = 0; expectedIndex < expectedSources.size(); expectedIndex++) {
                    if (expectedSources.get(expectedIndex).matches(item)) {
                        matchedExpectedIndexes.add(expectedIndex);
                        if (mrr == 0.0d) {
                            mrr = 1.0d / (index + 1);
                        }
                    }
                }
            }
            return new MatchMetrics((double) matchedExpectedIndexes.size() / expectedSources.size(), mrr);
        } catch (Exception ex) {
            return new MatchMetrics(0.0d, 0.0d);
        }
    }

    private List<ExpectedSource> parseExpectedSources(JsonNode root) {
        List<ExpectedSource> expectedSources = new ArrayList<>();
        if (root == null || root.isNull()) {
            return expectedSources;
        }
        if (root.isArray()) {
            for (JsonNode node : root) {
                expectedSources.add(ExpectedSource.from(node));
            }
            return expectedSources;
        }
        expectedSources.add(ExpectedSource.from(root));
        return expectedSources;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private BigDecimal average(List<BigDecimal> values) {
        List<BigDecimal> nonNull = values.stream().filter(java.util.Objects::nonNull).toList();
        if (nonNull.isEmpty()) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal sum = nonNull.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(nonNull.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal scale(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(int numerator, int denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private RagEvalCaseResponse toCaseResponse(RagEvalCase evalCase) {
        return RagEvalCaseResponse.builder()
                .id(evalCase.getId())
                .spaceId(evalCase.getSpaceId())
                .name(evalCase.getName())
                .queryText(evalCase.getQueryText())
                .expectedAnswer(evalCase.getExpectedAnswer())
                .expectedSourceJson(evalCase.getExpectedSourceJson())
                .tagsJson(evalCase.getTagsJson())
                .enabled(evalCase.isEnabled())
                .createdBy(evalCase.getCreatedBy())
                .createdAt(evalCase.getCreatedAt())
                .updatedAt(evalCase.getUpdatedAt())
                .build();
    }

    private RagEvalRunResponse toRunResponse(RagEvalRun run) {
        return RagEvalRunResponse.builder()
                .id(run.getId())
                .spaceId(run.getSpaceId())
                .name(run.getName())
                .status(run.getStatus())
                .caseCount(run.getCaseCount())
                .startedBy(run.getStartedBy())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .summaryJson(run.getSummaryJson())
                .createdAt(run.getCreatedAt())
                .updatedAt(run.getUpdatedAt())
                .build();
    }

    private RagEvalResultResponse toResultResponse(RagEvalResult result) {
        return RagEvalResultResponse.builder()
                .id(result.getId())
                .runId(result.getRunId())
                .caseId(result.getCaseId())
                .answerMessageId(result.getAnswerMessageId())
                .retrievalTraceId(result.getRetrievalTraceId())
                .llmCallLogId(result.getLlmCallLogId())
                .recallAtK(result.getRecallAtK())
                .mrr(result.getMrr())
                .citationCoverage(result.getCitationCoverage())
                .groundednessScore(result.getGroundednessScore())
                .answerQualityScore(result.getAnswerQualityScore())
                .latencyMs(result.getLatencyMs())
                .errorMessage(result.getErrorMessage())
                .answerSnapshot(result.getAnswerSnapshot())
                .createdAt(result.getCreatedAt())
                .build();
    }

    private record EvaluationOutcome(
            Long retrievalTraceId,
            Long llmCallLogId,
            String answer,
            double recallAtK,
            double mrr,
            double citationCoverage,
            long latencyMs,
            int inputTokens,
            int outputTokens,
            int totalTokens
    ) {
    }

    private record MatchMetrics(double recallAtK, double mrr) {
    }

    private record ExpectedSource(String sourceType, Long sourceId, Long documentId, Long chunkId, Long wikiPageId) {

        static ExpectedSource from(JsonNode node) {
            return new ExpectedSource(
                    text(node, "sourceType"),
                    longValue(node, "sourceId"),
                    longValue(node, "documentId"),
                    longValue(node, "chunkId"),
                    longValue(node, "wikiPageId")
            );
        }

        boolean matches(com.noteweave.chat.dto.RetrievalTraceItemResponse item) {
            if (chunkId != null && chunkId.equals(item.chunkId())) {
                return true;
            }
            if (wikiPageId != null && wikiPageId.equals(item.wikiPageId())) {
                return true;
            }
            if (documentId != null && documentId.equals(item.documentId())) {
                return true;
            }
            return sourceId != null
                    && sourceId.equals(item.sourceId())
                    && (sourceType == null || sourceType.isBlank() || sourceType.equalsIgnoreCase(item.sourceType()));
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node == null ? null : node.path(field);
            return value == null || value.isMissingNode() || value.isNull() ? null : value.asText(null);
        }

        private static Long longValue(JsonNode node, String field) {
            JsonNode value = node == null ? null : node.path(field);
            if (value == null || value.isMissingNode() || value.isNull()) {
                return null;
            }
            return value.asLong();
        }
    }
}

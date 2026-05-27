package com.noteweave.artifact.service;

import com.noteweave.artifact.model.Artifact;
import com.noteweave.artifact.model.ArtifactScopeType;
import com.noteweave.artifact.model.ArtifactSourceType;
import com.noteweave.artifact.model.ArtifactStatus;
import com.noteweave.artifact.model.ArtifactType;
import com.noteweave.artifact.repository.ArtifactRepository;
import com.noteweave.artifact.skill.service.SkillExecutionLogService;
import com.noteweave.chat.dto.RetrievalTraceCreateRequest;
import com.noteweave.chat.dto.RetrievalTraceItemCreateRequest;
import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatMessageRole;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.repository.ChatMessageRepository;
import com.noteweave.chat.service.ChatSessionService;
import com.noteweave.chat.service.RetrievalTraceService;
import com.noteweave.citation.model.Citation;
import com.noteweave.citation.repository.CitationRepository;
import com.noteweave.citation.repository.MessageCitationRepository;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.llm.dto.LlmCallContext;
import com.noteweave.llm.dto.LlmMessage;
import com.noteweave.llm.dto.LlmOptions;
import com.noteweave.llm.dto.LlmResponse;
import com.noteweave.llm.service.ObservedLlmGateway;
import com.noteweave.personal.card.model.ArticleCard;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.model.SynthesisCard;
import com.noteweave.personal.generation.service.PersonalEvidenceItem;
import com.noteweave.personal.generation.service.PersonalGenerationService;
import com.noteweave.personal.methodology.MethodologyPromptSectionBuilder;
import com.noteweave.personal.methodology.model.MethodologyCard;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.prompt.model.PromptVersion;
import com.noteweave.prompt.service.PromptTemplateRenderer;
import com.noteweave.prompt.service.PromptVersionService;
import com.noteweave.studio.service.ArtifactGenerateTaskInput;
import com.noteweave.studio.service.StudioMcpTool;
import com.noteweave.studio.service.StudioMcpToolRegistry;
import com.noteweave.task.worker.TaskExecutionContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ArtifactPlanExecutor {

    private final ArtifactRepository artifactRepository;
    private final CitationRepository citationRepository;
    private final MessageCitationRepository messageCitationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionService chatSessionService;
    private final ObservedLlmGateway observedLlmGateway;
    private final SkillExecutionLogService skillExecutionLogService;
    private final ArtifactPersistenceService artifactPersistenceService;
    private final MethodologyPromptSectionBuilder methodologyPromptSectionBuilder;
    private final PersonalGenerationService personalGenerationService;
    private final RetrievalTraceService retrievalTraceService;
    private final PromptVersionService promptVersionService;
    private final PromptTemplateRenderer promptTemplateRenderer;
    private final StudioMcpToolRegistry studioMcpToolRegistry;

    public ArtifactExecutionResult execute(TaskExecutionContext taskContext) {
        ArtifactGenerateTaskInput input = taskContext.readInput(ArtifactGenerateTaskInput.class);
        Artifact artifact = artifactRepository.findById(input.getArtifactId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ARTIFACT_NOT_FOUND));
        if (artifact.getDeletedAt() != null || artifact.getStatus() == ArtifactStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.ARTIFACT_NOT_FOUND);
        }
        artifact.setStatus(ArtifactStatus.GENERATING);
        artifactRepository.save(artifact);

        GenerationState state = new GenerationState(taskContext.task().getId(), taskContext.task().getUserId(), input, artifact);
        try {
            for (String skillName : planFor(input)) {
                taskContext.ensureNotCancelled();
                taskContext.publishProgress(skillName, Map.of("skill", skillName));
                runSkill(skillName, state);
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("artifactId", artifact.getId());
            output.put("artifactVersionId", state.artifactVersionId);
            output.put("artifactType", input.getArtifactType().name());
            return new ArtifactExecutionResult(state.artifactVersionId, output);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.PLAN_EXECUTION_FAILED, safeMessage(ex));
        }
    }

    private List<String> planFor(ArtifactGenerateTaskInput input) {
        List<String> plan = new ArrayList<>();
        plan.add("LoadGenerationContextSkill");
        if (studioMcpToolRegistry.resolve(input.getParams()).isPresent()) {
            plan.add("LoadMcpToolContextSkill");
        }
        switch (input.getArtifactType()) {
            case REPORT -> {
                plan.add("SelectEvidenceSkill");
                plan.add("GenerateReportSkill");
            }
            case STUDY_GUIDE -> {
                plan.add("SelectArticleCardSkill");
                plan.add("SelectConceptCardSkill");
                plan.add("GenerateStudyGuideSkill");
            }
            case BRIEFING -> {
                plan.add("SelectEvidenceSkill");
                plan.add("GenerateBriefingSkill");
            }
            case FAQ -> {
                plan.add("SelectEvidenceSkill");
                plan.add("GenerateFaqSkill");
            }
            case COMPARISON -> {
                plan.add("SelectEvidenceSkill");
                plan.add("GenerateComparisonSkill");
            }
            case WORK_PREP -> {
                plan.add("SelectConceptCardSkill");
                plan.add("GenerateWorkPrepSkill");
            }
            case READING_NOTES -> {
                plan.add("SelectArticleCardSkill");
                plan.add("SelectEvidenceSkill");
                plan.add("GenerateReadingNotesSkill");
            }
            case WIKI_DRAFT -> {
                plan.add("SelectEvidenceSkill");
                plan.add("GenerateWikiDraftSkill");
            }
            default -> throw new BusinessException(ErrorCode.ARTIFACT_TYPE_UNSUPPORTED);
        }
        plan.add("SaveArtifactSkill");
        return List.copyOf(plan);
    }

    private void runSkill(String skillName, GenerationState state) {
        Instant start = Instant.now();
        Map<String, Object> inputSummary = redactedInputSummary(skillName, state);
        try {
            SkillOutcome outcome = switch (skillName) {
                case "LoadGenerationContextSkill" -> loadGenerationContext(state);
                case "LoadMcpToolContextSkill" -> loadMcpToolContext(state);
                case "SelectEvidenceSkill" -> selectEvidence(state);
                case "SelectArticleCardSkill" -> selectArticleCards(state);
                case "SelectConceptCardSkill" -> selectConceptCards(state);
                case "GenerateReportSkill" -> generateArtifact(state, skillName, "生成一份结构化技术报告，先给结论，再给分节说明。");
                case "GenerateStudyGuideSkill" -> generateArtifact(state, skillName, "生成学习指南，包含目标、重点概念、复习建议。");
                case "GenerateBriefingSkill" -> generateArtifact(state, skillName, "生成简报，适合快速汇报。");
                case "GenerateFaqSkill" -> generateArtifact(state, skillName, "生成 FAQ，使用问答结构。");
                case "GenerateComparisonSkill" -> generateArtifact(state, skillName, "生成比较报告，强调差异、优缺点和适用场景。");
                case "GenerateWorkPrepSkill" -> generateArtifact(state, skillName, personalGenerationService.instructionFor(ArtifactType.WORK_PREP));
                case "GenerateReadingNotesSkill" -> generateArtifact(state, skillName, personalGenerationService.instructionFor(ArtifactType.READING_NOTES));
                case "GenerateWikiDraftSkill" -> generateArtifact(state, skillName, "生成 Wiki 草稿，使用中性、可维护的知识表达。");
                case "SaveArtifactSkill" -> saveArtifact(state);
                default -> throw new BusinessException(ErrorCode.SKILL_EXECUTION_FAILED, "Unsupported skill: " + skillName);
            };
            skillExecutionLogService.record(
                    state.taskId,
                    state.artifact.getId(),
                    outcome.artifactVersionId(),
                    skillName,
                    inputSummary,
                    outcome.output(),
                    "SUCCESS",
                    null,
                    Math.max(1L, Duration.between(start, Instant.now()).toMillis()),
                    outcome.modelName(),
                    "phase8-v1",
                    outcome.inputTokens(),
                    outcome.outputTokens()
            );
        } catch (Exception ex) {
            skillExecutionLogService.record(
                    state.taskId,
                    state.artifact.getId(),
                    state.artifactVersionId,
                    skillName,
                    inputSummary,
                    Map.of("redacted", true, "failed", true),
                    "FAILED",
                    safeMessage(ex),
                    Math.max(1L, Duration.between(start, Instant.now()).toMillis()),
                    null,
                    "phase8-v1",
                    null,
                    null
            );
            throw ex;
        }
    }

    private SkillOutcome loadGenerationContext(GenerationState state) {
        ArtifactScopeType scopeType = ArtifactScopeType.valueOf(state.input.getSourceScopeType().trim().toUpperCase());
        state.artifact.setSourceScopeType(scopeType);
        artifactRepository.save(state.artifact);

        if (scopeType == ArtifactScopeType.RESEARCH_PROJECT) {
            PersonalGenerationService.PersonalGenerationPreparation preparation =
                    personalGenerationService.prepare(state.userId, state.input);
            ResearchProject project = preparation.context().researchProject();
            state.articleCards = new ArrayList<>(preparation.context().articleCards());
            state.conceptCards = new ArrayList<>(preparation.context().conceptCards());
            state.synthesisCards = new ArrayList<>(preparation.context().synthesisCards());
            state.methodologyCard = preparation.context().methodologyCard();
            state.personalEvidenceItems = new ArrayList<>(preparation.evidenceItems());
            state.sourceRefs.addAll(preparation.sourceRefs().stream()
                    .map(ref -> new SourceRef(ref.sourceType(), ref.sourceId()))
                    .toList());
            state.citations = new ArrayList<>(state.personalEvidenceItems.stream()
                    .map(PersonalEvidenceItem::citation)
                    .toList());
        } else {
            Long messageId = state.input.getCreatedFromMessageId() != null
                    ? state.input.getCreatedFromMessageId()
                    : firstId(state.input.getSourceIds());
            ChatMessage message = chatMessageRepository.findById(messageId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_SESSION_NOT_FOUND));
            ChatSession session = chatSessionService.getRequiredActiveSession(message.getSessionId());
            if (state.input.getCreatedFromSessionId() != null && !Objects.equals(state.input.getCreatedFromSessionId(), session.getId())) {
                throw new BusinessException(ErrorCode.PLAN_EXECUTION_FAILED, "Chat message does not belong to the requested session");
            }
            state.session = session;
            state.focusMessage = message;
            state.sessionMessages = new ArrayList<>(chatMessageRepository.findBySessionIdOrderByMessageSeqAsc(session.getId()));
            state.citations = messageCitationRepository.findByMessageId(message.getId()).stream()
                    .map(relation -> citationRepository.findById(relation.getCitationId()))
                    .flatMap(java.util.Optional::stream)
                    .toList();
            state.sourceRefs.add(new SourceRef(ArtifactSourceType.CHAT_MESSAGE, message.getId()));
            if (state.citations.isEmpty() && studioMcpToolRegistry.resolve(state.input.getParams()).isEmpty()) {
                throw new BusinessException(ErrorCode.PLAN_EXECUTION_FAILED, "Chat message has no citations to ground the artifact");
            }
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("redacted", true);
        output.put("scopeType", scopeType.name());
        output.put("articleCardCount", state.articleCards.size());
        output.put("conceptCardCount", state.conceptCards.size());
        output.put("citationCount", state.citations.size());
        if (state.methodologyCard != null) {
            output.put("methodologyName", state.methodologyCard.getName());
        }
        return SkillOutcome.simple(output);
    }

    private SkillOutcome loadMcpToolContext(GenerationState state) {
        StudioMcpToolRegistry.McpInvocationSpec spec = studioMcpToolRegistry.resolve(state.input.getParams())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_EXECUTION_FAILED, "MCP tool config is missing"));
        StudioMcpTool tool = studioMcpToolRegistry.getRequired(spec.toolName());
        StudioMcpTool.ToolResult result = tool.invoke(spec.args());
        state.mcpToolResult = result;
        return SkillOutcome.simple(Map.of(
                "redacted", true,
                "toolName", result.toolName(),
                "displayName", result.displayName(),
                "title", result.title(),
                "output", result.output()
        ));
    }

    private SkillOutcome selectEvidence(GenerationState state) {
        LinkedHashMap<Long, Citation> unique = new LinkedHashMap<>();
        for (Citation citation : state.citations) {
            unique.putIfAbsent(citation.getId(), citation);
            if (unique.size() >= 8) {
                break;
            }
        }
        state.citations = new ArrayList<>(unique.values());
        return SkillOutcome.simple(Map.of(
                "redacted", true,
                "selectedCitationCount", state.citations.size()
        ));
    }

    private SkillOutcome selectArticleCards(GenerationState state) {
        if (state.articleCards.size() > 5) {
            state.articleCards = new ArrayList<>(state.articleCards.subList(0, 5));
        }
        return SkillOutcome.simple(Map.of(
                "redacted", true,
                "selectedArticleCardCount", state.articleCards.size()
        ));
    }

    private SkillOutcome selectConceptCards(GenerationState state) {
        if (state.conceptCards.size() > 8) {
            state.conceptCards = new ArrayList<>(state.conceptCards.subList(0, 8));
        }
        return SkillOutcome.simple(Map.of(
                "redacted", true,
                "selectedConceptCardCount", state.conceptCards.size()
        ));
    }

    private SkillOutcome generateArtifact(GenerationState state, String skillName, String instruction) {
        String scene = state.input.getResearchProjectId() != null ? "PERSONAL_ARTIFACT_GENERATE" : "ARTIFACT_GENERATE";
        PromptVersion activePrompt = promptVersionService.findActiveEntity(scene).orElse(null);
        String promptPrefix = activePrompt == null
                ? null
                : promptTemplateRenderer.render(activePrompt.getContent(), Map.of("instruction", instruction));
        ensureRetrievalTrace(state, scene, skillName);
        String prompt = buildPrompt(state, instruction, promptPrefix);
        ObservedLlmGateway.ObservedLlmResult observed = observedLlmGateway.chat(
                LlmCallContext.builder()
                        .userId(state.userId)
                        .spaceId(state.artifact.getSpaceId())
                        .sessionId(state.session == null ? null : state.session.getId())
                        .messageId(state.focusMessage == null ? null : state.focusMessage.getId())
                        .taskId(state.taskId)
                        .artifactId(state.artifact.getId())
                        .scene(scene)
                        .promptVersionId(activePrompt == null ? null : activePrompt.getId())
                        .messages(List.of(new LlmMessage("user", prompt)))
                        .build(),
                LlmOptions.builder().temperature(0.2d).maxTokens(2200).build()
        );
        LlmResponse response = observed.response();
        state.generatedTitle = resolveTitle(state, response.content());
        state.generatedContent = normalizeGeneratedContent(state.generatedTitle, response.content());
        return new SkillOutcome(
                Map.of(
                        "redacted", true,
                        "generated", true,
                        "artifactType", state.input.getArtifactType().name(),
                        "contentLength", state.generatedContent.length()
                ),
                response.model(),
                response.inputTokens(),
                response.outputTokens(),
                state.artifactVersionId
        );
    }

    protected SkillOutcome saveArtifact(GenerationState state) {
        ArtifactPersistenceService.ArtifactSaveResult saveResult = artifactPersistenceService.saveGeneratedArtifact(
                state.artifact.getId(),
                state.taskId,
                state.userId,
                state.generatedTitle,
                state.generatedContent,
                state.sourceRefs.stream()
                        .map(ref -> new ArtifactPersistenceService.ArtifactSourceLink(ref.sourceType(), ref.sourceId()))
                        .toList(),
                state.citations,
                state.artifact.getCreatedFromSessionId()
        );
        state.artifactVersionId = saveResult.version().getId();
        state.artifact = saveResult.artifact();

        return new SkillOutcome(
                Map.of(
                        "redacted", true,
                        "artifactVersionId", saveResult.version().getId(),
                        "versionNo", saveResult.version().getVersionNo()
                ),
                null,
                null,
                null,
                saveResult.version().getId()
        );
    }

    private String buildPrompt(GenerationState state, String instruction, String promptPrefix) {
        StringBuilder prompt = new StringBuilder();
        if (promptPrefix != null && !promptPrefix.isBlank()) {
            prompt.append(promptPrefix).append("\n");
        }
        prompt.append("你是 NoteWeave Studio 产物生成器。\n");
        prompt.append(instruction).append("\n");
        prompt.append("如果资料不足，请明确指出不足，不要编造。\n");
        prompt.append("主题：").append(topic(state)).append("\n\n");

        if (state.methodologyCard != null) {
            prompt.append(methodologyPromptSectionBuilder.build(state.methodologyCard));
        }

        if (!state.articleCards.isEmpty()) {
            prompt.append("Article Cards:\n");
            for (ArticleCard articleCard : state.articleCards) {
                prompt.append("- 标题：").append(articleCard.getTitle()).append("\n");
                prompt.append("  摘要：").append(articleCard.getSummary()).append("\n");
            }
            prompt.append("\n");
        }
        if (!state.conceptCards.isEmpty()) {
            prompt.append("Concept Cards:\n");
            for (ConceptCard conceptCard : state.conceptCards) {
                prompt.append("- 概念：").append(conceptCard.getName()).append("\n");
                prompt.append("  定义：").append(conceptCard.getDefinition()).append("\n");
            }
            prompt.append("\n");
        }
        if (!state.synthesisCards.isEmpty()) {
            prompt.append("Synthesis Cards:\n");
            for (SynthesisCard synthesisCard : state.synthesisCards) {
                prompt.append("- Title: ").append(synthesisCard.getTitle()).append("\n");
                prompt.append("  Summary: ").append(synthesisCard.getSummary()).append("\n");
            }
            prompt.append("\n");
        }
        if (state.mcpToolResult != null) {
            prompt.append("MCP Tool Context (").append(state.mcpToolResult.toolName()).append("):\n");
            prompt.append(state.mcpToolResult.promptContext()).append("\n\n");
        }
        if (state.focusMessage != null) {
            prompt.append("Chat Context:\n");
            for (ChatMessage message : state.sessionMessages) {
                if (message.getRole() == ChatMessageRole.USER || message.getId().equals(state.focusMessage.getId())) {
                    prompt.append("- ").append(message.getRole().name()).append("：").append(message.getContent()).append("\n");
                }
            }
            prompt.append("\n");
        }

        for (int index = 0; index < state.citations.size(); index++) {
            Citation citation = state.citations.get(index);
            prompt.append("[来源#").append(index + 1).append("]\n");
            prompt.append("标题：").append(citation.getTitle()).append("\n");
            prompt.append("内容：").append(citation.getQuoteText()).append("\n\n");
        }
        return prompt.toString();
    }

    private Map<String, Object> redactedInputSummary(String skillName, GenerationState state) {
        var resolved = studioMcpToolRegistry.resolve(state.input.getParams());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("redacted", true);
        summary.put("skillName", skillName);
        summary.put("artifactType", state.input.getArtifactType().name());
        summary.put("sourceScopeType", state.input.getSourceScopeType());
        summary.put("sourceCount", state.input.getSourceIds() == null ? 0 : state.input.getSourceIds().size());
        summary.put("citationCount", state.citations == null ? 0 : state.citations.size());
        summary.put("hasMcpTool", resolved.isPresent());
        if (resolved.isPresent()) {
            summary.put("mcpToolName", resolved.get().toolName());
        }
        return summary;
    }

    private String resolveTitle(GenerationState state, String content) {
        if (content != null && content.startsWith("#")) {
            String firstLine = content.lines().findFirst().orElse("").trim();
            String stripped = firstLine.replaceFirst("^#+\\s*", "").trim();
            if (!stripped.isBlank()) {
                return stripped;
            }
        }
        return topic(state);
    }

    private String normalizeGeneratedContent(String title, String content) {
        String safeContent = content == null ? "" : content.trim();
        if (safeContent.startsWith("#")) {
            return safeContent;
        }
        return "# " + title + "\n\n" + safeContent;
    }

    private String topic(GenerationState state) {
        Object topic = state.input.getParams() == null ? null : state.input.getParams().get("topic");
        if (topic != null && !topic.toString().isBlank()) {
            return topic.toString().trim();
        }
        if (state.mcpToolResult != null && state.mcpToolResult.title() != null && !state.mcpToolResult.title().isBlank()) {
            return state.mcpToolResult.title();
        }
        return state.artifact.getTitle();
    }

    private Long firstId(List<Long> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PLAN_EXECUTION_FAILED, "At least one source id is required");
        }
        return sourceIds.get(0);
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private void ensureRetrievalTrace(GenerationState state, String scene, String skillName) {
        if (state.retrievalTraceId != null) {
            return;
        }
        Long traceId = retrievalTraceService.createTrace(RetrievalTraceCreateRequest.builder()
                .userId(state.userId)
                .spaceId(state.artifact.getSpaceId())
                .sessionId(state.session == null ? null : state.session.getId())
                .messageId(state.focusMessage == null ? null : state.focusMessage.getId())
                .taskId(state.taskId)
                .scene(scene)
                .queryText(topic(state) == null ? skillName : topic(state))
                .retrieverType("ARTIFACT_CONTEXT")
                .topK(Math.max(state.citations.size(), state.sourceRefs.size()))
                .latencyMs(1L)
                .retrievedChunkCount(state.citations.size())
                .traceJson("{\"skill\":\"" + skillName + "\"}")
                .build());
        retrievalTraceService.addItems(traceId, buildTraceItems(state));
        state.retrievalTraceId = traceId;
    }

    private List<RetrievalTraceItemCreateRequest> buildTraceItems(GenerationState state) {
        List<RetrievalTraceItemCreateRequest> items = new ArrayList<>();
        int rank = 1;
        for (Citation citation : state.citations) {
            items.add(RetrievalTraceItemCreateRequest.builder()
                    .sourceType(citation.getSourceType())
                    .sourceId(citation.getSourceId())
                    .documentId("DOCUMENT".equalsIgnoreCase(citation.getSourceType()) ? citation.getSourceId() : null)
                    .chunkId(citation.getChunkId())
                    .wikiPageId("WIKI_PAGE".equalsIgnoreCase(citation.getSourceType()) ? citation.getSourceId() : null)
                    .score(1.0d / rank)
                    .rank(rank++)
                    .selectedAsEvidence(true)
                    .metadataJson(null)
                    .build());
        }
        for (SourceRef sourceRef : state.sourceRefs) {
            items.add(RetrievalTraceItemCreateRequest.builder()
                    .sourceType(sourceRef.sourceType().name())
                    .sourceId(sourceRef.sourceId())
                    .documentId(null)
                    .chunkId(null)
                    .wikiPageId(null)
                    .score(0.1d)
                    .rank(rank++)
                    .selectedAsEvidence(true)
                    .metadataJson(null)
                    .build());
        }
        return items;
    }

    public record ArtifactExecutionResult(Long artifactVersionId, Map<String, Object> output) {
    }

    private record SkillOutcome(
            Map<String, Object> output,
            String modelName,
            Integer inputTokens,
            Integer outputTokens,
            Long artifactVersionId
    ) {
        static SkillOutcome simple(Map<String, Object> output) {
            return new SkillOutcome(output, null, null, null, null);
        }
    }

    private record SourceRef(ArtifactSourceType sourceType, Long sourceId) {
    }

    private static class GenerationState {
        private final Long taskId;
        private final Long userId;
        private final ArtifactGenerateTaskInput input;
        private Artifact artifact;
        private List<ArticleCard> articleCards = new ArrayList<>();
        private List<ConceptCard> conceptCards = new ArrayList<>();
        private List<SynthesisCard> synthesisCards = new ArrayList<>();
        private List<Citation> citations = new ArrayList<>();
        private List<PersonalEvidenceItem> personalEvidenceItems = new ArrayList<>();
        private List<ChatMessage> sessionMessages = new ArrayList<>();
        private ChatSession session;
        private ChatMessage focusMessage;
        private MethodologyCard methodologyCard;
        private String generatedTitle;
        private String generatedContent;
        private final List<SourceRef> sourceRefs = new ArrayList<>();
        private Long artifactVersionId;
        private Long retrievalTraceId;
        private StudioMcpTool.ToolResult mcpToolResult;

        private GenerationState(Long taskId, Long userId, ArtifactGenerateTaskInput input, Artifact artifact) {
            this.taskId = taskId;
            this.userId = userId;
            this.input = input;
            this.artifact = artifact;
        }
    }
}

package com.noteweave.artifact.service;

import com.noteweave.artifact.model.Artifact;
import com.noteweave.artifact.model.ArtifactScopeType;
import com.noteweave.artifact.model.ArtifactSourceType;
import com.noteweave.artifact.model.ArtifactStatus;
import com.noteweave.artifact.model.ArtifactType;
import com.noteweave.artifact.repository.ArtifactRepository;
import com.noteweave.artifact.skill.service.SkillExecutionLogService;
import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatMessageRole;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.repository.ChatMessageRepository;
import com.noteweave.chat.service.ChatSessionService;
import com.noteweave.citation.model.Citation;
import com.noteweave.citation.repository.CitationRepository;
import com.noteweave.citation.repository.MessageCitationRepository;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.llm.dto.LlmMessage;
import com.noteweave.llm.dto.LlmOptions;
import com.noteweave.llm.dto.LlmResponse;
import com.noteweave.llm.service.LlmClient;
import com.noteweave.personal.card.model.ArticleCard;
import com.noteweave.personal.card.model.ArticleCardCitation;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.model.ConceptCardCitation;
import com.noteweave.personal.card.repository.ArticleCardCitationRepository;
import com.noteweave.personal.card.repository.ArticleCardRepository;
import com.noteweave.personal.card.repository.ConceptCardCitationRepository;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.model.ResearchProjectStatus;
import com.noteweave.personal.project.repository.ResearchProjectRepository;
import com.noteweave.studio.service.ArtifactGenerateTaskInput;
import com.noteweave.task.worker.TaskExecutionContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ArtifactPlanExecutor {

    private final ArtifactRepository artifactRepository;
    private final ResearchProjectRepository researchProjectRepository;
    private final ArticleCardRepository articleCardRepository;
    private final ConceptCardRepository conceptCardRepository;
    private final ArticleCardCitationRepository articleCardCitationRepository;
    private final ConceptCardCitationRepository conceptCardCitationRepository;
    private final CitationRepository citationRepository;
    private final MessageCitationRepository messageCitationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionService chatSessionService;
    private final LlmClient llmClient;
    private final SkillExecutionLogService skillExecutionLogService;
    private final ArtifactPersistenceService artifactPersistenceService;

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
            for (String skillName : planFor(input.getArtifactType())) {
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

    private List<String> planFor(ArtifactType artifactType) {
        return switch (artifactType) {
            case REPORT -> List.of(
                    "LoadGenerationContextSkill",
                    "SelectEvidenceSkill",
                    "GenerateReportSkill",
                    "SaveArtifactSkill"
            );
            case STUDY_GUIDE -> List.of(
                    "LoadGenerationContextSkill",
                    "SelectArticleCardSkill",
                    "SelectConceptCardSkill",
                    "GenerateStudyGuideSkill",
                    "SaveArtifactSkill"
            );
            case BRIEFING -> List.of(
                    "LoadGenerationContextSkill",
                    "SelectEvidenceSkill",
                    "GenerateBriefingSkill",
                    "SaveArtifactSkill"
            );
            case FAQ -> List.of(
                    "LoadGenerationContextSkill",
                    "SelectEvidenceSkill",
                    "GenerateFaqSkill",
                    "SaveArtifactSkill"
            );
            case COMPARISON -> List.of(
                    "LoadGenerationContextSkill",
                    "SelectEvidenceSkill",
                    "GenerateComparisonSkill",
                    "SaveArtifactSkill"
            );
            case WIKI_DRAFT -> List.of(
                    "LoadGenerationContextSkill",
                    "SelectEvidenceSkill",
                    "GenerateWikiDraftSkill",
                    "SaveArtifactSkill"
            );
            default -> throw new BusinessException(ErrorCode.ARTIFACT_TYPE_UNSUPPORTED);
        };
    }

    private void runSkill(String skillName, GenerationState state) {
        Instant start = Instant.now();
        Map<String, Object> inputSummary = redactedInputSummary(skillName, state);
        try {
            SkillOutcome outcome = switch (skillName) {
                case "LoadGenerationContextSkill" -> loadGenerationContext(state);
                case "SelectEvidenceSkill" -> selectEvidence(state);
                case "SelectArticleCardSkill" -> selectArticleCards(state);
                case "SelectConceptCardSkill" -> selectConceptCards(state);
                case "GenerateReportSkill" -> generateArtifact(state, skillName, "生成一份结构化技术报告，先给结论，再给分节说明。");
                case "GenerateStudyGuideSkill" -> generateArtifact(state, skillName, "生成学习指南，包含目标、重点概念、复习建议。");
                case "GenerateBriefingSkill" -> generateArtifact(state, skillName, "生成简报，适合快速汇报。");
                case "GenerateFaqSkill" -> generateArtifact(state, skillName, "生成 FAQ，使用问答结构。");
                case "GenerateComparisonSkill" -> generateArtifact(state, skillName, "生成比较报告，强调差异、优缺点和适用场景。");
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
            Long projectId = state.input.getResearchProjectId() != null
                    ? state.input.getResearchProjectId()
                    : firstId(state.input.getSourceIds());
            ResearchProject project = researchProjectRepository.findById(projectId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESEARCH_PROJECT_NOT_FOUND));
            if (project.getDeletedAt() != null || project.getStatus() != ResearchProjectStatus.ACTIVE) {
                throw new BusinessException(ErrorCode.PLAN_EXECUTION_FAILED, "Research project is not active");
            }
            state.articleCards = new ArrayList<>(articleCardRepository.findByResearchProjectIdAndSpaceIdOrderByUpdatedAtDesc(projectId, state.artifact.getSpaceId()));
            state.conceptCards = new ArrayList<>(conceptCardRepository.findByResearchProjectIdAndSpaceIdOrderByUpdatedAtDesc(projectId, state.artifact.getSpaceId()));
            if (state.articleCards.isEmpty() && state.conceptCards.isEmpty()) {
                throw new BusinessException(ErrorCode.PLAN_EXECUTION_FAILED, "No compiled project cards available for artifact generation");
            }
            state.sourceRefs.addAll(state.articleCards.stream()
                    .map(card -> new SourceRef(ArtifactSourceType.ARTICLE_CARD, card.getId()))
                    .toList());
            state.sourceRefs.addAll(state.conceptCards.stream()
                    .map(card -> new SourceRef(ArtifactSourceType.CONCEPT_CARD, card.getId()))
                    .toList());
            state.citations = new ArrayList<>(uniqueProjectCitations(state));
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
            if (state.citations.isEmpty()) {
                throw new BusinessException(ErrorCode.PLAN_EXECUTION_FAILED, "Chat message has no citations to ground the artifact");
            }
        }
        return SkillOutcome.simple(Map.of(
                "redacted", true,
                "scopeType", scopeType.name(),
                "articleCardCount", state.articleCards.size(),
                "conceptCardCount", state.conceptCards.size(),
                "citationCount", state.citations.size()
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
        String prompt = buildPrompt(state, instruction);
        LlmResponse response = llmClient.chat(List.of(new LlmMessage("user", prompt)), LlmOptions.builder().temperature(0.2d).maxTokens(2200).build());
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

    private List<Citation> uniqueProjectCitations(GenerationState state) {
        LinkedHashMap<Long, Citation> unique = new LinkedHashMap<>();
        for (ArticleCard articleCard : state.articleCards) {
            for (ArticleCardCitation relation : articleCardCitationRepository.findByArticleCardIdOrderByIdAsc(articleCard.getId())) {
                citationRepository.findById(relation.getCitationId()).ifPresent(citation -> unique.putIfAbsent(citation.getId(), citation));
            }
        }
        for (ConceptCard conceptCard : state.conceptCards) {
            for (ConceptCardCitation relation : conceptCardCitationRepository.findByConceptCardIdOrderByIdAsc(conceptCard.getId())) {
                citationRepository.findById(relation.getCitationId()).ifPresent(citation -> unique.putIfAbsent(citation.getId(), citation));
            }
        }
        return new ArrayList<>(unique.values());
    }

    private String buildPrompt(GenerationState state, String instruction) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 NoteWeave Studio 产物生成器。\n");
        prompt.append(instruction).append("\n");
        prompt.append("如果资料不足，请明确指出不足，不要编造。\n");
        prompt.append("主题：").append(topic(state)).append("\n\n");

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
        return Map.of(
                "redacted", true,
                "skillName", skillName,
                "artifactType", state.input.getArtifactType().name(),
                "sourceScopeType", state.input.getSourceScopeType(),
                "sourceCount", state.input.getSourceIds() == null ? 0 : state.input.getSourceIds().size(),
                "citationCount", state.citations == null ? 0 : state.citations.size()
        );
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
        private List<Citation> citations = new ArrayList<>();
        private List<ChatMessage> sessionMessages = new ArrayList<>();
        private ChatSession session;
        private ChatMessage focusMessage;
        private String generatedTitle;
        private String generatedContent;
        private final List<SourceRef> sourceRefs = new ArrayList<>();
        private Long artifactVersionId;

        private GenerationState(Long taskId, Long userId, ArtifactGenerateTaskInput input, Artifact artifact) {
            this.taskId = taskId;
            this.userId = userId;
            this.input = input;
            this.artifact = artifact;
        }
    }
}

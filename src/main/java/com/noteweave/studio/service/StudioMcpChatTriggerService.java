package com.noteweave.studio.service;

import com.noteweave.artifact.model.ArtifactType;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.common.PersonalSpaceService;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.model.ResearchProjectStatus;
import com.noteweave.personal.project.repository.ResearchProjectRepository;
import com.noteweave.space.model.Space;
import com.noteweave.studio.dto.CreateStudioTaskRequest;
import com.noteweave.studio.dto.CreateStudioTaskResponse;
import com.noteweave.task.model.TaskType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class StudioMcpChatTriggerService {

    private static final String MCP_COMMAND_PREFIX = "/mcp";
    private static final String ARTIFACT_COMMAND_PREFIX = "/artifact";
    private static final String ARTIFACT_COMMAND_PREFIX_ZH = "/成果";

    private final StudioTaskService studioTaskService;
    private final StudioMcpToolRegistry studioMcpToolRegistry;
    private final PersonalSpaceService personalSpaceService;
    private final ResearchProjectRepository researchProjectRepository;

    public StudioMcpChatTriggerService(
            StudioTaskService studioTaskService,
            StudioMcpToolRegistry studioMcpToolRegistry,
            PersonalSpaceService personalSpaceService,
            ResearchProjectRepository researchProjectRepository
    ) {
        this.studioTaskService = studioTaskService;
        this.studioMcpToolRegistry = studioMcpToolRegistry;
        this.personalSpaceService = personalSpaceService;
        this.researchProjectRepository = researchProjectRepository;
    }

    public Optional<ToolTriggerResult> maybeTrigger(Long userId, ChatSession session, Long userMessageId, String content) {
        Optional<ParsedCommand> parsed = parseMcpCommand(content);
        if (parsed.isEmpty()) {
            return maybeTriggerArtifact(userId, session, userMessageId, content);
        }
        ParsedCommand command = parsed.get();
        StudioMcpTool tool = studioMcpToolRegistry.getRequired(command.toolName());
        ArtifactType artifactType = resolveArtifactType(command.options());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("artifactType", artifactType.name());
        params.put("mcpToolName", tool.toolName());
        params.put("mcpArgs", Map.of("url", command.url()));
        if (command.options().containsKey("topic")) {
            params.put("topic", command.options().get("topic"));
        }

        CreateStudioTaskRequest request = new CreateStudioTaskRequest();
        request.setSpaceId(session.getSpaceId());
        request.setTaskType(TaskType.ARTIFACT_GENERATE);
        request.setSourceScopeType("CHAT_MESSAGE");
        request.setSourceIds(java.util.List.of(userMessageId));
        request.setCreatedFromSessionId(session.getId());
        request.setCreatedFromMessageId(userMessageId);
        request.setParams(params);

        CreateStudioTaskResponse task = studioTaskService.createTask(userId, request);
        String answer = "已触发 MCP 工具 " + tool.displayName()
                + "，正在基于 B 站链接生成 " + artifactType.name()
                + " 产物。artifactId=" + task.artifactId()
                + "，taskId=" + task.taskId() + "。";
        return Optional.of(new ToolTriggerResult(
                tool.toolName(),
                tool.displayName(),
                artifactType,
                answer,
                task
        ));
    }

    private Optional<ToolTriggerResult> maybeTriggerArtifact(Long userId, ChatSession session, Long userMessageId, String content) {
        Optional<ArtifactCommand> parsed = parseArtifactCommand(content);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        ArtifactCommand command = parsed.get();
        ResearchProject project = resolveProject(userId, session, command.options());
        ArtifactType artifactType = resolveArtifactType(command.options());
        String topic = resolveTopic(command);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("artifactType", artifactType.name());
        params.put("topic", topic);
        if (command.options().containsKey("methodologyCardId")) {
            params.put("methodologyCardId", parseLong(command.options().get("methodologyCardId"), "methodologyCardId"));
        } else if (command.options().containsKey("method")) {
            params.put("methodologyCardId", parseLong(command.options().get("method"), "method"));
        }

        CreateStudioTaskRequest request = new CreateStudioTaskRequest();
        request.setSpaceId(project.getSpaceId());
        request.setResearchProjectId(project.getId());
        request.setTaskType(TaskType.ARTIFACT_GENERATE);
        request.setSourceScopeType("RESEARCH_PROJECT");
        request.setSourceIds(List.of(project.getId()));
        request.setCreatedFromSessionId(session.getId());
        request.setCreatedFromMessageId(userMessageId);
        request.setParams(params);

        CreateStudioTaskResponse task = studioTaskService.createTask(userId, request);
        String answer = "已创建个人成果生成任务："
                + humanizeArtifactType(artifactType)
                + "《" + topic + "》。"
                + "项目：" + project.getTitle()
                + "，artifactId=" + task.artifactId()
                + "，taskId=" + task.taskId() + "。";
        return Optional.of(new ToolTriggerResult(
                "artifact",
                "Artifact Generator",
                artifactType,
                answer,
                task
        ));
    }

    public Optional<ParsedCommand> parseMcpCommand(String content) {
        if (content == null) {
            return Optional.empty();
        }
        String trimmed = content.trim();
        if (!trimmed.regionMatches(true, 0, MCP_COMMAND_PREFIX, 0, MCP_COMMAND_PREFIX.length())) {
            return Optional.empty();
        }
        String[] tokens = trimmed.split("\\s+");
        if (tokens.length < 3) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "MCP command format: /mcp <tool> <url>");
        }
        String toolName = tokens[1].trim().toLowerCase(Locale.ROOT);
        String url = tokens[2].trim();
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 3; index < tokens.length; index++) {
            String token = tokens[index];
            int sep = token.indexOf('=');
            if (sep <= 0 || sep >= token.length() - 1) {
                continue;
            }
            String key = token.substring(0, sep).trim();
            String value = token.substring(sep + 1).trim();
            if (!key.isBlank() && !value.isBlank()) {
                options.put(key, value.replace('_', ' '));
            }
        }
        return Optional.of(new ParsedCommand(toolName, url, Map.copyOf(options)));
    }

    public Optional<ArtifactCommand> parseArtifactCommand(String content) {
        if (content == null) {
            return Optional.empty();
        }
        String trimmed = content.trim();
        int prefixLength;
        if (trimmed.regionMatches(true, 0, ARTIFACT_COMMAND_PREFIX, 0, ARTIFACT_COMMAND_PREFIX.length())) {
            prefixLength = ARTIFACT_COMMAND_PREFIX.length();
        } else if (trimmed.startsWith(ARTIFACT_COMMAND_PREFIX_ZH)) {
            prefixLength = ARTIFACT_COMMAND_PREFIX_ZH.length();
        } else {
            return Optional.empty();
        }
        String rest = trimmed.substring(prefixLength).trim();
        if (rest.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Artifact command format: /artifact <topic> [type=REPORT] [project=123]");
        }

        Map<String, String> options = new LinkedHashMap<>();
        StringBuilder topic = new StringBuilder();
        for (String token : rest.split("\\s+")) {
            int sep = token.indexOf('=');
            if (sep > 0 && sep < token.length() - 1) {
                String key = normalizeOptionKey(token.substring(0, sep));
                String value = token.substring(sep + 1).trim();
                if (!key.isBlank() && !value.isBlank()) {
                    options.put(key, value.replace('_', ' '));
                    continue;
                }
            }
            if (!topic.isEmpty()) {
                topic.append(' ');
            }
            topic.append(token);
        }
        return Optional.of(new ArtifactCommand(topic.toString().trim(), Map.copyOf(options)));
    }

    private ArtifactType resolveArtifactType(Map<String, String> options) {
        String raw = options.getOrDefault("artifactType", options.get("type"));
        if (raw == null || raw.isBlank()) {
            return ArtifactType.READING_NOTES;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        ArtifactType alias = artifactTypeAlias(normalized);
        if (alias != null) {
            return alias;
        }
        try {
            return ArtifactType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.ARTIFACT_TYPE_UNSUPPORTED, "Unsupported artifactType in MCP command");
        }
    }

    private ArtifactType artifactTypeAlias(String normalized) {
        return switch (normalized) {
            case "报告", "研究报告", "REPORT" -> ArtifactType.REPORT;
            case "学习指南", "指南", "STUDY_GUIDE" -> ArtifactType.STUDY_GUIDE;
            case "笔记", "阅读笔记", "READING_NOTES" -> ArtifactType.READING_NOTES;
            case "简报", "BRIEFING" -> ArtifactType.BRIEFING;
            case "问答", "问答集", "FAQ" -> ArtifactType.FAQ;
            case "对比", "对比分析", "COMPARISON" -> ArtifactType.COMPARISON;
            case "工作准备", "面试准备", "WORK_PREP" -> ArtifactType.WORK_PREP;
            case "WIKI", "WIKI草稿", "WIKI_草稿", "WIKI_DRAFT" -> ArtifactType.WIKI_DRAFT;
            default -> null;
        };
    }

    private ResearchProject resolveProject(Long userId, ChatSession session, Map<String, String> options) {
        Long explicitProjectId = firstLongOption(options, "projectId", "project");
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        if (explicitProjectId != null) {
            return researchProjectRepository.findByIdAndSpaceIdAndDeletedAtIsNullAndStatus(
                            explicitProjectId,
                            personalSpace.getId(),
                            ResearchProjectStatus.ACTIVE
                    )
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESEARCH_PROJECT_NOT_FOUND));
        }

        if (session != null && session.getSpaceId() != null && session.getSpaceId().equals(personalSpace.getId())) {
            List<ResearchProject> currentSpaceProjects = researchProjectRepository.findBySpaceIdAndDeletedAtIsNullAndStatusOrderByCreatedAtDesc(
                    session.getSpaceId(),
                    ResearchProjectStatus.ACTIVE
            );
            if (!currentSpaceProjects.isEmpty()) {
                return currentSpaceProjects.get(0);
            }
        }

        return researchProjectRepository.findBySpaceIdAndDeletedAtIsNullAndStatusOrderByCreatedAtDesc(
                        personalSpace.getId(),
                        ResearchProjectStatus.ACTIVE
                )
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESEARCH_PROJECT_NOT_FOUND, "No active personal research project for /artifact"));
    }

    private String resolveTopic(ArtifactCommand command) {
        String explicit = command.options().get("topic");
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        if (command.topic() != null && !command.topic().isBlank()) {
            return command.topic().trim();
        }
        return resolveArtifactType(command.options()).name().replace('_', ' ');
    }

    private Long firstLongOption(Map<String, String> options, String... keys) {
        for (String key : keys) {
            if (options.containsKey(key)) {
                return parseLong(options.get(key), key);
            }
        }
        return null;
    }

    private Long parseLong(String raw, String field) {
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " must be a number");
        }
    }

    private String normalizeOptionKey(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if ("project".equalsIgnoreCase(value)) {
            return "project";
        }
        if ("projectId".equalsIgnoreCase(value) || "project_id".equalsIgnoreCase(value)) {
            return "projectId";
        }
        if ("methodologyCardId".equalsIgnoreCase(value) || "methodology_card_id".equalsIgnoreCase(value)) {
            return "methodologyCardId";
        }
        if ("artifactType".equalsIgnoreCase(value) || "artifact_type".equalsIgnoreCase(value)) {
            return "artifactType";
        }
        return value;
    }

    private String humanizeArtifactType(ArtifactType artifactType) {
        return switch (artifactType) {
            case REPORT -> "研究报告";
            case STUDY_GUIDE -> "学习指南";
            case READING_NOTES -> "阅读笔记";
            case BRIEFING -> "简报";
            case FAQ -> "问答集";
            case COMPARISON -> "对比分析";
            case WORK_PREP -> "工作准备";
            case WIKI_DRAFT -> "Wiki 草稿";
            default -> artifactType.name().replace('_', ' ');
        };
    }

    public record ParsedCommand(String toolName, String url, Map<String, String> options) {
    }

    public record ArtifactCommand(String topic, Map<String, String> options) {
    }

    public record ToolTriggerResult(
            String toolName,
            String toolDisplayName,
            ArtifactType artifactType,
            String answer,
            CreateStudioTaskResponse task
    ) {
    }
}

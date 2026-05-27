package com.noteweave.studio.service;

import com.noteweave.artifact.model.ArtifactType;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.studio.dto.CreateStudioTaskRequest;
import com.noteweave.studio.dto.CreateStudioTaskResponse;
import com.noteweave.task.model.TaskType;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class StudioMcpChatTriggerService {

    private static final String COMMAND_PREFIX = "/mcp";

    private final StudioTaskService studioTaskService;
    private final StudioMcpToolRegistry studioMcpToolRegistry;

    public StudioMcpChatTriggerService(
            StudioTaskService studioTaskService,
            StudioMcpToolRegistry studioMcpToolRegistry
    ) {
        this.studioTaskService = studioTaskService;
        this.studioMcpToolRegistry = studioMcpToolRegistry;
    }

    public Optional<ToolTriggerResult> maybeTrigger(Long userId, ChatSession session, Long userMessageId, String content) {
        Optional<ParsedCommand> parsed = parse(content);
        if (parsed.isEmpty()) {
            return Optional.empty();
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

    public Optional<ParsedCommand> parse(String content) {
        if (content == null) {
            return Optional.empty();
        }
        String trimmed = content.trim();
        if (!trimmed.regionMatches(true, 0, COMMAND_PREFIX, 0, COMMAND_PREFIX.length())) {
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

    private ArtifactType resolveArtifactType(Map<String, String> options) {
        String raw = options.getOrDefault("artifactType", options.get("type"));
        if (raw == null || raw.isBlank()) {
            return ArtifactType.READING_NOTES;
        }
        try {
            return ArtifactType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.ARTIFACT_TYPE_UNSUPPORTED, "Unsupported artifactType in MCP command");
        }
    }

    public record ParsedCommand(String toolName, String url, Map<String, String> options) {
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

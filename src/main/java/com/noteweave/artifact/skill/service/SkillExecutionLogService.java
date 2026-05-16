package com.noteweave.artifact.skill.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.artifact.skill.dto.SkillExecutionLogResponse;
import com.noteweave.artifact.skill.model.SkillExecutionLog;
import com.noteweave.artifact.skill.repository.SkillExecutionLogRepository;
import com.noteweave.common.security.CurrentUser;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.task.model.Task;
import com.noteweave.task.service.TaskService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SkillExecutionLogService {

    private final SkillExecutionLogRepository skillExecutionLogRepository;
    private final TaskService taskService;
    private final ResourceAccessService resourceAccessService;
    private final ObjectMapper objectMapper;

    @Transactional
    public void record(
            Long taskId,
            Long artifactId,
            Long artifactVersionId,
            String skillName,
            Map<String, Object> input,
            Map<String, Object> output,
            String status,
            String errorMessage,
            Long latencyMs,
            String modelName,
            String promptVersion,
            Integer inputTokens,
            Integer outputTokens
    ) {
        SkillExecutionLog log = new SkillExecutionLog();
        log.setTaskId(taskId);
        log.setArtifactId(artifactId);
        log.setArtifactVersionId(artifactVersionId);
        log.setSkillName(skillName);
        log.setInputJson(writeJson(input));
        log.setOutputJson(writeJson(output));
        log.setStatus(status);
        log.setErrorMessage(errorMessage);
        log.setLatencyMs(latencyMs);
        log.setModelName(modelName);
        log.setPromptVersion(promptVersion);
        log.setInputTokens(inputTokens);
        log.setOutputTokens(outputTokens);
        skillExecutionLogRepository.save(log);
    }

    @Transactional(readOnly = true)
    public List<SkillExecutionLogResponse> listByTask(CurrentUser currentUser, Long taskId) {
        Task task = taskService.getRequiredTask(taskId);
        resourceAccessService.requireViewTask(currentUser, task);
        return skillExecutionLogRepository.findByTaskIdOrderByIdAsc(taskId).stream()
                .map(this::toResponse)
                .toList();
    }

    private SkillExecutionLogResponse toResponse(SkillExecutionLog log) {
        return SkillExecutionLogResponse.builder()
                .id(log.getId())
                .taskId(log.getTaskId())
                .artifactId(log.getArtifactId())
                .artifactVersionId(log.getArtifactVersionId())
                .skillName(log.getSkillName())
                .input(readJson(log.getInputJson()))
                .output(readJson(log.getOutputJson()))
                .status(log.getStatus())
                .errorMessage(log.getErrorMessage())
                .latencyMs(log.getLatencyMs())
                .modelName(log.getModelName())
                .promptVersion(log.getPromptVersion())
                .inputTokens(log.getInputTokens())
                .outputTokens(log.getOutputTokens())
                .createdAt(log.getCreatedAt())
                .build();
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize skill execution log", e);
        }
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize skill execution log", e);
        }
    }
}

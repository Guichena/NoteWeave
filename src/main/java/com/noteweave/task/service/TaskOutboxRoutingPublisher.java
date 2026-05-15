package com.noteweave.task.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.task.model.TaskType;
import com.noteweave.team.document.dto.DocumentProcessTaskPayload;
import com.noteweave.team.document.service.DocumentProcessPublisher;
import com.noteweave.team.document.service.DocumentUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaskOutboxRoutingPublisher {

    private final TaskKafkaPublisher taskKafkaPublisher;
    private final DocumentProcessPublisher documentProcessPublisher;
    private final DocumentUploadService documentUploadService;
    private final ObjectMapper objectMapper;

    @Value("${noteweave.kafka.topics.task:noteweave.task}")
    private String taskTopic;

    public void publish(TaskOutboxMessage message) {
        if (message.getTaskType() == TaskType.DOCUMENT_PROCESS) {
            publishDocumentTask(message);
            return;
        }
        taskKafkaPublisher.publish(taskTopic, message);
    }

    private void publishDocumentTask(TaskOutboxMessage message) {
        try {
            DocumentProcessTaskPayload rawPayload = objectMapper.readValue(message.getInputJson(), DocumentProcessTaskPayload.class);
            DocumentProcessTaskPayload payload = withTaskId(message.getTaskId(), rawPayload);
            documentProcessPublisher.publish(documentUploadService.topic(), message.getTaskId(), payload);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.KAFKA_DISPATCH_FAILED, "failed to deserialize document task payload");
        }
    }

    private DocumentProcessTaskPayload withTaskId(Long taskId, DocumentProcessTaskPayload payload) {
        return DocumentProcessTaskPayload.builder()
                .taskId(taskId)
                .documentId(payload.getDocumentId())
                .spaceId(payload.getSpaceId())
                .knowledgeBaseId(payload.getKnowledgeBaseId())
                .fileMd5(payload.getFileMd5())
                .objectKey(payload.getObjectKey())
                .fileName(payload.getFileName())
                .contentType(payload.getContentType())
                .uploadedBy(payload.getUploadedBy())
                .createdAt(payload.getCreatedAt())
                .build();
    }
}

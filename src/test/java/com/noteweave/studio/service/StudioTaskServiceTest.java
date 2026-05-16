package com.noteweave.studio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.artifact.model.Artifact;
import com.noteweave.artifact.model.ArtifactScopeType;
import com.noteweave.artifact.model.ArtifactStatus;
import com.noteweave.artifact.model.ArtifactType;
import com.noteweave.artifact.repository.ArtifactRepository;
import com.noteweave.artifact.repository.SessionArtifactRepository;
import com.noteweave.artifact.service.ArtifactService;
import com.noteweave.chat.repository.ChatMessageRepository;
import com.noteweave.chat.service.ChatSessionService;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.service.ResearchProjectService;
import com.noteweave.studio.dto.CreateStudioTaskRequest;
import com.noteweave.studio.dto.CreateStudioTaskResponse;
import com.noteweave.task.dto.TaskResponse;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.model.TaskType;
import com.noteweave.task.repository.TaskRepository;
import com.noteweave.task.service.TaskService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StudioTaskServiceTest {

    @Mock
    private TaskService taskService;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private ArtifactRepository artifactRepository;
    @Mock
    private SessionArtifactRepository sessionArtifactRepository;
    @Mock
    private ArtifactService artifactService;
    @Mock
    private ResourceAccessService resourceAccessService;
    @Mock
    private ResearchProjectService researchProjectService;
    @Mock
    private ChatSessionService chatSessionService;
    @Mock
    private ChatMessageRepository chatMessageRepository;

    private StudioTaskService studioTaskService;

    @BeforeEach
    void setUp() {
        studioTaskService = new StudioTaskService(
                taskService,
                taskRepository,
                artifactRepository,
                sessionArtifactRepository,
                artifactService,
                resourceAccessService,
                researchProjectService,
                chatSessionService,
                chatMessageRepository
        );
    }

    @Test
    void createTaskShouldArchiveDuplicatePlaceholderWhenTaskServiceReturnsExistingTask() {
        Long userId = 11L;
        Long spaceId = 22L;
        Long projectId = 33L;

        CreateStudioTaskRequest request = new CreateStudioTaskRequest();
        request.setSpaceId(spaceId);
        request.setResearchProjectId(projectId);
        request.setTaskType(TaskType.ARTIFACT_GENERATE);
        request.setSourceScopeType("RESEARCH_PROJECT");
        request.setSourceIds(List.of(projectId));
        request.setParams(Map.of(
                "artifactType", "REPORT",
                "topic", "Duplicate placeholder test"
        ));

        ResearchProject project = new ResearchProject();
        project.setId(projectId);
        project.setSpaceId(spaceId);
        when(researchProjectService.getRequiredActiveProject(userId, projectId)).thenReturn(project);

        when(taskRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());

        Artifact placeholder = new Artifact();
        placeholder.setId(100L);
        placeholder.setStatus(ArtifactStatus.GENERATING);
        when(artifactRepository.save(any(Artifact.class))).thenAnswer(invocation -> {
            Artifact artifact = invocation.getArgument(0);
            if (artifact.getId() == null) {
                artifact.setId(100L);
            }
            return artifact;
        });

        Artifact existingArtifact = new Artifact();
        existingArtifact.setId(200L);
        existingArtifact.setStatus(ArtifactStatus.READY);
        when(artifactRepository.findById(200L)).thenReturn(Optional.of(existingArtifact));

        TaskResponse existingTask = TaskResponse.builder()
                .id(300L)
                .targetId(200L)
                .taskStatus(TaskStatus.PENDING)
                .build();
        when(taskService.createTask(any())).thenReturn(existingTask);

        CreateStudioTaskResponse response = studioTaskService.createTask(userId, request);

        assertThat(response.taskId()).isEqualTo(300L);
        assertThat(response.artifactId()).isEqualTo(200L);
        assertThat(response.taskStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(response.artifactStatus()).isEqualTo(ArtifactStatus.READY);

        ArgumentCaptor<Artifact> artifactCaptor = ArgumentCaptor.forClass(Artifact.class);
        verify(artifactRepository, org.mockito.Mockito.times(2)).save(artifactCaptor.capture());
        Artifact archivedPlaceholder = artifactCaptor.getAllValues().get(1);
        assertThat(archivedPlaceholder.getId()).isEqualTo(100L);
        assertThat(archivedPlaceholder.getStatus()).isEqualTo(ArtifactStatus.ARCHIVED);
        assertThat(archivedPlaceholder.getDeletedBy()).isEqualTo(userId);
        assertThat(archivedPlaceholder.getDeletedAt()).isNotNull();
        verify(sessionArtifactRepository, never()).save(any());
    }
}

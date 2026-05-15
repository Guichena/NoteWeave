package com.noteweave.task.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.auth.dto.RegisterRequest;
import com.noteweave.auth.service.AuthService;
import com.noteweave.common.api.PageResponse;
import com.noteweave.common.security.CurrentUser;
import com.noteweave.space.dto.CreateSpaceRequest;
import com.noteweave.space.dto.SpaceResponse;
import com.noteweave.space.service.SpaceService;
import com.noteweave.support.ContainerizedIntegrationTest;
import com.noteweave.task.dto.TaskQuery;
import com.noteweave.task.dto.TaskResponse;
import com.noteweave.task.model.TaskType;
import com.noteweave.task.worker.NoopTestTaskInput;
import com.noteweave.user.model.User;
import com.noteweave.user.model.UserSystemRole;
import com.noteweave.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TaskAdminVisibilityIntegrationTest extends ContainerizedIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SpaceService spaceService;

    @Autowired
    private TaskService taskService;

    @Test
    void adminShouldSeeTaskButReceiveRedactedSensitiveFields() {
        TestActor actor = createTeamActor("task_admin_" + System.nanoTime());

        TaskResponse createdTask = taskService.createTask(TaskCreateCommand.builder()
                .userId(actor.userId())
                .spaceId(actor.spaceId())
                .taskType(TaskType.NOOP_TEST)
                .targetType("NOOP_TARGET")
                .targetId(9901L)
                .idempotencyKey("NOOP_TEST:" + actor.spaceId() + ":NOOP_TARGET:9901:redaction")
                .input(successInput())
                .build());

        CurrentUser adminUser = new CurrentUser(-1L, "admin", UserSystemRole.ADMIN);
        TaskResponse adminView = taskService.getTask(adminUser, createdTask.getId());

        TaskQuery query = new TaskQuery();
        query.setPage(1);
        query.setPageSize(20);
        PageResponse<TaskResponse> adminPage = taskService.listTasks(adminUser, query);

        assertThat(adminView.getInput()).isNotNull();
        assertThat(adminView.getInput().path("redacted").asBoolean()).isTrue();
        assertThat(adminView.getOutput()).isNull();
        assertThat(adminView.getErrorMessage()).isNull();
        assertThat(adminPage.getItems()).extracting(TaskResponse::getId).contains(createdTask.getId());
        assertThat(adminPage.getItems()).allSatisfy(task -> {
            assertThat(task.getInput()).isNotNull();
            assertThat(task.getInput().path("redacted").asBoolean()).isTrue();
        });
    }

    private NoopTestTaskInput successInput() {
        NoopTestTaskInput input = new NoopTestTaskInput();
        input.setSuccessMessage("done");
        input.setDurationMillis(0L);
        return input;
    }

    private TestActor createTeamActor(String username) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setEmail(username + "@example.com");
        request.setPassword("Password123!");
        request.setDisplayName("Task Admin Test");
        authService.register(request);

        User user = userRepository.findByUsername(username).orElseThrow();
        CreateSpaceRequest createSpaceRequest = new CreateSpaceRequest();
        createSpaceRequest.setName(username + "-team");
        createSpaceRequest.setDescription("team space");
        SpaceResponse space = spaceService.createTeamSpace(user.getId(), createSpaceRequest);
        return new TestActor(user.getId(), space.getId());
    }

    private record TestActor(Long userId, Long spaceId) {
    }
}

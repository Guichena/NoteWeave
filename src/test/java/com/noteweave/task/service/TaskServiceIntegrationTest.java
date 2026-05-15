package com.noteweave.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.noteweave.auth.dto.RegisterRequest;
import com.noteweave.auth.service.AuthService;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.security.CurrentUser;
import com.noteweave.space.dto.CreateSpaceRequest;
import com.noteweave.space.dto.SpaceResponse;
import com.noteweave.space.service.SpaceService;
import com.noteweave.support.ContainerizedIntegrationTest;
import com.noteweave.task.dto.TaskResponse;
import com.noteweave.task.model.TaskAttempt;
import com.noteweave.task.model.TaskOutbox;
import com.noteweave.task.model.TaskOutboxStatus;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.model.TaskType;
import com.noteweave.task.repository.TaskOutboxRepository;
import com.noteweave.task.worker.NoopTestTaskInput;
import com.noteweave.user.model.User;
import com.noteweave.user.model.UserSystemRole;
import com.noteweave.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

@SpringBootTest
class TaskServiceIntegrationTest extends ContainerizedIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SpaceService spaceService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskDispatcher taskDispatcher;

    @Autowired
    private TaskOutboxRepository taskOutboxRepository;

    @SpyBean
    private LocalTaskMessagePublisher localTaskMessagePublisher;

    @AfterEach
    void tearDown() {
        reset(localTaskMessagePublisher);
    }

    @Test
    void createTask_WithSameIdempotencyKey_ShouldReturnSameTask() {
        TestActor actor = createTeamActor("task_idempotency_" + System.nanoTime());
        NoopTestTaskInput input = successInput();

        TaskResponse first = taskService.createTask(TaskCreateCommand.builder()
                .userId(actor.userId())
                .spaceId(actor.spaceId())
                .taskType(TaskType.NOOP_TEST)
                .targetType("NOOP_TARGET")
                .targetId(1001L)
                .idempotencyKey("NOOP_TEST:" + actor.spaceId() + ":NOOP_TARGET:1001:v1")
                .input(input)
                .build());

        TaskResponse second = taskService.createTask(TaskCreateCommand.builder()
                .userId(actor.userId())
                .spaceId(actor.spaceId())
                .taskType(TaskType.NOOP_TEST)
                .targetType("NOOP_TARGET")
                .targetId(1001L)
                .idempotencyKey("NOOP_TEST:" + actor.spaceId() + ":NOOP_TARGET:1001:v1")
                .input(input)
                .build());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(taskOutboxRepository.findByTaskIdOrderByCreatedAtAsc(first.getId())).hasSize(1);
    }

    @Test
    void dispatchPendingTask_ShouldRecordAttemptEventAndSuccess() {
        TestActor actor = createTeamActor("task_success_" + System.nanoTime());
        TaskResponse createdTask = createNoopTask(actor, successInput(), "success-v1");

        int dispatched = taskDispatcher.dispatchPendingMessages();

        TaskResponse task = taskService.getTask(actor.currentUser(), createdTask.getId());
        List<TaskAttempt> attempts = taskService.getAttempts(createdTask.getId());
        List<TaskOutbox> outboxes = taskOutboxRepository.findByTaskIdOrderByCreatedAtAsc(createdTask.getId());

        assertThat(dispatched).isGreaterThan(0);
        assertThat(task.getTaskStatus()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(task.getOutput()).isNotNull();
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getStatus()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(outboxes).hasSize(1);
        assertThat(outboxes.get(0).getStatus()).isEqualTo(TaskOutboxStatus.SENT);
    }

    @Test
    void dispatchTask_ShouldFailAndRetryWithNewAttempt() {
        TestActor actor = createTeamActor("task_retry_" + System.nanoTime());
        NoopTestTaskInput input = new NoopTestTaskInput();
        input.setShouldFail(true);
        TaskResponse createdTask = createNoopTask(actor, input, "retry-v1");

        taskDispatcher.dispatchPendingMessages();
        TaskResponse failedTask = taskService.getTask(actor.currentUser(), createdTask.getId());
        assertThat(failedTask.getTaskStatus()).isEqualTo(TaskStatus.FAILED);

        TaskResponse retriedTask = taskService.retryTask(actor.currentUser(), createdTask.getId());
        assertThat(retriedTask.getTaskStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(retriedTask.getRetryCount()).isEqualTo(1);

        taskDispatcher.dispatchPendingMessages();
        List<TaskAttempt> attempts = taskService.getAttempts(createdTask.getId());
        TaskResponse retriedFailedTask = taskService.getTask(actor.currentUser(), createdTask.getId());

        assertThat(retriedFailedTask.getTaskStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0).getAttemptNo()).isEqualTo(1);
        assertThat(attempts.get(1).getAttemptNo()).isEqualTo(2);
    }

    @Test
    void dispatchTask_ShouldMarkTimeout() {
        TestActor actor = createTeamActor("task_timeout_" + System.nanoTime());
        NoopTestTaskInput input = new NoopTestTaskInput();
        input.setShouldTimeout(true);
        TaskResponse createdTask = createNoopTask(actor, input, "timeout-v1");

        taskDispatcher.dispatchPendingMessages();

        TaskResponse timedOutTask = taskService.getTask(actor.currentUser(), createdTask.getId());
        assertThat(timedOutTask.getTaskStatus()).isEqualTo(TaskStatus.TIMEOUT);
    }

    @Test
    void cancelPendingTask_ShouldCancelImmediately() {
        TestActor actor = createTeamActor("task_cancel_pending_" + System.nanoTime());
        TaskResponse createdTask = createNoopTask(actor, successInput(), "cancel-pending-v1");

        taskService.cancelTask(actor.currentUser(), createdTask.getId());

        TaskResponse cancelledTask = taskService.getTask(actor.currentUser(), createdTask.getId());
        assertThat(cancelledTask.getTaskStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void cancelRunningTask_ShouldBeObservedByWorker() throws Exception {
        TestActor actor = createTeamActor("task_cancel_running_" + System.nanoTime());
        NoopTestTaskInput input = new NoopTestTaskInput();
        input.setDurationMillis(400L);
        input.setStepSleepMillis(50L);
        TaskResponse createdTask = createNoopTask(actor, input, "cancel-running-v1");

        CompletableFuture<Void> future = CompletableFuture.runAsync(taskDispatcher::dispatchPendingMessages);
        waitForTaskStatus(createdTask.getId(), TaskStatus.RUNNING);

        taskService.cancelTask(actor.currentUser(), createdTask.getId());
        future.get(5, TimeUnit.SECONDS);

        TaskResponse cancelledTask = taskService.getTask(actor.currentUser(), createdTask.getId());
        List<TaskAttempt> attempts = taskService.getAttempts(createdTask.getId());
        assertThat(cancelledTask.getTaskStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void retryBeyondMaxRetryCount_ShouldBeRejected() {
        TestActor actor = createTeamActor("task_retry_limit_" + System.nanoTime());
        NoopTestTaskInput input = new NoopTestTaskInput();
        input.setShouldFail(true);

        TaskResponse createdTask = taskService.createTask(TaskCreateCommand.builder()
                .userId(actor.userId())
                .spaceId(actor.spaceId())
                .taskType(TaskType.NOOP_TEST)
                .targetType("NOOP_TARGET")
                .targetId(7001L)
                .idempotencyKey("NOOP_TEST:" + actor.spaceId() + ":NOOP_TARGET:7001:retry-limit")
                .input(input)
                .maxRetryCount(0)
                .build());

        taskDispatcher.dispatchPendingMessages();

        assertThatThrownBy(() -> taskService.retryTask(actor.currentUser(), createdTask.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("retry");
    }

    @Test
    void dispatcherFailure_ShouldMarkOutboxFailedAndAllowCompensationRetry() {
        TestActor actor = createTeamActor("task_dispatch_failure_" + System.nanoTime());
        TaskResponse createdTask = createNoopTask(actor, successInput(), "dispatch-failure-v1");

        doThrow(new IllegalStateException("publisher boom")).when(localTaskMessagePublisher).publish(any());
        taskDispatcher.dispatchPendingMessages();

        TaskOutbox failedOutbox = taskOutboxRepository.findByTaskIdOrderByCreatedAtAsc(createdTask.getId()).get(0);
        assertThat(failedOutbox.getStatus()).isEqualTo(TaskOutboxStatus.FAILED);
        assertThat(failedOutbox.getNextRetryAt()).isNotNull();

        reset(localTaskMessagePublisher);
        failedOutbox.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        taskOutboxRepository.save(failedOutbox);

        taskDispatcher.dispatchPendingMessages();

        TaskOutbox sentOutbox = taskOutboxRepository.findByTaskIdOrderByCreatedAtAsc(createdTask.getId()).get(0);
        TaskResponse completedTask = taskService.getTask(actor.currentUser(), createdTask.getId());
        assertThat(sentOutbox.getStatus()).isEqualTo(TaskOutboxStatus.SENT);
        assertThat(completedTask.getTaskStatus()).isEqualTo(TaskStatus.SUCCESS);
    }

    private TaskResponse createNoopTask(TestActor actor, NoopTestTaskInput input, String businessKey) {
        return taskService.createTask(TaskCreateCommand.builder()
                .userId(actor.userId())
                .spaceId(actor.spaceId())
                .taskType(TaskType.NOOP_TEST)
                .targetType("NOOP_TARGET")
                .targetId((long) businessKey.hashCode())
                .idempotencyKey("NOOP_TEST:" + actor.spaceId() + ":NOOP_TARGET:" + businessKey)
                .input(input)
                .build());
    }

    private NoopTestTaskInput successInput() {
        NoopTestTaskInput input = new NoopTestTaskInput();
        input.setDurationMillis(0L);
        input.setSuccessMessage("done");
        return input;
    }

    private void waitForTaskStatus(Long taskId, TaskStatus expectedStatus) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            TaskStatus currentStatus = taskService.getRequiredTask(taskId).getTaskStatus();
            if (currentStatus == expectedStatus) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Timed out waiting for task status " + expectedStatus);
    }

    private TestActor createTeamActor(String username) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setEmail(username + "@example.com");
        request.setPassword("Password123!");
        request.setDisplayName("Task User");
        authService.register(request);

        User user = userRepository.findByUsername(username).orElseThrow();
        CreateSpaceRequest createSpaceRequest = new CreateSpaceRequest();
        createSpaceRequest.setName(username + "-team");
        createSpaceRequest.setDescription("team space");
        SpaceResponse space = spaceService.createTeamSpace(user.getId(), createSpaceRequest);
        return new TestActor(user.getId(), user.getUsername(), space.getId());
    }

    private record TestActor(Long userId, String username, Long spaceId) {
        CurrentUser currentUser() {
            return new CurrentUser(userId, username, UserSystemRole.USER);
        }
    }
}

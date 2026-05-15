package com.noteweave.task.worker;

import com.noteweave.task.model.TaskType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class NoopTestTaskWorker implements TaskWorker {

    @Override
    public TaskType taskType() {
        return TaskType.NOOP_TEST;
    }

    @Override
    public TaskResult execute(TaskExecutionContext context) {
        NoopTestTaskInput input = context.readInput(NoopTestTaskInput.class);
        long stepSleepMillis = Math.max(input.getStepSleepMillis(), 1L);
        long durationMillis = Math.max(input.getDurationMillis(), 0L);
        int totalSteps = (int) Math.max(1L, (durationMillis + stepSleepMillis - 1L) / stepSleepMillis);

        for (int step = 1; step <= totalSteps; step++) {
            context.ensureNotCancelled();
            if (durationMillis > 0L) {
                sleep(stepSleepMillis);
            }
            context.publishProgress(
                    "NOOP progress " + step + "/" + totalSteps,
                    Map.of("currentStep", step, "totalSteps", totalSteps)
            );
        }

        if (input.isShouldTimeout()) {
            throw new TaskExecutionTimeoutException("NOOP task timed out");
        }
        if (input.isShouldFail()) {
            throw new IllegalStateException("NOOP task failed");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("message", input.getSuccessMessage());
        output.put("completedSteps", totalSteps);
        output.put("durationMillis", durationMillis);
        return TaskResult.success(output);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("NOOP task interrupted", e);
        }
    }
}

package com.noteweave.task.repository;

import com.noteweave.task.model.Task;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.model.TaskType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {

    Optional<Task> findByIdempotencyKey(String idempotencyKey);

    Optional<Task> findTopByTaskTypeAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
            TaskType taskType,
            String targetType,
            Long targetId
    );

    Optional<Task> findTopByTaskTypeAndTargetTypeAndTargetIdAndTaskStatusInOrderByCreatedAtDesc(
            TaskType taskType,
            String targetType,
            Long targetId,
            Collection<TaskStatus> taskStatuses
    );

    long countByTaskTypeAndTargetTypeAndTargetId(TaskType taskType, String targetType, Long targetId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Task t where t.id = :id")
    Optional<Task> findByIdForUpdate(Long id);
}

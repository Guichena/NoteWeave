package com.noteweave.task.repository;

import com.noteweave.task.model.TaskAttempt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskAttemptRepository extends JpaRepository<TaskAttempt, Long> {

    Optional<TaskAttempt> findByTaskIdAndAttemptNo(Long taskId, int attemptNo);

    List<TaskAttempt> findByTaskIdOrderByAttemptNoAsc(Long taskId);
}

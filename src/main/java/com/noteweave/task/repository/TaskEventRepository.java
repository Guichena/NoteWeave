package com.noteweave.task.repository;

import com.noteweave.task.model.TaskEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskEventRepository extends JpaRepository<TaskEvent, Long> {

    Page<TaskEvent> findByTaskId(Long taskId, Pageable pageable);
}

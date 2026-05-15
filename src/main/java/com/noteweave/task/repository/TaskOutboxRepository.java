package com.noteweave.task.repository;

import com.noteweave.task.model.TaskOutbox;
import com.noteweave.task.model.TaskOutboxStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TaskOutboxRepository extends JpaRepository<TaskOutbox, Long> {

    @Query("""
            select o from TaskOutbox o
            where o.status = com.noteweave.task.model.TaskOutboxStatus.PENDING
               or (o.status = com.noteweave.task.model.TaskOutboxStatus.FAILED
                   and (o.nextRetryAt is null or o.nextRetryAt <= :now))
            order by o.createdAt asc
            """)
    List<TaskOutbox> findDispatchable(LocalDateTime now);

    List<TaskOutbox> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    long countByStatus(TaskOutboxStatus status);
}

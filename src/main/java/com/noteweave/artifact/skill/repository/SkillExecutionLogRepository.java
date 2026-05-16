package com.noteweave.artifact.skill.repository;

import com.noteweave.artifact.skill.model.SkillExecutionLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillExecutionLogRepository extends JpaRepository<SkillExecutionLog, Long> {

    List<SkillExecutionLog> findByTaskIdOrderByIdAsc(Long taskId);
}

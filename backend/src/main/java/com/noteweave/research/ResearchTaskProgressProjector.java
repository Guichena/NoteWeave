package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.AnswerLiveEvent;
import com.noteweave.answer.ConversationEventMux;
import com.noteweave.common.Json;
import com.noteweave.task.TaskProgressRecordedEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ResearchTaskProgressProjector {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ConversationEventMux conversationEventMux;

    public ResearchTaskProgressProjector(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ConversationEventMux conversationEventMux
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.conversationEventMux = conversationEventMux;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void project(TaskProgressRecordedEvent event) {
        Scope scope = jdbcTemplate.query("""
                select id, conversation_id from research_run
                where task_id = ? and conversation_id is not null
                """, rs -> rs.next() ? new Scope(rs.getString(1), rs.getString(2)) : null, event.taskId());
        if (scope == null) {
            return;
        }
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("phase", event.phase());
        data.put("message", event.message());
        data.put("progress_percent", event.progressPercent());
        data.put("metrics", event.metrics());
        data.put("payload", event.payload());
        conversationEventMux.publish(
                scope.conversationId(),
                scope.runId(),
                new AnswerLiveEvent(0L, "research.progress", Json.write(objectMapper, data), Instant.now())
        );
    }

    private record Scope(String runId, String conversationId) {
    }
}

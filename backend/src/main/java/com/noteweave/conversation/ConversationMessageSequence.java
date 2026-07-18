package com.noteweave.conversation;

import com.noteweave.common.BusinessException;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.security.SystemActor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ConversationMessageSequence {

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final AuditActorProvider auditActorProvider;

    public ConversationMessageSequence(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new SimpleMeterRegistry(), null);
    }

    public ConversationMessageSequence(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this(jdbcTemplate, meterRegistry, null);
    }

    @Autowired
    public ConversationMessageSequence(
            JdbcTemplate jdbcTemplate,
            MeterRegistry meterRegistry,
            AuditActorProvider auditActorProvider
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        this.auditActorProvider = auditActorProvider;
    }

    public int allocatePair(String conversationId) {
        String actor = auditActorProvider == null
                ? SystemActor.of("CONVERSATION")
                : auditActorProvider.currentOrSystem("CONVERSATION");
        int updated = jdbcTemplate.update("""
                update conversation
                set next_message_seq = next_message_seq + 2,
                    updated_by = ?, updated_at = current_timestamp
                where id = ? and status = 'ACTIVE'
                """, actor, conversationId);
        if (updated == 0) {
            throw new BusinessException("CONVERSATION_NOT_FOUND", "会话不存在或不可写入");
        }
        Integer nextValue = jdbcTemplate.queryForObject("""
                select next_message_seq from conversation where id = ?
                """, Integer.class, conversationId);
        if (nextValue == null || nextValue < 3) {
            meterRegistry.counter("noteweave.conversation.sequence.invalid").increment();
            throw new BusinessException("CONVERSATION_SEQUENCE_INVALID", "会话消息序号分配失败");
        }
        return nextValue - 2;
    }
}

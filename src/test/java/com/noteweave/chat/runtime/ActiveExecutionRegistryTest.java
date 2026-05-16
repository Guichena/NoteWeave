package com.noteweave.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.chat.runtime.service.ActiveExecution;
import com.noteweave.chat.runtime.service.ActiveExecutionRegistry;
import org.junit.jupiter.api.Test;

class ActiveExecutionRegistryTest {

    private final ActiveExecutionRegistry registry = new ActiveExecutionRegistry();

    @Test
    void shouldRejectSecondActiveExecutionForSameSession() {
        assertThat(registry.registerIfAbsent(10L, new ActiveExecution("stream-1", "request-1"))).isTrue();
        assertThat(registry.registerIfAbsent(10L, new ActiveExecution("stream-2", "request-2"))).isFalse();
        assertThat(registry.get(10L)).isPresent();
    }
}

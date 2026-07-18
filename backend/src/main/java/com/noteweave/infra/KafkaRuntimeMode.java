package com.noteweave.infra;

import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.source.SourceMessagingMode;
import org.springframework.stereotype.Component;

@Component
public class KafkaRuntimeMode implements SourceMessagingMode {

    private final boolean enabled;

    public KafkaRuntimeMode(NoteWeaveProperties properties) {
        this.enabled = properties.kafka().enabled();
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isAsyncEnabled() {
        return enabled;
    }
}

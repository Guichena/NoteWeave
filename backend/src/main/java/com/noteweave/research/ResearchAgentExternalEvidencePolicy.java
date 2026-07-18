package com.noteweave.research;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Server-owned switch that controls whether new Agent tasks may use external tools. */
@Component
public class ResearchAgentExternalEvidencePolicy {
    private final boolean enabled;

    public ResearchAgentExternalEvidencePolicy(
            @Value("${noteweave.research.agent.external-evidence-enabled:false}") boolean enabled
    ) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }
}

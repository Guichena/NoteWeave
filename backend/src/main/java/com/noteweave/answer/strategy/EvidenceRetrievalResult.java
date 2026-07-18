package com.noteweave.answer.strategy;

import java.util.List;
import java.util.Map;

public record EvidenceRetrievalResult(
        List<EvidenceBundle.Evidence> evidence,
        Map<String, String> metadata,
        boolean degraded,
        List<String> degradationReasons,
        Map<String, Long> measurements
) {
    public EvidenceRetrievalResult {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
        measurements = measurements == null ? Map.of() : Map.copyOf(measurements);
    }

    public EvidenceRetrievalResult(
            List<EvidenceBundle.Evidence> evidence,
            Map<String, String> metadata,
            boolean degraded,
            List<String> degradationReasons
    ) {
        this(evidence, metadata, degraded, degradationReasons, Map.of());
    }

    public static EvidenceRetrievalResult success(List<EvidenceBundle.Evidence> evidence) {
        return new EvidenceRetrievalResult(evidence, Map.of(), false, List.of(), Map.of());
    }
}

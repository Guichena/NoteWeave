package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Authoritative role capability matrix; profiles alone never grant runtime authority. */
@Component
class ResearchAgentRoleCapabilityRegistry {
    private final boolean evidenceAuditEnabled;
    private final boolean synthesisEnabled;
    private final boolean wideDiscoveryEnabled;

    private static final Map<String, Capability> CAPABILITIES = Map.of(
            "DEEP_CELL", new Capability(true, "DeepCellExecutor", "v1-v3", "CANDIDATE", "CELL_CANDIDATE",
                    Set.of("search", "fetch", "read", "extract", "archive")),
            "COUNTERFACTUAL", new Capability(true, "DeepCellExecutor", "v2-v3", "CANDIDATE_OR_QUORUM", "REPAIR_CANDIDATE",
                    Set.of("search", "fetch", "read", "extract", "archive")),
            "EVIDENCE_AUDIT", new Capability(false, "EvidenceAuditExecutor", "v3", "AUDIT_RESULT", "AUDIT_DECISION", Set.of()),
            "SYNTHESIS", new Capability(false, "SynthesisExecutor", "v3", "ARTIFACT_CANDIDATE", "ARTIFACT_PROMOTION_ONLY", Set.of()),
            "WIDE_DISCOVERY", new Capability(false, "DiscoveryExecutor", "v3", "SCOPE_PROPOSAL", "PROPOSAL_ONLY", Set.of("search", "read")),
            "CELL_VERIFIER", new Capability(false, "Internal", "internal", "DECISION", "NONE", Set.of()),
            "GLOBAL_VERIFIER", new Capability(false, "Internal", "internal", "DECISION", "NONE", Set.of()));

    ResearchAgentRoleCapabilityRegistry(
            @Value("${noteweave.research.evidence-audit-v1:false}") boolean evidenceAuditEnabled,
            @Value("${noteweave.research.worker-synthesis-v1:false}") boolean synthesisEnabled,
            @Value("${noteweave.research.wide-discovery-v1:false}") boolean wideDiscoveryEnabled
    ) {
        this.evidenceAuditEnabled = evidenceAuditEnabled;
        this.synthesisEnabled = synthesisEnabled;
        this.wideDiscoveryEnabled = wideDiscoveryEnabled;
    }

    Capability requireSchedulable(String role) {
        Capability capability = CAPABILITIES.get(role);
        if (capability == null || !schedulable(role, capability)) {
            throw new BusinessException("RESEARCH_AGENT_ROLE_NOT_SCHEDULABLE",
                    "Research agent role is unknown, internal, planned, or feature-gated: " + role);
        }
        return capability;
    }

    Capability requireRegistered(String role) {
        Capability capability = CAPABILITIES.get(role);
        if (capability == null) {
            throw new BusinessException("RESEARCH_AGENT_ROLE_UNREGISTERED", "Research agent role is not registered");
        }
        return capability;
    }

    private boolean schedulable(String role, Capability capability) {
        if (capability.defaultSchedulable()) return true;
        return switch (role) {
            case "EVIDENCE_AUDIT" -> evidenceAuditEnabled;
            case "SYNTHESIS" -> synthesisEnabled;
            case "WIDE_DISCOVERY" -> wideDiscoveryEnabled;
            default -> false;
        };
    }

    record Capability(boolean defaultSchedulable, String executor, String snapshotSchema,
                      String completionHandler, String mutationAuthority, Set<String> allowedTools) { }
}

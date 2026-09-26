package com.noteweave.research;

import com.noteweave.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchAgentRoleCapabilityRegistryTest {

    @Test
    void deepCellAndCounterfactualRemainDefaultSchedulable() {
        ResearchAgentRoleCapabilityRegistry registry =
                new ResearchAgentRoleCapabilityRegistry(false, false, false);

        assertThat(registry.requireSchedulable("DEEP_CELL").mutationAuthority())
                .isEqualTo("CELL_CANDIDATE");
        assertThat(registry.requireSchedulable("COUNTERFACTUAL").mutationAuthority())
                .isEqualTo("REPAIR_CANDIDATE");
    }

    @Test
    void controlledRolesAreFailClosedByDefault() {
        ResearchAgentRoleCapabilityRegistry registry =
                new ResearchAgentRoleCapabilityRegistry(false, false, false);

        assertThatThrownBy(() -> registry.requireSchedulable("EVIDENCE_AUDIT"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_ROLE_NOT_SCHEDULABLE");
        assertThatThrownBy(() -> registry.requireSchedulable("SYNTHESIS"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> registry.requireSchedulable("WIDE_DISCOVERY"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void discoveryNeverReceivesCellMutationAuthority() {
        ResearchAgentRoleCapabilityRegistry registry =
                new ResearchAgentRoleCapabilityRegistry(false, false, true);

        ResearchAgentRoleCapabilityRegistry.Capability capability =
                registry.requireSchedulable("WIDE_DISCOVERY");

        assertThat(capability.mutationAuthority()).isEqualTo("PROPOSAL_ONLY");
        assertThat(capability.allowedTools()).containsExactlyInAnyOrder("search", "read");
    }

    @Test
    void auditAndSynthesisAreToolFreeWhenEnabled() {
        ResearchAgentRoleCapabilityRegistry registry =
                new ResearchAgentRoleCapabilityRegistry(true, true, false);

        assertThat(registry.requireSchedulable("EVIDENCE_AUDIT").allowedTools()).isEmpty();
        assertThat(registry.requireSchedulable("SYNTHESIS").allowedTools()).isEmpty();
    }
}

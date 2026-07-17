package com.noteweave.research;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MA4I I3 seam: one replayable advancement receipt and its normal-wave taskization. */
@Service
public class ResearchAgentAdvancementTaskizationService {

    private final ResearchAgentRunAdvancementService advancements;
    private final ResearchAgentTaskCoordinatorService coordinator;

    public ResearchAgentAdvancementTaskizationService(ResearchAgentRunAdvancementService advancements,
                                                       ResearchAgentTaskCoordinatorService coordinator) {
        this.advancements = advancements;
        this.coordinator = coordinator;
    }

    @Transactional
    public AdvanceAndTaskizeReceipt advanceAndTaskize(ResearchAgentRunAdvancementService.AdvanceCommand command) {
        ResearchAgentRunAdvancementService.AdvanceReceipt advancement = advancements.advance(command);
        ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskization = coordinator.planAndEnqueueForWave(
                command.researchRunId(), command.waveNo());
        return new AdvanceAndTaskizeReceipt(advancement, taskization);
    }

    public record AdvanceAndTaskizeReceipt(ResearchAgentRunAdvancementService.AdvanceReceipt advancement,
                                            ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskization) { }
}

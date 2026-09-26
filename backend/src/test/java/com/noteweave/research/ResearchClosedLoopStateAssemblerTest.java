package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchClosedLoopStateAssemblerTest {

    @Test
    void shouldDeriveVerifiedRowWhenEveryCanonicalCellIsVerified() {
        ResearchClosedLoopStateAssembler assembler = new ResearchClosedLoopStateAssembler(
                new ResearchEvidenceSampleAssembler(),
                mock(ResearchCheckpointReadModelAssembler.class),
                new ResearchCounterfactualSummaryAssembler()
        );
        ResearchClosedLoopData data = new ResearchClosedLoopData(
                List.of(Map.of("branch_id", "branch-main")),
                List.of(Map.of("row_id", "subject", "row_status", "DISCOVERED")),
                List.of(
                        Map.of("cell_id", "subject:answer", "row_id", "subject", "status", "VERIFIED"),
                        Map.of("cell_id", "subject:limits", "row_id", "subject", "status", "VERIFIED")
                ),
                List.of(), List.of(), List.of(), List.of()
        );

        ResearchClosedLoopStateResponse result = assembler.build(List.of(), data, Map.of());

        assertThat(result.stateLedger().verifiedRowCount()).isEqualTo(1);
        assertThat(result.stateLedger().rows().get(0))
                .containsEntry("row_status", "VERIFIED")
                .containsEntry("verification_status", "CELL_LEDGER_VERIFIED");
        assertThat(result.rows().get(0))
                .containsEntry("row_status", "VERIFIED")
                .containsEntry("verification_status", "CELL_LEDGER_VERIFIED");
    }

    @Test
    void shouldNotDeriveVerifiedRowWhenAnyCanonicalCellIsPending() {
        ResearchClosedLoopStateAssembler assembler = new ResearchClosedLoopStateAssembler(
                new ResearchEvidenceSampleAssembler(),
                mock(ResearchCheckpointReadModelAssembler.class),
                new ResearchCounterfactualSummaryAssembler()
        );
        ResearchClosedLoopData data = new ResearchClosedLoopData(
                List.of(),
                List.of(Map.of("row_id", "subject", "row_status", "DISCOVERED")),
                List.of(
                        Map.of("cell_id", "subject:answer", "row_id", "subject", "status", "VERIFIED"),
                        Map.of("cell_id", "subject:limits", "row_id", "subject", "status", "PENDING")
                ),
                List.of(), List.of(), List.of(), List.of()
        );

        ResearchClosedLoopStateResponse result = assembler.build(List.of(), data, Map.of());

        assertThat(result.stateLedger().verifiedRowCount()).isZero();
        assertThat(result.stateLedger().rows().get(0)).containsEntry("row_status", "DISCOVERED");
    }
}

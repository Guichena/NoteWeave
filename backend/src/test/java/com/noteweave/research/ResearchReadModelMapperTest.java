package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchReadModelMapperTest {

    @Test
    void checkpointSummaryToleratesLegacyScalarRepresentations() {
        ResearchCheckpointSnapshotSummaryResponse response =
                ResearchReadModelMapper.readCheckpointSnapshotSummaryResponse(Map.of(
                        "checkpoint_no", "7",
                        "snapshot_type", "LOOP_END",
                        "intent_constraint_count", 3L,
                        "missing_intent_requirements", "freshness",
                        "state_ledger", Map.of(
                                "active_branch_id", "branch-a",
                                "row_count", "4",
                                "verified_row_samples", List.of(Map.of("row_id", "row-1"))
                        )
                ));

        assertThat(response.checkpointNo()).isEqualTo(7);
        assertThat(response.snapshotType()).isEqualTo("LOOP_END");
        assertThat(response.intentConstraintCount()).isEqualTo(3);
        assertThat(response.missingIntentRequirements()).containsExactly("freshness");
        assertThat(response.stateLedger().activeBranchId()).isEqualTo("branch-a");
        assertThat(response.stateLedger().rowCount()).isEqualTo(4);
        assertThat(response.stateLedger().verifiedRowSamples())
                .containsExactly(Map.of("row_id", "row-1"));
    }

    @Test
    void artifactCandidateMapsNestedResumeContextAndCitations() {
        ResearchArtifactCandidateResponse response =
                ResearchReadModelMapper.readResearchArtifactCandidateResponse(Map.of(
                        "artifact_type", "RESEARCH_REPORT",
                        "title", "Evidence review",
                        "citation_count", "1",
                        "citations", List.of(Map.of("source_id", "source-1"), "ignored"),
                        "resume_context_summary", Map.of(
                                "source_research_run_id", "run-1",
                                "checkpoint_no", 2,
                                "restored_evidence_card_count", 5
                        )
                ));

        assertThat(response.artifactType()).isEqualTo("RESEARCH_REPORT");
        assertThat(response.title()).isEqualTo("Evidence review");
        assertThat(response.citationCount()).isEqualTo(1);
        assertThat(response.citations()).containsExactly(Map.of("source_id", "source-1"));
        assertThat(response.resumeContextSummary().sourceResearchRunId()).isEqualTo("run-1");
        assertThat(response.resumeContextSummary().checkpointNo()).isEqualTo(2);
        assertThat(response.resumeContextSummary().restoredEvidenceCardCount()).isEqualTo(5);
    }

    @Test
    void missingOptionalReadModelsUseStableEmptySemantics() {
        assertThat(ResearchReadModelMapper.readCounterfactualSummary(Map.of())).isNull();
        assertThat(ResearchReadModelMapper.readResearchArtifactCandidateResponse(Map.of())).isNull();

        ResearchRecoveryTargetsResponse recoveryTargets =
                ResearchReadModelMapper.readRecoveryTargetsResponse(Map.of());
        assertThat(recoveryTargets.requirementIds()).isEmpty();
        assertThat(recoveryTargets.requirementCount()).isZero();

        ResearchVerifierGatedSummaryResponse verifier =
                ResearchReadModelMapper.readVerifierGatedSummaryResponse(null);
        assertThat(verifier.blockedRowCount()).isZero();
        assertThat(verifier.blockedRowSamples()).isEmpty();
    }
}

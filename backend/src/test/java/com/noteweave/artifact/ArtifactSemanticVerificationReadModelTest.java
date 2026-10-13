package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.noteweave.knowledge.KnowledgeCommandService;
import com.noteweave.memory.MemoryCompilerService;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import com.noteweave.source.GeneratedSourceService;
import com.noteweave.task.TaskService;
import com.noteweave.workspace.WorkspaceService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArtifactSemanticVerificationReadModelTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final ArtifactPayloadReadModelAssembler assembler = new ArtifactPayloadReadModelAssembler(mapper);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final ArtifactJobReadRepository reads = mock(ArtifactJobReadRepository.class);
    private final ArtifactExportService exports = mock(ArtifactExportService.class);
    private final ArtifactJobService jobs = new ArtifactJobService(mapper, workspaces, mock(TaskService.class),
            reads, mock(ArtifactJobWriteRepository.class), assembler, mock(MemoryCompilerService.class),
            mock(ArtifactSkillCatalogService.class), mock(ArtifactVideoMaterialService.class),
            mock(GeneratedSourceService.class), mock(KnowledgeCommandService.class),
            mock(ArtifactContextV2ShadowSnapshotService.class), mock(ArtifactMemoryRevisionGuard.class),
            mock(ResearchGeneratedSourceReadGate.class), exports);

    private ArtifactVersionDetailResponse version(String json) {
        when(workspaces.exists("workspace")).thenReturn(true);
        when(reads.getVersionDetail("workspace", "job", 1)).thenReturn(new ArtifactVersionDetailRow(
                "version", "job", "resume_highlight", 1, "产物", "# 产物", "诊断", json, "[]", Instant.EPOCH));
        when(reads.loadArtifactFiles("version")).thenReturn(List.of());
        return jobs.getVersionDetail("workspace", "job", 1);
    }

    @Test
    void versionDetailExposesFinalVerificationAtTopLevelAndRuntimeTrace() throws Exception {
        var detail = version("""
                {"verification":{"status":"WARN","passed_checks":["原文引用"],"repaired_checks":["正文"],
                  "failed_checks":[],"warnings":["可选建议"],"contract_status":"PASS","evidence_status":"WARN",
                  "repair_rounds":[{"round":1,"failed_checks":["陈述缺少依据"],"repaired_sections":["概览"],
                    "status":"PASS","backtrack_nodes":["write"],"remaining_failed_checks":[],"validation_errors":[]}],
                  "citation_recall":0.92,"citation_precision":0.88,"llm_calls":3,
                  "statement_verification_version":"artifact-statement-verification.v1","verification_mode":"STATEMENT",
                  "sections":[{"heading":"概览","support":"SUPPORTED","chunk_ids":["chunk-1"],
                    "statements":[{"text":"已核实的陈述","label":"SUPPORTED","assessment_status":"COMPLETED",
                      "sufficiently_supported":true,"citations":[{"chunk_id":"chunk-1","quote":"连续原文","label":"SUPPORTED"}]}]}]},
                 "runtime_trace":{"verification":{"status":"FAIL"},"llm_usage":{"current_call_count":3}}}
                """);
        assertThat(detail.verification()).isSameAs(detail.runtimeTrace().verification());
        assertThat(detail.verification().status()).isEqualTo("WARN");
        assertThat(detail.verification().contractStatus()).isEqualTo("PASS");
        assertThat(detail.verification().repairRounds().get(0).failedChecks()).containsExactly("陈述缺少依据");
        assertThat(detail.verification().repairRounds().get(0).repairedSections()).containsExactly("概览");
        assertThat(detail.verification().repairRounds().get(0).status()).isEqualTo("PASS");
        assertThat(detail.verification().repairRounds().get(0).backtrackNodes()).containsExactly("write");
        assertThat(detail.verification().citationRecall()).isEqualTo(0.92);
        assertThat(detail.verification().citationPrecision()).isEqualTo(0.88);
        assertThat(detail.verification().sections().get(0)).containsKey("statements");
        var wire = mapper.readTree(mapper.writeValueAsString(detail));
        assertThat(wire.path("verification").path("sections").get(0).path("statements").get(0)
                .path("citations").get(0).path("chunk_id").asText()).isEqualTo("chunk-1");
        assertThat(wire.path("verification").path("statement_verification_version").asText())
                .isEqualTo("artifact-statement-verification.v1");
        assertThat(wire.path("verification")).isEqualTo(wire.path("runtime_trace").path("verification"));
        verify(reads).requireJob("workspace", "job");
        verify(exports).requireVersionReadable("workspace", "job", 1);
    }

    @Test
    void olderPayloadKeepsItsStatusWithoutInventingNewMetricsOrRepairs() throws Exception {
        var detail = version("""
                {"runtime_trace":{"verification":{"status":"FAIL","passed_checks":[],"failed_checks":["历史失败"]}}}
                """);
        var verification = detail.verification();
        assertThat(verification.status()).isEqualTo("FAIL");
        assertThat(verification.failedChecks()).containsExactly("历史失败");
        assertThat(verification.contractStatus()).isNull();
        assertThat(verification.evidenceStatus()).isNull();
        assertThat(verification.citationRecall()).isNull();
        assertThat(verification.citationPrecision()).isNull();
        assertThat(verification.repairRounds()).isEmpty();
        assertThat(verification.sections()).isEmpty();
        assertThat(verification.llmCalls()).isNull();
        var wire = mapper.readTree(mapper.writeValueAsString(detail));
        assertThat(wire.path("verification").path("citation_recall").isNull()).isTrue();
        assertThat(wire.path("verification").path("repair_rounds").size()).isZero();
    }

    @Test
    void missingHistoricalVerificationIsNotReadAsPass() {
        var detail = version("{}");
        assertThat(detail.verification()).isNull();
        assertThat(detail.runtimeTrace()).isNull();
    }
}

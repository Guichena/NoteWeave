package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchRunQueryPayloadReaderTest {

    private final ResearchRunQueryPayloadReader reader =
            new ResearchRunQueryPayloadReader(new ObjectMapper());
    private final ResearchCheckpointProcessAssembler checkpointProcessAssembler =
            new ResearchCheckpointProcessAssembler(
                    new ResearchVerifierGatedSummaryAssembler(),
                    new ResearchCounterfactualSummaryAssembler()
            );

    @Test
    void payloadJsonShouldDecodeAndRejectMalformedJson() {
        assertThat(reader.readPayloadMap("{\"answer\":\"ok\"}"))
                .containsEntry("answer", "ok");
        assertThat(reader.readPayloadMap(" ")).isEmpty();

        assertThatThrownBy(() -> reader.readPayloadMap("not-json"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("研究轨迹载荷解析失败");
    }

    @Test
    void traceReaderShouldUseLatestPayloadAndLatestDecisionReason() {
        List<ResearchTraceResponse> traces = List.of(
                trace("VERIFIER", Map.of("decision_records", List.of(
                        Map.of("reason_code", "old")
                ))),
                trace("VERIFIER", Map.of("decision_records", List.of(
                        Map.of("reason_code", "new")
                )))
        );

        assertThat(reader.extractTracePayload(traces, "VERIFIER"))
                .containsKey("decision_records");
        assertThat(reader.extractDecisionReason(reader.extractTracePayload(traces, "VERIFIER")))
                .isEqualTo("new");
    }

    @Test
    void recoveryTargetsShouldPreferReportStructureAndSupportStopContractFallback() {
        List<ResearchTraceResponse> directTrace = List.of(trace(
                "REPORT_STRUCTURE",
                Map.of("report_structure", Map.of(
                        "closed_loop_state", Map.of(
                                "recovery_targets", Map.of("requirement_ids", List.of("req-1"))
                        )
                ))
        ));
        assertThat(reader.extractRecoveryTargets(directTrace, checkpointProcessAssembler))
                .containsEntry("requirement_ids", List.of("req-1"));

        List<ResearchTraceResponse> fallbackTrace = List.of(trace(
                "FINAL_REPORT",
                Map.of("result_payload", Map.of(
                        "stop_contract", Map.of(
                                "recovery_target_requirement_ids", List.of("req-2")
                        )
                ))
        ));
        assertThat(reader.extractRecoveryTargets(fallbackTrace, checkpointProcessAssembler))
                .containsEntry("requirement_ids", List.of("req-2"));
    }

    private static ResearchTraceResponse trace(String type, Map<String, Object> payload) {
        return new ResearchTraceResponse("trace-" + type, type, "", payload, null);
    }
}

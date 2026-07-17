package com.noteweave.research;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Raw HTTP proof that malformed completion bytes fail before the DB service. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResearchAgentCompletionRawHttpContractTest {
    private static final String TASK_ID = "00000000-0000-0000-0000-000000000001";

    @Autowired private MockMvc mockMvc;
    @MockBean private ResearchAgentCompletionService completionService;

    @BeforeEach
    void resetService() {
        org.mockito.Mockito.reset(completionService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void shouldRejectMalformedRawBodyBeforeService(String name, byte[] body) throws Exception {
        mockMvc.perform(post("/internal/research-agent-tasks/{taskId}/complete", TASK_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESEARCH_AGENT_COMPLETION_INVALID"));
        verifyNoInteractions(completionService);
    }

    @Test
    void shouldBoundRawReadBeforeAllocatingUnboundedBody() throws Exception {
        byte[] oversized = new byte[ResearchAgentCompletionEnvelopeParser.MAX_PAYLOAD_BYTES + 1];
        Arrays.fill(oversized, (byte) 'x');
        mockMvc.perform(post("/internal/research-agent-tasks/{taskId}/complete", TASK_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESEARCH_AGENT_COMPLETION_INVALID"));
        verifyNoInteractions(completionService);
    }

    @Test
    void shouldRejectNonJsonContentTypeAtFrameworkBoundary() throws Exception {
        mockMvc.perform(post("/internal/research-agent-tasks/{taskId}/complete", TASK_ID)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(validJson().getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(completionService);
    }

    private static Stream<Arguments> invalidBodies() {
        String valid = validJson();
        String duplicate = valid.replaceFirst("\"task_id\":\"" + TASK_ID + "\"",
                "\"task_id\":\"" + TASK_ID + "\",\"task_id\":\"" + TASK_ID + "\"");
        String unknown = valid.substring(0, valid.length() - 1) + ",\"future_field\":0}";
        String nfcCollision = valid.substring(0, valid.length() - 1)
                + ",\"futuré\":0,\"future\\u0301\":0}";
        String unpaired = valid.replace("\"worker-a\"", "\"worker-\\ud800\"");
        byte[] bom = new byte[valid.getBytes(StandardCharsets.UTF_8).length + 3];
        bom[0] = (byte) 0xef; bom[1] = (byte) 0xbb; bom[2] = (byte) 0xbf;
        System.arraycopy(valid.getBytes(StandardCharsets.UTF_8), 0, bom, 3, bom.length - 3);
        byte[] invalidUtf8 = valid.getBytes(StandardCharsets.UTF_8);
        invalidUtf8 = Arrays.copyOf(invalidUtf8, invalidUtf8.length + 1);
        invalidUtf8[invalidUtf8.length - 1] = (byte) 0xc3;
        return Stream.of(
                Arguments.of("empty", new byte[0]),
                Arguments.of("duplicate-key", duplicate.getBytes(StandardCharsets.UTF_8)),
                Arguments.of("unknown-field", unknown.getBytes(StandardCharsets.UTF_8)),
                Arguments.of("trailing-token", (valid + " {}").getBytes(StandardCharsets.UTF_8)),
                Arguments.of("utf8-bom", bom),
                Arguments.of("invalid-utf8", invalidUtf8),
                Arguments.of("utf16-no-bom", valid.getBytes(StandardCharsets.UTF_16LE)),
                Arguments.of("utf32-no-bom", utf32Le(valid)),
                Arguments.of("unpaired-surrogate-escape", unpaired.getBytes(StandardCharsets.UTF_8)),
                Arguments.of("nfc-key-collision", nfcCollision.getBytes(StandardCharsets.UTF_8)),
                Arguments.of("float-integer", valid.replace("\"lease_epoch\":1", "\"lease_epoch\":1.0")
                        .getBytes(StandardCharsets.UTF_8)),
                Arguments.of("exponent-integer", valid.replace("\"lease_epoch\":1", "\"lease_epoch\":1e0")
                        .getBytes(StandardCharsets.UTF_8)),
                Arguments.of("boolean-integer", valid.replace("\"lease_epoch\":1", "\"lease_epoch\":true")
                        .getBytes(StandardCharsets.UTF_8)),
                Arguments.of("null-integer", valid.replace("\"lease_epoch\":1", "\"lease_epoch\":null")
                        .getBytes(StandardCharsets.UTF_8)),
                Arguments.of("overflow-integer", valid.replace("\"fencing_token\":1",
                        "\"fencing_token\":999999999999999999999999999999")
                        .getBytes(StandardCharsets.UTF_8))
        );
    }

    private static String validJson() {
        return """
                {"schema_version":"research-agent-completion.v1","task_id":"%s","worker_instance_id":"worker-a",
                "lease_epoch":1,"fencing_token":1,"execution_key":"deep-cell:%s:1:1",
                "task_snapshot_digest":"sha256:%s","termination_reason":"NO_SUPPORTED_CANDIDATE",
                "budget_usage":{"llm_calls":0,"search_calls":0,"fetch_calls":0,"read_calls":0,
                "extract_calls":0,"evidence_cards":0,"candidates_submitted":0},
                "telemetry":{"search_hits":0,"documents":0,"windows":0},"trace_digest":"sha256:%s",
                "evidence":[],"candidates":[],"envelope_digest":"sha256:%s"}
                """.formatted(TASK_ID, TASK_ID, "1".repeat(64), "2".repeat(64), "3".repeat(64))
                .replace("\n", "");
    }

    private static byte[] utf32Le(String value) {
        int[] codePoints = value.codePoints().toArray();
        byte[] result = new byte[codePoints.length * 4];
        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];
            result[index * 4] = (byte) codePoint;
            result[index * 4 + 1] = (byte) (codePoint >>> 8);
            result[index * 4 + 2] = (byte) (codePoint >>> 16);
            result[index * 4 + 3] = (byte) (codePoint >>> 24);
        }
        return result;
    }
}

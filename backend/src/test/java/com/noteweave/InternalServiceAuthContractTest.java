package com.noteweave;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.noteweave.config.InternalServiceAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "noteweave.internal.auth-token=test-internal-secret",
        "noteweave.internal.research-auth-token=test-research-secret",
        "noteweave.internal.artifact-auth-token=test-artifact-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalServiceAuthContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void internalEndpointsShouldRequireConfiguredServiceToken() throws Exception {
        mockMvc.perform(get("/internal/worker/artifact-outbox/metrics"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INTERNAL_AUTH_REQUIRED"))
                .andExpect(jsonPath("$.request_id").isNotEmpty())
                .andExpect(header().exists("X-Request-ID"));

        mockMvc.perform(get("/internal/worker/artifact-outbox/metrics")
                        .header(InternalServiceAuthFilter.HEADER_NAME, "wrong-secret"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/internal/worker/artifact-outbox/metrics")
                        .header(InternalServiceAuthFilter.HEADER_NAME, "test-internal-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void publicEndpointsShouldRemainAccessibleWithoutInternalToken() throws Exception {
        mockMvc.perform(get("/api/v2/skills"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())))
                .andExpect(header().string("X-Request-ID", org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())))
                .andExpect(jsonPath("$.request_id").isNotEmpty());
    }

    @Test
    void scopedWorkerTokensShouldNotCrossInternalServiceBoundaries() throws Exception {
        mockMvc.perform(post("/internal/research-agent-tasks/missing/claim")
                        .contentType("application/json")
                        .content("{\"worker_instance_id\":\"worker-a\",\"lease_seconds\":60}")
                        .header(InternalServiceAuthFilter.HEADER_NAME, "test-internal-secret"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/internal/worker/artifact-tasks/missing/input")
                        .header(InternalServiceAuthFilter.HEADER_NAME, "test-research-secret"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/internal/research-agent-tasks/missing/claim")
                        .contentType("application/json")
                        .content("{\"worker_instance_id\":\"worker-a\",\"lease_seconds\":60}")
                        .header(InternalServiceAuthFilter.HEADER_NAME, "test-artifact-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void videoMaterialRoutesShouldUseArtifactWorkerToken() throws Exception {
        String path = "/internal/worker/video-material-tasks/missing/input";
        mockMvc.perform(get(path)
                        .header(InternalServiceAuthFilter.HEADER_NAME, "test-internal-secret"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(path)
                        .header(InternalServiceAuthFilter.HEADER_NAME, "test-research-secret"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(path)
                        .header(InternalServiceAuthFilter.HEADER_NAME, "test-artifact-secret"))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertNotEquals(
                        401, result.getResponse().getStatus()));
    }

    @Test
    void suppliedCorrelationIdShouldBePreservedAcrossResponseAndApiEnvelope() throws Exception {
        mockMvc.perform(get("/api/v2/skills").header("X-Correlation-ID", "contract-correlation-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", "contract-correlation-1"))
                .andExpect(jsonPath("$.request_id").isNotEmpty());
    }
}

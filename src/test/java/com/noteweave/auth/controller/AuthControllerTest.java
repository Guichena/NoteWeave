package com.noteweave.auth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registerAndGetMe_ShouldSucceed() throws Exception {
        TokenPair tokenPair = registerAndGetTokens("auth_test_user_" + System.nanoTime());

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tokenPair.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.username").exists())
                .andExpect(jsonPath("$.data.email").exists());
    }

    @Test
    void refreshAndLogout_ShouldRevokeRefreshToken() throws Exception {
        TokenPair initialTokens = registerAndGetTokens("refresh_test_user_" + System.nanoTime());

        Map<String, Object> refreshPayload = new HashMap<>();
        refreshPayload.put("refreshToken", initialTokens.refreshToken());

        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.refreshToken").exists())
                .andReturn();

        JsonNode refreshRoot = objectMapper.readTree(refreshResult.getResponse().getContentAsString());
        String refreshedAccessToken = refreshRoot.path("data").path("accessToken").asText();
        String refreshedRefreshToken = refreshRoot.path("data").path("refreshToken").asText();

        Map<String, Object> logoutPayload = new HashMap<>();
        logoutPayload.put("refreshToken", refreshedRefreshToken);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + refreshedAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Map<String, Object> secondRefreshPayload = new HashMap<>();
        secondRefreshPayload.put("refreshToken", refreshedRefreshToken);
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondRefreshPayload)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    private TokenPair registerAndGetTokens(String username) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", username);
        payload.put("email", username + "@example.com");
        payload.put("password", "Password123!");
        payload.put("displayName", "Test User");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.refreshToken").exists())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TokenPair(
                root.path("data").path("accessToken").asText(),
                root.path("data").path("refreshToken").asText()
        );
    }

    private record TokenPair(String accessToken, String refreshToken) {
    }
}

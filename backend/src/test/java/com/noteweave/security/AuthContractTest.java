package com.noteweave.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthContractTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PasswordHasher passwordHasher;
    @Autowired ObjectMapper objectMapper;

    @Test
    void loginRefreshSessionAndLogoutShouldUseHashedRotatingTokens() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();
        String username = "auth-" + suffix;
        String password = "Correct-Horse-" + suffix;
        jdbcTemplate.update("""
                insert into users(id, username, email, display_name, password_hash, status)
                values (?, ?, ?, 'Auth Contract', ?, 'ACTIVE')
                """, userId, username, username + "@noteweave.test", passwordHasher.hash(password));

        String loginJson = mockMvc.perform(post("/api/v2/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "login", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.user_id").value(userId))
                .andReturn().getResponse().getContentAsString();
        JsonNode login = objectMapper.readTree(loginJson).path("data");
        String accessToken = login.path("access_token").asText();
        String refreshToken = login.path("refresh_token").asText();

        String storedSessionToken = jdbcTemplate.queryForObject("""
                select session_token from user_session where token_hash = ?
                """, String.class, TokenHasher.sha256(accessToken));
        assertThat(storedSessionToken).isEmpty();

        mockMvc.perform(get("/api/v2/auth/session")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username));

        String refreshJson = mockMvc.perform(post("/api/v2/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "refresh_token", refreshToken))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String rotatedAccessToken = objectMapper.readTree(refreshJson).path("data").path("access_token").asText();
        assertThat(rotatedAccessToken).isNotEqualTo(accessToken);

        mockMvc.perform(get("/api/v2/auth/session")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v2/auth/logout")
                        .header("Authorization", "Bearer " + rotatedAccessToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v2/auth/session")
                        .header("Authorization", "Bearer " + rotatedAccessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedLoginFailuresShouldBeRateLimitedAndSecurityAudited() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String login = "missing-" + suffix;
        String remoteAddress = "198.51.100.42";
        int eventsBefore = jdbcTemplate.queryForObject(
                "select count(*) from auth_login_security_event", Integer.class);

        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post("/api/v2/auth/login")
                            .with(request -> {
                                request.setRemoteAddr(remoteAddress);
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of(
                                    "login", attempt == 1 ? login.toUpperCase() : login,
                                    "password", "Definitely-Wrong-Password"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_INVALID"));
        }

        mockMvc.perform(post("/api/v2/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(remoteAddress);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "login", login,
                                "password", "Definitely-Wrong-Password"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_RATE_LIMITED"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from auth_login_security_event", Integer.class))
                .isEqualTo(eventsBefore + 4);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from auth_login_security_event
                where outcome = 'RATE_LIMITED'
                """, Integer.class)).isGreaterThanOrEqualTo(1);
    }
}

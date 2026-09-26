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
    void registrationShouldCreateAHashedCredentialAndAuthenticatedSession() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String username = "registered-" + suffix;
        String email = username + "@noteweave.test";
        String password = "Correct-Horse-" + suffix;

        String registrationJson = mockMvc.perform(post("/api/v2/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", username,
                                "email", email,
                                "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.username").value(username))
                .andExpect(jsonPath("$.data.user.email").value(email))
                .andReturn().getResponse().getContentAsString();

        JsonNode registration = objectMapper.readTree(registrationJson).path("data");
        String accessToken = registration.path("access_token").asText();
        String storedPasswordHash = jdbcTemplate.queryForObject(
                "select password_hash from users where username = ?", String.class, username);
        assertThat(storedPasswordHash).isNotEqualTo(password);
        assertThat(passwordHasher.matches(password, storedPasswordHash)).isTrue();

        mockMvc.perform(get("/api/v2/auth/session")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username));
    }

    @Test
    void registrationShouldRejectDuplicateUsernameOrEmail() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String username = "duplicate-" + suffix;
        String email = username + "@noteweave.test";
        String password = "Correct-Horse-" + suffix;
        java.util.Map<String, String> registration = java.util.Map.of(
                "username", username, "email", email, "password", password);

        mockMvc.perform(post("/api/v2/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registration)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v2/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", username, "email", "other-" + email, "password", password))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_REGISTRATION_CONFLICT"));

        mockMvc.perform(post("/api/v2/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", "other-" + username, "email", email, "password", password))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_REGISTRATION_CONFLICT"));
    }

    @Test
    void registrationShouldValidatePublicCredentials() throws Exception {
        mockMvc.perform(post("/api/v2/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"no","email":"not-an-email","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void repeatedRegistrationAttemptsShouldBeRateLimitedAndAudited() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String username = "limited-" + suffix;
        String remoteAddress = "198.51.100.77";

        for (int attempt = 0; attempt < 3; attempt++) {
            var result = mockMvc.perform(post("/api/v2/auth/register")
                    .with(request -> {
                        request.setRemoteAddr(remoteAddress);
                        return request;
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(java.util.Map.of(
                            "username", attempt == 1 ? username.toUpperCase() : username,
                            "email", "limited-" + attempt + "-" + suffix + "@noteweave.test",
                            "password", "Correct-Horse-" + suffix))));
            if (attempt < 2) {
                result.andExpect(status().isOk());
            } else {
                result.andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("AUTH_REGISTRATION_CONFLICT"));
            }
        }

        mockMvc.perform(post("/api/v2/auth/register")
                        .with(request -> {
                            request.setRemoteAddr(remoteAddress);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", username,
                                "email", "limited-final-" + suffix + "@noteweave.test",
                                "password", "Correct-Horse-" + suffix))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_REGISTRATION_RATE_LIMITED"));

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from auth_registration_security_event
                where outcome = 'RATE_LIMITED'
                """, Integer.class)).isGreaterThanOrEqualTo(1);
    }

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

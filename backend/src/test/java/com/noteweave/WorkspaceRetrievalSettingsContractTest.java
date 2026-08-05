package com.noteweave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import com.noteweave.security.TokenHasher;
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
class WorkspaceRetrievalSettingsContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void retrievalStrategyV2MustDefaultOffAndOnlyOwnerCanUpdateIt() throws Exception {
        String ownerToken = createSessionForExistingUser("local-user", "retrieval-owner");
        String workspaceId = createWorkspace(ownerToken);
        UserSession member = createUserAndSession("retrieval-member");
        addMember(workspaceId, member.userId());

        assertThat(jdbcTemplate.queryForObject("""
                select retrieval_strategy_v2_enabled from workspace where id = ?
                """, Boolean.class, workspaceId)).isFalse();

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/retrieval-settings", workspaceId)
                        .header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspace_id").value(workspaceId))
                .andExpect(jsonPath("$.data.retrieval_strategy_v2_enabled").value(false));

        mockMvc.perform(put("/api/v2/workspaces/{workspaceId}/retrieval-settings", workspaceId)
                        .header("Authorization", "Bearer " + member.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "retrieval_strategy_v2_enabled", true))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));

        assertThat(jdbcTemplate.queryForObject("""
                select retrieval_strategy_v2_enabled from workspace where id = ?
                """, Boolean.class, workspaceId)).isFalse();

        mockMvc.perform(put("/api/v2/workspaces/{workspaceId}/retrieval-settings", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        Instant oldUpdatedAt = Instant.parse("2000-01-01T00:00:00Z");
        jdbcTemplate.update("""
                update workspace
                set updated_by = 'SYSTEM:TEST', updated_at = ?
                where id = ?
                """, Timestamp.from(oldUpdatedAt), workspaceId);

        mockMvc.perform(put("/api/v2/workspaces/{workspaceId}/retrieval-settings", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "retrieval_strategy_v2_enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspace_id").value(workspaceId))
                .andExpect(jsonPath("$.data.retrieval_strategy_v2_enabled").value(true));

        WorkspaceAuditRow stored = jdbcTemplate.queryForObject("""
                select retrieval_strategy_v2_enabled, updated_by, updated_at
                from workspace
                where id = ?
                """, (rs, rowNum) -> new WorkspaceAuditRow(
                rs.getBoolean("retrieval_strategy_v2_enabled"),
                rs.getString("updated_by"),
                rs.getTimestamp("updated_at").toInstant()
        ), workspaceId);
        assertThat(stored).isNotNull();
        assertThat(stored.enabled()).isTrue();
        assertThat(stored.updatedBy()).isEqualTo("local-user");
        assertThat(stored.updatedAt()).isAfter(oldUpdatedAt);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/retrieval-settings", workspaceId)
                        .header("Authorization", "Bearer " + member.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retrieval_strategy_v2_enabled").value(true));
    }

    private String createSessionForExistingUser(String userId, String prefix) {
        String token = prefix + "-" + UUID.randomUUID();
        jdbcTemplate.update("""
                insert into user_session(id, user_id, session_token, token_hash, status)
                values (?, ?, '', ?, 'ACTIVE')
                """, UUID.randomUUID().toString(), userId, TokenHasher.sha256(token));
        return token;
    }

    private UserSession createUserAndSession(String prefix) {
        String userId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into users(id, username, email, display_name, status)
                values (?, ?, ?, ?, 'ACTIVE')
                """, userId, prefix + "-" + userId, userId + "@noteweave.test", prefix);
        return new UserSession(userId, createSessionForExistingUser(userId, prefix));
    }

    private void addMember(String workspaceId, String userId) {
        jdbcTemplate.update("""
                insert into workspace_member(id, workspace_id, user_id, role, status)
                values (?, ?, ?, 'VIEWER', 'ACTIVE')
                """, UUID.randomUUID().toString(), workspaceId, userId);
    }

    private String createWorkspace(String token) throws Exception {
        String response = mockMvc.perform(post("/api/v2/workspaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Retrieval Settings " + UUID.randomUUID()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.path("data").path("workspace_id").asText();
    }

    private record UserSession(String userId, String token) {
    }

    private record WorkspaceAuditRow(boolean enabled, String updatedBy, Instant updatedAt) {
    }
}

package com.noteweave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.security.TokenHasher;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
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
class WorkspaceAccessContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void workspaceResourcesMustEnforceSessionAndMembership() throws Exception {
        String ownerToken = "owner-" + UUID.randomUUID();
        jdbcTemplate.update("""
                insert into user_session(id, user_id, session_token, token_hash, status)
                values (?, 'local-user', '', ?, 'ACTIVE')
                """, UUID.randomUUID().toString(), TokenHasher.sha256(ownerToken));

        String outsiderId = UUID.randomUUID().toString();
        String outsiderToken = "outsider-" + UUID.randomUUID();
        jdbcTemplate.update("""
                insert into users(id, username, email, display_name, status)
                values (?, ?, ?, 'Outsider', 'ACTIVE')
                """, outsiderId, "outsider-" + outsiderId, outsiderId + "@noteweave.test");
        jdbcTemplate.update("""
                insert into user_session(id, user_id, session_token, token_hash, status)
                values (?, ?, '', ?, 'ACTIVE')
                """, UUID.randomUUID().toString(), outsiderId, TokenHasher.sha256(outsiderToken));

        String createBody = "{\"name\":\"Access Contract\",\"description\":\"guard verification\"}";
        String createJson = mockMvc.perform(post("/api/v2/workspaces")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode createResponse = objectMapper.readTree(createJson);
        String workspaceId = createResponse.path("data").path("workspace_id").asText();

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/sources", workspaceId)
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_INVALID"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/sources", workspaceId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/sources", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        String taskId = UUID.randomUUID().toString();
        String itemId = UUID.randomUUID().toString();
        String conversationId = UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        String uploadId = UUID.randomUUID().toString();
        String assistantRequestId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into task(id, workspace_id, task_type, task_status)
                values (?, ?, 'ACCESS_CHECK', 'PENDING')
                """, taskId, workspaceId);
        jdbcTemplate.update("""
                insert into knowledge_item(id, workspace_id, item_type, title, status)
                values (?, ?, 'WIKI', 'Access Check', 'ACTIVE')
                """, itemId, workspaceId);
        jdbcTemplate.update("""
                insert into conversation(id, workspace_id, title, conversation_type, status)
                values (?, ?, 'Access Check', 'QA', 'ACTIVE')
                """, conversationId, workspaceId);
        jdbcTemplate.update("""
                insert into conversation_message(
                    id, conversation_id, workspace_id, message_seq, role, content, assistant_request_id
                ) values (?, ?, ?, 1, 'ASSISTANT', 'access check', ?)
                """, messageId, conversationId, workspaceId, assistantRequestId);
        jdbcTemplate.update("""
                insert into document_upload(
                    id, workspace_id, file_name, file_size, mime_type, chunk_size, total_chunks, status
                ) values (?, ?, 'access.md', 1, 'text/markdown', 1, 1, 'UPLOADING')
                """, uploadId, workspaceId);

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
        mockMvc.perform(get("/api/v2/knowledge-items/{itemId}", itemId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
        mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
        mockMvc.perform(post("/api/v2/messages/{messageId}/save-as-source", messageId)
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"denied\",\"content\":\"body\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
        mockMvc.perform(put("/api/v2/uploads/{uploadId}/chunks/0", uploadId)
                        .header("Authorization", "Bearer " + outsiderToken)
                        .content(new byte[]{1}))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
        mockMvc.perform(get("/api/v2/chat/requests/{requestId}/stream", assistantRequestId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));

        double deniedCount = meterRegistry.find("noteweave.security.access.denied")
                .tag("resource", "workspace")
                .counters()
                .stream()
                .mapToDouble(counter -> counter.count())
                .sum();
        assertThat(deniedCount).isGreaterThanOrEqualTo(7.0);

        jdbcTemplate.update("""
                insert into workspace_member(id, workspace_id, user_id, role)
                values (?, ?, ?, 'EDITOR')
                """, UUID.randomUUID().toString(), workspaceId, outsiderId);
        String signalBody = objectMapper.writeValueAsString(java.util.Map.of(
                "signal_type", "PREFERENCE",
                "source_type", "USER_FEEDBACK",
                "source_id", "forged-feedback-event",
                "signal_text", "record the authenticated user",
                "task_neighborhood", "CHAT_QA"
        ));
        String signalJson = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/signals", workspaceId)
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signalBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String signalId = objectMapper.readTree(signalJson).path("data").path("signal_id").asText();
        String storedUserId = jdbcTemplate.queryForObject(
                "select user_id from memory_signal where id = ?", String.class, signalId);
        assertThat(storedUserId).isEqualTo(outsiderId);
        assertThat(jdbcTemplate.queryForMap(
                "select source_type, source_id from memory_signal where id = ?", signalId))
                .containsEntry("SOURCE_TYPE", "USER_FEEDBACK")
                .containsEntry("SOURCE_ID", "memory-signal:" + signalId);
        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/promotions", workspaceId)
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "signal_ids", java.util.List.of(signalId)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    void ownerEditorViewerAndInactiveMembershipMustFollowPermissionMatrix() throws Exception {
        String ownerToken = createSessionForExistingUser("local-user", "matrix-owner");
        String workspaceId = createWorkspace(ownerToken, "Permission Matrix");
        assertThat(jdbcTemplate.queryForObject(
                "select created_by from workspace where id = ?", String.class, workspaceId))
                .isEqualTo("local-user");
        assertThat(jdbcTemplate.queryForObject("""
                select created_by from workspace_member where workspace_id = ? and user_id = 'local-user'
                """, String.class, workspaceId)).isEqualTo("local-user");
        UserSession editor = createUserAndSession("editor");
        UserSession viewer = createUserAndSession("viewer");
        UserSession inactive = createUserAndSession("inactive");
        addMember(workspaceId, editor.userId(), "EDITOR", "ACTIVE");
        addMember(workspaceId, viewer.userId(), "VIEWER", "ACTIVE");
        addMember(workspaceId, inactive.userId(), "EDITOR", "SUSPENDED");

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/sources", workspaceId)
                        .header("Authorization", "Bearer " + viewer.token()))
                .andExpect(status().isOk());

        String conversationBody = "{\"title\":\"Matrix\",\"conversation_type\":\"QA\"}";
        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                        .header("Authorization", "Bearer " + viewer.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conversationBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                        .header("Authorization", "Bearer " + editor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conversationBody))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v2/workspaces/{workspaceId}/wiki-settings", workspaceId)
                        .header("Authorization", "Bearer " + editor.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"wiki_enabled\":false}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v2/workspaces/{workspaceId}/wiki-settings", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"wiki_enabled\":false}"))
                .andExpect(status().isOk());

        Long versionBefore = jdbcTemplate.queryForObject(
                "select acl_version from workspace where id = ?", Long.class, workspaceId);
        mockMvc.perform(put("/api/v2/workspaces/{workspaceId}/members/{userId}", workspaceId, viewer.userId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"EDITOR\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("EDITOR"));
        assertThat(jdbcTemplate.queryForObject("""
                select updated_by from workspace_member where workspace_id = ? and user_id = ?
                """, String.class, workspaceId, viewer.userId())).isEqualTo("local-user");
        Long versionAfter = jdbcTemplate.queryForObject(
                "select acl_version from workspace where id = ?", Long.class, workspaceId);
        assertThat(versionAfter).isEqualTo(versionBefore + 1);

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                        .header("Authorization", "Bearer " + viewer.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conversationBody))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v2/workspaces/{workspaceId}/members/{userId}", workspaceId, viewer.userId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/sources", workspaceId)
                        .header("Authorization", "Bearer " + viewer.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v2/workspaces/{workspaceId}/members/local-user", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_OWNER_IMMUTABLE"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/sources", workspaceId)
                        .header("Authorization", "Bearer " + inactive.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void workspaceListMustOnlyExposeActiveMemberships() throws Exception {
        String ownerToken = createSessionForExistingUser("local-user", "list-owner");
        String workspaceId = createWorkspace(ownerToken, "Visible Workspace");
        UserSession member = createUserAndSession("list-member");
        UserSession outsider = createUserAndSession("list-outsider");
        addMember(workspaceId, member.userId(), "VIEWER", "ACTIVE");

        assertThat(listWorkspaceIds(ownerToken)).contains(workspaceId);
        assertThat(listWorkspaceIds(member.token())).contains(workspaceId);
        assertThat(listWorkspaceIds(outsider.token())).doesNotContain(workspaceId);

        jdbcTemplate.update("""
                update workspace_member set status = 'SUSPENDED'
                where workspace_id = ? and user_id = ?
                """, workspaceId, member.userId());
        assertThat(listWorkspaceIds(member.token())).doesNotContain(workspaceId);
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

    private void addMember(String workspaceId, String userId, String role, String memberStatus) {
        jdbcTemplate.update("""
                insert into workspace_member(id, workspace_id, user_id, role, status)
                values (?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), workspaceId, userId, role, memberStatus);
    }

    private String createWorkspace(String token, String name) throws Exception {
        String response = mockMvc.perform(post("/api/v2/workspaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("name", name))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("workspace_id").asText();
    }

    private Set<String> listWorkspaceIds(String token) throws Exception {
        String response = mockMvc.perform(get("/api/v2/workspaces")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        return StreamSupport.stream(objectMapper.readTree(response).path("data").spliterator(), false)
                .map(item -> item.path("workspace_id").asText())
                .collect(Collectors.toSet());
    }

    private record UserSession(String userId, String token) {
    }
}

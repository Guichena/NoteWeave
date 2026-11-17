package com.noteweave;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.config.InternalServiceAuthFilter;
import com.noteweave.security.TokenHasher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "noteweave.security.local-user-fallback=false",
        "noteweave.internal.auth-token=path-coordinator-secret",
        "noteweave.internal.artifact-auth-token=path-artifact-secret",
        "noteweave.internal.research-auth-token=path-research-secret"
})
@ActiveProfiles("test")
class RequestPathSecurityContractTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private String itemId;
    private String ownerToken;
    private String outsiderToken;

    @BeforeEach
    void createProtectedResource() {
        String workspaceId = UUID.randomUUID().toString();
        itemId = UUID.randomUUID().toString();
        ownerToken = UUID.randomUUID().toString();
        outsiderToken = UUID.randomUUID().toString();
        String outsiderId = UUID.randomUUID().toString();
        jdbc.update("insert into users(id, username, email, display_name, status) values (?, ?, ?, '外部用户', 'ACTIVE')",
                outsiderId, outsiderId, outsiderId + "@path.test");
        jdbc.update("insert into user_session(id, user_id, session_token, token_hash, status) values (?, 'local-user', '', ?, 'ACTIVE')",
                UUID.randomUUID().toString(), TokenHasher.sha256(ownerToken));
        jdbc.update("insert into user_session(id, user_id, session_token, token_hash, status) values (?, ?, '', ?, 'ACTIVE')",
                UUID.randomUUID().toString(), outsiderId, TokenHasher.sha256(outsiderToken));
        jdbc.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', '路径权限回归', 'ACTIVE')", workspaceId);
        jdbc.update("insert into workspace_member(id, workspace_id, user_id, role) values (?, ?, 'local-user', 'OWNER')",
                UUID.randomUUID().toString(), workspaceId);
        jdbc.update("insert into knowledge_item(id, workspace_id, item_type, title, status) values (?, ?, 'NOTE', '受保护页面', 'ACTIVE')",
                itemId, workspaceId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v2", "/api;x=1/v2", "/api/v2;x=1", "/%61pi/v2", "/api/%762"})
    void anonymousPathVariantsCannotDelete(String prefix) throws Exception {
        assertThat(delete(prefix + "/knowledge-items/" + itemId, "")).isEqualTo(401);
        assertActive();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v2/knowledge-items/", "/api;x=1/v2/knowledge-items/", "/api/v2/knowledge-items;x=1/"})
    void authenticatedOutsiderCannotBypassResourcePermission(String prefix) throws Exception {
        assertThat(delete(prefix + itemId, outsiderToken)).isEqualTo(403);
        assertActive();
    }

    @Test
    void ownerUsesTheSameDecodedResourceAsMvc() throws Exception {
        String encodedId = "%" + Integer.toHexString(itemId.charAt(0)) + itemId.substring(1);
        assertThat(delete("/api;x=1/v2/knowledge-items;x=1/" + encodedId, ownerToken)).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from knowledge_item where id = ?", String.class, itemId))
                .isEqualTo("DELETED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal", "/internal;x=1", "/%69nternal"})
    void internalPathVariantsStillEnforceTokenAudience(String prefix) throws Exception {
        String path = prefix + "/worker/artifact-outbox/metrics";
        assertThat(get(path, "")).isEqualTo(401);
        assertThat(get(path, "path-research-secret")).isEqualTo(401);
        assertThat(get(path, "path-coordinator-secret")).isEqualTo(200);
    }

    private int delete(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).DELETE();
        if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (!token.isBlank()) request.header(InternalServiceAuthFilter.HEADER_NAME, token);
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private void assertActive() {
        assertThat(jdbc.queryForObject("select status from knowledge_item where id = ?", String.class, itemId))
                .isEqualTo("ACTIVE");
    }
}

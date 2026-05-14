package com.noteweave.space.controller;

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
class SpaceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void unauthenticatedRequest_ShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/spaces"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void viewer_ShouldNotManageMembers() throws Exception {
        String ownerName = "owner_" + System.nanoTime();
        String viewerName = "viewer_" + System.nanoTime();
        String targetName = "target_" + System.nanoTime();

        String ownerToken = registerAndGetToken(ownerName);
        String viewerToken = registerAndGetToken(viewerName);
        registerAndGetToken(targetName);

        Long spaceId = createTeamSpace(ownerToken, "team_" + System.nanoTime());

        Map<String, Object> addViewerPayload = new HashMap<>();
        addViewerPayload.put("email", viewerName + "@example.com");
        addViewerPayload.put("role", "VIEWER");
        mockMvc.perform(post("/api/v1/spaces/{spaceId}/members", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addViewerPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.role").value("VIEWER"));

        Map<String, Object> viewerAddPayload = new HashMap<>();
        viewerAddPayload.put("email", targetName + "@example.com");
        viewerAddPayload.put("role", "EDITOR");
        mockMvc.perform(post("/api/v1/spaces/{spaceId}/members", spaceId)
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(viewerAddPayload)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("SPACE_ACCESS_DENIED"));
    }

    @Test
    void listSpacesAndMembers_ShouldReturnPageResponse() throws Exception {
        String ownerName = "owner_page_" + System.nanoTime();
        String memberName = "member_page_" + System.nanoTime();

        String ownerToken = registerAndGetToken(ownerName);
        registerAndGetToken(memberName);

        Long spaceId = createTeamSpace(ownerToken, "page_team_" + System.nanoTime());

        Map<String, Object> addMemberPayload = new HashMap<>();
        addMemberPayload.put("email", memberName + "@example.com");
        addMemberPayload.put("role", "EDITOR");
        mockMvc.perform(post("/api/v1/spaces/{spaceId}/members", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addMemberPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/v1/spaces")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("page", "1")
                        .param("pageSize", "20")
                        .param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.sort").value("createdAt,desc"));

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/members", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("page", "1")
                        .param("pageSize", "20")
                        .param("sort", "createdAt,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.sort").value("createdAt,asc"));
    }

    private Long createTeamSpace(String token, String spaceName) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", spaceName);
        payload.put("description", "team test space");

        MvcResult result = mockMvc.perform(post("/api/v1/spaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("id").asLong();
    }

    private String registerAndGetToken(String username) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", username);
        payload.put("email", username + "@example.com");
        payload.put("password", "Password123!");
        payload.put("displayName", "Test User");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("accessToken").asText();
    }
}

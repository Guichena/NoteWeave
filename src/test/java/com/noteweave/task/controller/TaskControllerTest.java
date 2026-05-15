package com.noteweave.task.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.support.ContainerizedIntegrationTest;
import com.noteweave.task.dto.TaskResponse;
import com.noteweave.task.model.TaskType;
import com.noteweave.task.service.TaskCreateCommand;
import com.noteweave.task.service.TaskService;
import com.noteweave.task.worker.NoopTestTaskInput;
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
class TaskControllerTest extends ContainerizedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskService taskService;

    @Test
    void nonMember_ShouldNotQueryOrCancelTeamTask() throws Exception {
        String ownerName = "task_owner_" + System.nanoTime();
        String outsiderName = "task_outsider_" + System.nanoTime();

        String ownerToken = registerAndGetToken(ownerName);
        String outsiderToken = registerAndGetToken(outsiderName);
        long ownerId = getCurrentUserId(ownerToken);
        Long teamSpaceId = createTeamSpace(ownerToken, "task-team-" + System.nanoTime());

        TaskResponse task = taskService.createTask(TaskCreateCommand.builder()
                .userId(ownerId)
                .spaceId(teamSpaceId)
                .taskType(TaskType.NOOP_TEST)
                .targetType("NOOP_TARGET")
                .targetId(9001L)
                .idempotencyKey("NOOP_TEST:" + teamSpaceId + ":NOOP_TARGET:9001:v1")
                .input(new NoopTestTaskInput())
                .build());

        mockMvc.perform(get("/api/v1/tasks/{taskId}", task.getId())
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("TASK_ACCESS_DENIED"));

        mockMvc.perform(post("/api/v1/tasks/{taskId}/cancel", task.getId())
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("TASK_ACCESS_DENIED"));
    }

    @Test
    void owner_ShouldListAndReadOwnTask() throws Exception {
        String ownerName = "task_list_owner_" + System.nanoTime();
        String ownerToken = registerAndGetToken(ownerName);
        long ownerId = getCurrentUserId(ownerToken);
        Long teamSpaceId = createTeamSpace(ownerToken, "task-list-team-" + System.nanoTime());

        TaskResponse task = taskService.createTask(TaskCreateCommand.builder()
                .userId(ownerId)
                .spaceId(teamSpaceId)
                .taskType(TaskType.NOOP_TEST)
                .targetType("NOOP_TARGET")
                .targetId(9002L)
                .idempotencyKey("NOOP_TEST:" + teamSpaceId + ":NOOP_TARGET:9002:v1")
                .input(new NoopTestTaskInput())
                .build());

        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("spaceId", String.valueOf(teamSpaceId))
                        .param("page", "1")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].id").value(task.getId()))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20));

        mockMvc.perform(get("/api/v1/tasks/{taskId}/events", task.getId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].eventType").value("TASK_CREATED"));
    }

    private long getCurrentUserId(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("id").asLong();
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

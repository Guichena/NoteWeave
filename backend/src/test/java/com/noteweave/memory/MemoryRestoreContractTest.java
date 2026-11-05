package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 验收 11-04 中危 20：记忆停用后要能看到已停用的条目并重新启用。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "test-es"})
class MemoryRestoreContractTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void revokedMemoryIsListedAndCanBeRestored() throws Exception {
        String workspaceId = createWorkspace();
        JsonNode active = addActivePreference(workspaceId, "回答先给结论再展开");
        String itemId = active.path("memory_item_id").asText();

        review(workspaceId, active.path("revision_id").asText(), "REVOKE");
        assertThat(activeItemIds(workspaceId)).doesNotContain(itemId);

        JsonNode revoked = data(get("/api/v2/workspaces/{workspaceId}/memory/items/revoked", workspaceId));
        assertThat(revoked.findValuesAsText("memory_item_id")).contains(itemId);
        JsonNode entry = find(revoked, itemId);
        assertThat(entry.path("display_text").asText()).isEqualTo(active.path("display_text").asText());
        assertThat(entry.path("revoked_at").isMissingNode()).isFalse();

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/items/{itemId}/restore", workspaceId, itemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memory_item_id").value(itemId))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        assertThat(activeItemIds(workspaceId)).contains(itemId);
        assertThat(data(get("/api/v2/workspaces/{workspaceId}/memory/items/revoked", workspaceId))
                .findValuesAsText("memory_item_id")).doesNotContain(itemId);

        // 已经生效的记忆不能再走重新启用
        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/items/{itemId}/restore", workspaceId, itemId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMORY_RESTORE_NOT_AVAILABLE"));

        // 恢复后仍可以再次停用
        JsonNode restored = find(data(get("/api/v2/workspaces/{workspaceId}/memory/items", workspaceId)), itemId);
        review(workspaceId, restored.path("revision_id").asText(), "REVOKE");
        assertThat(activeItemIds(workspaceId)).doesNotContain(itemId);
    }

    @Test
    void restoreIsRejectedWhenTheSameMemoryIsAlreadyActive() throws Exception {
        String workspaceId = createWorkspace();
        JsonNode first = addActivePreference(workspaceId, "表格优先用中文列名");
        review(workspaceId, first.path("revision_id").asText(), "REVOKE");
        addActivePreference(workspaceId, "表格优先用中文列名");

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/items/{itemId}/restore",
                        workspaceId, first.path("memory_item_id").asText()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMORY_RESTORE_CONFLICT"));
    }

    @Test
    void neverActivatedProposalIsNotOfferedForRestore() throws Exception {
        String workspaceId = createWorkspace();
        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/items/{itemId}/restore",
                        workspaceId, "missing-item"))
                .andExpect(status().isNotFound());
    }

    private JsonNode addActivePreference(String workspaceId, String text) throws Exception {
        String signalId = data(post("/api/v2/workspaces/{workspaceId}/memory/signals", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "signal_type", "PREFERENCE",
                        "source_type", "USER_FEEDBACK",
                        "signal_text", text,
                        "task_neighborhood", "COMMON",
                        "style_constraints", List.of(text)))))
                .path("signal_id").asText();
        JsonNode promotion = data(post("/api/v2/workspaces/{workspaceId}/memory/promotions", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("signal_ids", List.of(signalId)))));
        String itemId = promotion.path("candidates").get(0).path("candidate_id").asText();
        JsonNode item = find(data(get("/api/v2/workspaces/{workspaceId}/memory/items", workspaceId)), itemId);
        if ("PROPOSED".equals(item.path("revision_status").asText())) {
            review(workspaceId, item.path("revision_id").asText(), "ACCEPT");
            item = find(data(get("/api/v2/workspaces/{workspaceId}/memory/items", workspaceId)), itemId);
        }
        assertThat(item.path("revision_status").asText()).isEqualTo("ACTIVE");
        return item;
    }

    private void review(String workspaceId, String revisionId, String decision) throws Exception {
        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/memory/revisions/{revisionId}/review",
                        workspaceId, revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("decision", decision))))
                .andExpect(status().isOk());
    }

    private List<String> activeItemIds(String workspaceId) throws Exception {
        return data(get("/api/v2/workspaces/{workspaceId}/memory/items", workspaceId))
                .findValuesAsText("memory_item_id");
    }

    private JsonNode find(JsonNode items, String itemId) {
        for (JsonNode item : items) {
            if (itemId.equals(item.path("memory_item_id").asText())) {
                return item;
            }
        }
        throw new AssertionError("列表里没有记忆 " + itemId + "：" + items);
    }

    private JsonNode data(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private String createWorkspace() throws Exception {
        return data(post("/api/v2/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "记忆重新启用工作台"))))
                .path("workspace_id").asText();
    }
}

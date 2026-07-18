package com.noteweave;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MvcResult;

final class ChatStreamTestSupport {

    private ChatStreamTestSupport() {
    }

    static ResultActions perform(MockMvc mockMvc, String assistantRequestId) throws Exception {
        MvcResult pending = mockMvc.perform(get(
                        "/api/v2/chat/requests/{assistantRequestId}/stream",
                        assistantRequestId
                ).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(pending));
    }

    static ResultActions performAnswerRun(MockMvc mockMvc, String workspaceId, String runId) throws Exception {
        return performAnswerRunAfter(mockMvc, workspaceId, runId, null);
    }

    static ResultActions performAnswerRunAfter(
            MockMvc mockMvc,
            String workspaceId,
            String runId,
            String lastEventId
    ) throws Exception {
        var request = get(
                "/api/v2/workspaces/{workspaceId}/answer-runs/{runId}/events",
                workspaceId,
                runId
        ).accept(MediaType.TEXT_EVENT_STREAM);
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        MvcResult pending = mockMvc.perform(request)
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(pending));
    }
}

package com.noteweave.common;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    @Test
    void disconnectedSseClientShouldNotBeConvertedToJsonErrorResponse() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DisconnectedStreamController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/disconnected-stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @RestController
    static class DisconnectedStreamController {

        @GetMapping(value = "/disconnected-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        void disconnectedStream(HttpServletResponse response) throws AsyncRequestNotUsableException {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            throw new AsyncRequestNotUsableException("ServletOutputStream failed to flush: broken pipe");
        }
    }
}

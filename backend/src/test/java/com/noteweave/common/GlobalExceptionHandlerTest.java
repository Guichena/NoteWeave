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

    @Test
    void unsupportedMethodShouldReturn405InsteadOfInternalError() throws Exception {
        // 验收中用错请求方法拿到的是 500 服务内部错误
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DisconnectedStreamController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/disconnected-stream"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Allow", "GET"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("METHOD_NOT_ALLOWED"));
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

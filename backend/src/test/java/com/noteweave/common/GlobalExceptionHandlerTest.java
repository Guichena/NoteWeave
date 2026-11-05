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

    /** 验收中缺必填参数、并发死锁都报成 500，不存在的资源报成 400。 */
    @Test
    void callerAndConcurrencyErrorsShouldNotBeInternalErrors() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DisconnectedStreamController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/needs-param"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("MISSING_REQUEST_PARAMETER"));
        mockMvc.perform(get("/deadlock"))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("CONCURRENT_UPDATE_CONFLICT"));
        mockMvc.perform(get("/missing-run"))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("RESEARCH_RUN_NOT_FOUND"));
    }

    @Test
    void validationMessagesShouldUseSnakeCaseFieldNames() {
        org.assertj.core.api.Assertions.assertThat(GlobalExceptionHandler.snakeCase("signalType"))
                .isEqualTo("signal_type");
        org.assertj.core.api.Assertions.assertThat(GlobalExceptionHandler.snakeCase("content")).isEqualTo("content");
    }

    @RestController
    static class DisconnectedStreamController {

        @GetMapping(value = "/disconnected-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        void disconnectedStream(HttpServletResponse response) throws AsyncRequestNotUsableException {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            throw new AsyncRequestNotUsableException("ServletOutputStream failed to flush: broken pipe");
        }

        @GetMapping("/needs-param")
        String needsParam(@org.springframework.web.bind.annotation.RequestParam("answer_mode") String answerMode) {
            return answerMode;
        }

        @GetMapping("/deadlock")
        String deadlock() {
            throw new org.springframework.dao.CannotAcquireLockException("Deadlock found when trying to get lock");
        }

        @GetMapping("/missing-run")
        String missingRun() {
            throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "研究运行不存在");
        }
    }
}

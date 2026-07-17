package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import com.noteweave.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Strict raw-byte internal boundary for the single MA4G result write. */
@RestController
@RequestMapping("/internal/research-agent-tasks")
public class ResearchAgentCompletionInternalController {
    private final ResearchAgentCompletionEnvelopeParser parser;
    private final ResearchAgentCompletionService completionService;

    public ResearchAgentCompletionInternalController(
            ResearchAgentCompletionEnvelopeParser parser,
            ResearchAgentCompletionService completionService
    ) {
        this.parser = parser;
        this.completionService = completionService;
    }

    @PostMapping(value = "/{taskId}/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<ResearchAgentCompletionReceipt> complete(
            @PathVariable String taskId,
            HttpServletRequest request
    ) {
        return ApiResponse.success(completionService.complete(taskId, parser.parse(readBounded(request))));
    }

    private byte[] readBounded(HttpServletRequest request) {
        long declared = request.getContentLengthLong();
        if (declared > ResearchAgentCompletionEnvelopeParser.MAX_PAYLOAD_BYTES) {
            throw invalidSize();
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    declared > 0 ? (int) Math.min(declared, ResearchAgentCompletionEnvelopeParser.MAX_PAYLOAD_BYTES) : 1024);
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = request.getInputStream().read(buffer)) != -1) {
                total += read;
                if (total > ResearchAgentCompletionEnvelopeParser.MAX_PAYLOAD_BYTES) throw invalidSize();
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_INVALID",
                    "Completion request body could not be read");
        }
    }

    private BusinessException invalidSize() {
        return new BusinessException("RESEARCH_AGENT_COMPLETION_INVALID",
                "Completion request size is invalid");
    }
}

package com.noteweave.research;

import com.noteweave.common.ApiResponse;
import com.noteweave.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

/** 使用现有 internal 鉴权，仅接受当前成文任务的不可变片段。 */
@RestController
@RequestMapping("/internal/research-agent-tasks")
class ResearchSynthesisUploadInternalController {
    private final ResearchSynthesisUploadService uploads;
    private final ObjectMapper mapper;
    private final ResearchSynthesisRecoveryService recovery;
    ResearchSynthesisUploadInternalController(ResearchSynthesisUploadService uploads, ObjectMapper mapper, ResearchSynthesisRecoveryService recovery) {
        this.uploads = uploads; this.mapper = mapper; this.recovery = recovery;
    }
    @PostMapping("/{taskId}/synthesis-recovery")
    ApiResponse<Map<String, Object>> recover(@PathVariable String taskId, @RequestBody Map<String, Object> request) {
        if (!request.keySet().equals(Set.of("worker_instance_id", "lease_epoch", "fencing_token", "snapshot_digest"))) throw invalid();
        return ApiResponse.success(recovery.recover(taskId, lease(request)));
    }
    @PostMapping("/{taskId}/synthesis-generation")
    ApiResponse<Map<String, Object>> generation(@PathVariable String taskId, @RequestBody Map<String, Object> request) {
        if (!request.keySet().equals(Set.of("worker_instance_id", "lease_epoch", "fencing_token", "snapshot_digest", "content_digest", "part_count", "total_bytes", "llm_calls"))) throw invalid();
        var node = mapper.valueToTree(request);
        for (String field : Set.of("part_count", "total_bytes", "llm_calls"))
            if (!node.path(field).isIntegralNumber() || !node.path(field).canConvertToInt()) throw invalid();
        if (!node.path("content_digest").isTextual()) throw invalid();
        return ApiResponse.success(recovery.start(taskId, new ResearchSynthesisRecoveryService.GenerationRequest(lease(request),
                node.path("content_digest").asText(), node.path("part_count").asInt(), node.path("total_bytes").asInt(), node.path("llm_calls").asInt())));
    }
    @PostMapping("/{taskId}/synthesis-call-permit")
    ApiResponse<Map<String,Object>> callPermit(@PathVariable String taskId,@RequestBody Map<String,Object> request) {
        if (!request.keySet().equals(Set.of("worker_instance_id","lease_epoch","fencing_token","snapshot_digest","call_index","purpose"))) throw invalid();
        var node = mapper.valueToTree(request);
        if (!node.path("call_index").isIntegralNumber() || !node.path("call_index").canConvertToInt() || !node.path("purpose").isTextual()) throw invalid();
        return ApiResponse.success(recovery.permitCall(taskId,lease(request),node.path("call_index").asInt(),node.path("purpose").asText()));
    }
    private ResearchSynthesisRecoveryService.LeaseRequest lease(Map<String, Object> request) {
        var node = mapper.valueToTree(request);
        if (!node.path("lease_epoch").isIntegralNumber() || !node.path("lease_epoch").canConvertToInt()
                || !node.path("fencing_token").isIntegralNumber() || !node.path("fencing_token").canConvertToLong()
                || !node.path("worker_instance_id").isTextual() || !node.path("snapshot_digest").isTextual()) throw invalid();
        return new ResearchSynthesisRecoveryService.LeaseRequest(node.path("worker_instance_id").asText(), node.path("lease_epoch").asInt(),
                node.path("fencing_token").asLong(), node.path("snapshot_digest").asText());
    }
    @PostMapping("/{taskId}/synthesis-parts")
    ApiResponse<Map<String, Object>> upload(@PathVariable String taskId, @RequestBody Map<String, Object> request) {
        if (!request.keySet().equals(Set.of("worker_instance_id", "lease_epoch", "fencing_token", "snapshot_digest", "content_digest",
                "part_index", "part_count", "total_bytes", "part_digest", "content_base64"))) throw invalid();
        var node = mapper.valueToTree(request);
        for (String field : Set.of("lease_epoch", "part_index", "part_count", "total_bytes"))
            if (!node.path(field).isIntegralNumber() || !node.path(field).canConvertToInt()) throw invalid();
        if (!node.path("fencing_token").isIntegralNumber() || !node.path("fencing_token").canConvertToLong()) throw invalid();
        for (String field : Set.of("worker_instance_id", "snapshot_digest", "content_digest", "part_digest", "content_base64"))
            if (!node.path(field).isTextual()) throw invalid();
        return ApiResponse.success(uploads.upload(taskId, mapper.convertValue(request, ResearchSynthesisUploadService.PartRequest.class)));
    }
    private BusinessException invalid() { return new BusinessException("RESEARCH_SYNTHESIS_UPLOAD_INVALID", "报告片段请求字段不符合约定"); }
}

package com.noteweave.research;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 片段上传只保存不可变候选；整份报告仍在原完成事务内核验后生效。 */
@Service
class ResearchSynthesisUploadService {
    static final int PART_BYTES = 65_536;
    static final int MAX_BYTES = 4 * 1024 * 1024;
    static final String REFERENCE_SCHEMA = "research-synthesis-reference.v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ResearchAgentTaskService tasks;

    ResearchSynthesisUploadService(JdbcTemplate jdbc, ObjectMapper mapper, ResearchAgentTaskService tasks) {
        this.jdbc = jdbc; this.mapper = mapper; this.tasks = tasks;
    }

    @Transactional
    public Map<String, Object> upload(String taskId, PartRequest request) {
        if (request == null || request.workerInstanceId() == null || request.workerInstanceId().isBlank()
                || request.snapshotDigest() == null || !request.snapshotDigest().matches("sha256:[0-9a-f]{64}")
                || request.leaseEpoch() < 1 || request.fencingToken() < 1) throw invalid("报告上传缺少有效的租约身份");
        validateManifest(request.contentDigest(), request.partCount(), request.totalBytes());
        if (request.partIndex() < 0 || request.partIndex() >= request.partCount() || request.contentBase64() == null
                || request.contentBase64().length() > 87_384) throw invalid("报告片段编号或大小不符合约定");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(request.contentBase64()); }
        catch (IllegalArgumentException error) { throw invalid("报告片段编码不符合约定"); }
        int expected = Math.min(PART_BYTES, request.totalBytes() - request.partIndex() * PART_BYTES);
        if (bytes.length != expected || !digest(bytes).equals(request.partDigest())) throw invalid("报告片段长度或摘要不一致");
        // 复用已有的 Run → Task 锁顺序、数据库时钟和租约校验，上传期间正常续租。
        var claimed = tasks.heartbeat(new ResearchAgentTaskService.LeaseCommand(taskId, request.workerInstanceId(), request.leaseEpoch(), request.fencingToken(), 60));
        if (!request.snapshotDigest().equals(claimed.snapshotDigest())) throw invalid("报告上传快照已经变化");
        String context = jdbc.query("select execution_context_json from research_agent_task where id = ? and role = 'SYNTHESIS'",
                rs -> rs.next() ? rs.getString(1) : null, taskId);
        if (context == null || !read(context).path("query_policy").path("synthesis_input").path("report_upload_version").asText().equals("1"))
            throw invalid("当前任务未启用报告分段提交");
        new ResearchSynthesisRecoveryService(jdbc, mapper, null).requirePart(taskId, request);
        List<Map<String, Object>> manifests = jdbc.query("""
                select content_digest, part_count, total_bytes from research_synthesis_upload_part
                where task_id = ? and lease_epoch = ? and fencing_token = ? order by part_index limit 1
                """, (rs, n) -> Map.of("digest", rs.getString(1), "count", rs.getInt(2), "bytes", rs.getInt(3)),
                taskId, request.leaseEpoch(), request.fencingToken());
        if (!manifests.isEmpty() && (!manifests.get(0).get("digest").equals(request.contentDigest())
                || !manifests.get(0).get("count").equals(request.partCount()) || !manifests.get(0).get("bytes").equals(request.totalBytes())))
            throw invalid("同一租约已经绑定另一份报告内容");
        // 重放只读取当前片段，不在每次上传时重新读取全部已上传的正文。
        List<Part> existing = parts(taskId, request.leaseEpoch(), request.fencingToken(), request.partIndex());
        for (Part part : existing) {
            if (!part.contentDigest().equals(request.contentDigest()) || part.count() != request.partCount() || part.totalBytes() != request.totalBytes())
                throw invalid("同一租约已经绑定另一份报告内容");
            if (part.index() == request.partIndex()) {
                if (!part.digest().equals(request.partDigest()) || !java.util.Arrays.equals(part.bytes(), bytes)) throw invalid("已上传的报告片段不能改写");
                return receipt(request, true);
            }
        }
        jdbc.update("""
                insert into research_synthesis_upload_part(id, task_id, lease_epoch, fencing_token, content_digest,
                    part_index, part_count, total_bytes, part_digest, content_bytes) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Ids.newId(), taskId, request.leaseEpoch(), request.fencingToken(), request.contentDigest(),
                request.partIndex(), request.partCount(), request.totalBytes(), request.partDigest(), bytes);
        return receipt(request, false);
    }

    /** 调用方已经持有完成事务的 Run、Task 锁；旧租约或缺段不能组装成正式报告。 */
    Map<String, Object> resolve(ResearchAgentCompletionCommitter.TaskRow task, Map<String, Object> reference) {
        if (!REFERENCE_SCHEMA.equals(reference.get("result_schema_version"))) return reference;
        if (!"SYNTHESIS".equals(task.role()) || !reference.keySet().equals(Set.of("result_schema_version", "role", "status", "ledger_digest", "audit_digest", "artifact_ref")))
            throw invalid("报告引用字段不符合约定");
        if (!"SYNTHESIS".equals(reference.get("role")) || !"CANDIDATE".equals(reference.get("status"))) throw invalid("报告引用的角色或状态不符合约定");
        var input = read(task.executionContextJson()).path("query_policy").path("synthesis_input");
        if (input.path("report_upload_version").asInt() != 1) throw invalid("当前任务不允许引用分段报告");
        var ref = mapper.valueToTree(reference.get("artifact_ref"));
        if (!ref.isObject() || ref.size() != 3 || !ref.path("part_count").isIntegralNumber() || !ref.path("total_bytes").isIntegralNumber()
                || !ref.path("part_count").canConvertToInt() || !ref.path("total_bytes").canConvertToInt())
            throw invalid("报告片段清单不符合约定");
        int count = ref.path("part_count").asInt(), total = ref.path("total_bytes").asInt();
        String contentDigest = ref.path("content_digest").asText();
        validateManifest(contentDigest, count, total);
        List<Part> parts = parts(task.id(), task.leaseEpoch(), task.fencingToken(), null);
        if (parts.size() != count) throw invalid("报告片段尚未上传完整");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(total);
        for (int index = 0; index < count; index++) {
            Part part = parts.get(index);
            if (part.index() != index || part.count() != count || part.totalBytes() != total || !part.contentDigest().equals(contentDigest)
                    || !digest(part.bytes()).equals(part.digest()) || part.bytes().length != Math.min(PART_BYTES, total - index * PART_BYTES))
                throw invalid("报告片段清单或内容已经变化");
            bytes.writeBytes(part.bytes());
        }
        byte[] assembled = bytes.toByteArray();
        if (assembled.length != total || !digest(assembled).equals(contentDigest)) throw invalid("整份报告摘要不一致");
        Map<String, Object> result;
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(assembled)).toString();
            try (var parser = mapper.getFactory().createParser(json)) {
                parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                result = mapper.readerFor(new TypeReference<Map<String, Object>>() {}).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readValue(parser);
            }
        } catch (Exception error) { throw invalid("分段报告不是完整的 UTF-8 JSON 对象"); }
        if (!"research-synthesis-candidate.v2".equals(result.get("result_schema_version"))) throw invalid("分段报告类型不符合约定");
        if (result.values().stream().anyMatch(java.util.Objects::isNull)) throw invalid("分段报告的顶层字段不能为 null");
        for (String key : List.of("role", "status", "ledger_digest", "audit_digest"))
            if (!java.util.Objects.equals(reference.get(key), result.get(key))) throw invalid("报告引用与正文快照不一致");
        return Map.copyOf(result);
    }

    private void validateManifest(String digest, int count, int total) {
        if (digest == null || !digest.matches("sha256:[0-9a-f]{64}") || total < 1 || total > MAX_BYTES
                || count != (total + PART_BYTES - 1) / PART_BYTES) throw invalid("报告大小或片段数量超出约定范围");
    }
    private List<Part> parts(String task, int lease, long fence, Integer index) {
        java.util.List<Object> parameters = new java.util.ArrayList<>(List.of(task, lease, fence));
        if (index != null) parameters.add(index);
        return jdbc.query("""
                select part_index, part_count, total_bytes, content_digest, part_digest, content_bytes
                from research_synthesis_upload_part where task_id = ? and lease_epoch = ? and fencing_token = ?
                """ + (index == null ? " order by part_index" : " and part_index = ?"),
                (rs, n) -> new Part(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getBytes(6)), parameters.toArray());
    }
    private Map<String, Object> receipt(PartRequest request, boolean replay) {
        return Map.of("content_digest", request.contentDigest(), "part_index", request.partIndex(), "part_digest", request.partDigest(), "idempotent_replay", replay);
    }
    static String digest(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new IllegalStateException("无法计算报告摘要", error); }
    }
    private com.fasterxml.jackson.databind.JsonNode read(String value) {
        try { return mapper.readTree(value); } catch (Exception error) { throw invalid("报告任务快照无法读取"); }
    }
    private BusinessException invalid(String message) { return new BusinessException("RESEARCH_SYNTHESIS_UPLOAD_INVALID", message); }
    private record Part(int index, int count, int totalBytes, String contentDigest, String digest, byte[] bytes) { }
    record PartRequest(String workerInstanceId, int leaseEpoch, long fencingToken, String snapshotDigest,
                       String contentDigest, int partIndex, int partCount, int totalBytes, String partDigest, String contentBase64) { }
}

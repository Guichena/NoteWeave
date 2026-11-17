package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 复用同一冻结输入的不可变报告，旧租约始终不能直接提交或写入新租约。 */
@Service
class ResearchSynthesisRecoveryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ResearchAgentTaskService tasks;
    ResearchSynthesisRecoveryService(JdbcTemplate jdbc, ObjectMapper mapper, ResearchAgentTaskService tasks) {
        this.jdbc = jdbc; this.mapper = mapper; this.tasks = tasks;
    }

    /** 授权先于模型请求；中断后未知结果的尝试仍占用预算，不授予重复消费机会。 */
    @Transactional
    public Map<String,Object> permitCall(String task, LeaseRequest request, int index, String purpose) {
        JsonNode context = authorize(task,request);
        if (context.path("query_policy").path("synthesis_input").path("report_call_meter_version").asInt() != 1
                || index < 0 || index > 15 || !java.util.Set.of("research.synthesis","research.synthesis.statement_support",
                        "research.synthesis.requirement_coverage","research.synthesis.repair_answers","research.synthesis.answers").contains(purpose)
                || "research.synthesis.answers".equals(purpose) && context.path("query_policy").path("synthesis_input").path(ResearchAnswerPolicy.FLAG).asInt() != 1)
            throw invalid("成文调用授权不符合冻结策略");
        List<String> existing = jdbc.queryForList("select purpose from research_synthesis_call_permit where task_id = ? and lease_epoch = ? and fencing_token = ? and call_index = ?",
                String.class,task,request.leaseEpoch(),request.fencingToken(),index);
        if (!existing.isEmpty()) {
            if (!existing.get(0).equals(purpose)) throw invalid("相同成文调用序号不能变更用途");
            return Map.of("call_index",index,"recorded_llm_calls",recordedCalls(task),"idempotent_replay",true);
        }
        if (jdbc.queryForObject("select count(*) from research_synthesis_generation where task_id = ? and lease_epoch = ? and fencing_token = ?",Integer.class,task,request.leaseEpoch(),request.fencingToken()) > 0)
            throw invalid("当前租约的报告已经登记，不能追加模型调用");
        if (index != leaseCalls(task,request.leaseEpoch(),request.fencingToken()) || recordedCalls(task) >= context.path("query_policy").path("synthesis_input").path("narrative_llm_budget").asInt())
            throw invalid("成文调用不连续或已耗尽跨租约预算");
        jdbc.update("insert into research_synthesis_call_permit(task_id,lease_epoch,fencing_token,call_index,purpose) values (?,?,?,?,?)",task,request.leaseEpoch(),request.fencingToken(),index,purpose);
        return Map.of("call_index",index,"recorded_llm_calls",recordedCalls(task),"idempotent_replay",false);
    }

    @Transactional
    public Map<String, Object> recover(String task, LeaseRequest request) {
        JsonNode context = authorize(task, request);
        String input = inputDigest(context);
        List<Generation> old = generations(task, input);
        Generation bound = old.stream().filter(g -> g.lease() == request.leaseEpoch() && g.fence() == request.fencingToken()).findFirst().orElse(null);
        if (bound == null && context.path("query_policy").path("synthesis_input").path("report_call_meter_version").asInt() == 1
                && leaseCalls(task,request.leaseEpoch(),request.fencingToken()) > 0) throw invalid("当前租约已经授权生成但未保存完整报告，请重新领取任务后恢复");
        if (recordedCalls(task) > context.path("query_policy").path("synthesis_input").path("narrative_llm_budget").asInt()) throw invalid("报告恢复已经耗尽模型预算");
        for (Generation generation : old) {
            if (generation.lease() > request.leaseEpoch()) continue;
            if (bound != null && !bound.equals(generation)) continue;
            List<Part> parts = parts(task, generation.lease(), generation.fence());
            if (!complete(generation, parts)) continue;
            bind(task, request, generation, generation.origin(), generation.calls(), input);
            copy(task, request, generation, parts);
            JsonNode frozen = context.path("query_policy").path("synthesis_input");
            return Map.of("ready", true, "recorded_llm_calls", recordedCalls(task), "present_parts", parts.stream().map(Part::index).toList(),
                    "reference", Map.of("result_schema_version", ResearchSynthesisUploadService.REFERENCE_SCHEMA, "role", "SYNTHESIS", "status", "CANDIDATE",
                            "ledger_digest", frozen.path("ledger_digest").asText(), "audit_digest", frozen.path("audit_digest").asText(),
                            "artifact_ref", Map.of("content_digest", generation.digest(), "part_count", generation.count(), "total_bytes", generation.bytes())));
        }
        if (bound != null) throw invalid("当前租约已有未完整上传的报告，请重新领取任务后恢复");
        return Map.of("ready", false, "recorded_llm_calls", recordedCalls(task), "present_parts", List.of());
    }

    @Transactional
    public Map<String, Object> start(String task, GenerationRequest request) {
        JsonNode context = authorize(task, request.lease());
        if (request.contentDigest() == null || !request.contentDigest().matches("sha256:[0-9a-f]{64}") || request.totalBytes() < 1
                || request.totalBytes() > ResearchSynthesisUploadService.MAX_BYTES
                || request.partCount() != (request.totalBytes() + ResearchSynthesisUploadService.PART_BYTES - 1) / ResearchSynthesisUploadService.PART_BYTES
                || request.llmCalls() < 0 || request.llmCalls() > context.path("query_policy").path("synthesis_input").path("narrative_llm_budget").asInt())
            throw invalid("报告生成清单或模型消耗不符合约定");
        String input = inputDigest(context);
        if (context.path("query_policy").path("synthesis_input").path("report_call_meter_version").asInt() == 1
                && request.llmCalls() != leaseCalls(task,request.lease().leaseEpoch(),request.lease().fencingToken())) throw invalid("报告生成消耗与服务端调用授权不一致");
        Generation current = new Generation(request.lease().leaseEpoch(), request.lease().fencingToken(), request.contentDigest(), request.partCount(),
                request.totalBytes(), ResearchSynthesisUploadService.digest((task + ":" + request.lease().leaseEpoch() + ":" + request.lease().fencingToken()).getBytes(java.nio.charset.StandardCharsets.UTF_8)), request.llmCalls());
        bind(task, request.lease(), current, current.origin(), current.calls(), input);
        if (recordedCalls(task) > context.path("query_policy").path("synthesis_input").path("narrative_llm_budget").asInt()) throw invalid("报告跨租约生成已经耗尽模型预算");
        // 新生成内容摘要相同时可以复用旧片段；本次真实生成保留自己的消耗身份。
        for (Generation old : generations(task, input)) {
            if (old.lease() >= current.lease() || !old.digest().equals(current.digest())) continue;
            copy(task, request.lease(), current, parts(task, old.lease(), old.fence()));
        }
        return Map.of("recorded_llm_calls", recordedCalls(task), "present_parts", parts(task, current.lease(), current.fence()).stream().map(Part::index).toList());
    }

    void requireRegistered(ResearchAgentCompletionCommitter.TaskRow task, Map<String, Object> result, long charged) {
        JsonNode context = read(task.executionContextJson());
        if (context.path("query_policy").path("synthesis_input").path("report_recovery_version").asInt() != 1) return;
        List<Generation> current = generations(task.id(), inputDigest(context)).stream()
                .filter(g -> g.lease() == task.leaseEpoch() && g.fence() == task.fencingToken()).toList();
        if (current.size() != 1 || !complete(current.get(0), parts(task.id(), task.leaseEpoch(), task.fencingToken())))
            throw invalid("报告提交缺少当前租约的完整生成记录");
        if (charged != recordedCalls(task.id()) || charged > context.path("query_policy").path("synthesis_input").path("narrative_llm_budget").asInt())
            throw invalid("报告提交没有准确结算跨租约生成消耗");
    }

    void requirePart(String task, ResearchSynthesisUploadService.PartRequest request) {
        String raw = jdbc.queryForObject("select execution_context_json from research_agent_task where id = ?", String.class, task);
        JsonNode context = read(raw);
        if (context.path("query_policy").path("synthesis_input").path("report_recovery_version").asInt() != 1) return;
        List<Generation> rows = generations(task, inputDigest(context)).stream()
                .filter(g -> g.lease() == request.leaseEpoch() && g.fence() == request.fencingToken()).toList();
        if (rows.size() != 1 || !rows.get(0).digest().equals(request.contentDigest()) || rows.get(0).count() != request.partCount()
                || rows.get(0).bytes() != request.totalBytes()) throw invalid("当前租约没有登记对应报告生成清单");
    }

    private JsonNode authorize(String task, LeaseRequest request) {
        if (request == null || request.workerInstanceId() == null || request.workerInstanceId().isBlank() || request.leaseEpoch() < 1
                || request.fencingToken() < 1 || request.snapshotDigest() == null) throw invalid("报告恢复缺少当前租约身份");
        var current = tasks.heartbeat(new ResearchAgentTaskService.LeaseCommand(task, request.workerInstanceId(), request.leaseEpoch(), request.fencingToken(), 60));
        if (!request.snapshotDigest().equals(current.snapshotDigest())) throw invalid("报告恢复快照已经变化");
        String raw = jdbc.query("select execution_context_json from research_agent_task where id = ? and role = 'SYNTHESIS'", rs -> rs.next() ? rs.getString(1) : null, task);
        JsonNode context = read(raw);
        if (context.path("query_policy").path("synthesis_input").path("report_recovery_version").asInt() != 1) throw invalid("当前任务未启用报告恢复");
        return context;
    }

    private void bind(String task, LeaseRequest request, Generation value, String origin, int calls, String input) {
        List<Generation> existing = generations(task, input).stream().filter(g -> g.lease() == request.leaseEpoch() && g.fence() == request.fencingToken()).toList();
        if (!existing.isEmpty()) {
            Generation old = existing.get(0);
            if (!old.digest().equals(value.digest()) || old.count() != value.count() || old.bytes() != value.bytes()
                    || !old.origin().equals(origin) || old.calls() != calls) throw invalid("当前租约已经绑定另一份报告生成清单");
            return;
        }
        Integer occupied = jdbc.queryForObject("select count(*) from research_synthesis_generation where task_id = ? and lease_epoch = ? and fencing_token = ?", Integer.class,
                task, request.leaseEpoch(), request.fencingToken());
        if (occupied != null && occupied > 0) throw invalid("当前租约的报告输入已经变化");
        jdbc.update("insert into research_synthesis_generation(task_id,lease_epoch,fencing_token,input_digest,origin_key,content_digest,part_count,total_bytes,llm_calls) values (?,?,?,?,?,?,?,?,?)",
                task, request.leaseEpoch(), request.fencingToken(), input, origin, value.digest(), value.count(), value.bytes(), calls);
    }

    private void copy(String task, LeaseRequest request, Generation manifest, List<Part> source) {
        for (Part part : source) {
            if (part.index() < 0 || part.index() >= manifest.count() || !part.contentDigest().equals(manifest.digest())
                    || part.count() != manifest.count() || part.total() != manifest.bytes()
                    || part.bytes().length != Math.min(ResearchSynthesisUploadService.PART_BYTES, manifest.bytes() - part.index() * ResearchSynthesisUploadService.PART_BYTES)
                    || !ResearchSynthesisUploadService.digest(part.bytes()).equals(part.digest())) throw invalid("旧报告片段无法通过摘要检查");
            Integer exists = jdbc.queryForObject("select count(*) from research_synthesis_upload_part where task_id = ? and lease_epoch = ? and fencing_token = ? and part_index = ?", Integer.class,
                    task, request.leaseEpoch(), request.fencingToken(), part.index());
            if (exists != null && exists > 0) {
                Part existing = parts(task, request.leaseEpoch(), request.fencingToken()).stream().filter(p -> p.index() == part.index()).findFirst().orElseThrow();
                if (!existing.digest().equals(part.digest()) || !java.util.Arrays.equals(existing.bytes(), part.bytes())) throw invalid("恢复的报告片段与当前内容冲突");
                continue;
            }
            jdbc.update("insert into research_synthesis_upload_part(id,task_id,lease_epoch,fencing_token,content_digest,part_index,part_count,total_bytes,part_digest,content_bytes) values (?,?,?,?,?,?,?,?,?,?)",
                    Ids.newId(), task, request.leaseEpoch(), request.fencingToken(), manifest.digest(), part.index(), part.count(), part.total(), part.digest(), part.bytes());
        }
    }

    private boolean complete(Generation generation, List<Part> parts) {
        if (parts.size() != generation.count()) return false;
        ByteArrayOutputStream out = new ByteArrayOutputStream(generation.bytes());
        for (int index = 0; index < parts.size(); index++) {
            Part part = parts.get(index);
            if (part.index() != index || !part.contentDigest().equals(generation.digest()) || part.count() != generation.count() || part.total() != generation.bytes()
                    || part.bytes().length != Math.min(ResearchSynthesisUploadService.PART_BYTES, generation.bytes() - index * ResearchSynthesisUploadService.PART_BYTES)
                    || !ResearchSynthesisUploadService.digest(part.bytes()).equals(part.digest())) return false;
            out.writeBytes(part.bytes());
        }
        return ResearchSynthesisUploadService.digest(out.toByteArray()).equals(generation.digest());
    }

    private List<Generation> generations(String task, String input) {
        return jdbc.query("select lease_epoch,fencing_token,content_digest,part_count,total_bytes,origin_key,llm_calls from research_synthesis_generation where task_id = ? and input_digest = ? order by lease_epoch desc,fencing_token desc",
                (rs,n) -> new Generation(rs.getInt(1),rs.getLong(2),rs.getString(3),rs.getInt(4),rs.getInt(5),rs.getString(6),rs.getInt(7)), task,input);
    }
    private List<Part> parts(String task, int lease, long fence) {
        return jdbc.query("select part_index,part_count,total_bytes,content_digest,part_digest,content_bytes from research_synthesis_upload_part where task_id = ? and lease_epoch = ? and fencing_token = ? order by part_index",
                (rs,n) -> new Part(rs.getInt(1),rs.getInt(2),rs.getInt(3),rs.getString(4),rs.getString(5),rs.getBytes(6)), task,lease,fence);
    }
    private int recordedCalls(String task) {
        String raw = jdbc.queryForObject("select execution_context_json from research_agent_task where id = ?",String.class,task);
        if (read(raw).path("query_policy").path("synthesis_input").path("report_call_meter_version").asInt() == 1) {
            Integer permits = jdbc.queryForObject("select count(*) from research_synthesis_call_permit where task_id = ?",Integer.class,task);
            if (permits == null || permits < 0 || permits > 16) throw invalid("成文调用授权数量超出范围");
            return permits;
        }
        Long calls = jdbc.queryForObject("select coalesce(sum(used.calls),0) from (select max(llm_calls) as calls from research_synthesis_generation where task_id = ? group by origin_key) used", Long.class,task);
        if (calls == null || calls < 0 || calls > 4096) throw invalid("报告生成消耗超出范围");
        return calls.intValue();
    }
    private int leaseCalls(String task,int lease,long fence) {
        return jdbc.queryForObject("select count(*) from research_synthesis_call_permit where task_id = ? and lease_epoch = ? and fencing_token = ?",Integer.class,task,lease,fence);
    }
    private String inputDigest(JsonNode context) { return new ResearchAgentCompletionCanonicalizer().domainSeparatedDigest("research-synthesis-recovery-input.v1", mapper.convertValue(context,Object.class)); }
    private JsonNode read(String raw) {
        try { JsonNode value = mapper.readTree(raw); return value.isTextual() ? mapper.readTree(value.asText()) : value; }
        catch (Exception error) { throw invalid("报告恢复输入无法读取"); }
    }
    private BusinessException invalid(String text) { return new BusinessException("RESEARCH_SYNTHESIS_RECOVERY_INVALID",text); }
    record LeaseRequest(String workerInstanceId,int leaseEpoch,long fencingToken,String snapshotDigest) { }
    record GenerationRequest(LeaseRequest lease,String contentDigest,int partCount,int totalBytes,int llmCalls) { }
    private record Generation(int lease,long fence,String digest,int count,int bytes,String origin,int calls) { }
    private record Part(int index,int count,int total,String contentDigest,String digest,byte[] bytes) { }
}

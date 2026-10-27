package com.noteweave.research;

import com.noteweave.common.BusinessException;
import com.noteweave.source.eligibility.AccessPurpose;
import com.noteweave.source.eligibility.EligibilityDecision;
import com.noteweave.source.eligibility.EligibilityStatus;
import com.noteweave.source.eligibility.GeneratedDependencyAssessment;
import com.noteweave.source.eligibility.MaterialEligibilityPort;
import com.noteweave.source.eligibility.OriginRef;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Validates the originating Research Run before exposing a generated Source.
 *
 * <p>来源事实与依赖闭包统一委托 C1 MaterialEligibilityPort；本类只保留 Research
 * 特有的来源有效性检查（origin run 存在、上下文快照未被完全脱敏）。
 * 空 context snapshot 只说明运行较旧，依赖有效性由闭包评估单独保证，不再直接视为通过。</p>
 */
@Service
public class ResearchGeneratedSourceReadGate {
    private final JdbcTemplate jdbc;
    private final ResearchContextV2Gate contextGate;
    private final MaterialEligibilityPort eligibilityPort;

    public ResearchGeneratedSourceReadGate(JdbcTemplate jdbc,
                                           ResearchContextV2Gate contextGate) {
        this(jdbc, contextGate, null);
    }

    @Autowired
    public ResearchGeneratedSourceReadGate(JdbcTemplate jdbc,
                                           ResearchContextV2Gate contextGate,
                                           MaterialEligibilityPort eligibilityPort) {
        this.jdbc = jdbc;
        this.contextGate = contextGate;
        this.eligibilityPort = eligibilityPort;
    }

    public boolean visible(String workspaceId, String generatedBy, String generatedRefId) {
        if (!"research_agent".equals(generatedBy)) return true;
        if (generatedRefId == null || generatedRefId.isBlank()) {
            throw new BusinessException("RESEARCH_SOURCE_ORIGIN_INVALID",
                    "Generated Research Source has no origin", HttpStatus.CONFLICT);
        }
        Origin origin = jdbc.query("""
                select rr.context_snapshot_id, ris.replay_availability
                from research_run rr
                left join run_input_snapshot ris on ris.id = rr.context_snapshot_id
                where rr.id = ? and rr.workspace_id = ?
                """, rs -> rs.next() ? new Origin(rs.getString(1), rs.getString(2)) : null,
                generatedRefId, workspaceId);
        if (origin == null) {
            throw new BusinessException("RESEARCH_SOURCE_ORIGIN_INVALID",
                    "Generated Research Source origin is unavailable", HttpStatus.CONFLICT);
        }
        if (origin.snapshotId() == null) return true;
        if ("METADATA_ONLY".equals(origin.replayAvailability())) {
            return false;
        }
        contextGate.requireReadable(generatedRefId);
        return true;
    }

    public void requireReadable(String workspaceId, String generatedBy, String generatedRefId) {
        if (!visible(workspaceId, generatedBy, generatedRefId)) {
            throw new BusinessException("RESEARCH_SOURCE_CONTEXT_REDACTED",
                    "Generated Research Source is no longer readable", HttpStatus.CONFLICT);
        }
    }

    /**
     * 批量判定来源可读性：经 C1 端口取得状态与生成依赖闭包，
     * READY 且闭包内全部依赖满足当前证据用途才算可读。
     */
    public Set<String> readableSourceIds(String workspaceId, List<String> sourceIds) {
        List<String> ids = sourceIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) return Set.of();
        if (eligibilityPort == null) {
            // 兼容无端口的旧测试构造：仅保留行级 READY 判断
            return Set.copyOf(ids);
        }
        Map<String, EligibilityDecision> decisions = eligibilityPort.assess(
                workspaceId,
                ids.stream().map(OriginRef::internalSource).toList(),
                AccessPurpose.CURRENT_EVIDENCE);
        Map<String, String[]> origins = loadOrigins(workspaceId, ids);
        LinkedHashSet<String> readable = new LinkedHashSet<>();
        for (String id : ids) {
            EligibilityDecision decision = decisions.get(OriginRef.internalSource(id).originKey());
            String[] origin = origins.get(id);
            if (decision == null || origin == null || !decision.evidenceEligible()) {
                continue;
            }
            // NR03：批量读路径不因单个 origin 缺失中断整次取证——按不可读排除。
            // 但快照损坏/上下文校验失败（SNAPSHOT_MISMATCH 等）是数据完整性问题，
            // 不能与「来源已撤销」混淆后静默过滤：向上传播让调用方明确失败。
            try {
                if (visible(workspaceId, origin[0], origin[1])) {
                    readable.add(id);
                }
            } catch (BusinessException integrityFailure) {
                if ("RESEARCH_SOURCE_ORIGIN_INVALID".equals(integrityFailure.code())) {
                    continue;
                }
                throw integrityFailure;
            }
        }
        return Set.copyOf(readable);
    }

    private Map<String, String[]> loadOrigins(String workspaceId, List<String> sourceIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(sourceIds.size(), "?"));
        Object[] parameters = new Object[sourceIds.size() + 1];
        parameters[0] = workspaceId;
        for (int i = 0; i < sourceIds.size(); i++) {
            parameters[i + 1] = sourceIds.get(i);
        }
        Map<String, String[]> origins = new LinkedHashMap<>();
        jdbc.query("""
                select id, coalesce(generated_by, '') as generated_by,
                       coalesce(generated_ref_id, '') as generated_ref_id
                from source where workspace_id = ? and id in (%s)
                """.formatted(placeholders), rs -> {
            origins.put(rs.getString("id"),
                    new String[] {rs.getString("generated_by"), rs.getString("generated_ref_id")});
        }, parameters);
        return origins;
    }

    /**
     * 保存或导出研究报告前，经 C1 闭包评估校验报告依赖的来源。
     *
     * <p>声明范围（source_scope_json）与实际引用（evidence manifest、source_evidence）
     * 都进入闭包；内部来源要求当前可读，生成来源递归校验，采集器外部证据按归档身份放行。
     * 失败时返回逐项结构化原因，不再只抛一个笼统的已删除异常。</p>
     */
    public void requireReportDependenciesReadable(String workspaceId, String researchRunId) {
        if (workspaceId == null || workspaceId.isBlank()
                || researchRunId == null || researchRunId.isBlank()) {
            return;
        }
        if (eligibilityPort == null) {
            return;
        }
        GeneratedDependencyAssessment assessment = eligibilityPort.assessGeneratedDependenciesDetailed(
                workspaceId, "research_agent", researchRunId, AccessPurpose.REPUBLISH);
        Map<String, String> failures = new LinkedHashMap<>();
        if (!assessment.resolved()) {
            for (String reason : assessment.blockingReasons()) {
                failures.put(reason, "UNRESOLVED");
            }
        }
        assessment.dependencies().forEach((dependencyId, decision) -> {
            if (!decision.allowed(AccessPurpose.REPUBLISH)) {
                failures.put(dependencyId, decision.status().name() + "/" + decision.reasonCode());
            }
        });
        if (!failures.isEmpty()) {
            StringJoiner detail = new StringJoiner("; ");
            failures.entrySet().stream().limit(5)
                    .forEach(entry -> detail.add(entry.getKey() + ":" + entry.getValue()));
            throw new BusinessException("RESEARCH_SOURCE_REVOKED",
                    "研究报告依赖的来源已删除或不可读，无法保存或导出（" + detail + "）",
                    HttpStatus.CONFLICT);
        }
    }

    /** 单个已生成报告来源的再发布资格校验；供重复保存路径复用。 */
    public void requireSourceRepublishable(String workspaceId, String sourceId) {
        if (eligibilityPort == null || sourceId == null || sourceId.isBlank()) {
            return;
        }
        EligibilityDecision decision = eligibilityPort.assessOne(
                workspaceId, OriginRef.internalSource(sourceId), AccessPurpose.REPUBLISH);
        if (!decision.allowed(AccessPurpose.REPUBLISH)) {
            throw new BusinessException("RESEARCH_REPORT_SOURCE_REVOKED",
                    "研究报告来源已删除或依赖失效：" + decision.status() + "/" + decision.reasonCode(),
                    HttpStatus.CONFLICT);
        }
    }

    /**
     * 报告正文是否可以继续下发：依赖关系无法解析（范围损坏/运行缺失）直接阻断；
     * COMPLIANCE 撤销、内部缺失、跨工作台、USER 删除的依赖都阻断正文
     * （USER 删除后的正文保留政策未确认，按总计划保守默认处理）；
     * 暂不可用/快照过期的依赖只影响再发布，不影响历史正文展示；
     * 生成来源依赖自身的传递撤销由 C1 闭包在 HISTORY 用途下同样阻断。
     */
    public boolean reportBodyAllowed(String workspaceId, String researchRunId) {
        if (eligibilityPort == null || researchRunId == null || researchRunId.isBlank()) {
            return true;
        }
        GeneratedDependencyAssessment assessment = eligibilityPort.assessGeneratedDependenciesDetailed(
                workspaceId, "research_agent", researchRunId, AccessPurpose.HISTORY_DISPLAY);
        if (!assessment.resolved()) {
            return false;
        }
        return assessment.dependencies().values().stream().allMatch(decision ->
                decision.historyReadable() && decision.status() != EligibilityStatus.USER_DELETED);
    }

    private record Origin(String snapshotId, String replayAvailability) {}
}

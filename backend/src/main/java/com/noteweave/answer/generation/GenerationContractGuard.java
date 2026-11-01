package com.noteweave.answer.generation;

import com.noteweave.common.BusinessException;
import com.noteweave.source.eligibility.AccessPurpose;
import com.noteweave.source.eligibility.EligibilityDecision;
import com.noteweave.source.eligibility.MaterialEligibilityPort;
import com.noteweave.source.eligibility.OriginRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 生成前复核冻结契约里允许引用的证据是否仍然有效。
 *
 * <p>冻结与生成之间可能发生删除、来源失效或正文改写。三种情况都要在调用模型之前判定，
 * 失效时整个运行失败并返回 {@code ANSWER_EVIDENCE_REVOKED}，不能先生成再撤回已发出的正文。</p>
 *
 * <p>复核依据是本次运行的证据清单：编号绑定的证据必须仍在清单里，正文摘要必须与冻结时一致，
 * 资料来源还要经过 C1 资格端口按当前证据用途判定。</p>
 */
@Service
public class GenerationContractGuard {

    static final String CODE_REVOKED = "ANSWER_EVIDENCE_REVOKED";
    private static final String KIND_KNOWLEDGE_VERSION = "KNOWLEDGE_VERSION";
    private static final String KIND_PASSAGE = "PASSAGE";

    private final JdbcTemplate jdbcTemplate;
    private final MaterialEligibilityPort eligibilityPort;

    public GenerationContractGuard(JdbcTemplate jdbcTemplate, MaterialEligibilityPort eligibilityPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.eligibilityPort = eligibilityPort;
    }

    /** 契约缺失（历史运行）时不复核，由调用方按旧协议处理。 */
    public void requireEligible(String workspaceId, String runId, GenerationContract contract) {
        if (contract == null || contract.refusal() || contract.allowedEvidenceIds().isEmpty()) {
            return;
        }
        Map<String, ManifestRow> manifest = loadManifest(workspaceId, runId, contract.allowedEvidenceIds());
        List<String> revoked = new ArrayList<>();
        List<OriginRef> origins = new ArrayList<>();
        Map<String, String> originToEvidence = new LinkedHashMap<>();
        List<String> knowledgeVersionIds = new ArrayList<>();
        for (String evidenceId : contract.allowedEvidenceIds()) {
            ManifestRow row = manifest.get(evidenceId);
            if (row == null) {
                revoked.add(evidenceId + ":MISSING_FROM_MANIFEST");
                continue;
            }
            String expected = contract.evidenceTextHashes().get(evidenceId);
            if (expected != null && !expected.equals(row.contentHash())) {
                revoked.add(evidenceId + ":CONTENT_CHANGED");
                continue;
            }
            if (KIND_KNOWLEDGE_VERSION.equals(row.kind())) {
                if (row.knowledgeVersionId() != null) {
                    knowledgeVersionIds.add(row.knowledgeVersionId());
                }
                continue;
            }
            if (KIND_PASSAGE.equals(row.kind()) && row.sourceId() != null) {
                OriginRef origin = OriginRef.internalSnapshot(row.sourceId(), row.sourceSnapshotId());
                origins.add(origin);
                originToEvidence.put(origin.originKey(), evidenceId);
            }
        }
        if (!origins.isEmpty()) {
            Map<String, EligibilityDecision> decisions = eligibilityPort.assess(
                    workspaceId, origins, AccessPurpose.CURRENT_EVIDENCE);
            for (Map.Entry<String, String> entry : originToEvidence.entrySet()) {
                EligibilityDecision decision = decisions.get(entry.getKey());
                if (decision == null || !decision.allowed(AccessPurpose.CURRENT_EVIDENCE)) {
                    revoked.add(entry.getValue() + ":SOURCE_NOT_ELIGIBLE");
                }
            }
        }
        if (!knowledgeVersionIds.isEmpty()) {
            List<String> active = jdbcTemplate.queryForList("""
                    select id from knowledge_version where workspace_id = ? and id in (%s)
                    """.formatted(placeholders(knowledgeVersionIds.size())),
                    String.class, concat(workspaceId, knowledgeVersionIds));
            for (String versionId : knowledgeVersionIds) {
                if (!active.contains(versionId)) {
                    revoked.add("knowledge-version:" + versionId + ":NOT_ACTIVE");
                }
            }
        }
        if (!revoked.isEmpty()) {
            throw new BusinessException(CODE_REVOKED,
                    "冻结的引用证据已失效：" + String.join(",", revoked), HttpStatus.CONFLICT);
        }
    }

    private Map<String, ManifestRow> loadManifest(String workspaceId, String runId, List<String> evidenceIds) {
        Map<String, ManifestRow> rows = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select evidence_id, evidence_kind, source_id, source_snapshot_id, knowledge_version_id, content_hash
                from answer_run_evidence_manifest
                where workspace_id = ? and answer_run_id = ? and evidence_id in (%s)
                """.formatted(placeholders(evidenceIds.size())), rs -> {
            rows.put(rs.getString("evidence_id"), new ManifestRow(
                    rs.getString("evidence_id"),
                    rs.getString("evidence_kind"),
                    rs.getString("source_id"),
                    rs.getString("source_snapshot_id"),
                    rs.getString("knowledge_version_id"),
                    rs.getString("content_hash")));
        }, concat(workspaceId, runId, evidenceIds));
        return rows;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static Object[] concat(String workspaceId, String runId, List<String> ids) {
        List<Object> parameters = new ArrayList<>(ids.size() + 2);
        parameters.add(workspaceId);
        parameters.add(runId);
        parameters.addAll(ids);
        return parameters.toArray();
    }

    private static Object[] concat(String workspaceId, List<String> ids) {
        List<Object> parameters = new ArrayList<>(ids.size() + 1);
        parameters.add(workspaceId);
        parameters.addAll(ids);
        return parameters.toArray();
    }

    record ManifestRow(
            String evidenceId,
            String kind,
            String sourceId,
            String sourceSnapshotId,
            String knowledgeVersionId,
            String contentHash
    ) {
    }
}

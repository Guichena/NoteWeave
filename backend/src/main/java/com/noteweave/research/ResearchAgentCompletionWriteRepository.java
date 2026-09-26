package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists the evidence and candidate append phase inside the caller's completion transaction. */
@Repository
class ResearchAgentCompletionWriteRepository {
    private static final String EVIDENCE_DIGEST_DOMAIN = "research-agent-source-evidence.v1";
    private static final String CANDIDATE_DIGEST_DOMAIN = "research-agent-candidate.v1";
    private static final String BLIND_DIGEST_DOMAIN = "research-agent-blind-candidate.v1";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentCompletionCanonicalizer canonicalizer;

    ResearchAgentCompletionWriteRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchAgentCompletionCanonicalizer canonicalizer
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
    }

    Map<String, ResearchAgentCompletionCommitter.EvidencePersisted> appendEvidence(
            String runId,
            String completionId,
            List<ResearchAgentCompletionEnvelope.Evidence> evidence,
            Map<String, ResearchAgentCompletionCommitter.TrustedEvidenceSource> trustedEvidence,
            BiConsumer<ResearchAgentCompletionFaultInjector.Stage, Integer> checkpoint
    ) {
        Map<String, ResearchAgentCompletionCommitter.EvidencePersisted> result = new HashMap<>();
        int ordinal = 0;
        for (ResearchAgentCompletionEnvelope.Evidence item : evidence.stream()
                .sorted(Comparator.comparing(ResearchAgentCompletionEnvelope.Evidence::evidenceKey)).toList()) {
            ResearchAgentCompletionCommitter.TrustedEvidenceSource authority = trustedEvidence.get(item.evidenceKey());
            if (authority == null) throw new IllegalStateException("Validated evidence authority is missing");
            if (!authority.sourceId().equals(item.sourceId())) {
                throw new IllegalStateException("Validated evidence source identity is inconsistent");
            }
            String evidenceId = Ids.newId();
            BigDecimal legacySupport = legacyScore(item.supportScorePpm());
            BigDecimal legacyConflict = legacyScore(item.conflictScorePpm());
            Map<String, Object> content = rowContent();
            content.put("id", evidenceId);
            content.put("research_run_id", runId);
            content.put("agent_completion_id", completionId);
            content.put("evidence_key", item.evidenceKey());
            content.put("window_id", item.windowId());
            content.put("source_id", item.sourceId());
            content.put("source_title", authority.sourceTitle());
            content.put("source_url", authority.sourceUrl());
            content.put("source_origin", authority.sourceOrigin());
            content.put("source_domain", authority.sourceDomain());
            content.put("lineage_digest", authority.lineageDigest());
            content.put("provider", authority.provider());
            content.put("adapter", authority.adapter());
            content.put("search_query", item.searchQuery());
            content.put("read_focus", item.readFocus());
            content.put("quote_text", item.quoteText());
            content.put("claim_text", item.claimText());
            content.put("relation_type", item.relationType());
            content.put("support_score", legacySupport.toPlainString());
            content.put("conflict_score", legacyConflict.toPlainString());
            content.put("support_score_ppm", item.supportScorePpm());
            content.put("conflict_score_ppm", item.conflictScorePpm());
            content.put("snapshot_status", authority.snapshotStatus());
            content.put("snapshot_key", authority.snapshotKey());
            String contentDigest = canonicalizer.domainSeparatedDigest(EVIDENCE_DIGEST_DOMAIN, content);
            try {
                jdbcTemplate.update("""
                        insert into source_evidence(
                            id, research_run_id, evidence_key, window_id, source_id, source_title,
                            source_url, source_origin, source_domain, lineage_digest,
                            provider, adapter, search_query, read_focus, quote_text, claim_text,
                            relation_type, support_score, conflict_score, snapshot_status, snapshot_key,
                            agent_completion_id, content_digest, support_score_ppm, conflict_score_ppm
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, evidenceId, runId, item.evidenceKey(), item.windowId(), item.sourceId(),
                        authority.sourceTitle(), authority.sourceUrl(), authority.sourceOrigin(),
                        authority.sourceDomain(), authority.lineageDigest(), authority.provider(), authority.adapter(),
                        item.searchQuery(), item.readFocus(), item.quoteText(), item.claimText(), item.relationType(),
                        legacySupport, legacyConflict, authority.snapshotStatus(), authority.snapshotKey(),
                        completionId, contentDigest, item.supportScorePpm(), item.conflictScorePpm());
            } catch (DataIntegrityViolationException exception) {
                throw new com.noteweave.common.BusinessException("RESEARCH_AGENT_COMPLETION_EVIDENCE_CONFLICT",
                        "Evidence stable key raced with different content");
            }
            result.put(item.evidenceKey(), new ResearchAgentCompletionCommitter.EvidencePersisted(
                    evidenceId, item.evidenceKey(), item.sourceId(), authority.sourceOrigin(),
                    authority.sourceDomain(), authority.lineageDigest(), item.claimText(),
                    item.relationType(), contentDigest));
            checkpoint.accept(ResearchAgentCompletionFaultInjector.Stage.AFTER_NTH_EVIDENCE, ++ordinal);
        }
        return Map.copyOf(result);
    }

    List<ResearchAgentCompletionCommitter.CandidatePersisted> appendCandidates(
            String runId,
            ResearchAgentCompletionCommitter.TaskRow task,
            String completionId,
            String executionId,
            List<ResearchAgentCompletionEnvelope.Candidate> candidates,
            Map<String, ResearchAgentCompletionCommitter.EvidencePersisted> evidenceByKey,
            BiConsumer<ResearchAgentCompletionFaultInjector.Stage, Integer> checkpoint
    ) {
        List<ResearchAgentCompletionCommitter.CandidatePersisted> result = new ArrayList<>();
        int ordinal = 0;
        for (ResearchAgentCompletionEnvelope.Candidate item : candidates.stream()
                .sorted(Comparator.comparing(ResearchAgentCompletionEnvelope.Candidate::candidateKey)).toList()) {
            String candidateId = Ids.newId();
            List<String> evidenceKeys = item.evidenceKeys().stream().sorted().toList();
            List<ResearchAgentCompletionCommitter.EvidencePersisted> boundEvidence = evidenceKeys.stream()
                    .map(evidenceByKey::get).toList();
            List<String> sourceDomains = boundEvidence.stream()
                    .flatMap(itemEvidence -> itemEvidence.quorumIdentities().stream())
                    .distinct().sorted().toList();
            BigDecimal legacyConfidence = legacyScore(item.confidencePpm());
            Map<String, Object> content = rowContent();
            content.put("id", candidateId);
            content.put("research_run_id", runId);
            content.put("agent_completion_id", completionId);
            content.put("research_agent_execution_id", executionId);
            content.put("task_id", task.id());
            content.put("execution_id", executionId);
            content.put("idempotency_key", item.candidateKey());
            content.put("cell_key", item.cellKey());
            content.put("base_cell_version", item.baseCellVersion());
            content.put("plan_revision", task.planRevision());
            content.put("entity_set_version", task.entitySetVersion());
            content.put("lease_epoch", task.leaseEpoch());
            content.put("fencing_token", task.fencingToken());
            content.put("candidate_value", item.candidateValue());
            content.put("evidence_ids", evidenceKeys);
            content.put("confidence_score", legacyConfidence.toPlainString());
            content.put("confidence_score_ppm", item.confidencePpm());
            String blindDigest = null;
            if (task.candidateQuorum() == 2) {
                Map<String, Object> blindContent = rowContent();
                blindContent.put("cell_key", item.cellKey());
                blindContent.put("base_cell_version", item.baseCellVersion());
                blindContent.put("candidate_value", nfc(item.candidateValue()));
                blindContent.put("evidence_ids", evidenceKeys);
                blindContent.put("source_domains", sourceDomains);
                blindDigest = canonicalizer.domainSeparatedDigest(BLIND_DIGEST_DOMAIN, blindContent);
                content.put("quorum_group_key", task.quorumGroupKey());
                content.put("candidate_slot", task.candidateSlot());
                content.put("source_domains", sourceDomains);
                content.put("blind_digest", blindDigest);
            }
            String contentDigest = canonicalizer.domainSeparatedDigest(CANDIDATE_DIGEST_DOMAIN, content);
            try {
                jdbcTemplate.update("""
                        insert into research_agent_candidate(
                            id, research_run_id, task_id, execution_id, idempotency_key, cell_key,
                            base_cell_version, plan_revision, entity_set_version, lease_epoch, fencing_token,
                            candidate_value, evidence_ids_json, confidence_score,
                            agent_completion_id, content_digest, research_agent_execution_id, confidence_score_ppm,
                            quorum_group_key, candidate_slot, source_domains_json, blind_digest
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, candidateId, runId, task.id(), executionId, item.candidateKey(), item.cellKey(),
                        item.baseCellVersion(), task.planRevision(), task.entitySetVersion(), task.leaseEpoch(),
                        task.fencingToken(), item.candidateValue(), Json.write(objectMapper, evidenceKeys),
                        legacyConfidence, completionId, contentDigest, executionId, item.confidencePpm(),
                        task.quorumGroupKey(), task.candidateSlot(),
                        task.candidateQuorum() == 2 ? Json.write(objectMapper, sourceDomains) : null, blindDigest);
            } catch (DataIntegrityViolationException exception) {
                throw new com.noteweave.common.BusinessException("RESEARCH_AGENT_COMPLETION_CANDIDATE_CONFLICT",
                        "Candidate stable key raced with different content");
            }
            result.add(new ResearchAgentCompletionCommitter.CandidatePersisted(
                    candidateId, item, boundEvidence, contentDigest));
            checkpoint.accept(ResearchAgentCompletionFaultInjector.Stage.AFTER_NTH_CANDIDATE, ++ordinal);
        }
        return List.copyOf(result);
    }

    private BigDecimal legacyScore(int ppm) {
        return BigDecimal.valueOf(ppm, 6).setScale(4, RoundingMode.HALF_UP);
    }

    private Map<String, Object> rowContent() {
        return new LinkedHashMap<>();
    }

    private String nfc(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }
}

package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Validates the originating Research Run before exposing a generated Source. */
@Service
public class ResearchGeneratedSourceReadGate {
    private final JdbcTemplate jdbc;
    private final ResearchContextV2Gate contextGate;
    private final ObjectMapper objectMapper;

    public ResearchGeneratedSourceReadGate(JdbcTemplate jdbc, ResearchContextV2Gate contextGate) {
        this(jdbc, contextGate, new ObjectMapper());
    }

    @Autowired
    public ResearchGeneratedSourceReadGate(JdbcTemplate jdbc, ResearchContextV2Gate contextGate,
                                           ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.contextGate = contextGate;
        this.objectMapper = objectMapper;
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

    public Set<String> readableSourceIds(String workspaceId, List<String> sourceIds) {
        List<String> ids = sourceIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) return Set.of();
        Object[] parameters = new Object[ids.size() + 1];
        parameters[0] = workspaceId;
        for (int i = 0; i < ids.size(); i++) parameters[i + 1] = ids.get(i);
        List<SourceOrigin> sources = jdbc.query("""
                select id, coalesce(generated_by, '') as generated_by,
                       coalesce(generated_ref_id, '') as generated_ref_id
                from source where workspace_id = ? and status = 'READY' and id in (%s)
                """.formatted(String.join(",", Collections.nCopies(ids.size(), "?"))),
                (rs, rowNum) -> new SourceOrigin(rs.getString(1), rs.getString(2), rs.getString(3)),
                parameters);
        LinkedHashSet<String> readable = new LinkedHashSet<>();
        for (SourceOrigin source : sources) {
            if (visible(workspaceId, source.generatedBy(), source.generatedRefId())) {
                readable.add(source.sourceId());
            }
        }
        return Set.copyOf(readable);
    }

    /**
     * 保存或导出研究报告前，校验报告依赖的来源仍然可读。
     *
     * <p>依赖清单来自两处稳定记录：research_run.source_scope_json 是运行时绑定的来源范围，
     * research_evidence_manifest_item 和 source_evidence 是报告实际引用的证据来源。
     * 外网证据的 source_id 形如 web-... 或 external:...，不在 source 表里，不作为删除判断对象。</p>
     */
    public void requireReportDependenciesReadable(String workspaceId, String researchRunId) {
        if (workspaceId == null || workspaceId.isBlank()
                || researchRunId == null || researchRunId.isBlank()) {
            return;
        }
        LinkedHashSet<String> declared = new LinkedHashSet<>(
                readSourceScopeIds(workspaceId, researchRunId));
        LinkedHashSet<String> cited = new LinkedHashSet<>(
                readCitedSourceIds(workspaceId, researchRunId));
        LinkedHashSet<String> candidates = new LinkedHashSet<>(declared);
        candidates.addAll(cited);
        if (candidates.isEmpty()) {
            return;
        }
        Map<String, String> statuses = loadSourceStatuses(workspaceId, candidates);
        Set<String> readable = declared.isEmpty()
                ? Set.of()
                : readableSourceIds(workspaceId, List.copyOf(declared));
        for (String sourceId : declared) {
            if (!"READY".equals(statuses.get(sourceId)) || !readable.contains(sourceId)) {
                throw revoked();
            }
        }
        for (String sourceId : cited) {
            String status = statuses.get(sourceId);
            // 外网证据没有 source 行，status 为 null 时不能当成已删除
            if (status != null && !"READY".equals(status)) {
                throw revoked();
            }
        }
    }

    private BusinessException revoked() {
        return new BusinessException("RESEARCH_SOURCE_REVOKED",
                "研究报告依赖的来源已删除或不可读，无法保存或导出", HttpStatus.CONFLICT);
    }

    private List<String> readSourceScopeIds(String workspaceId, String researchRunId) {
        String json = jdbc.query("""
                select source_scope_json from research_run
                where id = ? and workspace_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, researchRunId, workspaceId);
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> ids = objectMapper.readValue(json, new TypeReference<>() {});
            if (ids == null) return List.of();
            return ids.stream().filter(id -> id != null && !id.isBlank()).toList();
        } catch (JsonProcessingException ex) {
            throw new BusinessException("RESEARCH_SOURCE_SCOPE_PARSE_FAILED",
                    "研究资料范围解析失败", HttpStatus.CONFLICT);
        }
    }

    private List<String> readCitedSourceIds(String workspaceId, String researchRunId) {
        List<String> manifestIds = jdbc.query("""
                select distinct item.source_id
                from research_evidence_manifest m
                join research_evidence_manifest_item item on item.manifest_id = m.id
                where m.workspace_id = ? and m.research_run_id = ?
                  and item.source_id is not null and item.source_id <> ''
                """, (rs, rowNum) -> rs.getString(1), workspaceId, researchRunId);
        List<String> evidenceIds = jdbc.query("""
                select distinct source_id from source_evidence
                where research_run_id = ? and source_id is not null and source_id <> ''
                """, (rs, rowNum) -> rs.getString(1), researchRunId);
        LinkedHashSet<String> ids = new LinkedHashSet<>(manifestIds);
        ids.addAll(evidenceIds);
        return List.copyOf(ids);
    }

    private Map<String, String> loadSourceStatuses(String workspaceId, Set<String> sourceIds) {
        List<String> ids = List.copyOf(sourceIds);
        Object[] parameters = new Object[ids.size() + 1];
        parameters[0] = workspaceId;
        for (int i = 0; i < ids.size(); i++) parameters[i + 1] = ids.get(i);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, status from source
                where workspace_id = ? and id in (%s)
                """.formatted(String.join(",", Collections.nCopies(ids.size(), "?"))), parameters);
        Map<String, String> statuses = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            statuses.put(String.valueOf(row.get("id")), String.valueOf(row.get("status")));
        }
        return statuses;
    }

    private record Origin(String snapshotId, String replayAvailability) {}
    private record SourceOrigin(String sourceId, String generatedBy, String generatedRefId) {}
}

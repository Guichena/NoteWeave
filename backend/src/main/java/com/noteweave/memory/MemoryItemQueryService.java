package com.noteweave.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.security.CurrentUserProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

/** 记忆列表的只读查询：当前生效版本与待确认版本，附带晋升时的门控打分。 */
@Service
public class MemoryItemQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final CurrentUserProvider currentUserProvider;
    private final MemoryCandidatePolicy candidatePolicy;
    private MemorySourceReadGate sourceReadGate;

    /** MEM-06：分页默认值与上限，避免一次把整张列表拉进内存。 */
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 500;
    /** 兼容旧列表接口时每批装配条数：整体仍返回全部，但逐批装配、按批加载信号。 */
    private static final int LEGACY_BATCH_SIZE = 200;
    /** 信号 IN 查询每批上限，避免超长 IN 列表。 */
    private static final int SIGNAL_BATCH_SIZE = 500;

    public MemoryItemQueryService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WorkspaceAccessGuard workspaceAccessGuard,
            CurrentUserProvider currentUserProvider,
            MemoryCandidatePolicy candidatePolicy
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.currentUserProvider = currentUserProvider;
        this.candidatePolicy = candidatePolicy;
        this.sourceReadGate = new MemorySourceReadGate(jdbcTemplate, objectMapper);
    }

    public List<MemoryItemResponse> listItems(String workspaceId) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.MEMORY_REVIEW);
        String userId = currentUserProvider.requireUserId();
        List<MemoryItemResponse> items = loadAllItems(workspaceId, userId);
        // 来源被删除或不再可读时，只在读路径排除，不物理删除 memory_item。
        Map<String, String> exclusionReasons = sourceReadGate.exclusionReasons(
                workspaceId,
                items.stream().map(MemoryItemResponse::revisionId).toList());
        return items.stream()
                .filter(item -> !exclusionReasons.containsKey(item.revisionId()))
                .toList();
    }

    /**
     * MEM-06：keyset 分页。按 (候选状态分组, created_at desc, revision_id desc) 翻页，
     * 每页只装配这一页用到的信号与来源，避免「一次把全工作台信号读进内存」。
     * 读门排除会让一页返回的条目变少，但游标按原始顺序推进，不会漏项或重复。
     */
    public MemoryItemPageResponse pageItems(String workspaceId, String cursor, Integer limit) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.MEMORY_REVIEW);
        String userId = currentUserProvider.requireUserId();
        int pageSize = limit == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        MemoryItemCursor decoded = cursor == null || cursor.isBlank() ? null : MemoryItemCursor.decode(cursor);
        // 多取一条用来判断是否还有下一页
        List<MemoryItemResponse> raw = loadItemPage(workspaceId, userId, decoded, pageSize + 1);
        boolean hasMore = raw.size() > pageSize;
        List<MemoryItemResponse> page = hasMore ? raw.subList(0, pageSize) : raw;
        Map<String, String> exclusionReasons = sourceReadGate.exclusionReasons(
                workspaceId,
                page.stream().map(MemoryItemResponse::revisionId).toList());
        List<MemoryItemResponse> visible = page.stream()
                .filter(item -> !exclusionReasons.containsKey(item.revisionId()))
                .toList();
        String nextCursor = null;
        if (hasMore) {
            MemoryItemResponse last = page.get(page.size() - 1);
            nextCursor = new MemoryItemCursor(
                    rankOf(last), last.createdAt(), last.revisionId()).encode();
        }
        return new MemoryItemPageResponse(visible, nextCursor);
    }

    /** 兼容旧接口：整体返回，但逐页装配，信号只按页加载。 */
    private List<MemoryItemResponse> loadAllItems(String workspaceId, String userId) {
        List<MemoryItemResponse> items = new ArrayList<>();
        MemoryItemCursor cursor = null;
        while (true) {
            List<MemoryItemResponse> page = loadItemPage(workspaceId, userId, cursor, LEGACY_BATCH_SIZE);
            items.addAll(page);
            if (page.size() < LEGACY_BATCH_SIZE) {
                return items;
            }
            MemoryItemResponse last = page.get(page.size() - 1);
            cursor = new MemoryItemCursor(rankOf(last), last.createdAt(), last.revisionId());
        }
    }

    /** 与列表排序一致的分组序号：待确认（PROPOSED）在前。 */
    private static int rankOf(MemoryItemResponse item) {
        return "PROPOSED".equals(item.revisionStatus()) ? 0 : 1;
    }

    /**
     * MEM-05：列出被读路径排除的记忆及原因，供管理端排查「为什么召回不到」。
     * 原因码区分「暂不支持」（Wiki 引用，等 C1 端口补齐）与真实不可读（来源删除/未就绪/不可作依据）。
     */
    public List<MemoryReadGateExclusion> readGateExclusions(String workspaceId) {
        workspaceAccessGuard.requirePermission(workspaceId, WorkspacePermission.MEMORY_REVIEW);
        String userId = currentUserProvider.requireUserId();
        List<MemoryItemResponse> items = loadAllItems(workspaceId, userId);
        Map<String, String> reasons = sourceReadGate.exclusionReasons(
                workspaceId,
                items.stream().map(MemoryItemResponse::revisionId).toList());
        return items.stream()
                .filter(item -> reasons.containsKey(item.revisionId()))
                .map(item -> {
                    String reasonCode = reasons.get(item.revisionId());
                    return new MemoryReadGateExclusion(
                            item.memoryItemId(),
                            item.revisionId(),
                            item.displayText(),
                            item.itemStatus(),
                            reasonCode,
                            MemorySourceReadGate.reasonText(reasonCode));
                })
                .toList();
    }

    private List<MemoryItemResponse> loadItemPage(String workspaceId, String userId,
                                                  MemoryItemCursor cursor, int limit) {
        Map<String, SignalEvidence> signals = loadPageSignalEvidence(workspaceId, userId, cursor, limit);
        // 个人范围的记忆只对其所有者可见，与编译时的范围规则一致
        return jdbcTemplate.query("""
                select i.id as item_id, i.memory_scope, i.status as item_status, i.review_status,
                       i.utility_score, i.application_count, i.last_confirmed_at,
                       r.id as revision_id, r.status as revision_status, r.version_no, r.display_text,
                       r.provenance_type, r.normalized_value_json, r.created_at,
                       c.evidence_gate_status, c.marginal_utility_score, c.risk_score as candidate_risk_score,
                       c.scope_status, c.conflict_status as candidate_conflict_status, c.policy_version,
                       c.created_from_signal_ids_json, c.conflict_type, c.conflicting_item_id, c.conflicting_text,
                       c.slot_key as candidate_slot_key, i.semantic_slot_key,
                       i.evidence_alpha, i.evidence_beta, i.reinforcement_count, i.last_reinforced_at
                from memory_item i
                join memory_runtime_revision r on r.memory_item_id = i.id
                left join memory_candidate c
                  on r.provenance_type = 'MEMORY_CANDIDATE' and c.id = r.provenance_ref
                where i.workspace_id = ?
                  and i.status not in ('DELETED', 'SUPERSEDED')
                  and (i.memory_scope <> 'USER' or i.owner_user_id = ?)
                  and (r.status = 'PROPOSED' or (r.status = 'ACTIVE' and r.id = i.current_revision_id))
                  and (case when r.status = 'PROPOSED' then 0 else 1 end > ?
                       or (case when r.status = 'PROPOSED' then 0 else 1 end = ?
                           and (r.created_at < ? or (r.created_at = ? and r.id < ?))))
                order by case when r.status = 'PROPOSED' then 0 else 1 end, r.created_at desc, r.id
                limit ?
                """, (rs, rowNum) -> {
            JsonNode payload = readJson(rs.getString("normalized_value_json"));
            SignalEvidence evidence = firstSignal(rs.getString("created_from_signal_ids_json"), signals);
            String conflictStatus = firstText(
                    rs.getString("candidate_conflict_status"),
                    text(payload, "conflict_status"),
                    "NO_CONFLICT");
            return new MemoryItemResponse(
                    rs.getString("item_id"),
                    rs.getString("revision_id"),
                    rs.getString("memory_scope"),
                    rs.getString("item_status"),
                    rs.getString("review_status"),
                    rs.getString("revision_status"),
                    rs.getInt("version_no"),
                    rs.getString("display_text"),
                    firstText(text(payload, "candidate_type"), null, "PREFERENCE"),
                    stringList(payload == null ? null : payload.get("task_neighborhoods")),
                    rs.getString("provenance_type"),
                    rs.getDouble("utility_score"),
                    rs.getInt("application_count"),
                    conflictStatus,
                    rs.getString("evidence_gate_status") == null ? null : new MemoryGateResponse(
                            gateResult(rs.getString("evidence_gate_status"), rs.getDouble("marginal_utility_score"),
                                    rs.getDouble("candidate_risk_score"), rs.getString("scope_status"), conflictStatus),
                            evidence.sourceType(),
                            evidence.confidence(),
                            candidatePolicy.minimumEvidenceConfidence(),
                            rs.getDouble("marginal_utility_score"),
                            candidatePolicy.minimumAutoPromotionUtility(),
                            rs.getDouble("candidate_risk_score"),
                            candidatePolicy.reviewRequiredRisk(),
                            rs.getString("scope_status"),
                            conflictStatus,
                            rs.getString("policy_version"),
                            evidence.sourceRef()),
                    toInstant(rs.getTimestamp("last_confirmed_at")),
                    toInstant(rs.getTimestamp("created_at")),
                    displaySlot(rs.getString("semantic_slot_key"), rs.getString("candidate_slot_key"),
                            text(payload, "slot")),
                    MemoryConflictResponse.from(
                            firstText(rs.getString("conflict_type"), text(payload, "conflict_type"), ""),
                            firstText(rs.getString("conflicting_item_id"), text(payload, "conflicting_item_id"), ""),
                            firstText(rs.getString("conflicting_text"), text(payload, "conflicting_text"), ""),
                            conflictStatus),
                    MemoryConfidenceResponse.of(rs.getDouble("evidence_alpha"), rs.getDouble("evidence_beta")),
                    rs.getInt("reinforcement_count"),
                    toInstant(rs.getTimestamp("last_reinforced_at")));
        }, itemPageArguments(workspaceId, userId, cursor, limit));
    }

    /** 与 MemoryCandidateGate 的判定一致：全部条件满足才直接生效。 */
    private String gateResult(String evidenceGateStatus, double utility, double risk, String scopeStatus,
                              String conflictStatus) {
        boolean ready = "PASS".equals(evidenceGateStatus)
                && utility >= candidatePolicy.minimumAutoPromotionUtility()
                && risk < candidatePolicy.reviewRequiredRisk()
                && "VALID".equals(scopeStatus)
                && !"CONFLICTING_ACTIVE_MEMORY".equals(conflictStatus)
                && !"CONFLICT_CHECK_NOT_RUN".equals(conflictStatus);
        return ready ? "READY" : "NEEDS_REVIEW";
    }

    /**
     * MEM-06：只加载本页条目引用到的信号，并按批做 IN，避免全工作台信号一次性进内存
     * 与超长 IN 列表。预查询与主查询使用同一套过滤条件（两处必须同步修改）。
     */
    private Map<String, SignalEvidence> loadPageSignalEvidence(String workspaceId, String userId,
                                                               MemoryItemCursor cursor, int limit) {
        Set<String> signalIds = new LinkedHashSet<>();
        jdbcTemplate.query("""
                select c.created_from_signal_ids_json
                from memory_item i
                join memory_runtime_revision r on r.memory_item_id = i.id
                left join memory_candidate c
                  on r.provenance_type = 'MEMORY_CANDIDATE' and c.id = r.provenance_ref
                where i.workspace_id = ?
                  and i.status not in ('DELETED', 'SUPERSEDED')
                  and (i.memory_scope <> 'USER' or i.owner_user_id = ?)
                  and (r.status = 'PROPOSED' or (r.status = 'ACTIVE' and r.id = i.current_revision_id))
                  and (case when r.status = 'PROPOSED' then 0 else 1 end > ?
                       or (case when r.status = 'PROPOSED' then 0 else 1 end = ?
                           and (r.created_at < ? or (r.created_at = ? and r.id < ?))))
                order by case when r.status = 'PROPOSED' then 0 else 1 end, r.created_at desc, r.id
                limit ?
                """, (RowCallbackHandler) rs -> signalIds.addAll(
                        stringList(readJson(rs.getString("created_from_signal_ids_json")))),
                itemPageArguments(workspaceId, userId, cursor, limit));
        return loadSignalEvidence(workspaceId, signalIds);
    }

    private Map<String, SignalEvidence> loadSignalEvidence(String workspaceId, Set<String> signalIds) {
        Map<String, SignalEvidence> signals = new HashMap<>();
        List<String> ids = new ArrayList<>(signalIds);
        for (int start = 0; start < ids.size(); start += SIGNAL_BATCH_SIZE) {
            List<String> chunk = ids.subList(start, Math.min(ids.size(), start + SIGNAL_BATCH_SIZE));
            List<Object> arguments = new ArrayList<>();
            arguments.add(workspaceId);
            arguments.addAll(chunk);
            jdbcTemplate.query("select id, source_type, source_id, confidence_score from memory_signal"
                            + " where workspace_id = ? and id in (" + placeholders(chunk.size()) + ")",
                    (RowCallbackHandler) rs -> signals.put(rs.getString("id"),
                            new SignalEvidence(rs.getString("source_type"),
                                    rs.getDouble("confidence_score"), rs.getString("source_id"))),
                    arguments.toArray());
        }
        return signals;
    }

    /** 分页参数：rank=-1 表示第一页（0/1 之外的取值让 keyset 条件恒真）。 */
    private static Object[] itemPageArguments(String workspaceId, String userId,
                                              MemoryItemCursor cursor, int limit) {
        if (cursor == null) {
            return new Object[]{workspaceId, userId, -1, -1, Timestamp.from(Instant.EPOCH),
                    Timestamp.from(Instant.EPOCH), "", limit};
        }
        Timestamp createdAt = Timestamp.from(cursor.createdAt());
        return new Object[]{workspaceId, userId, cursor.rank(), cursor.rank(),
                createdAt, createdAt, cursor.revisionId(), limit};
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    /** MEM-06：keyset 游标，(分组序号, 创建时间, revision id) 编码成一个不透明字符串。 */
    record MemoryItemCursor(int rank, Instant createdAt, String revisionId) {

        String encode() {
            // 用 ISO-8601 保留纳秒精度：按毫秒截断会让同一毫秒内的多行在翻页时漏掉或重复。
            String raw = rank + "|" + createdAt + "|" + revisionId;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static MemoryItemCursor decode(String encoded) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                String[] parts = raw.split("\\|", 3);
                if (parts.length != 3) {
                    throw new IllegalArgumentException("malformed cursor");
                }
                return new MemoryItemCursor(Integer.parseInt(parts[0]),
                        Instant.parse(parts[1]), parts[2]);
            } catch (RuntimeException exception) {
                throw new com.noteweave.common.BusinessException(
                        "MEMORY_PAGE_CURSOR_INVALID", "记忆分页游标无效");
            }
        }
    }

    private SignalEvidence firstSignal(String signalIdsJson, Map<String, SignalEvidence> signals) {
        for (String signalId : stringList(readJson(signalIdsJson))) {
            SignalEvidence evidence = signals.get(signalId);
            if (evidence != null) {
                return evidence;
            }
        }
        return new SignalEvidence("", 0, null);
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) {
                    values.add(item.asText());
                }
            });
        }
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String displaySlot(String semanticSlot, String candidateSlot, String payloadSlot) {
        String fromColumn = MemorySlots.publicSlot(semanticSlot);
        if (!"unknown".equals(fromColumn)) {
            return fromColumn;
        }
        String fromCandidate = MemorySlots.publicSlot(candidateSlot);
        if (!"unknown".equals(fromCandidate)) {
            return fromCandidate;
        }
        return MemorySlots.publicSlot(payloadSlot);
    }

    private static String firstText(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : fallback;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record SignalEvidence(String sourceType, double confidence, String sourceRef) {
    }

    /** FB-05/C1：注入共享读门实例（携带 MaterialEligibilityPort）；直连构造回退内置实现。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSourceReadGate(MemorySourceReadGate sourceReadGate) {
        this.sourceReadGate = sourceReadGate;
    }
}

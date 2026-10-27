package com.noteweave.chat;

import com.noteweave.chat.NoteRetrievalService.ReadingWindow;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 检索水合器：所有窗口/片段装载 SQL 的唯一入口。
 *
 * <p>窗口装载是两阶段的：先按语义命中、词法命中与头部回退选出窗口身份（不取正文），
 * 再按优先级有界装载正文。语义/词法命中可到达任意深度的窗口，有限头部窗口只做回退；
 * 全局身份配额按来源轮转分配，不因来源 ID 顺序挤占其他来源。
 * 正文预算按 UTF-8 字节计数；跳过发生在行读出之后，限制的是保留内容，
 * 不宣称限制 JDBC 传输或数据库扫描。</p>
 */
@Component
public class RetrievalHydrator {

    private final JdbcTemplate jdbcTemplate;
    private final ResearchGeneratedSourceReadGate generatedSourceGate;

    public RetrievalHydrator(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null);
    }

    @Autowired
    public RetrievalHydrator(JdbcTemplate jdbcTemplate,
                             ResearchGeneratedSourceReadGate generatedSourceGate) {
        this.jdbcTemplate = jdbcTemplate;
        this.generatedSourceGate = generatedSourceGate;
    }

    public Map<String, PassageOwnership> hydratePassageOwnership(
            String workspaceId,
            List<String> chunkIds
    ) {
        List<String> ids = distinctIds(chunkIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Object> parameters = parameters(workspaceId, ids);
        List<PassageOwnership> rows = jdbcTemplate.query("""
                select c.id as chunk_id, c.source_id, c.source_snapshot_id,
                       coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id,
                       coalesce(c.location_info, '') as location_info
                from source_chunk c
                join source s
                  on s.id = c.source_id and s.workspace_id = c.workspace_id
                join source_snapshot ss
                  on ss.id = c.source_snapshot_id and ss.source_id = s.id
                where c.workspace_id = ? and c.id in (%s)
                  and s.status = 'READY'
                  and s.index_status = 'INDEXED'
                  and ss.index_status = 'INDEXED'
                  and ss.version_no = (
                      select max(current_ss.version_no)
                      from source_snapshot current_ss
                      where current_ss.source_id = s.id
                        and current_ss.index_status = 'INDEXED'
                  )
                  and c.projection_status = 'PROJECTED'
                """.formatted(placeholders(ids.size())), (rs, rowNum) -> new PassageOwnership(
                rs.getString("chunk_id"),
                rs.getString("source_id"),
                rs.getString("source_snapshot_id"),
                rs.getString("generated_by"),
                rs.getString("generated_ref_id"),
                rs.getString("location_info")
        ), parameters.toArray());
        Map<String, PassageOwnership> byChunkId = new LinkedHashMap<>();
        if (generatedSourceGate == null) {
            rows.forEach(row -> byChunkId.put(row.chunkId(), row));
        } else {
            // NR03：归属补全改走 C1 统一资格批量判定（传递依赖闭包 + 合规撤销），
            // 不再逐行只查 research origin 脱敏——生成来源引用的上游被撤销时整源排除
            Set<String> readable = generatedSourceGate.readableSourceIds(
                    workspaceId, rows.stream().map(PassageOwnership::sourceId).distinct().toList());
            rows.stream().filter(row -> readable.contains(row.sourceId()))
                    .forEach(row -> byChunkId.put(row.chunkId(), row));
        }
        return Map.copyOf(byChunkId);
    }

    public Set<String> readableSourceIds(String workspaceId, List<String> sourceIds) {
        List<String> ids = distinctIds(sourceIds);
        return generatedSourceGate == null ? Set.copyOf(ids)
                : generatedSourceGate.readableSourceIds(workspaceId, ids);
    }

    public Map<String, List<AdjacentPassage>> hydrateAdjacentPassages(
            String workspaceId,
            List<String> anchorChunkIds
    ) {
        List<String> ids = distinctIds(anchorChunkIds);
        if (ids.isEmpty()) return Map.of();
        if (generatedSourceGate != null) {
            Set<String> readableAnchors = hydratePassageOwnership(workspaceId, ids).keySet();
            ids = ids.stream().filter(readableAnchors::contains).toList();
            if (ids.isEmpty()) return Map.of();
        }
        List<Object> parameters = parameters(workspaceId, ids);
        List<AdjacentPassage> rows = jdbcTemplate.query("""
                select anchor.id as anchor_chunk_id, anchor.chunk_no as anchor_chunk_no,
                       neighbor.id as chunk_id, neighbor.chunk_no, coalesce(neighbor.heading, '') as heading,
                       neighbor.content
                from source_chunk anchor
                join source s on s.id = anchor.source_id and s.workspace_id = anchor.workspace_id
                join source_snapshot ss on ss.id = anchor.source_snapshot_id and ss.source_id = s.id
                join source_chunk neighbor on neighbor.workspace_id = anchor.workspace_id
                  and neighbor.source_id = anchor.source_id
                  and neighbor.source_snapshot_id = anchor.source_snapshot_id
                  and neighbor.chunk_no between anchor.chunk_no - 1 and anchor.chunk_no + 1
                  and neighbor.id <> anchor.id
                where anchor.workspace_id = ? and anchor.id in (%s)
                  and s.status = 'READY' and s.index_status = 'INDEXED'
                  and ss.index_status = 'INDEXED'
                  and ss.version_no = (
                    select max(current_ss.version_no) from source_snapshot current_ss
                    where current_ss.source_id = s.id and current_ss.index_status = 'INDEXED')
                  and anchor.projection_status = 'PROJECTED'
                  and neighbor.projection_status = 'PROJECTED'
                order by anchor.id, neighbor.chunk_no
                """.formatted(placeholders(ids.size())), (rs, rowNum) -> new AdjacentPassage(
                rs.getString("anchor_chunk_id"), rs.getInt("anchor_chunk_no"),
                rs.getString("chunk_id"), rs.getInt("chunk_no"), rs.getString("heading"),
                rs.getString("content")), parameters.toArray());
        Map<String, List<AdjacentPassage>> result = new LinkedHashMap<>();
        for (AdjacentPassage row : rows) {
            result.computeIfAbsent(row.anchorChunkId(), ignored -> new ArrayList<>()).add(row);
        }
        Map<String, List<AdjacentPassage>> immutable = new LinkedHashMap<>();
        result.forEach((id, passages) -> immutable.put(id, List.copyOf(passages)));
        return Map.copyOf(immutable);
    }

    public Map<String, List<ReadingWindow>> hydrateNoteWindows(
            String workspaceId,
            List<String> sourceIds
    ) {
        return hydrateNoteWindows(workspaceId, sourceIds, WindowLoadBudget.defaults());
    }

    /**
     * 兼容入口：只按头部回退选窗口身份并有界装载正文。
     * 阅读检索请使用 {@link #loadWindowIndex} + {@link #hydrateWindowBodies} 两阶段接口，
     * 以保证语义/词法命中可到达文末窗口。
     */
    public Map<String, List<ReadingWindow>> hydrateNoteWindows(
            String workspaceId,
            List<String> sourceIds,
            WindowLoadBudget budget
    ) {
        List<String> ids = distinctIds(sourceIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        WindowLoadBudget effectiveBudget = budget == null ? WindowLoadBudget.defaults() : budget;
        WindowIndexResult index = loadWindowIndex(
                workspaceId, ids, List.of(), List.of(),
                new WindowIndexBudget(effectiveBudget.perSourceLimit(), effectiveBudget.totalLimit(),
                        0, effectiveBudget.perSourceLimit(), 0));
        if (index.windows().isEmpty()) {
            lastWindowLoadBudget = new WindowLoadUsage(0, 0, false, 0, 0,
                    index.semanticAnchorCount(), index.lexicalAnchorCount(), index.headFallbackCount());
            return Map.of();
        }
        BodyLoadResult bodies = hydrateWindowBodies(workspaceId, index.windows(),
                new WindowBodyBudget(effectiveBudget.maxBytes(), DEFAULT_SINGLE_WINDOW_MAX_BYTES,
                        effectiveBudget.totalLimit()));
        Map<String, List<ReadingWindow>> bySourceId = new LinkedHashMap<>();
        for (WindowIdentity identity : index.windows()) {
            bySourceId.computeIfAbsent(identity.sourceId(), ignored -> new ArrayList<>())
                    .add(identity.toReadingWindow(bodies.contentById().getOrDefault(identity.id(), "")));
        }
        Map<String, List<ReadingWindow>> immutable = new LinkedHashMap<>();
        bySourceId.forEach((sourceId, windows) -> immutable.put(sourceId, List.copyOf(windows)));
        return Map.copyOf(immutable);
    }

    /** 单窗口正文的最大 UTF-8 字节数；超过按超限跳过并计数。 */
    static final int DEFAULT_SINGLE_WINDOW_MAX_BYTES = 64 * 1024;

    /** 窗口身份预算：单来源身份数、全局身份数、词法命中单来源数、头部回退单来源数与词法词数上限。 */
    public record WindowIndexBudget(
            int perSourceIdentityLimit,
            int totalIdentityLimit,
            int lexicalPerSourceLimit,
            int headFallbackPerSource,
            int maxLexicalTerms
    ) {
        public static WindowIndexBudget defaults() {
            return new WindowIndexBudget(24, 384, 12, 4, 6);
        }
    }

    /** 正文装载预算：总字节、单窗口字节与窗口数上限，均按 UTF-8 实际字节数计。 */
    public record WindowBodyBudget(int maxTotalBytes, int maxSingleWindowBytes, int maxWindowCount) {
        public static WindowBodyBudget defaults() {
            return new WindowBodyBudget(
                    com.noteweave.retrieval.NoteRetrievalStrategyProfile.WINDOW_BYTES_MAX,
                    DEFAULT_SINGLE_WINDOW_MAX_BYTES,
                    96);
        }
    }

    /** 窗口身份：除正文外的全部字段；anchorKind 记录进入候选池的原因。 */
    public record WindowIdentity(
            String id,
            String sourceId,
            String sourceSnapshotId,
            String chunkId,
            int chunkNo,
            String heading,
            String title,
            String generatedBy,
            String generatedRefId,
            int windowNo,
            String locationInfo,
            String anchorKind
    ) {
        public ReadingWindow toReadingWindow(String content) {
            // ReadingWindow 首字段是 chunkId（基线语义）：证据 passageId 与语义分都按 chunk 身份匹配；
            // source_window 行 id 只在本类内部用于正文装载，不外泄
            return new ReadingWindow(chunkId, sourceId, sourceSnapshotId, chunkNo, heading, title,
                    generatedBy, generatedRefId, windowNo, content, locationInfo, 0,
                    "candidate-window", windowNo, "candidate-pool");
        }
    }

    /** 身份选择结果与各通道命中计数；观测与配额说明用。 */
    public record WindowIndexResult(
            List<WindowIdentity> windows,
            int semanticAnchorCount,
            int lexicalAnchorCount,
            int headFallbackCount
    ) {
    }

    /** 正文装载结果：窗口 ID 到正文的映射。 */
    public record BodyLoadResult(Map<String, String> contentById) {
        public BodyLoadResult {
            contentById = contentById == null ? Map.of() : Map.copyOf(contentById);
        }
    }

    /**
     * 阶段一：选出窗口身份，不取正文。
     *
     * <p>语义命中 chunk 的全部窗口（含同 chunk 邻接窗口）优先；词法命中按查询词在
     * heading/content/location 上做有界 LIKE 预筛；头部窗口只做无信号回退。
     * 三路身份按来源公平配额与全局轮转合并，来源顺序遵循输入顺序而非字典序。</p>
     */
    public WindowIndexResult loadWindowIndex(
            String workspaceId,
            List<String> sourceIds,
            List<String> semanticChunkIds,
            List<String> lexicalTerms,
            WindowIndexBudget budget
    ) {
        List<String> ids = distinctIds(sourceIds);
        if (ids.isEmpty()) {
            return new WindowIndexResult(List.of(), 0, 0, 0);
        }
        WindowIndexBudget effectiveBudget = budget == null ? WindowIndexBudget.defaults() : budget;
        Map<String, WindowIdentity> byId = new LinkedHashMap<>();
        int semanticCount = 0;
        if (semanticChunkIds != null && !semanticChunkIds.isEmpty()) {
            for (WindowIdentity identity : querySemanticIdentities(workspaceId,
                    distinctIds(semanticChunkIds), 512)) {
                if (byId.putIfAbsent(identity.id(), identity) == null) {
                    semanticCount++;
                }
            }
        }
        int lexicalCount = 0;
        List<String> terms = boundedTerms(lexicalTerms, effectiveBudget.maxLexicalTerms());
        if (!terms.isEmpty() && effectiveBudget.lexicalPerSourceLimit() > 0) {
            for (WindowIdentity identity : queryRankedIdentities(workspaceId, ids, terms,
                    effectiveBudget.lexicalPerSourceLimit(), "lexical-anchor")) {
                if (byId.putIfAbsent(identity.id(), identity) == null) {
                    lexicalCount++;
                }
            }
        }
        int headCount = 0;
        if (effectiveBudget.headFallbackPerSource() > 0) {
            for (WindowIdentity identity : queryRankedIdentities(workspaceId, ids, null,
                    effectiveBudget.headFallbackPerSource(), "head-fallback")) {
                if (byId.putIfAbsent(identity.id(), identity) == null) {
                    headCount++;
                }
            }
        }
        List<WindowIdentity> fair = fairAllocate(new ArrayList<>(byId.values()), ids,
                effectiveBudget.perSourceIdentityLimit(), effectiveBudget.totalIdentityLimit());
        return new WindowIndexResult(List.copyOf(fair), semanticCount, lexicalCount, headCount);
    }

    /**
     * 阶段二：按身份优先级有界装载正文。
     *
     * <p>窗口按入参顺序分批查询，预算先消耗在优先级高的窗口上；
     * 单窗口超限或预算耗尽的窗口被跳过并计数，不截断正文冒充完整内容。</p>
     */
    public BodyLoadResult hydrateWindowBodies(
            String workspaceId,
            List<WindowIdentity> windows,
            WindowBodyBudget budget
    ) {
        if (windows == null || windows.isEmpty()) {
            lastWindowLoadBudget = new WindowLoadUsage(0, 0, false, 0, 0, 0, 0, 0);
            return new BodyLoadResult(Map.of());
        }
        WindowBodyBudget effectiveBudget = budget == null ? WindowBodyBudget.defaults() : budget;
        Map<String, String> contentById = new LinkedHashMap<>();
        long loadedBytes = 0;
        int loadedWindows = 0;
        boolean byteTruncated = false;
        int droppedOversized = 0;
        int droppedByBudget = 0;
        List<String> orderedIds = windows.stream().map(WindowIdentity::id).distinct().toList();
        for (List<String> batch : partition(orderedIds, 100)) {
            if (loadedWindows >= effectiveBudget.maxWindowCount()) {
                droppedByBudget += batch.size();
                continue;
            }
            List<Object> parameters = new ArrayList<>();
            parameters.add(workspaceId);
            parameters.addAll(batch);
            List<String[]> rows = new ArrayList<>();
            jdbcTemplate.query("""
                    select w.id, w.content
                    from source_window w
                    join source_chunk c on c.id = w.source_chunk_id
                    where c.workspace_id = ? and w.id in (%s)
                    """.formatted(placeholders(batch.size())), rs -> {
                // RowCallbackHandler 由 Spring 逐行回调，这里不调用 rs.next()
                rows.add(new String[] {rs.getString("id"), rs.getString("content")});
            }, parameters.toArray());
            for (String[] row : rows) {
                String content = row[1];
                int contentBytes = utf8Bytes(content);
                if (contentBytes > effectiveBudget.maxSingleWindowBytes()) {
                    droppedOversized++;
                    continue;
                }
                if (loadedBytes + contentBytes > effectiveBudget.maxTotalBytes()
                        || loadedWindows >= effectiveBudget.maxWindowCount()) {
                    byteTruncated = true;
                    droppedByBudget++;
                    continue;
                }
                loadedBytes += contentBytes;
                loadedWindows++;
                contentById.put(row[0], content == null ? "" : content);
            }
        }
        lastWindowLoadBudget = new WindowLoadUsage(loadedBytes, loadedWindows, byteTruncated,
                droppedOversized, droppedByBudget, 0, 0, 0);
        return new BodyLoadResult(contentById);
    }

    /** 语义命中路径：命中 chunk 的全部窗口（含同 chunk 邻接窗口），受总条数限制。 */
    private List<WindowIdentity> querySemanticIdentities(
            String workspaceId,
            List<String> chunkIds,
            int limit
    ) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        parameters.addAll(chunkIds);
        parameters.add(limit);
        List<WindowIdentity> rows = new ArrayList<>();
        jdbcTemplate.query("""
                select w.id, c.id as chunk_id, c.source_id, c.source_snapshot_id, c.chunk_no,
                       coalesce(c.heading, '') as heading, s.title,
                       coalesce(s.generated_by, '') as generated_by,
                       coalesce(s.generated_ref_id, '') as generated_ref_id,
                       w.window_no, coalesce(w.location_info, '') as location_info
                from source_window w
                join source_chunk c
                  on c.id = w.source_chunk_id
                join source s
                  on s.id = c.source_id and s.workspace_id = c.workspace_id
                join source_snapshot ss
                  on ss.id = c.source_snapshot_id and ss.source_id = s.id
                where c.workspace_id = ? and c.id in (%s)
                  and s.status = 'READY'
                  and s.index_status = 'INDEXED'
                  and ss.index_status = 'INDEXED'
                  and ss.version_no = (
                      select max(current_ss.version_no)
                      from source_snapshot current_ss
                      where current_ss.source_id = s.id
                        and current_ss.index_status = 'INDEXED'
                  )
                  and c.projection_status = 'PROJECTED'
                order by c.source_id asc, c.chunk_no asc, w.window_no asc
                limit ?
                """.formatted(placeholders(chunkIds.size())), rs -> {
            // RowCallbackHandler 由 Spring 逐行回调，这里不调用 rs.next()
            rows.add(new WindowIdentity(
                    rs.getString("id"),
                    rs.getString("source_id"),
                    rs.getString("source_snapshot_id"),
                    rs.getString("chunk_id"),
                    rs.getInt("chunk_no"),
                    rs.getString("heading"),
                    rs.getString("title"),
                    rs.getString("generated_by"),
                    rs.getString("generated_ref_id"),
                    rs.getInt("window_no"),
                    rs.getString("location_info"),
                    "semantic-anchor"));
        }, parameters.toArray());
        return filterGenerated(workspaceId, rows);
    }

    /** 按来源配额的排名路径：词法命中（可选）或头部回退，避免单一来源耗尽全局名额。 */
    private List<WindowIdentity> queryRankedIdentities(
            String workspaceId,
            List<String> sourceIds,
            List<String> lexicalTerms,
            int perSourceLimit,
            String anchorKind
    ) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        parameters.addAll(sourceIds);
        StringBuilder lexicalFilter = new StringBuilder();
        if (lexicalTerms != null && !lexicalTerms.isEmpty()) {
            lexicalFilter.append(" and (");
            for (int i = 0; i < lexicalTerms.size(); i++) {
                if (i > 0) {
                    lexicalFilter.append(" or ");
                }
                lexicalFilter.append("(c0.heading like ? escape '\\' or w0.content like ? escape '\\' "
                        + "or coalesce(w0.location_info, '') like ? escape '\\')");
            }
            lexicalFilter.append(")");
            for (String term : lexicalTerms) {
                String pattern = "%" + escapeLike(term) + "%";
                parameters.add(pattern);
                parameters.add(pattern);
                parameters.add(pattern);
            }
        }
        parameters.add(perSourceLimit);
        List<WindowIdentity> rows = new ArrayList<>();
        jdbcTemplate.query("""
                select ranked.id, ranked.chunk_id, ranked.source_id, ranked.source_snapshot_id,
                       ranked.chunk_no, ranked.heading, ranked.title,
                       ranked.generated_by, ranked.generated_ref_id,
                       ranked.window_no, ranked.location_info
                from (
                    select w0.id, c0.id as chunk_id, c0.source_id, c0.source_snapshot_id, c0.chunk_no,
                           coalesce(c0.heading, '') as heading, s0.title,
                           coalesce(s0.generated_by, '') as generated_by,
                           coalesce(s0.generated_ref_id, '') as generated_ref_id,
                           w0.window_no, coalesce(w0.location_info, '') as location_info,
                           row_number() over (
                               partition by c0.source_id
                               order by c0.chunk_no asc, w0.window_no asc
                           ) as source_rank
                    from source_window w0
                    join source_chunk c0 on c0.id = w0.source_chunk_id
                    join source s0 on s0.id = c0.source_id and s0.workspace_id = c0.workspace_id
                    join source_snapshot ss0 on ss0.id = c0.source_snapshot_id and ss0.source_id = s0.id
                    where c0.workspace_id = ? and c0.source_id in (%s)
                      and s0.status = 'READY'
                      and s0.index_status = 'INDEXED'
                      and ss0.index_status = 'INDEXED'
                      and ss0.version_no = (
                          select max(current_ss.version_no)
                          from source_snapshot current_ss
                          where current_ss.source_id = s0.id
                            and current_ss.index_status = 'INDEXED'
                      )
                      and c0.projection_status = 'PROJECTED'%s
                ) ranked
                where ranked.source_rank <= ?
                order by ranked.source_id asc, ranked.chunk_no asc, ranked.window_no asc
                """.formatted(placeholders(sourceIds.size()), lexicalFilter), rs -> {
            rows.add(new WindowIdentity(
                    rs.getString("id"),
                    rs.getString("source_id"),
                    rs.getString("source_snapshot_id"),
                    rs.getString("chunk_id"),
                    rs.getInt("chunk_no"),
                    rs.getString("heading"),
                    rs.getString("title"),
                    rs.getString("generated_by"),
                    rs.getString("generated_ref_id"),
                    rs.getInt("window_no"),
                    rs.getString("location_info"),
                    anchorKind));
        }, parameters.toArray());
        return filterGenerated(workspaceId, rows);
    }

    private List<WindowIdentity> filterGenerated(String workspaceId, List<WindowIdentity> rows) {
        if (generatedSourceGate == null) {
            return rows;
        }
        Map<String, Boolean> visibilityByOrigin = new LinkedHashMap<>();
        List<WindowIdentity> visible = new ArrayList<>();
        for (WindowIdentity row : rows) {
            String key = row.generatedBy() + '\u0000' + row.generatedRefId();
            boolean visibleOrigin = visibilityByOrigin.computeIfAbsent(key,
                    ignored -> generatedSourceGate.visible(workspaceId, row.generatedBy(), row.generatedRefId()));
            if (visibleOrigin) {
                visible.add(row);
            }
        }
        return visible;
    }

    /** 来源间公平配额：单来源上限 + 按输入来源顺序轮转消耗全局配额。 */
    private List<WindowIdentity> fairAllocate(
            List<WindowIdentity> identities,
            List<String> sourceOrder,
            int perSourceLimit,
            int totalLimit
    ) {
        if (identities.isEmpty() || perSourceLimit <= 0 || totalLimit <= 0) {
            return List.of();
        }
        Map<String, Integer> orderIndex = new LinkedHashMap<>();
        for (int i = 0; i < sourceOrder.size(); i++) {
            orderIndex.put(sourceOrder.get(i), i);
        }
        Map<String, List<WindowIdentity>> bySource = new LinkedHashMap<>();
        for (String sourceId : sourceOrder) {
            bySource.put(sourceId, new ArrayList<>());
        }
        for (WindowIdentity identity : identities) {
            bySource.computeIfAbsent(identity.sourceId(), ignored -> new ArrayList<>()).add(identity);
        }
        Map<String, Integer> priority = Map.of(
                "semantic-anchor", 3, "lexical-anchor", 2, "head-fallback", 1);
        for (List<WindowIdentity> windows : bySource.values()) {
            windows.sort((left, right) -> {
                int byPriority = Integer.compare(priority.getOrDefault(right.anchorKind(), 0),
                        priority.getOrDefault(left.anchorKind(), 0));
                if (byPriority != 0) return byPriority;
                int byChunk = Integer.compare(left.chunkNo(), right.chunkNo());
                if (byChunk != 0) return byChunk;
                return Integer.compare(left.windowNo(), right.windowNo());
            });
        }
        List<WindowIdentity> allocated = new ArrayList<>();
        int[] cursor = new int[bySource.size()];
        List<List<WindowIdentity>> pools = new ArrayList<>(bySource.values());
        boolean progressed = true;
        while (allocated.size() < totalLimit && progressed) {
            progressed = false;
            for (int i = 0; i < pools.size() && allocated.size() < totalLimit; i++) {
                if (cursor[i] < Math.min(pools.get(i).size(), perSourceLimit)) {
                    allocated.add(pools.get(i).get(cursor[i]));
                    cursor[i]++;
                    progressed = true;
                }
            }
        }
        allocated.sort((left, right) -> {
            int leftOrder = orderIndex.getOrDefault(left.sourceId(), Integer.MAX_VALUE);
            int rightOrder = orderIndex.getOrDefault(right.sourceId(), Integer.MAX_VALUE);
            int byOrder = Integer.compare(leftOrder, rightOrder);
            if (byOrder != 0) return byOrder;
            int byChunk = Integer.compare(left.chunkNo(), right.chunkNo());
            if (byChunk != 0) return byChunk;
            return Integer.compare(left.windowNo(), right.windowNo());
        });
        return allocated;
    }

    private List<String> boundedTerms(List<String> lexicalTerms, int maxTerms) {
        if (lexicalTerms == null || lexicalTerms.isEmpty() || maxTerms <= 0) {
            return List.of();
        }
        LinkedHashSet<String> bounded = new LinkedHashSet<>();
        for (String term : lexicalTerms) {
            if (term == null || term.length() < 2) {
                continue;
            }
            bounded.add(term);
            if (bounded.size() >= maxTerms) {
                break;
            }
        }
        return List.copyOf(bounded);
    }

    private String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private int utf8Bytes(String content) {
        return content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
    }

    private static List<List<String>> partition(List<String> ids, int size) {
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += size) {
            batches.add(ids.subList(i, Math.min(i + size, ids.size())));
        }
        return batches;
    }

    private volatile WindowLoadUsage lastWindowLoadBudget = WindowLoadUsage.NONE;

    /** 最近一次窗口加载的使用情况；观测用，不参与资格判断。 */
    public WindowLoadUsage lastWindowLoadUsage() {
        WindowLoadUsage usage = lastWindowLoadBudget;
        return usage == null ? WindowLoadUsage.NONE : usage;
    }

    /** 窗口加载预算：单来源条数、全局条数与字节上限。 */
    public record WindowLoadBudget(int perSourceLimit, int totalLimit, int maxBytes) {
        public static WindowLoadBudget defaults() {
            return new WindowLoadBudget(
                    com.noteweave.retrieval.NoteRetrievalStrategyProfile.WINDOW_CANDIDATES_PER_SOURCE,
                    com.noteweave.retrieval.NoteRetrievalStrategyProfile.WINDOW_CANDIDATES_TOTAL,
                    com.noteweave.retrieval.NoteRetrievalStrategyProfile.WINDOW_BYTES_MAX);
        }
    }

    /** 窗口装载使用情况；字节按 UTF-8 计数，丢弃窗口分别按超限与预算耗尽计数。 */
    public record WindowLoadUsage(
            long loadedBytes,
            int loadedWindows,
            boolean byteTruncated,
            int droppedOversized,
            int droppedByBudget,
            int semanticAnchorCount,
            int lexicalAnchorCount,
            int headFallbackCount
    ) {
        public static final WindowLoadUsage NONE =
                new WindowLoadUsage(0, 0, false, 0, 0, 0, 0, 0);

        public WindowLoadUsage(long loadedBytes, int loadedWindows, boolean byteTruncated) {
            this(loadedBytes, loadedWindows, byteTruncated, 0, 0, 0, 0, 0);
        }
    }

    public Map<String, NoteSourceStats> hydrateNoteSourceStats(
            String workspaceId,
            List<String> sourceIds
    ) {
        List<String> ids = distinctIds(sourceIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Object> parameters = parameters(workspaceId, ids);
        List<NoteSourceStats> rows = jdbcTemplate.query("""
                select s.id,
                       coalesce(s.parse_status, 'UNKNOWN') as parse_status,
                       coalesce(s.index_status, 'UNKNOWN') as index_status,
                       count(w.id) as window_count
                from source s
                left join source_snapshot ss
                  on ss.source_id = s.id
                 and ss.index_status = 'INDEXED'
                 and ss.version_no = (
                     select max(current_ss.version_no)
                     from source_snapshot current_ss
                     where current_ss.source_id = s.id
                       and current_ss.index_status = 'INDEXED'
                 )
                left join source_chunk c
                  on c.source_id = s.id and c.workspace_id = s.workspace_id
                 and c.source_snapshot_id = ss.id
                 and c.projection_status = 'PROJECTED'
                left join source_window w on w.source_chunk_id = c.id
                where s.workspace_id = ? and s.id in (%s)
                group by s.id, s.parse_status, s.index_status
                """.formatted(placeholders(ids.size())), (rs, rowNum) -> new NoteSourceStats(
                rs.getString("id"),
                rs.getString("parse_status"),
                rs.getString("index_status"),
                rs.getInt("window_count")
        ), parameters.toArray());
        Map<String, NoteSourceStats> bySourceId = new LinkedHashMap<>();
        rows.forEach(row -> bySourceId.put(row.sourceId(), row));
        return Map.copyOf(bySourceId);
    }

    private List<String> distinctIds(List<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        sourceIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .forEach(ids::add);
        return List.copyOf(ids);
    }

    private List<Object> parameters(String workspaceId, List<String> ids) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        parameters.addAll(ids);
        return parameters;
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    /** 片段归属与定位；locationInfo 来自切片阶段写入的页码、时间与章节，旧数据为空。 */
    public record PassageOwnership(
            String chunkId,
            String sourceId,
            String sourceSnapshotId,
            String generatedBy,
            String generatedRefId,
            String locationInfo
    ) {
        public PassageOwnership(String chunkId, String sourceId, String sourceSnapshotId,
                                String generatedBy, String generatedRefId) {
            this(chunkId, sourceId, sourceSnapshotId, generatedBy, generatedRefId, "");
        }

        public String locationOr(String fallback) {
            return locationInfo == null || locationInfo.isBlank() ? fallback : locationInfo;
        }
    }

    public record AdjacentPassage(
            String anchorChunkId,
            int anchorChunkNo,
            String chunkId,
            int chunkNo,
            String heading,
            String content
    ) {
    }

    public record NoteSourceStats(
            String sourceId,
            String parseStatus,
            String indexStatus,
            int windowCount
    ) {
        public static NoteSourceStats unknown(String sourceId) {
            return new NoteSourceStats(sourceId, "UNKNOWN", "UNKNOWN", 0);
        }
    }
}

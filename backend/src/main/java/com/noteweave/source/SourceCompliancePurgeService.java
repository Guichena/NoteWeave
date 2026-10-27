package com.noteweave.source;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 合规删除（COMPLIANCE）的正文清理。
 *
 * <p>资料删除此前只改 {@code source} 与 {@code source_snapshot} 的状态，
 * {@code source_chunk}、{@code source_window} 里的正文以及 {@code citation.quote_text}
 * 仍留在业务库，合规删除承诺的正文清除并未生效。这里补齐四件事：</p>
 *
 * <ol>
 *   <li>抹掉该资料的引用摘录，避免引用行继续携带正文片段；</li>
 *   <li>删除分块窗口行；</li>
 *   <li>物理删除没有被引用指向的分块；</li>
 *   <li>清空仍被引用分块的正文、标题、上下文与结构元数据，并清空资料摘要与元数据。</li>
 * </ol>
 *
 * <p>为什么保留被引用的分块行：{@code citation.source_chunk_id} 上有外键，
 * 物理删除会破坏引用完整性。保留空壳行既能保证库内没有可读正文，也不影响引用行继续承担审计职责。
 * 清理全部使用幂等 SQL，可以重复执行，用于回补历史已删除资料。</p>
 */
@Service
public class SourceCompliancePurgeService {

    private static final Logger log = LoggerFactory.getLogger(SourceCompliancePurgeService.class);
    private static final int DEFAULT_BATCH_LIMIT = 20;

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;

    public SourceCompliancePurgeService(
            JdbcTemplate jdbcTemplate,
            MeterRegistry meterRegistry,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = transactionTemplate;
    }

    /** 合规删除事务内调用：清空该资料的正文、引用摘录与摘要元数据。 */
    @Transactional
    public PurgeReport purge(String workspaceId, String sourceId) {
        return purgeContent(workspaceId, sourceId);
    }

    /**
     * 回补历史数据：扫描仍是 COMPLIANCE 删除、但库内还留着正文或摘要的资料，逐条清理。
     * 幂等，可重复执行；{@code dryRun} 只返回待清理数量，不写库。
     */
    public int purgeResidual(boolean dryRun, int limit) {
        List<ResidualSource> residual = findResidual(limit);
        if (dryRun) {
            return residual.size();
        }
        int purged = 0;
        for (ResidualSource source : residual) {
            try {
                transactionTemplate.executeWithoutResult(status ->
                        purgeContent(source.workspaceId(), source.sourceId()));
                purged++;
            } catch (RuntimeException ex) {
                // 单条失败不影响其余资料；下一轮扫描会再次拾取
                log.warn("合规删除正文清理失败，等待下一轮重试: workspaceId={}, sourceId={}",
                        source.workspaceId(), source.sourceId(), ex);
                meterRegistry.counter("noteweave.source.compliance.purge", "result", "failed")
                        .increment();
            }
        }
        return purged;
    }

    /** 待回补的资料清单：已按 COMPLIANCE 删除，但正文或摘要仍有残留。 */
    public List<ResidualSource> findResidual(int limit) {
        return jdbcTemplate.query("""
                select s.workspace_id, s.id
                from source s
                where s.status = 'DELETED'
                  and s.delete_reason = 'COMPLIANCE'
                  and (
                      s.summary is not null
                      or s.metadata_json is not null
                      or exists (
                          select 1 from source_chunk c
                          where c.source_id = s.id and c.content <> ''
                      )
                  )
                order by s.updated_at asc, s.id asc
                limit ?
                """, (rs, rowNum) -> new ResidualSource(
                rs.getString("workspace_id"), rs.getString("id")), limit);
    }

    /** 周期回补：历史删除发生在本次修复之前，删除事务不会为它们补跑清理。 */
    @Scheduled(fixedDelayString = "${noteweave.source.compliance-purge-delay-ms:600000}")
    public void purgeResidualScheduled() {
        int purged = purgeResidual(false, DEFAULT_BATCH_LIMIT);
        if (purged > 0) {
            log.info("合规删除正文回补完成 {} 条", purged);
        }
    }

    private PurgeReport purgeContent(String workspaceId, String sourceId) {
        int citationsRedacted = jdbcTemplate.update("""
                update citation
                set quote_text = ''
                where workspace_id = ? and source_id = ? and quote_text <> ''
                """, workspaceId, sourceId);
        int windowsDeleted = jdbcTemplate.update("""
                delete from source_window
                where source_chunk_id in (
                    select id from source_chunk where workspace_id = ? and source_id = ?
                )
                """, workspaceId, sourceId);
        // 先删没有引用指向的分块；被引用的分块保行，下面清空正文
        int chunksDeleted = jdbcTemplate.update("""
                delete from source_chunk
                where workspace_id = ? and source_id = ?
                  and not exists (
                      select 1 from citation c where c.source_chunk_id = source_chunk.id
                  )
                """, workspaceId, sourceId);
        int chunksRedacted = jdbcTemplate.update("""
                update source_chunk
                set content = '', heading = null, context_text = null,
                    structure_metadata_json = null, projection_status = 'DELETED'
                where workspace_id = ? and source_id = ?
                  and (content <> '' or heading is not null or context_text is not null
                       or structure_metadata_json is not null)
                """, workspaceId, sourceId);
        int summaryRedacted = jdbcTemplate.update("""
                update source
                set summary = null, metadata_json = null, updated_at = current_timestamp
                where workspace_id = ? and id = ?
                  and (summary is not null or metadata_json is not null)
                """, workspaceId, sourceId);
        PurgeReport report = new PurgeReport(
                citationsRedacted, windowsDeleted, chunksDeleted, chunksRedacted, summaryRedacted > 0);
        meterRegistry.counter("noteweave.source.compliance.purge", "result", "purged").increment();
        log.info("合规删除正文清理完成: workspaceId={}, sourceId={}, citations={}, windows={}, "
                        + "chunksDeleted={}, chunksRedacted={}, summaryRedacted={}",
                workspaceId, sourceId, citationsRedacted, windowsDeleted,
                chunksDeleted, chunksRedacted, summaryRedacted > 0);
        return report;
    }

    public record PurgeReport(
            int citationsRedacted,
            int windowsDeleted,
            int chunksDeleted,
            int chunksRedacted,
            boolean summaryRedacted
    ) {
    }

    public record ResidualSource(String workspaceId, String sourceId) {
    }
}

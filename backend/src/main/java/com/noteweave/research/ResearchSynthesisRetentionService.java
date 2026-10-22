package com.noteweave.research;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 分批清理已结束或旧租约的正文，当前活动租约和未结算的生成消耗始终保留。 */
@Service
class ResearchSynthesisRetentionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final int retentionDays;
    ResearchSynthesisRetentionService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${noteweave.research.synthesis-retention.enabled:false}") boolean enabled,
            @Value("${noteweave.research.synthesis-retention.days:7}") int days) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.enabled = enabled;
        this.retentionDays = Math.max(1, days);
    }
    @Scheduled(fixedDelayString = "${noteweave.research.synthesis-retention.interval-ms:3600000}")
    public void clean() {
        if (!enabled) return;
        Timestamp cutoff = Timestamp.from(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        var tasks = jdbc.query("""
                select task.id, task.research_run_id from research_agent_task task
                join research_run run on run.id = task.research_run_id
                where exists (select 1 from research_synthesis_upload_part part where part.task_id = task.id and part.created_at < ?
                    and (run.status in ('COMPLETED','CANCELLED','FAILED') or task.status in ('SUBMITTED','FAILED','CANCELLED')
                        or part.lease_epoch < task.lease_epoch or part.fencing_token < task.fencing_token))
                  or (run.status in ('COMPLETED','CANCELLED','FAILED') and exists (
                    select 1 from research_synthesis_generation generation where generation.task_id = task.id and generation.created_at < ?))
                  or (run.status in ('COMPLETED','CANCELLED','FAILED') and exists (
                    select 1 from research_synthesis_call_permit permit where permit.task_id = task.id and permit.created_at < ?))
                order by task.research_run_id, task.id limit 100
                """, (rs,n) -> new String[]{rs.getString(1),rs.getString(2)}, cutoff,cutoff,cutoff);
        for (String[] task : tasks) transactions.executeWithoutResult(status -> {
            String run = jdbc.query("select status from research_run where id = ? for update", rs -> rs.next() ? rs.getString(1) : "", task[1]);
            jdbc.query("select id from research_agent_task where id = ? for update", rs -> {}, task[0]);
            boolean terminal = java.util.Set.of("COMPLETED", "CANCELLED", "FAILED").contains(run);
            jdbc.update("""
                    delete from research_synthesis_upload_part where task_id = ? and created_at < ?
                    and (? or exists (select 1 from research_agent_task current_task where current_task.id = task_id
                        and (current_task.status in ('SUBMITTED','FAILED','CANCELLED')
                            or current_task.lease_epoch > research_synthesis_upload_part.lease_epoch
                            or current_task.fencing_token > research_synthesis_upload_part.fencing_token)))
                    """, task[0], cutoff, terminal);
            // 消耗元数据只在 Run 结束后删除，避免重领后重新获得已经花费的预算。
            if (terminal) jdbc.update("delete from research_synthesis_generation where task_id = ? and created_at < ? and not exists (select 1 from research_synthesis_upload_part part where part.task_id = ?)", task[0], cutoff, task[0]);
            if (terminal) jdbc.update("delete from research_synthesis_call_permit where task_id = ? and created_at < ? and not exists (select 1 from research_synthesis_generation generation where generation.task_id = ?)",task[0],cutoff,task[0]);
        });
    }
}

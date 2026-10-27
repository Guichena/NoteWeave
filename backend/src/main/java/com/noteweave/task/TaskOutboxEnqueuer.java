package com.noteweave.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 统一持久入队入口（C2）。所有走 task_outbox 的新生产路径必须经由本类写入，
 * 依靠 (semantic_key, delivery_generation) 唯一约束做数据库层幂等：
 * <ul>
 *   <li>同代次同载荷：返回既有消息（含 READY/PROCESSING/SENT/DEAD_LETTER），不重复入队；</li>
 *   <li>同代次不同载荷：抛 IDEMPOTENCY_CONFLICT，禁止静默改写；</li>
 *   <li>合法重驱/重试：调用方显式增加 deliveryGeneration，产生新的投递记录。</li>
 * </ul>
 * partitionKey 只决定 Kafka 分区，不承担业务幂等；语义身份由
 * {@link OutboxSemanticKey#semanticKey} 的规范化编码产生。
 *
 * <p>必须在业务事务内调用：入队与业务状态变更是同一提交单元，没有事务时明确拒绝，
 * 不隐式把业务写入和 outbox 写入拆成两次提交。
 */
@Component
public class TaskOutboxEnqueuer {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private org.springframework.beans.factory.ObjectProvider<com.noteweave.infra.TaskOutboxDispatcherService> redriveDelegate;

    public TaskOutboxEnqueuer(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 入队命令。businessKind/objectId/generation/stage/batchNo 构成语义身份；
     * generation 承载业务代次（快照摘要、批次版本、策略版本或重驱代次）。
     */
    public record EnqueueCommand(
            String workspaceId,
            String taskId,
            String topic,
            String partitionKey,
            String businessKind,
            String objectId,
            String snapshotId,
            String generation,
            String stage,
            Integer batchNo,
            int deliveryGeneration,
            Map<String, Object> payload
    ) {
        public EnqueueCommand {
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(partitionKey, "partitionKey");
            Objects.requireNonNull(businessKind, "businessKind");
            Objects.requireNonNull(objectId, "objectId");
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(payload, "payload");
            if (businessKind.isBlank() || objectId.isBlank() || generation.isBlank() || stage.isBlank()) {
                throw new IllegalArgumentException("入队业务身份字段不能为空");
            }
            if (deliveryGeneration < 1) {
                throw new IllegalArgumentException("deliveryGeneration 必须为正整数");
            }
            payload = Map.copyOf(payload);
        }
    }

    /** 入队结果：created=false 表示命中既有幂等记录，status 为该记录当前状态。 */
    public record EnqueueResult(String outboxId, String status, boolean created) {

        /** 已投递完成（SENT）的既有记录：不自动复活，重投需要显式重驱。 */
        public boolean alreadySent() {
            return "SENT".equals(status);
        }

        /** 死信中的既有记录：不自动复活，等待显式重驱。 */
        public boolean deadLettered() {
            return "DEAD_LETTER".equals(status);
        }
    }

    public EnqueueResult enqueue(EnqueueCommand command) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "TASK_OUTBOX_ENQUEUE_REQUIRES_TRANSACTION: 统一入队必须在业务事务内调用");
        }
        String semanticKey = OutboxSemanticKey.semanticKey(
                command.workspaceId(), command.businessKind(), command.objectId(),
                command.snapshotId(), command.generation(), command.stage(), command.batchNo());
        String digest = OutboxSemanticKey.payloadDigest(command.payload());
        String payloadJson = Json.write(objectMapper, command.payload());
        String outboxId = Ids.newId();
        try {
            jdbcTemplate.update("""
                    insert into task_outbox(
                        id, task_id, topic, message_key, payload_json, status,
                        semantic_key, delivery_generation, payload_digest)
                    values (?, ?, ?, ?, ?, 'READY', ?, ?, ?)
                    """, outboxId, command.taskId(), command.topic(), command.partitionKey(),
                    payloadJson, semanticKey, command.deliveryGeneration, digest);
            return new EnqueueResult(outboxId, "READY", true);
        } catch (DuplicateKeyException duplicate) {
            return resolveDuplicate(command, semanticKey, digest, duplicate);
        }
    }

    /** 唯一约束命中后读回既有行校验载荷；不是同一语义键的冲突原样抛出。 */
    private EnqueueResult resolveDuplicate(
            EnqueueCommand command, String semanticKey, String digest, DuplicateKeyException duplicate) {
        ExistingRow existing = jdbcTemplate.query("""
                select id, status, payload_digest from task_outbox
                where semantic_key = ? and delivery_generation = ?
                """, rs -> rs.next() ? new ExistingRow(
                rs.getString("id"), rs.getString("status"), rs.getString("payload_digest")) : null,
                semanticKey, command.deliveryGeneration());
        if (existing == null) {
            // 命中的是其他唯一约束（如外键），不能当幂等重复处理
            throw duplicate;
        }
        if (existing.payloadDigest() != null && !existing.payloadDigest().equals(digest)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "同一入队身份存在不同业务载荷：kind=" + command.businessKind()
                            + ", stage=" + command.stage() + ", generation=" + command.generation());
        }
        return new EnqueueResult(existing.id(), existing.status(), false);
    }

    private record ExistingRow(String id, String status, String payloadDigest) { }

    /**
     * 同一业务身份（topic + 分区键）已落定（SENT / DEAD_LETTER）的投递数。
     * 合法重投路径用它构造递增的 generation（taskId#N）：在途行（READY/PROCESSING）
     * 不推高代次，重复唤醒幂等返回既有行；只有上一条消息已消费完成或死信后，
     * 同一业务对象才产生新的投递代次。
     */
    public int priorDeliveries(String topic, String partitionKey) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from task_outbox
                where topic = ? and message_key = ? and status in ('SENT', 'DEAD_LETTER')
                """, Integer.class, topic, partitionKey);
        return count == null ? 0 : count;
    }

    /**
     * 显式重驱既有死信行（同一行换新代次重投，不新建行）：attempt 重新计数，
     * 旧代次事实由 task_outbox_redrive_audit 保留。
     *
     * <p>FB-01（v2 修订）：本入口走「系统中流重驱」模式（redriveMessage）——流水线正停在
     * 等待该消息的阶段，只换代次重投，不做人工重驱的失败态前置校验、不复位流水线与任务；
     * 保留来源/快照当前性校验、审计与按代次的旧补偿失效。只接受 DEAD_LETTER 行，
     * CAS 失败（并发重驱或状态变化）抛冲突异常。
     */
    public void redriveDeadLettered(String outboxId, String actor, String reason) {
        com.noteweave.infra.TaskOutboxDispatcherService delegate =
                redriveDelegate == null ? null : redriveDelegate.getIfAvailable();
        if (delegate == null) {
            throw new IllegalStateException(
                    "TASK_OUTBOX_REDRIVE_UNAVAILABLE: 统一重驱核心未装配，禁止回退到无事务重驱");
        }
        delegate.redriveMessage(outboxId,
                actor == null || actor.isBlank() ? "SYSTEM:TASK_OUTBOX_ENQUEUER" : actor, reason);
    }

    /**
     * 统一重驱核心（FB-01）：延迟解析打断 enqueuer→dispatcher→embedding 批次仓储→enqueuer
     * 的装配环；调用时才取得代理，生产装配必然可用，直连构造未注入时重驱明确失败。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRedriveDelegate(
            org.springframework.beans.factory.ObjectProvider<com.noteweave.infra.TaskOutboxDispatcherService> redriveDelegate) {
        this.redriveDelegate = redriveDelegate;
    }
}

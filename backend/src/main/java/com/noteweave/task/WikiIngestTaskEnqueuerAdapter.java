package com.noteweave.task;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A 的 Wiki 摄取任务入队端口适配（FB 组合接入，回应 A NEE-3/FA-08）：
 * 用 C2 统一入队替代裸 INSERT + LIKE 去重。语义身份 =
 * workspace/WIKI_INGEST/sourceId/snapshotId/policyVersion/INGEST；
 * 同身份在途行复用其任务不再新建。
 * 必须在调用方事务内执行（TransactionTemplate REQUIRED 语义，自动加入）。
 *
 * <p>WIKI-04：复用只对「任务还活着」的投递成立。原来只看 semantic_key，
 * 上一轮任务被取消或失败后，同一快照的新意图会拿到那张已经结束的任务，
 * 入队返回成功却没有任何消费者接手——资料永远不再编译。
 * 现在按投递代次（delivery_generation）区分：在途任务命中同一代次直接复用；
 * 上一轮已终态时新建任务并用下一代次入队，旧行与旧任务原样保留作为事实。</p>
 */
@Component
public class WikiIngestTaskEnqueuerAdapter implements com.noteweave.knowledge.WikiIngestTaskEnqueuerPort {

    private final TaskCommandPort taskCommandPort;
    private final TaskOutboxEnqueuer enqueuer;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final String topic;

    public WikiIngestTaskEnqueuerAdapter(
            TaskCommandPort taskCommandPort,
            TaskOutboxEnqueuer enqueuer,
            JdbcTemplate jdbcTemplate,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            @org.springframework.beans.factory.annotation.Value(
                    "${noteweave.kafka.topics.wiki-ingest:noteweave.wiki.ingest}") String topic) {
        this.taskCommandPort = taskCommandPort;
        this.enqueuer = enqueuer;
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
        this.topic = topic;
    }

    @Override
    public EnqueueOutcome enqueueIngest(IngestCommand command) {
        return transactions.execute(status -> {
            String semanticKey = OutboxSemanticKey.semanticKey(
                    command.workspaceId(), "WIKI_INGEST", command.sourceId(),
                    command.snapshotId(), command.ingestPolicyVersion(), "INGEST", null);
            DeliveryState latest = latestDelivery(semanticKey);
            if (latest != null && latest.live()) {
                // 任务仍在队列或执行中：复用，不产生第二个任务
                return new EnqueueOutcome(latest.taskId(), false);
            }
            int deliveryGeneration = latest == null ? 1 : latest.deliveryGeneration() + 1;
            String taskId = taskCommandPort.createTask(
                    command.workspaceId(), "WIKI_INGEST", "SOURCE", command.sourceId(),
                    "QUEUED", command.message());
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("taskId", taskId);
            payload.put("workspaceId", command.workspaceId());
            payload.put("sourceId", command.sourceId());
            payload.put("sourceSnapshotId", command.snapshotId());
            payload.put("ingestPolicyVersion", command.ingestPolicyVersion());
            TaskOutboxEnqueuer.EnqueueResult enqueued = enqueuer.enqueue(
                    new TaskOutboxEnqueuer.EnqueueCommand(
                            command.workspaceId(), taskId, topic, command.snapshotId(),
                            "WIKI_INGEST", command.sourceId(), command.snapshotId(),
                            command.ingestPolicyVersion(), "INGEST", null,
                            deliveryGeneration, payload));
            if (!enqueued.created()) {
                String winnerTaskId = taskIdOfOutboxRow(enqueued.outboxId());
                if (winnerTaskId != null && !winnerTaskId.equals(taskId)) {
                    // 并发重复唤醒：另一条路径已用同一代次入队，刚建的任务是多余的，
                    // 取消掉，避免同一资料出现两个任务
                    taskCommandPort.cancelTask(taskId, "WIKI_DUPLICATE_ENQUEUE",
                            "同一资料同一快照已由并发路径入队，本次重复任务已取消",
                            command.sourceId());
                    return new EnqueueOutcome(winnerTaskId, false);
                }
            }
            return new EnqueueOutcome(taskId, true);
        });
    }

    /** 该语义身份最新一次投递及其任务状态；没有历史投递时返回 null。 */
    private DeliveryState latestDelivery(String semanticKey) {
        return jdbcTemplate.query("""
                select o.task_id, o.delivery_generation, t.task_status
                from task_outbox o
                left join task t on t.id = o.task_id
                where o.semantic_key = ?
                order by o.delivery_generation desc, o.created_at desc
                limit 1
                """, rs -> rs.next()
                ? new DeliveryState(rs.getString(1), rs.getInt(2), rs.getString(3))
                : null, semanticKey);
    }

    private String taskIdOfOutboxRow(String outboxId) {
        return jdbcTemplate.query("select task_id from task_outbox where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, outboxId);
    }

    /** 既有投递与其任务状态：只有任务仍在队列/执行中的投递允许复用。 */
    private record DeliveryState(String taskId, int deliveryGeneration, String taskStatus) {

        boolean live() {
            if (taskId == null || taskStatus == null) {
                return false;
            }
            return switch (taskStatus) {
                case "PENDING", "RUNNING", "WAITING" -> true;
                default -> false;
            };
        }
    }
}

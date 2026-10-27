package com.noteweave.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.storage.FileObjectLifecycleRepository.DeleteTicket;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 共享文件对象生命周期测试（FB-06 验收）：
 * 上传-删除-再上传顺序；同 hash 并发唯一约束选赢家；DELETE_CLAIMED 拒绝新引用并
 * 明确待重试；DELETED 换新 key 换代再发布、旧票据不能删新 key；共享对象引用
 * 全部释放才可认领删除；派生对象登记/孤儿/来源删除意图与有界清理。
 */
class FileObjectLifecycleTest {

    private JdbcTemplate jdbcTemplate;
    private FileObjectLifecycleRepository objects;
    private FileObjectLifecycleService lifecycle;
    private DerivedObjectRegistry derived;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:file-lifecycle-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table file_object (
                    id varchar(36) primary key, workspace_id varchar(36) not null,
                    object_key varchar(500) not null, sha256 varchar(64) not null,
                    file_size bigint not null, mime_type varchar(160), ref_count int not null default 1,
                    status varchar(32) not null default 'ACTIVE',
                    object_generation bigint not null default 1,
                    created_at timestamp default current_timestamp,
                    constraint uk_file_object_workspace_hash unique (workspace_id, sha256)
                )
                """);
        jdbcTemplate.execute("""
                create table file_object_delete_ticket (
                    id varchar(36) primary key, file_object_id varchar(36) not null,
                    workspace_id varchar(36) not null, bucket varchar(160) not null,
                    object_key varchar(500) not null, object_generation bigint not null,
                    attempts int not null default 0, claimed_at timestamp default current_timestamp,
                    completed_at timestamp null, failed_at timestamp null, last_error varchar(500)
                )
                """);
        jdbcTemplate.execute("""
                create table derived_file_object (
                    id varchar(36) primary key, workspace_id varchar(36) not null,
                    source_id varchar(36), source_snapshot_id varchar(36), batch_version varchar(64),
                    object_kind varchar(64) not null, bucket varchar(160) not null,
                    object_key varchar(500) not null, sha256 varchar(64),
                    status varchar(32) not null,
                    created_at timestamp default current_timestamp, updated_at timestamp default current_timestamp,
                    constraint uq_derived_object_key unique (bucket, object_key)
                )
                """);
        // AU-001 对账扫描依赖来源/快照当前性判定（staleRegistered 左连接）
        jdbcTemplate.execute("""
                create table source (
                    id varchar(36) primary key, workspace_id varchar(36) not null,
                    status varchar(32) not null
                )
                """);
        jdbcTemplate.execute("""
                create table source_snapshot (
                    id varchar(36) primary key, source_id varchar(36) not null,
                    version_no int not null
                )
                """);
        PlatformTransactionManager manager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        objects = new FileObjectLifecycleRepository(jdbcTemplate);
        derived = new DerivedObjectRegistry(jdbcTemplate);
        lifecycle = new FileObjectLifecycleService(
                objects, derived, new FakeStorage(), jdbcTemplate, manager,
                new NoteWeaveProperties(null, null, null, null, null, null), true);
    }

    private String status(String fileObjectId) {
        return jdbcTemplate.queryForObject(
                "select status from file_object where id = ?", String.class, fileObjectId);
    }

    @Test
    void uploadDeleteUploadOrderingWithGenerationFencing() {
        // 上传：新建 ACTIVE
        var first = lifecycle.acquireOrPrepare("ws-1", "hash-a", 10L, "text/plain", "key-new-1");
        assertThat(first.activated()).isTrue();
        assertThat(first.objectKey()).isEqualTo("key-new-1");
        assertThat(status(first.fileObjectId())).isEqualTo("ACTIVE");

        // 删除：释放并认领票据
        DeleteTicket ticket = lifecycle.releaseAndClaimDeletion(first.fileObjectId());
        assertThat(ticket).isNotNull();
        assertThat(ticket.objectKey()).isEqualTo("key-new-1");
        assertThat(status(first.fileObjectId())).isEqualTo("DELETE_CLAIMED");

        // DELETE_CLAIMED 期间同 hash 上传被明确拒绝（待重试，不复用旧 key）
        assertThatThrownBy(() -> lifecycle.acquireOrPrepare("ws-1", "hash-a", 10L, "text/plain", "key-new-2"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("删除");

        // 票据完成后行进入 DELETED
        assertThat(objects.completeDeletion(ticket.ticketId())).isTrue();
        assertThat(status(first.fileObjectId())).isEqualTo("DELETED");

        // 再上传同 hash：换全新不可变 key、代次 +1、引用重置
        var republished = lifecycle.acquireOrPrepare("ws-1", "hash-a", 10L, "text/plain", "key-new-3");
        assertThat(republished.activated()).isTrue();
        assertThat(republished.objectKey()).isEqualTo("key-new-3");
        assertThat(jdbcTemplate.queryForObject(
                "select object_generation from file_object where id = ?",
                Long.class, first.fileObjectId())).isEqualTo(2L);
    }

    @Test
    void sameHashWithDifferentSizeIsRejected() {
        lifecycle.acquireOrPrepare("ws-1", "hash-x", 10L, "text/plain", "key-x1");
        // 同哈希不同大小：疑似哈希碰撞/数据损坏，拒绝共享错误对象（沿用旧错误码语义）
        assertThatThrownBy(() -> lifecycle.acquireOrPrepare("ws-1", "hash-x", 11L, "text/plain", "key-x2"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("大小不一致");
        assertThat(jdbcTemplate.queryForObject(
                "select ref_count from file_object where sha256 = 'hash-x'", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentHashInsertHasSingleWinner() {
        assertThat(objects.insertActive("fo-winner", "ws-1", "key-w", "hash-c", 1L, "text/plain")).isTrue();
        // 并发输家：唯一约束拒绝，不产生第二条行
        assertThat(objects.insertActive("fo-loser", "ws-1", "key-l", "hash-c", 1L, "text/plain")).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from file_object where sha256 = 'hash-c'", Integer.class)).isEqualTo(1);
    }

    @Test
    void sharedObjectRequiresAllReferencesReleasedBeforeClaim() {
        var a = lifecycle.acquireOrPrepare("ws-1", "hash-s", 1L, "text/plain", "key-s1");
        lifecycle.acquireOrPrepare("ws-1", "hash-s", 1L, "text/plain", "key-s2");

        // 两次引用只释放一次：仍有引用，不能认领删除
        assertThat(lifecycle.releaseAndClaimDeletion(a.fileObjectId())).isNull();
        assertThat(status(a.fileObjectId())).isEqualTo("ACTIVE");

        // 全部释放后才可认领；引用不会减为负数
        assertThat(lifecycle.releaseAndClaimDeletion(a.fileObjectId())).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "select ref_count from file_object where id = ?", Integer.class, a.fileObjectId())).isZero();
        assertThat(objects.releaseReference(a.fileObjectId())).isFalse();
    }

    @Test
    void derivedRegistryLifecycleFromRegisterToCleanup() {
        assertThat(derived.register("ws-1", "src-1", "snap-1", null, "PARSED_DOCUMENT",
                "noteweave-derived", "derived/key-1", null)).isTrue();
        // 同 (bucket,key) 幂等
        assertThat(derived.register("ws-1", "src-1", "snap-1", null, "PARSED_DOCUMENT",
                "noteweave-derived", "derived/key-1", null)).isFalse();
        // 孤儿标记
        assertThat(derived.markOrphan("noteweave-derived", "derived/key-1")).isTrue();
        assertThat(derived.dueForCleanup(10)).hasSize(1);
        // 清理完成保留为已完成事实
        assertThat(derived.markCleaned(derived.dueForCleanup(10).get(0).id())).isTrue();
        assertThat(derived.dueForCleanup(10)).isEmpty();
    }

    @Test
    void derivedReleaseForSourceScopesToSnapshotWhenRequested() {
        derived.register("ws-1", "src-1", "snap-old", "v1", "EMBEDDING_BATCH",
                "noteweave-derived", "derived/old-1", null);
        derived.register("ws-1", "src-1", "snap-new", "v2", "EMBEDDING_BATCH",
                "noteweave-derived", "derived/new-1", null);

        // 只释放被取代代次的对象：新代次登记不受影响
        List<DerivedObjectRegistry.DerivedObject> released =
                derived.releaseForSource("ws-1", "src-1", "snap-old");
        assertThat(released).hasSize(1);
        assertThat(released.get(0).objectKey()).isEqualTo("derived/old-1");
        assertThat(derived.dueForCleanup(10).stream()
                .anyMatch(item -> "derived/new-1".equals(item.objectKey()))).isFalse();
    }

    @Test
    void staleRegisteredDerivedRowsAreReconciledToOrphan() {
        // AU-001 第二部分：删除事务释放意图后才晚到登记的行（窄窗口竞态），
        // 由过期登记对账按目标失效收敛为孤儿，交孤儿清理执行物理删除
        jdbcTemplate.update("insert into source(id, workspace_id, status) values ('src-d', 'ws-1', 'DELETED')");
        jdbcTemplate.update("insert into source_snapshot(id, source_id, version_no) values ('snap-d', 'src-d', 1)");
        // 目标已删除：晚到登记滞留 REGISTERED
        assertThat(derived.register("ws-1", "src-d", "snap-d", null, "EXTRACTED_TEXT",
                "noteweave-derived", "derived/late-1", null)).isTrue();
        // 目标仍有效：不受对账影响
        jdbcTemplate.update("insert into source(id, workspace_id, status) values ('src-ok', 'ws-1', 'READY')");
        jdbcTemplate.update("insert into source_snapshot(id, source_id, version_no) values ('snap-ok', 'src-ok', 1)");
        derived.register("ws-1", "src-ok", "snap-ok", null, "EXTRACTED_TEXT",
                "noteweave-derived", "derived/live-1", null);

        lifecycle.reconcileStaleDerivedRegistrations();

        assertThat(jdbcTemplate.queryForObject("""
                select status from derived_file_object where object_key = 'derived/late-1'
                """, String.class)).isEqualTo("ORPHANED");
        assertThat(jdbcTemplate.queryForObject("""
                select status from derived_file_object where object_key = 'derived/live-1'
                """, String.class)).isEqualTo("REGISTERED");
        // 孤儿化后进入待清理队列，物理删除由 reconcileStagedOrphans 执行
        assertThat(derived.dueForCleanup(10).stream()
                .anyMatch(item -> "derived/late-1".equals(item.objectKey()))).isTrue();
    }

    @Test
    void staleReconcileCatchesSupersededSnapshotGenerations() {
        // 被取代代次的登记同样按过期收敛（staleRegistered 的快照当前性分支）
        jdbcTemplate.update("insert into source(id, workspace_id, status) values ('src-s', 'ws-1', 'READY')");
        jdbcTemplate.update("insert into source_snapshot(id, source_id, version_no) values ('snap-old2', 'src-s', 1)");
        jdbcTemplate.update("insert into source_snapshot(id, source_id, version_no) values ('snap-new2', 'src-s', 2)");
        derived.register("ws-1", "src-s", "snap-old2", null, "EMBEDDING_BATCH",
                "noteweave-derived", "derived/old-batch", null);
        derived.register("ws-1", "src-s", "snap-new2", null, "EMBEDDING_BATCH",
                "noteweave-derived", "derived/new-batch", null);

        lifecycle.reconcileStaleDerivedRegistrations();

        assertThat(jdbcTemplate.queryForObject("""
                select status from derived_file_object where object_key = 'derived/old-batch'
                """, String.class)).isEqualTo("ORPHANED");
        assertThat(jdbcTemplate.queryForObject("""
                select status from derived_file_object where object_key = 'derived/new-batch'
                """, String.class)).isEqualTo("REGISTERED");
    }

    @Test
    void prepareOnActiveSharedObjectDoesNotIncrementReference() {
        // NR13：生成来源 prepare 对 ACTIVE 共享对象零引用——不 +1（引用由提交事务增加）
        var uploaded = lifecycle.acquireOrPrepare("ws-1", "hash-p", 10L, "text/markdown", "key-p1");
        var prepared = lifecycle.prepareOrAcquire("ws-1", "hash-p", 10L, "text/markdown", "key-p2");
        assertThat(prepared.activated()).isFalse();
        assertThat(prepared.objectKey()).isEqualTo("key-p1");
        assertThat(prepared.fileObjectId()).isEqualTo(uploaded.fileObjectId());
        assertThat(jdbcTemplate.queryForObject(
                "select ref_count from file_object where id = ?", Integer.class, uploaded.fileObjectId()))
                .isEqualTo(1);
    }

    @Test
    void prepareOnDeleteClaimedIsRejectedWithExplicitRetry() {
        // NR13：待删除对象不得被生成来源裸复用——明确待重试错误
        var uploaded = lifecycle.acquireOrPrepare("ws-1", "hash-dc", 10L, "text/markdown", "key-dc1");
        assertThat(lifecycle.releaseAndClaimDeletion(uploaded.fileObjectId())).isNotNull();
        assertThatThrownBy(() -> lifecycle.prepareOrAcquire("ws-1", "hash-dc", 10L, "text/markdown", "key-dc2"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("物理删除");
    }

    @Test
    void prepareAfterDeletedRepublishesWithNewKeyAndCommitAcquireSucceeds() {
        // NR13：相同内容删除后再次保存——prepare 以新 key 换代（ref=0），
        // 提交事务 acquire 成功；旧票据的不可变 key 不覆盖新 key
        var first = lifecycle.acquireOrPrepare("ws-1", "hash-rp", 10L, "text/markdown", "key-rp1");
        var ticket = lifecycle.releaseAndClaimDeletion(first.fileObjectId());
        assertThat(ticket).isNotNull();
        assertThat(objects.completeDeletion(ticket.ticketId())).isTrue();
        assertThat(status(first.fileObjectId())).isEqualTo("DELETED");

        var prepared = lifecycle.prepareOrAcquire("ws-1", "hash-rp", 10L, "text/markdown", "key-rp2");
        assertThat(prepared.activated()).isTrue();
        assertThat(prepared.objectKey()).isEqualTo("key-rp2");
        assertThat(status(first.fileObjectId())).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "select ref_count from file_object where id = ?", Integer.class, first.fileObjectId()))
                .isZero();
        assertThat(objects.acquireReference(first.fileObjectId())).isTrue();
    }

    @Test
    void orphanClaimWithoutTicketIsRecoveredByReconcile() {
        // NR16：认领 UPDATE 与票据 INSERT 分开提交的故障形态——DELETE_CLAIMED 无未完成票据。
        // 对账循环重建票据并完成物理删除，行不再永久卡死
        var uploaded = lifecycle.acquireOrPrepare("ws-1", "hash-orph", 10L, "text/markdown", "key-orph1");
        assertThat(lifecycle.releaseAndClaimDeletion(uploaded.fileObjectId())).isNotNull();
        // 模拟票据写入丢失：直接删掉票据行，行滞留在 DELETE_CLAIMED
        jdbcTemplate.update("delete from file_object_delete_ticket where file_object_id = ?",
                uploaded.fileObjectId());
        assertThat(status(uploaded.fileObjectId())).isEqualTo("DELETE_CLAIMED");
        assertThat(objects.openTickets(10)).isEmpty();

        lifecycle.reconcileOpenTickets();

        assertThat(status(uploaded.fileObjectId())).isEqualTo("DELETED");
        assertThat(objects.claimedWithoutOpenTicket(10)).isEmpty();
    }

    /** 内存对象存储桩：只实现生命周期服务用到的最小面。 */
    static final class FakeStorage implements ObjectStorage {
        private final java.util.Set<String> deleted = new java.util.HashSet<>();

        @Override
        public Path write(String bucket, String key, byte[] content) {
            return null;
        }

        @Override
        public byte[] read(String bucket, String key) {
            return new byte[0];
        }

        @Override
        public boolean exists(String bucket, String key) {
            return !deleted.contains(bucket + "/" + key);
        }

        @Override
        public void delete(String bucket, String key) {
            deleted.add(bucket + "/" + key);
        }

        @Override
        public List<StoredObject> list(String bucket, String prefix, String afterKey, int limit) {
            return List.of();
        }

        @Override
        public String backendName() {
            return "fake";
        }
    }
}

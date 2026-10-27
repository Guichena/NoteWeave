package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import com.noteweave.retrieval.projection.SourceReadyIntentRepository.ReadySourceIntent;

/**
 * source READY 持久摄取意图测试（B-02/C3 验收 + FB-02 恢复语义）：
 * 同快照同政策幂等唯一；认领租约防止双消费；过期可重认领且旧令牌不能写回；
 * 暂时失败退避重试、达上限 FAILED 终态；任意年龄的未决意图都可恢复（N-06）；
 * 历史缺口回填按同一幂等身份补登记，游标分页。
 */
@SpringBootTest
@ActiveProfiles("test")
class SourceReadyIntentRepositoryTest {
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SourceReadyIntentRepository intents;

    private String[] seedReadySource(String suffix) {
        String workspace = "ws-ready-" + suffix;
        String source = "src-ready-" + suffix;
        String snapshot = "snap-ready-" + suffix;
        jdbcTemplate.update("""
                insert into workspace(id, owner_id, name, status) values (?, 'local-user', 'ready-intent-test', 'ACTIVE')
                """, workspace);
        jdbcTemplate.update("""
                insert into file_object(id, workspace_id, object_key, sha256, file_size)
                values (?, ?, 'ready-intent-key', ?, 1)
                """, "fo-" + suffix, workspace, "h" + suffix);
        jdbcTemplate.update("""
                insert into source(id, workspace_id, file_object_id, title, source_type, status,
                    parse_status, index_status)
                values (?, ?, ?, 't', 'USER_UPLOAD', 'READY', 'PARSED', 'INDEXED')
                """, source, workspace, "fo-" + suffix);
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256,
                    parse_status, index_status)
                values (?, ?, ?, 1, 'k', 'hash', 'PARSED', 'INDEXED')
                """, snapshot, source, "fo-" + suffix);
        jdbcTemplate.update("update source set index_status='INDEXED' where id=?", source);
        return new String[]{workspace, source, snapshot};
    }

    private ReadySourceIntent claimOne(String owner, String sourceId) {
        List<ReadySourceIntent> claimed = intents.claimDue(owner, 50, Duration.ofSeconds(120));
        return claimed.stream()
                .filter(intent -> intent.sourceId().equals(sourceId))
                .findFirst().orElseThrow();
    }

    @Test
    void recordIfAbsentIsIdempotentPerSnapshotAndPolicy() {
        String[] ids = seedReadySource("idem" + System.nanoTime());
        assertThat(intents.recordIfAbsent(ids[0], ids[1], ids[2], "1")).isTrue();
        assertThat(intents.recordIfAbsent(ids[0], ids[1], ids[2], "1")).isFalse();
        // 同快照不同政策是新意图；不同快照互不影响
        assertThat(intents.recordIfAbsent(ids[0], ids[1], ids[2], "2")).isTrue();
    }

    @Test
    void claimLeasePreventsDoubleConsumeAndExpiredLeaseCanBeReclaimed() {
        String[] ids = seedReadySource("claim" + System.nanoTime());
        intents.recordIfAbsent(ids[0], ids[1], ids[2], "1");
        ReadySourceIntent first = claimOne("consumer-a", ids[1]);
        assertThat(first.leaseOwner()).isEqualTo("consumer-a");
        // 租约有效：第二个消费者认领不到同一意图
        assertThat(intents.claimDue("consumer-b", 50, Duration.ofSeconds(120)))
                .noneMatch(intent -> intent.sourceId().equals(ids[1]));
        // 过期后可重认领，且生成新的一次性令牌
        jdbcTemplate.update("update source_ready_intent set lease_until = timestamp '2020-01-01 00:00:00' where source_id = ?",
                ids[1]);
        ReadySourceIntent second = claimOne("consumer-b", ids[1]);
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());
        // 旧令牌写回无效，新令牌完成
        assertThat(intents.complete(first.intentId(), first.claimToken())).isFalse();
        assertThat(intents.ignoreStale(first.intentId(), first.claimToken(), "X")).isFalse();
        assertThat(intents.complete(second.intentId(), second.claimToken())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select status from source_ready_intent where source_id = ?", String.class, ids[1]))
                .isEqualTo("CONSUMED");
    }

    @Test
    void pendingIntentOfAnyAgeStaysClaimableAfterLongOutage() {
        // N-06：停机/积压超过任何 maxAge 的未决意图不能永久滞留
        String[] ids = seedReadySource("age" + System.nanoTime());
        intents.recordIfAbsent(ids[0], ids[1], ids[2], "1");
        jdbcTemplate.update("""
                update source_ready_intent
                set created_at = timestamp '2020-01-01 00:00:00',
                    updated_at = timestamp '2020-01-01 00:00:00'
                where source_id = ?
                """, ids[1]);

        assertThat(claimOne("consumer-a", ids[1])).isNotNull();
    }

    @Test
    void retryWaitKeepsBackoffAndTerminalFailsAfterLimit() {
        String[] ids = seedReadySource("retry" + System.nanoTime());
        intents.recordIfAbsent(ids[0], ids[1], ids[2], "1");
        ReadySourceIntent claimed = claimOne("consumer-a", ids[1]);

        assertThat(intents.retryWait(claimed.intentId(), claimed.claimToken(), "BUSY", 60)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select status from source_ready_intent where source_id = ?", String.class, ids[1]))
                .isEqualTo("RETRY_WAIT");
        assertThat(jdbcTemplate.queryForObject("""
                        select next_attempt_at is not null from source_ready_intent where source_id = ?
                        """, Boolean.class, ids[1])).isTrue();

        // 到期后重领；达上限的意图由调度侧判终态，仓储按令牌 CAS 生效
        jdbcTemplate.update("update source_ready_intent set next_attempt_at = null where source_id = ?", ids[1]);
        ReadySourceIntent again = claimOne("consumer-a", ids[1]);
        assertThat(intents.failTerminal(again.intentId(), again.claimToken(), "EXHAUSTED")).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select status from source_ready_intent where source_id = ?", String.class, ids[1]))
                .isEqualTo("FAILED");
    }

    @Test
    void backfillRegistersMissingReadySourcesOnceWithCursorPaging() {
        String[] ids = seedReadySource("bf" + System.nanoTime());
        SourceReadyIntentRepository.BackfillPage first = intents.backfillMissing("1", 100, "");
        assertThat(first.recorded()).isGreaterThanOrEqualTo(1);
        // 同一幂等身份不重复登记：从游标继续扫描不再登记
        SourceReadyIntentRepository.BackfillPage second =
                intents.backfillMissing("1", 100, first.lastSnapshotId());
        assertThat(second.recorded()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from source_ready_intent where source_id = ?", Integer.class, ids[1]))
                .isEqualTo(1);
    }
}

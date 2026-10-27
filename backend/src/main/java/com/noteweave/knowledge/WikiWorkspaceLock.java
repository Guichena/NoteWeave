package com.noteweave.knowledge;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Wiki 工作台级互斥（WIKI-02）。
 *
 * <p>同一个工作台的 ingest 与 retract 都会重灌页面：retract 会删除来源页并按最新资料重灌其余来源，
 * ingest 会清理并重写概念页。两者并发时，一方的删除会让另一方正在写入的条目变成非 ACTIVE，
 * 于是出现重复版本或整体回滚。这里用一行带过期租约的记录做互斥，跨 MySQL 与 H2 语义一致。</p>
 *
 * <p>租约而不是永久锁：进程崩溃后锁会在 {@code ttl} 之后自动失效，不需要人工清理；
 * 代价是极端情况下两个任务可能短暂重叠，这一点与不加锁时相同，不会更差。</p>
 */
@Component
public class WikiWorkspaceLock {

    private final JdbcTemplate jdbcTemplate;

    public WikiWorkspaceLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String keyForWorkspace(String workspaceId) {
        return "wiki:workspace:" + workspaceId;
    }

    /**
     * 尝试取得租约。已过期或被自己持有都可以直接接管，因此重复调用是安全的。
     *
     * @return true 表示本次调用取得租约，false 表示同一工作台已有其他任务在写入
     */
    public boolean tryAcquire(String lockKey, String owner, Duration ttl) {
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp until = Timestamp.from(Instant.now().plus(ttl));
        int takenOver = jdbcTemplate.update("""
                update wiki_workspace_lock
                set locked_until = ?, owner = ?
                where lock_key = ? and (locked_until <= ? or owner = ?)
                """, until, owner, lockKey, now, owner);
        if (takenOver == 1) {
            return true;
        }
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from wiki_workspace_lock where lock_key = ?", Integer.class, lockKey);
        if (rows != null && rows > 0) {
            return false;
        }
        try {
            jdbcTemplate.update("""
                    insert into wiki_workspace_lock(lock_key, locked_until, owner)
                    values (?, ?, ?)
                    """, lockKey, until, owner);
            return true;
        } catch (DuplicateKeyException concurrentInsert) {
            // 另一个实例刚刚插入同一把锁：按未取得处理，由上层安排重试
            return false;
        }
    }

    /** 释放租约。只释放自己持有的锁，避免把接管者的租约清掉。 */
    public void release(String lockKey, String owner) {
        jdbcTemplate.update("""
                update wiki_workspace_lock
                set locked_until = ?, owner = null
                where lock_key = ? and owner = ?
                """, Timestamp.from(Instant.now()), lockKey, owner);
    }
}

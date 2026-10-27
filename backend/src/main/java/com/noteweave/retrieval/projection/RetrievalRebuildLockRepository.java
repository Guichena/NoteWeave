package com.noteweave.retrieval.projection;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 检索索引重建的工作台互斥锁（IDX-04）。
 *
 * <p>锁是数据库里的一行：主键冲突就说明该工作台已有重建在跑。
 * 不能用「先查有没有活跃构建再插入」这种两步判断——两个并发请求会同时通过检查，
 * 各自建一套新索引，最后互相覆盖别名。</p>
 */
@Repository
public class RetrievalRebuildLockRepository {
    private final JdbcTemplate jdbcTemplate;

    public RetrievalRebuildLockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 抢锁成功返回 true；已有重建在跑返回 false。 */
    public boolean tryAcquire(String workspaceId, String token) {
        try {
            return jdbcTemplate.update("""
                    insert into retrieval_index_rebuild_lock(workspace_id, build_token, acquired_at)
                    values (?, ?, current_timestamp)
                    """, workspaceId, token) == 1;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /** 只释放自己持有的锁：重建超时后被别人接管时，不能删掉对方的锁。 */
    public void release(String workspaceId, String token) {
        jdbcTemplate.update("""
                delete from retrieval_index_rebuild_lock where workspace_id = ? and build_token = ?
                """, workspaceId, token);
    }

    public Optional<Instant> acquiredAt(String workspaceId) {
        List<Instant> rows = jdbcTemplate.query("""
                select acquired_at from retrieval_index_rebuild_lock where workspace_id = ?
                """, (rs, rowNum) -> rs.getTimestamp("acquired_at").toInstant(), workspaceId);
        return rows.stream().findFirst();
    }
}

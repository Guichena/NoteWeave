package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** WIKI-02：工作台写入租约的取得、互斥、过期接管与释放归属。 */
class WikiWorkspaceLockTest {

    private JdbcTemplate jdbcTemplate;
    private WikiWorkspaceLock lock;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:wiki-lock-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table wiki_workspace_lock (
                    lock_key varchar(120) not null primary key,
                    locked_until timestamp not null,
                    owner varchar(120)
                )
                """);
        lock = new WikiWorkspaceLock(jdbcTemplate);
    }

    @Test
    void firstAcquireWinsAndConcurrentTaskIsRejected() {
        assertThat(lock.tryAcquire("k", "task-1", Duration.ofMinutes(5))).isTrue();
        assertThat(lock.tryAcquire("k", "task-2", Duration.ofMinutes(5))).isFalse();
    }

    @Test
    void releaseLetsAnotherTaskAcquire() {
        assertThat(lock.tryAcquire("k", "task-1", Duration.ofMinutes(5))).isTrue();

        lock.release("k", "task-1");

        assertThat(lock.tryAcquire("k", "task-2", Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void expiredLeaseFromCrashedTaskIsTakenOver() {
        jdbcTemplate.update("""
                insert into wiki_workspace_lock(lock_key, locked_until, owner)
                values (?, ?, ?)
                """, "k", Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))), "crashed-task");

        assertThat(lock.tryAcquire("k", "task-2", Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void releaseFromAnotherOwnerDoesNotClearCurrentLease() {
        assertThat(lock.tryAcquire("k", "task-1", Duration.ofMinutes(5))).isTrue();

        lock.release("k", "task-2");

        assertThat(lock.tryAcquire("k", "task-2", Duration.ofMinutes(5))).isFalse();
    }

    @Test
    void sameOwnerCanReacquireItsOwnLease() {
        assertThat(lock.tryAcquire("k", "task-1", Duration.ofMinutes(5))).isTrue();

        assertThat(lock.tryAcquire("k", "task-1", Duration.ofMinutes(5))).isTrue();
    }
}

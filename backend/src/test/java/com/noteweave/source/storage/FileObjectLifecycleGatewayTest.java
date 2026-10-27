package com.noteweave.source.storage;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FA-05 定向验证：引用增减与零引用删除认领的端口/legacy 双路径。
 * legacy 路径在真实 H2 上验证既有 SQL 语义；端口路径用桩验证调用转发与票据语义。
 */
class FileObjectLifecycleGatewayTest {

    @Test
    @SuppressWarnings("unchecked")
    void legacyPathReleasesAndClaimsOnRealSql() {
        JdbcTemplate jdbc = h2();
        jdbc.update("insert into file_object(id, workspace_id, object_key, sha256, ref_count) "
                + "values ('fo-1', 'ws-1', 'key-1', 'hash-1', 1)");
        ObjectProvider<FileObjectLifecyclePort> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        FileObjectLifecycleGateway gateway = new FileObjectLifecycleGateway(empty, jdbc);

        long remaining = gateway.release("ws-1", "fo-1");
        org.junit.jupiter.api.Assertions.assertEquals(0, remaining);
        Optional<FileObjectLifecyclePort.DeletionTicket> ticket =
                gateway.claimZeroReferenceDeletion("ws-1", "fo-1", "bucket", "key-1");
        org.junit.jupiter.api.Assertions.assertTrue(ticket.isPresent());
        org.junit.jupiter.api.Assertions.assertEquals("key-1", ticket.get().objectKey());

        // 重新获得引用后不再认领：共享对象保护
        gateway.acquire("ws-1", "fo-1");
        Optional<FileObjectLifecyclePort.DeletionTicket> denied =
                gateway.claimZeroReferenceDeletion("ws-1", "fo-1", "bucket", "key-1");
        org.junit.jupiter.api.Assertions.assertTrue(denied.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void portPathDelegatesAndPropagatesTicket() {
        CountingPort port = new CountingPort();
        ObjectProvider<FileObjectLifecyclePort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        FileObjectLifecycleGateway gateway =
                new FileObjectLifecycleGateway(provider, new JdbcTemplate(h2DataSource()));

        org.junit.jupiter.api.Assertions.assertTrue(gateway.portWired());
        gateway.acquire("ws-1", "fo-1");
        gateway.release("ws-1", "fo-1");
        gateway.claimZeroReferenceDeletion("ws-1", "fo-1", "bucket", "key-1");
        org.junit.jupiter.api.Assertions.assertEquals(1, port.acquires.get());
        org.junit.jupiter.api.Assertions.assertEquals(1, port.releases.get());
        org.junit.jupiter.api.Assertions.assertEquals(1, port.claims.get());
        org.junit.jupiter.api.Assertions.assertTrue(port.lastTicket.isPresent());
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyReleaseFloorsAtZero() {
        JdbcTemplate jdbc = h2();
        jdbc.update("insert into file_object(id, workspace_id, object_key, sha256, ref_count) "
                + "values ('fo-2', 'ws-1', 'key-2', 'hash-2', 0)");
        ObjectProvider<FileObjectLifecyclePort> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        FileObjectLifecycleGateway gateway = new FileObjectLifecycleGateway(empty, jdbc);
        org.junit.jupiter.api.Assertions.assertEquals(0, gateway.release("ws-1", "fo-2"));
        Integer refCount = jdbc.queryForObject(
                "select ref_count from file_object where id = 'fo-2'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, refCount);
    }

    private JdbcTemplate h2() {
        return new JdbcTemplate(h2DataSource());
    }

    private javax.sql.DataSource h2DataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:file-object-gateway-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.execute("""
                create table file_object(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    object_key varchar(500) not null,
                    sha256 varchar(64),
                    ref_count int not null default 0
                )
                """);
        return dataSource;
    }

    private static final class CountingPort implements FileObjectLifecyclePort {
        final AtomicInteger acquires = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();
        final AtomicInteger claims = new AtomicInteger();
        final Map<String, Long> counts = new HashMap<>();
        Optional<DeletionTicket> lastTicket = Optional.empty();

        @Override
        public long acquireReference(String workspaceId, String fileObjectId) {
            acquires.incrementAndGet();
            return counts.merge(fileObjectId, 1L, Long::sum);
        }

        @Override
        public long releaseReference(String workspaceId, String fileObjectId) {
            releases.incrementAndGet();
            return counts.merge(fileObjectId, -1L, Long::sum);
        }

        @Override
        public Optional<DeletionTicket> claimZeroReferenceDeletion(String workspaceId, String fileObjectId) {
            claims.incrementAndGet();
            lastTicket = Optional.of(new DeletionTicket(fileObjectId, "bucket", "key", 1L));
            return lastTicket;
        }
    }
}

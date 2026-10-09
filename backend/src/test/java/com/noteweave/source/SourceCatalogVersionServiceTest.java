package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SourceCatalogVersionServiceTest {

    private SourceCatalogVersionService service;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:source-version-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                create table workspace(
                    id varchar(36) primary key,
                    source_catalog_version bigint not null default 1
                )
                """);
        jdbcTemplate.update("insert into workspace(id) values ('workspace-1')");
        service = new SourceCatalogVersionService(jdbcTemplate);
    }

    @Test
    void shouldReadAndBumpCatalogVersion() {
        assertThat(service.current("workspace-1")).isEqualTo(1);
        service.bump("workspace-1");
        assertThat(service.current("workspace-1")).isEqualTo(2);
    }

    @Test
    void missingWorkspaceShouldFailClosed() {
        assertThatThrownBy(() -> service.bump("missing"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("工作台不存在");
    }

    @Test
    void bumpInsideATransactionIsAppliedOnceAfterCommitAndDroppedOnRollback() {
        var transactionManager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        SourceCatalogVersionService transactional = new SourceCatalogVersionService(new JdbcTemplate(dataSource),
                transactionManager);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            transactional.bump("workspace-1");
            transactional.bump("workspace-1");
            // 事务提交前不持有工作台行的更新锁，版本号也还没变
            assertThat(transactional.current("workspace-1")).isEqualTo(1);
        });
        assertThat(transactional.current("workspace-1")).isEqualTo(2);

        transaction.executeWithoutResult(status -> {
            transactional.bump("workspace-1");
            status.setRollbackOnly();
        });
        assertThat(transactional.current("workspace-1")).isEqualTo(2);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> transactional.bump("missing")))
                .isInstanceOf(BusinessException.class);
    }
}

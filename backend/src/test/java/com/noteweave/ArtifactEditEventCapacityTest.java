package com.noteweave;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * NR06：编辑事件容量。前端默认请求键 edit-{jobId}-{versionNo}-{UUID} 约 80 字符，
 * 事件体在长 Markdown 下远超 8000 字符——V209 后 request_id=128、event_json=text，
 * 普通保存不再因列宽失败回滚新版本。本测试跑在完整 Flyway schema 上验证列宽。
 */
@SpringBootTest
@ActiveProfiles({"test", "test-es"})
class ArtifactEditEventCapacityTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void defaultLengthRequestIdAndLargeEventJsonPersist() {
        String jobId = java.util.UUID.randomUUID().toString();
        String requestId = "edit-" + jobId + "-12-" + java.util.UUID.randomUUID().toString();
        assertThat(requestId.length()).isBetween(8, 128);
        String largeEventJson = "{\"content\":\"" + "长".repeat(9000) + "\"}";
        assertThat(largeEventJson.length()).isGreaterThan(8000);

        jdbcTemplate.update("""
                insert into artifact_edit_event(
                    id, workspace_id, artifact_job_id, request_id, event_json,
                    request_digest, payload_digest, status)
                values (?, 'ws-cap', ?, ?, ?, 'rd', 'pd', 'READY')
                """, java.util.UUID.randomUUID().toString(), jobId, requestId, largeEventJson);

        Integer stored = jdbcTemplate.queryForObject("""
                select length(event_json) from artifact_edit_event where request_id = ?
                """, Integer.class, requestId);
        assertThat(stored).isEqualTo(largeEventJson.length());
    }
}

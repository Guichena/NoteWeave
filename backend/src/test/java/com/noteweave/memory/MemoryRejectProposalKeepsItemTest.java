package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import com.noteweave.conversation.RunReplayRedactionService;
import com.noteweave.security.CurrentUserProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * NR10：拒绝 proposal 不得删除整个 memory_item。
 * 生效头、其他待审提案保留；仅空项（无生效头且无其他提案）才整体回收。
 */
class MemoryRejectProposalKeepsItemTest {

    private JdbcTemplate jdbc;
    private CanonicalMemoryReviewService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:memory-reject-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table memory_item(
                    id varchar(36) primary key, workspace_id varchar(36), semantic_slot_key varchar(128),
                    status varchar(32), review_status varchar(32), current_revision_id varchar(36),
                    lock_version int default 0, updated_at timestamp default current_timestamp)
                """);
        jdbc.execute("""
                create table memory_runtime_revision(
                    id varchar(36) primary key, workspace_id varchar(36), memory_item_id varchar(36),
                    status varchar(32), display_text clob, provenance_type varchar(32),
                    provenance_ref varchar(128), normalized_value_json clob)
                """);
        jdbc.execute("""
                create table memory_event(
                    id varchar(36) primary key, memory_item_id varchar(36), event_type varchar(64),
                    idempotency_key varchar(160), payload_json clob, created_at timestamp default current_timestamp)
                """);
        jdbc.execute("""
                create table memory_candidate(
                    id varchar(36) primary key, memory_item_id varchar(36), review_status varchar(32),
                    updated_at timestamp)
                """);
        CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
        when(currentUser.requireUserId()).thenReturn("reviewer");
        when(currentUser.currentUserId()).thenReturn(java.util.Optional.of("reviewer"));
        WorkspaceAccessGuard guard = mock(WorkspaceAccessGuard.class);
        service = new CanonicalMemoryReviewService(
                jdbc, new ObjectMapper(), currentUser, guard,
                mock(MemoryStatementMatcher.class), mock(RunReplayRedactionService.class),
                mock(MemoryIndexOutbox.class), mock(MemoryEvidenceService.class),
                mock(MemoryTextEmbeddingStore.class));
    }

    private String seedItemWithActiveHeadAndProposal() {
        String itemId = Ids.newId();
        String activeRev = Ids.newId();
        String proposal = Ids.newId();
        jdbc.update("""
                insert into memory_item(id, workspace_id, semantic_slot_key, status, review_status, current_revision_id)
                values (?, 'ws-1', 'preference.language', 'ACTIVE', null, ?)
                """, itemId, activeRev);
        jdbc.update("""
                insert into memory_runtime_revision(id, workspace_id, memory_item_id, status,
                                                    display_text, normalized_value_json)
                values (?, 'ws-1', ?, 'ACTIVE', '生效偏好', '{}')
                """, activeRev, itemId);
        jdbc.update("""
                insert into memory_runtime_revision(id, workspace_id, memory_item_id, status,
                                                    display_text, normalized_value_json)
                values (?, 'ws-1', ?, 'PROPOSED', '新提案', '{}')
                """, proposal, itemId);
        return proposal + ":" + itemId;
    }

    @Test
    void rejectingProposalKeepsActiveHeadAndItem() {
        String[] ids = seedItemWithActiveHeadAndProposal().split(":");
        String proposal = ids[0];
        String itemId = ids[1];

        service.review("ws-1", proposal, new MemoryReviewDecisionRequest("REJECT", null));

        assertThat(jdbc.queryForObject(
                "select status from memory_runtime_revision where id = ?", String.class, proposal))
                .isEqualTo("REJECTED");
        // NR10 核心：item 与其 ACTIVE 生效头必须保留
        assertThat(jdbc.queryForObject(
                "select status from memory_item where id = ?", String.class, itemId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "select status from memory_runtime_revision where memory_item_id = ? and status = 'ACTIVE'",
                String.class, itemId))
                .isEqualTo("ACTIVE");
    }

    @Test
    void rejectingSoleProposalOfEmptyItemRecyclesItem() {
        String itemId = Ids.newId();
        String proposal = Ids.newId();
        jdbc.update("""
                insert into memory_item(id, workspace_id, semantic_slot_key, status, current_revision_id)
                values (?, 'ws-1', 'preference.tone', 'PENDING', ?)
                """, itemId, proposal);
        jdbc.update("""
                insert into memory_runtime_revision(id, workspace_id, memory_item_id, status,
                                                    display_text, normalized_value_json)
                values (?, 'ws-1', ?, 'PROPOSED', '唯一提案', '{}')
                """, proposal, itemId);

        service.review("ws-1", proposal, new MemoryReviewDecisionRequest("REJECT", null));

        assertThat(jdbc.queryForObject(
                "select status from memory_item where id = ?", String.class, itemId))
                .isEqualTo("DELETED");
    }
}

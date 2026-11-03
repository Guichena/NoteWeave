package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.noteweave.common.BusinessException;
import com.noteweave.memory.CanonicalMemoryReviewService;
import com.noteweave.memory.MemoryReviewDecisionRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** F5-03：只用于本对话同时拒绝原候选并生成会话要求，重复点击幂等。 */
class ConversationSessionPreferenceServiceTest {

    private JdbcTemplate jdbc;
    private CanonicalMemoryReviewService reviewService;
    private ConversationSessionPreferenceService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:session-preference-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("insert into workspace(id,owner_id,name,status) values ('w','local-user','测试','ACTIVE')");
        jdbc.update("""
                insert into conversation(id,workspace_id,title,conversation_type,status)
                values ('c','w','测试','QA','ACTIVE')
                """);
        jdbc.update("""
                insert into memory_item(id,workspace_id,memory_scope,scope_ref_key,slot_key,
                    slot_schema_version,status)
                values ('item-1','w','WORKSPACE','w','style.language','v1','PENDING')
                """);
        jdbc.update("""
                insert into memory_runtime_revision(id,memory_item_id,workspace_id,version_no,status,
                    display_text,provenance_type,content_hash)
                values ('rev-1','item-1','w',1,'PROPOSED','回答用中文','OBSERVATION','hash-1')
                """);
        reviewService = mock(CanonicalMemoryReviewService.class);
        service = new ConversationSessionPreferenceService(jdbc, reviewService);
    }

    @Test
    void sessionOnlyRejectsProposalAndCreatesConversationRequirement() {
        var requirement = service.sessionOnly("w", "c", "rev-1");

        assertThat(requirement.requirementText()).isEqualTo("回答用中文");
        assertThat(requirement.status()).isEqualTo("ACTIVE");
        assertThat(service.activeRequirementTexts("w", "c")).containsExactly("回答用中文");
        verify(reviewService).review(eq("w"), eq("rev-1"),
                any(MemoryReviewDecisionRequest.class));
        assertThat(jdbc.queryForObject("""
                select count(*) from conversation_session_requirement
                where workspace_id = 'w' and source_revision_id = 'rev-1' and status = 'ACTIVE'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void repeatedClickIsIdempotentAndDoesNotReviewTwice() {
        var first = service.sessionOnly("w", "c", "rev-1");
        var second = service.sessionOnly("w", "c", "rev-1");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject(
                "select count(*) from conversation_session_requirement", Integer.class)).isEqualTo(1);
    }

    @Test
    void missingRevisionIsRejected() {
        assertThatThrownBy(() -> service.sessionOnly("w", "c", "missing"))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("MEMORY_REVISION_NOT_FOUND"));
        verify(reviewService, never()).review(any(), any(), any());
    }

    @Test
    void textRequirementCanBeAddedListedAndDeactivated() {
        var created = service.addText("w", "c", "回答只使用工作台资料");

        assertThat(service.list("w", "c")).extracting(
                        ConversationSessionPreferenceService.SessionRequirement::requirementText)
                .containsExactly("回答只使用工作台资料");
        assertThat(service.activeRequirementTexts("w", "c"))
                .containsExactly("回答只使用工作台资料");

        service.deactivate("w", "c", created.id());

        assertThat(service.list("w", "c")).isEmpty();
        assertThat(service.activeRequirementTexts("w", "c")).isEmpty();
    }

    @Test
    void emptyTextRequirementIsRejected() {
        assertThatThrownBy(() -> service.addText("w", "c", "  "))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("SESSION_REQUIREMENT_TEXT_EMPTY"));
    }
}

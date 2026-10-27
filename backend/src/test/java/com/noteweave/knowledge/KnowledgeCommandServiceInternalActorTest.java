package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.security.AuditActorProvider;
import com.noteweave.security.CurrentUserProvider;
import com.noteweave.source.eligibility.AllReadableEligibilityStub;
import com.noteweave.workspace.WorkspaceQueryPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * WIKI-05：内部执行写页面时不能继承线程上的当前用户。
 *
 * <p>线上复现路径：请求线程里内联触发 Wiki 编译，或本地免登录回退给出 {@code local-user}，
 * 自动写入被记成用户操作，WIKI-03 的保护逻辑于是把 ingest 自己产出的页面判成人工编辑，
 * 此后所有自动更新都只把页面标成待确认，页面内容永远停在第一版。</p>
 */
class KnowledgeCommandServiceInternalActorTest {

    private JdbcTemplate jdbcTemplate;
    private KnowledgeCommandService commandService;
    private KnowledgeVersionService versionService;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:wiki-internal-actor-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update("""
                insert into workspace(id, owner_id, name, status, wiki_enabled)
                values ('workspace', 'local-user', '工作台', 'ACTIVE', true)
                """);
        // 模拟「线程上挂着一个用户身份」：这正是复现缺陷的前置条件
        CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
        when(currentUser.currentUserId()).thenReturn(Optional.of("local-user"));
        AuditActorProvider actorProvider = new AuditActorProvider(currentUser);
        WorkspaceQueryPort workspaceQueryPort = mock(WorkspaceQueryPort.class);
        when(workspaceQueryPort.exists("workspace")).thenReturn(true);
        KnowledgeWikiMutationService mutationService = new KnowledgeWikiMutationService(jdbcTemplate);
        KnowledgeCitationReadGate citationGate =
                new KnowledgeCitationReadGate(jdbcTemplate, new AllReadableEligibilityStub());
        versionService = new KnowledgeVersionService(
                jdbcTemplate, actorProvider, mutationService, citationGate);
        commandService = new KnowledgeCommandService(
                jdbcTemplate, workspaceQueryPort, actorProvider, versionService,
                mutationService, citationGate);
    }

    @Test
    void automaticIngestStaysAutomaticEvenWhenAUserIsBoundToTheThread() {
        KnowledgeItemResponse first = commandService.upsertWikiSourcePageForInternalExecution(
                "workspace", "资料页", "第一版正文", List.of(), "source-1");

        assertThat(wikiPageState(first.itemId())).isEqualTo("AUTO");
        assertThat(updatedBy(first.itemId())).isEqualTo("SYSTEM:KNOWLEDGE");

        commandService.upsertWikiSourcePageForInternalExecution(
                "workspace", "资料页", "第二版正文", List.of(), "source-1");

        // 关键断言：自动 ingest 不会被自己上一轮的产物挡住，版本继续追加
        assertThat(versionCount(first.itemId())).isEqualTo(2);
        assertThat(wikiPageState(first.itemId())).isEqualTo("AUTO");
        assertThat(latestContent(first.itemId())).isEqualTo("第二版正文");
    }

    @Test
    void manualEditStillBlocksTheNextAutomaticUpdate() {
        KnowledgeItemResponse manual = commandService.createItem(
                "workspace",
                new KnowledgeItemRequest("WIKI", "人工页", "人工写的正文", null));
        assertThat(wikiPageState(manual.itemId())).isEqualTo("MANUAL");

        commandService.upsertWikiPageForInternalExecution(
                "workspace", "人工页", "自动生成的正文", List.of());

        // WIKI-03 的保护不能因为这次修复被削弱
        assertThat(wikiPageState(manual.itemId())).isEqualTo("NEEDS_REVIEW");
        assertThat(versionCount(manual.itemId())).isEqualTo(1);
        assertThat(latestContent(manual.itemId())).isEqualTo("人工写的正文");
    }

    @Test
    void internalDeleteIsAttributedToSystem() {
        KnowledgeItemResponse page = commandService.upsertWikiSourcePageForInternalExecution(
                "workspace", "待删除页", "正文", List.of(), "source-2");

        commandService.deleteItemForInternalExecution("workspace", page.itemId());

        assertThat(updatedBy(page.itemId())).isEqualTo("SYSTEM:KNOWLEDGE");
        assertThat(jdbcTemplate.queryForObject(
                "select status from knowledge_item where id = ?", String.class, page.itemId()))
                .isEqualTo("DELETED");
    }

    private String wikiPageState(String itemId) {
        return jdbcTemplate.queryForObject(
                "select wiki_page_state from knowledge_item where id = ?", String.class, itemId);
    }

    private String updatedBy(String itemId) {
        return jdbcTemplate.queryForObject(
                "select updated_by from knowledge_item where id = ?", String.class, itemId);
    }

    private int versionCount(String itemId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from knowledge_version where item_id = ?", Integer.class, itemId);
        return count == null ? 0 : count;
    }

    private String latestContent(String itemId) {
        return jdbcTemplate.queryForObject("""
                select v.content
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.id = ?
                """, String.class, itemId);
    }
}

package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.noteweave.security.AuditActorProvider;
import com.noteweave.research.ResearchGeneratedSourceReadGate;
import com.noteweave.workspace.WorkspaceQueryPort;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class KnowledgeCommandServiceTest {

    private JdbcTemplate jdbcTemplate;
    private KnowledgeCommandService commandService;
    private WorkspaceQueryPort workspaceQueryPort;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:knowledge-command-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();
        workspaceQueryPort = mock(WorkspaceQueryPort.class);
        when(workspaceQueryPort.exists("workspace")).thenReturn(true);
        AuditActorProvider actorProvider = mock(AuditActorProvider.class);
        when(actorProvider.currentOrSystem("KNOWLEDGE")).thenReturn("actor");
        KnowledgeWikiMutationService mutationService =
                new KnowledgeWikiMutationService(jdbcTemplate);
        ResearchGeneratedSourceReadGate sourceGate = mock(ResearchGeneratedSourceReadGate.class);
        when(sourceGate.readableSourceIds(org.mockito.ArgumentMatchers.eq("workspace"),
                org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> Set.copyOf(invocation.getArgument(1)));
        KnowledgeVersionService versionService = new KnowledgeVersionService(
                jdbcTemplate, actorProvider, mutationService,
                new KnowledgeCitationReadGate(jdbcTemplate, sourceGate));
        commandService = new KnowledgeCommandService(
                jdbcTemplate,
                workspaceQueryPort,
                actorProvider,
                versionService,
                mutationService);
    }

    @Test
    void backgroundWikiUpsertShouldNotRequireHttpWorkspaceIdentity() {
        KnowledgeItemResponse created = commandService.upsertWikiPageForInternalExecution(
                "workspace", "Background Page", "background content", List.of());

        assertThat(created.title()).isEqualTo("Background Page");
        verifyNoInteractions(workspaceQueryPort);
    }

    @Test
    void shouldCreateWikiFromMessageWithNormalizedLinkAndCitationOrder() {
        commandService.createItem(
                "workspace",
                new KnowledgeItemRequest(
                        "WIKI", "Related Page", "related content", null));
        seedAssistantMessageWithCitations();

        KnowledgeItemResponse created = commandService.createItem(
                "workspace",
                new KnowledgeItemRequest(
                        "WIKI",
                        "Concept Page",
                        "Concept definition references Related Page.",
                        "message"));

        assertThat(created.pageKind()).isEqualTo("CONCEPT");
        assertThat(jdbcTemplate.queryForObject(
                "select content from knowledge_version where id = ?",
                String.class,
                created.latestVersionId())).contains("[[Related Page]]");
        assertThat(jdbcTemplate.queryForList("""
                select citation_id
                from knowledge_version_citation
                where knowledge_version_id = ?
                order by sort_order
                """, String.class, created.latestVersionId()))
                .containsExactly("citation-b", "citation-a");
        assertThat(jdbcTemplate.queryForMap("""
                select target_item_id, target_title, relation_type, relation_status
                from knowledge_item_link
                where source_item_id = ?
                """, created.itemId()))
                .containsEntry("TARGET_TITLE", "Related Page")
                .containsEntry("RELATION_TYPE", "WIKI_LINK")
                .containsEntry("RELATION_STATUS", "RESOLVED");
    }

    @Test
    void shouldRenameWikiAndAppendImmutableVersionToIncomingPages() {
        KnowledgeItemResponse target = commandService.createItem(
                "workspace",
                new KnowledgeItemRequest("WIKI", "Old Page", "old definition", null));
        KnowledgeItemResponse incoming = commandService.createItem(
                "workspace",
                new KnowledgeItemRequest(
                        "WIKI", "Incoming Page", "See [[Old Page|alias]].", null));

        KnowledgeItemResponse renamed = commandService.renameItem(
                target.itemId(), new RenameKnowledgeItemRequest("New Page"));

        assertThat(renamed.title()).isEqualTo("New Page");
        assertThat(jdbcTemplate.queryForObject("""
                select v.version_no
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.id = ?
                """, Integer.class, incoming.itemId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                select v.content
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.id = ?
                """, String.class, incoming.itemId()))
                .contains("[[New Page|alias]]");
        assertThat(jdbcTemplate.queryForMap("""
                select target_item_id, target_title, relation_status
                from knowledge_item_link
                where source_item_id = ?
                """, incoming.itemId()))
                .containsEntry("TARGET_ITEM_ID", target.itemId())
                .containsEntry("TARGET_TITLE", "New Page")
                .containsEntry("RELATION_STATUS", "RESOLVED");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from wiki_log_entry
                where event_type in ('RENAME_PAGE', 'REFRESH_LINK_CONTENT')
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void shouldDeleteWikiAndInvalidateIncomingLinks() {
        KnowledgeItemResponse target = commandService.createItem(
                "workspace",
                new KnowledgeItemRequest("WIKI", "Delete Target", "target", null));
        KnowledgeItemResponse incoming = commandService.createItem(
                "workspace",
                new KnowledgeItemRequest(
                        "WIKI", "Delete Incoming", "See [[Delete Target]].", null));

        commandService.deleteItem(target.itemId());

        assertThat(jdbcTemplate.queryForObject(
                "select status from knowledge_item where id = ?",
                String.class,
                target.itemId())).isEqualTo("DELETED");
        assertThat(jdbcTemplate.queryForMap("""
                select target_item_id, relation_status
                from knowledge_item_link
                where source_item_id = ?
                """, incoming.itemId()))
                .containsEntry("TARGET_ITEM_ID", null)
                .containsEntry("RELATION_STATUS", "UNRESOLVED");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from wiki_log_entry
                where item_id = ? and event_type = 'DELETE_PAGE'
                """, Integer.class, target.itemId())).isEqualTo(1);
    }

    private void seedAssistantMessageWithCitations() {
        jdbcTemplate.update("""
                insert into conversation_message(id, workspace_id, role, content)
                values ('message', 'workspace', 'ASSISTANT', 'answer')
                """);
        jdbcTemplate.update("""
                insert into message_citation(message_id, citation_id, sort_order)
                values ('message', 'citation-b', 0), ('message', 'citation-a', 1)
                """);
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                create table knowledge_item(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    item_type varchar(32) not null,
                    page_kind varchar(32),
                    title varchar(300) not null,
                    status varchar(32) not null,
                    latest_version_id varchar(36),
                    created_by varchar(80),
                    updated_by varchar(80),
                    updated_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table knowledge_version(
                    id varchar(36) primary key,
                    item_id varchar(36) not null,
                    version_no int not null,
                    content clob not null,
                    summary varchar(1000),
                    source_message_id varchar(36),
                    created_at timestamp default current_timestamp,
                    unique(item_id, version_no)
                )
                """);
        jdbcTemplate.execute("""
                create table knowledge_version_citation(
                    id varchar(36) primary key,
                    knowledge_version_id varchar(36) not null,
                    citation_id varchar(36) not null,
                    sort_order int not null
                )
                """);
        jdbcTemplate.execute("""
                create table citation(
                    id varchar(36) primary key,
                    source_id varchar(36) not null
                )
                """);
        jdbcTemplate.execute("""
                create table conversation_message(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    role varchar(32) not null,
                    content clob not null
                )
                """);
        jdbcTemplate.execute("""
                create table message_citation(
                    message_id varchar(36) not null,
                    citation_id varchar(36) not null,
                    sort_order int not null
                )
                """);
        jdbcTemplate.execute("""
                create table knowledge_item_link(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    source_item_id varchar(36) not null,
                    target_item_id varchar(36),
                    target_title varchar(300) not null,
                    relation_type varchar(64) not null,
                    relation_status varchar(32) not null,
                    mention_count int not null,
                    updated_at timestamp default current_timestamp
                )
                """);
        jdbcTemplate.execute("""
                create table wiki_log_entry(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    item_id varchar(36),
                    event_type varchar(64) not null,
                    message varchar(1000) not null,
                    created_at timestamp default current_timestamp
                )
                """);
    }
}

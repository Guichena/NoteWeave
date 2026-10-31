package com.noteweave.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.noteweave.source.eligibility.AllReadableEligibilityStub;
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
    private AllReadableEligibilityStub eligibilityStub;

    private JdbcTemplate jdbcTemplate;
    private KnowledgeCommandService commandService;
    private WorkspaceQueryPort workspaceQueryPort;
    private ResearchGeneratedSourceReadGate sourceGate;
    private AuditActorProvider actorProvider;
    private KnowledgeVersionService versionService;
    private KnowledgeCitationReadGate citationGate;

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
        actorProvider = mock(AuditActorProvider.class);
        when(actorProvider.currentOrSystem("KNOWLEDGE")).thenReturn("actor");
        KnowledgeWikiMutationService mutationService =
                new KnowledgeWikiMutationService(jdbcTemplate);
        sourceGate = mock(ResearchGeneratedSourceReadGate.class);
        when(sourceGate.readableSourceIds(org.mockito.ArgumentMatchers.eq("workspace"),
                org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> Set.copyOf(invocation.getArgument(1)));
        eligibilityStub = new AllReadableEligibilityStub();
        citationGate = new KnowledgeCitationReadGate(jdbcTemplate, eligibilityStub);
        versionService = new KnowledgeVersionService(
                jdbcTemplate, actorProvider, mutationService,
                citationGate);
        commandService = new KnowledgeCommandService(
                jdbcTemplate,
                workspaceQueryPort,
                actorProvider,
                versionService,
                mutationService,
                citationGate);
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
    void revokedMessageCitationMustNotCreateKnowledgeItem() {
        seedAssistantMessageWithCitations();
        eligibilityStub.revokeAll();

        assertThatThrownBy(() -> commandService.createItem("workspace",
                new KnowledgeItemRequest("WIKI", "Revoked Page", "derived content", "message")))
                .isInstanceOfSatisfying(com.noteweave.common.BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("KNOWLEDGE_CITATION_REVOKED"));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_item where title = 'Revoked Page'", Integer.class))
                .isZero();
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

    @Test
    void internalIngestShouldNotOverwriteManuallyEditedPage() {
        // WIKI-03：自动 ingest 命中人工编辑过的页面时只标记待确认，不追加版本、不改正文
        when(actorProvider.currentOrSystem("KNOWLEDGE")).thenReturn("SYSTEM:KNOWLEDGE");
        KnowledgeItemResponse created = commandService.upsertWikiSourcePageForInternalExecution(
                "workspace", "Source Page", "auto content", List.of(), "source-1");
        assertThat(wikiPageState(created.itemId())).isEqualTo("AUTO");

        // 用户手工编辑：面向用户的版本追加，actor 是真实用户
        when(actorProvider.currentOrSystem("KNOWLEDGE")).thenReturn("user-1");
        versionService.appendVersion(created.itemId(),
                new AppendKnowledgeVersionRequest("manual content", null, List.of()));
        assertThat(wikiPageState(created.itemId())).isEqualTo("MANUAL");

        // 资料再次变化触发的自动 ingest 必须让路
        when(actorProvider.currentOrSystem("KNOWLEDGE")).thenReturn("SYSTEM:KNOWLEDGE");
        commandService.upsertWikiSourcePageForInternalExecution(
                "workspace", "Source Page", "regenerated content", List.of(), "source-1");

        assertThat(latestContent(created.itemId())).isEqualTo("manual content");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from knowledge_version where item_id = ?",
                Integer.class, created.itemId())).isEqualTo(2);
        assertThat(wikiPageState(created.itemId())).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from wiki_log_entry
                where item_id = ? and event_type = 'SKIP_MANUAL_EDIT'
                """, Integer.class, created.itemId())).isEqualTo(1);
    }

    @Test
    void needsReviewPageShouldStayProtectedUntilUserEditsAgain() {
        when(actorProvider.currentOrSystem("KNOWLEDGE")).thenReturn("SYSTEM:KNOWLEDGE");
        KnowledgeItemResponse created = commandService.upsertWikiSourcePageForInternalExecution(
                "workspace", "Source Page", "auto content", List.of(), "source-1");
        when(actorProvider.currentOrSystem("KNOWLEDGE")).thenReturn("user-1");
        versionService.appendVersion(created.itemId(),
                new AppendKnowledgeVersionRequest("manual content", null, List.of()));
        when(actorProvider.currentOrSystem("KNOWLEDGE")).thenReturn("SYSTEM:KNOWLEDGE");
        commandService.upsertWikiSourcePageForInternalExecution(
                "workspace", "Source Page", "regenerated content", List.of(), "source-1");
        assertThat(wikiPageState(created.itemId())).isEqualTo("NEEDS_REVIEW");

        // 用户再次编辑：状态回到 MANUAL，自动更新继续让路
        when(actorProvider.currentOrSystem("KNOWLEDGE")).thenReturn("user-1");
        versionService.appendVersion(created.itemId(),
                new AppendKnowledgeVersionRequest("manual v2", null, List.of()));
        assertThat(wikiPageState(created.itemId())).isEqualTo("MANUAL");

        when(actorProvider.currentOrSystem("KNOWLEDGE")).thenReturn("SYSTEM:KNOWLEDGE");
        commandService.upsertWikiSourcePageForInternalExecution(
                "workspace", "Source Page", "third auto content", List.of(), "source-1");
        assertThat(latestContent(created.itemId())).isEqualTo("manual v2");
    }

    private String wikiPageState(String itemId) {
        return jdbcTemplate.queryForObject(
                "select wiki_page_state from knowledge_item where id = ?", String.class, itemId);
    }

    private String latestContent(String itemId) {
        return jdbcTemplate.queryForObject("""
                select v.content
                from knowledge_item i
                join knowledge_version v on v.id = i.latest_version_id
                where i.id = ?
                """, String.class, itemId);
    }

    @Test
    void artifactWritebackShouldBindSourceCitationsAndMarkOrigin() {
        seedArtifactSource("source-1", "资料一", "snap-1", 1);

        KnowledgeItemResponse item = commandService.createArtifactItem(
                "workspace", "NOTE", "产物笔记", "产物正文",
                List.of(new ArtifactSourceCitation(
                        "source-1", "snap-1", "资料一", "原文引用", "第 2 节")));

        assertThat(jdbcTemplate.queryForObject(
                "select origin from knowledge_item where id = ?", String.class, item.itemId()))
                .isEqualTo("ARTIFACT_SOURCED");
        assertThat(item.citationCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("""
                select c.source_id, c.source_snapshot_id, c.source_chunk_id, c.quote_text
                from knowledge_version_citation kvc
                join citation c on c.id = kvc.citation_id
                where kvc.knowledge_version_id = ?
                """, item.latestVersionId()))
                .containsEntry("SOURCE_ID", "source-1")
                .containsEntry("SOURCE_SNAPSHOT_ID", "snap-1")
                .containsEntry("SOURCE_CHUNK_ID", null)
                .containsEntry("QUOTE_TEXT", "原文引用");
    }

    @Test
    void artifactWritebackWithoutSourcesShouldStayReadable() {
        KnowledgeItemResponse item = commandService.createArtifactItem(
                "workspace", "NOTE", "无来源产物", "正文", List.of());

        assertThat(jdbcTemplate.queryForObject(
                "select origin from knowledge_item where id = ?", String.class, item.itemId()))
                .isEqualTo("ARTIFACT_UNSOURCED");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from knowledge_version_citation where knowledge_version_id = ?
                """, Integer.class, item.latestVersionId())).isZero();
    }

    @Test
    void artifactWritebackWithUnresolvableSourcesShouldFail() {
        // 引用声明了来源，但来源没有任何快照可绑定：写回必须失败，不留下不可读的知识对象
        assertThatThrownBy(() -> commandService.createArtifactItem(
                "workspace", "NOTE", "失血产物", "正文",
                List.of(new ArtifactSourceCitation("source-missing", null, "缺失来源", "", null))))
                .hasMessageContaining("来源已不可追溯");

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from knowledge_item where title = '失血产物'
                """, Integer.class)).isZero();
    }

    @Test
    void artifactDerivedVersionWithoutCitationsShouldNotBeReadable() {
        seedArtifactSource("source-1", "资料一", "snap-1", 1);
        KnowledgeItemResponse artifactItem = commandService.createArtifactItem(
                "workspace", "NOTE", "有来源产物", "正文",
                List.of(new ArtifactSourceCitation("source-1", "snap-1", "资料一", "", null)));
        // 模拟血缘丢失：引用绑定被清掉后，产物写回的内容不能默认放行
        jdbcTemplate.update("""
                delete from knowledge_version_citation where knowledge_version_id = ?
                """, artifactItem.latestVersionId());

        assertThat(citationGate.readableVersionIds(
                "workspace", List.of(artifactItem.latestVersionId()))).isEmpty();

        // 人工笔记本来就没有来源依赖，缺引用照常可读
        KnowledgeItemResponse note = commandService.createItem(
                "workspace", new KnowledgeItemRequest("NOTE", "手写笔记", "正文", null));
        assertThat(citationGate.readableVersionIds(
                "workspace", List.of(note.latestVersionId())))
                .containsExactly(note.latestVersionId());
    }

    private void seedArtifactSource(String sourceId, String title, String snapshotId, int versionNo) {
        jdbcTemplate.update("""
                insert into source(id, workspace_id, title) values (?, 'workspace', ?)
                """, sourceId, title);
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, version_no) values (?, ?, ?)
                """, snapshotId, sourceId, versionNo);
    }

    private void seedAssistantMessageWithCitations() {
        jdbcTemplate.update("""
                insert into citation(id, source_id)
                values ('citation-a', 'source'), ('citation-b', 'source')
                """);
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
                    origin_source_id varchar(36),
                    wiki_page_state varchar(32),
                    origin varchar(32),
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
                    workspace_id varchar(36) not null default 'workspace',
                    source_id varchar(36) not null,
                    research_run_id varchar(36),
                    source_snapshot_id varchar(36),
                    source_chunk_id varchar(36),
                    title varchar(300),
                    quote_text clob,
                    page_no int,
                    location_info varchar(300)
                )
                """);
        jdbcTemplate.execute("""
                create table source(
                    id varchar(36) primary key,
                    workspace_id varchar(36) not null,
                    title varchar(300) not null
                )
                """);
        jdbcTemplate.execute("""
                create table source_snapshot(
                    id varchar(36) primary key,
                    source_id varchar(36) not null,
                    version_no int not null
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

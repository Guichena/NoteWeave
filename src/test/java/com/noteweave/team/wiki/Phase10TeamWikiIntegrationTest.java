package com.noteweave.team.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.llm.dto.LlmResponse;
import com.noteweave.llm.service.LlmClient;
import com.noteweave.support.ContainerizedIntegrationTest;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.service.TaskDispatcher;
import com.noteweave.team.document.dto.DocumentProcessTaskPayload;
import com.noteweave.team.document.service.DocumentProcessingService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "noteweave.rag.retrieval.mode=HYBRID",
        "noteweave.embedding.enabled=true",
        "noteweave.embedding.stub.enabled=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Phase10TeamWikiIntegrationTest extends ContainerizedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TaskDispatcher taskDispatcher;

    @Autowired
    private DocumentProcessingService documentProcessingService;

    @MockBean
    private LlmClient llmClient;

    @Test
    void editorShouldCreateDraftAndOwnerShouldPublishWithVersionAndIndexTask() throws Exception {
        String ownerToken = registerAndGetToken("phase10_owner_" + System.nanoTime());
        String editorName = "phase10_editor_" + System.nanoTime();
        String editorToken = registerAndGetToken(editorName);
        String viewerName = "phase10_viewer_" + System.nanoTime();
        String viewerToken = registerAndGetToken(viewerName);

        Long spaceId = createTeamSpace(ownerToken, "phase10-team-" + System.nanoTime());
        addMember(ownerToken, spaceId, editorName + "@example.com", "EDITOR");
        addMember(ownerToken, spaceId, viewerName + "@example.com", "VIEWER");

        mockMvc.perform(post("/api/v1/team/spaces/{spaceId}/wiki-pages", spaceId)
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Viewer draft",
                                  "content": "should fail"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WIKI_ACCESS_DENIED"));

        JsonNode created = readJson(mockMvc.perform(post("/api/v1/team/spaces/{spaceId}/wiki-pages", spaceId)
                        .header("Authorization", "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Deployment FAQ",
                                  "content": "Draft rollout checklist"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn());

        Long pageId = created.path("data").path("id").asLong();

        mockMvc.perform(get("/api/v1/team/wiki-pages/{pageId}", pageId)
                        .header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(pageId))
                .andExpect(jsonPath("$.data.title").value("Deployment FAQ"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        mockMvc.perform(put("/api/v1/team/wiki-pages/{pageId}", pageId)
                        .header("Authorization", "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Deployment FAQ Updated",
                                  "content": "Draft rollout checklist updated"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Deployment FAQ Updated"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        mockMvc.perform(post("/api/v1/team/wiki-pages/{pageId}/publish", pageId)
                        .header("Authorization", "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "changeNote": "editor cannot publish"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WIKI_ACCESS_DENIED"));

        JsonNode published = readJson(mockMvc.perform(post("/api/v1/team/wiki-pages/{pageId}/publish", pageId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "changeNote": "first publish"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.publishedVersionNo").value(1))
                .andReturn());

        Long publishedVersionId = published.path("data").path("publishedVersionId").asLong();
        Long indexTaskId = jdbcTemplate.queryForObject(
                "select id from task where task_type = 'WIKI_INDEX' and target_id = ? order by created_at desc limit 1",
                Long.class,
                pageId
        );
        assertThat(indexTaskId).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wiki_page_version where wiki_page_id = ?",
                Integer.class,
                pageId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select published_version_id from wiki_page where id = ?",
                Long.class,
                pageId
        )).isEqualTo(publishedVersionId);

        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(indexTaskId, TaskStatus.SUCCESS);

        mockMvc.perform(get("/api/v1/team/wiki-pages/{pageId}/versions", pageId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].versionNo").value(1))
                .andExpect(jsonPath("$.data[0].changeNote").value("first publish"));
    }

    @Test
    void artifactAndChatMessageShouldCreateTraceableWikiDraftsAndPublishShouldWriteCitationRelation() throws Exception {
        String ownerToken = registerAndGetToken("phase10_artifact_owner_" + System.nanoTime());
        String viewerName = "phase10_artifact_viewer_" + System.nanoTime();
        String viewerToken = registerAndGetToken(viewerName);

        Long spaceId = createTeamSpace(ownerToken, "phase10-artifact-team-" + System.nanoTime());
        addMember(ownerToken, spaceId, viewerName + "@example.com", "VIEWER");
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase10-artifact-kb-" + System.nanoTime());
        IndexedDocument indexedDocument = uploadAndProcess(
                ownerToken,
                spaceId,
                kbId,
                "deploy.txt",
                "text/plain",
                "Rollback rehearsal must finish before any production deployment.".getBytes(StandardCharsets.UTF_8)
        );
        Long autoWikiPageId = jdbcTemplate.queryForObject(
                "select id from wiki_page where source_document_id = ? and auto_maintained = true",
                Long.class,
                indexedDocument.documentId()
        );
        assertThat(autoWikiPageId).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "select status from wiki_page where id = ?",
                String.class,
                autoWikiPageId
        )).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wiki_page_citation where wiki_page_id = ?",
                Integer.class,
                autoWikiPageId
        )).isGreaterThanOrEqualTo(1);

        givenStubAnswer("Rollback rehearsal must finish before any production deployment.");
        Long sessionId = createChatSession(viewerToken, spaceId, "wiki-draft session", "KNOWLEDGE_BASE", new long[]{kbId});
        Long assistantMessageId = askQuestion(viewerToken, sessionId, "What is required before production deployment?");

        JsonNode artifact = createArtifactFromChatMessage(viewerToken, spaceId, sessionId, assistantMessageId, "Deployment FAQ");
        Long artifactId = artifact.path("data").path("artifactId").asLong();
        Long artifactTaskId = artifact.path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(artifactTaskId, TaskStatus.SUCCESS);

        JsonNode draftFromArtifact = readJson(mockMvc.perform(post("/api/v1/artifacts/{artifactId}/publish-to-wiki", artifactId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "spaceId": %d,
                                  "title": "Deployment FAQ Wiki"
                                }
                                """.formatted(spaceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.sourceArtifactId").value(artifactId))
                .andReturn());

        Long artifactDraftId = draftFromArtifact.path("data").path("id").asLong();

        JsonNode draftFromMessage = readJson(mockMvc.perform(post("/api/v1/team/chat-messages/{messageId}/wiki-drafts", assistantMessageId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Deployment Answer Draft"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.title").value("Deployment Answer Draft"))
                .andReturn());

        Long messageDraftId = draftFromMessage.path("data").path("id").asLong();

        JsonNode published = readJson(mockMvc.perform(post("/api/v1/team/wiki-pages/{pageId}/publish", messageDraftId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "changeNote": "publish answer"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());

        Long indexTaskId = jdbcTemplate.queryForObject(
                "select id from task where task_type = 'WIKI_INDEX' and target_id = ? order by created_at desc limit 1",
                Long.class,
                messageDraftId
        );
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(indexTaskId, TaskStatus.SUCCESS);

        assertThat(jdbcTemplate.queryForObject(
                "select status from artifact where id = ?",
                String.class,
                artifactId
        )).isEqualTo("READY");

        Long artifactSourceId = jdbcTemplate.queryForObject(
                "select source_artifact_id from wiki_page where id = ?",
                Long.class,
                artifactDraftId
        );
        assertThat(artifactSourceId).isEqualTo(artifactId);

        Integer citationCount = jdbcTemplate.queryForObject(
                "select count(*) from wiki_page_citation where wiki_page_id = ? and wiki_page_version_id = ?",
                Integer.class,
                messageDraftId,
                published.path("data").path("publishedVersionId").asLong()
        );
        assertThat(citationCount).isGreaterThanOrEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wiki_page_citation wpc join citation c on c.id = wpc.citation_id where wpc.wiki_page_id = ? and c.source_type = 'DOCUMENT' and c.source_id = ?",
                Integer.class,
                messageDraftId,
                indexedDocument.documentId()
        )).isGreaterThanOrEqualTo(1);

        mockMvc.perform(get("/api/v1/team/spaces/{spaceId}/wiki-graph", spaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.totalPageCount").value(3))
                .andExpect(jsonPath("$.data.summary.sourceBackedPageCount").value(3))
                .andExpect(jsonPath("$.data.summary.evidenceBackedPageCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.nodes[?(@.id==%d)].sourceType".formatted(messageDraftId)).value(org.hamcrest.Matchers.hasItem("CHAT_MESSAGE")))
                .andExpect(jsonPath("$.data.nodes[?(@.id==%d)].evidenceCitationCount".formatted(messageDraftId)).value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.greaterThanOrEqualTo(1))));

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-graph/path", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .queryParam("sourceNodeId", "WIKI_PAGE:" + messageDraftId)
                        .queryParam("targetNodeId", "DOCUMENT:" + indexedDocument.documentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceNodeId").value("WIKI_PAGE:" + messageDraftId))
                .andExpect(jsonPath("$.data.targetNodeId").value("DOCUMENT:" + indexedDocument.documentId()))
                .andExpect(jsonPath("$.data.edges[?(@.type=='WIKI_CITES_DOCUMENT')]").exists());

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-graph/path", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .queryParam("sourceNodeId", "WIKI_PAGE:" + autoWikiPageId)
                        .queryParam("targetNodeId", "DOCUMENT:" + indexedDocument.documentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.edges[?(@.type=='WIKI_SOURCE_DOCUMENT')]").exists());

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-graph", spaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.edges[?(@.type=='WIKI_SOURCE_MESSAGE')]").exists())
                .andExpect(jsonPath("$.data.edges[?(@.type=='CHAT_CITES_DOCUMENT')]").exists())
                .andExpect(jsonPath("$.data.edges[?(@.type=='CHAT_GENERATES_ARTIFACT')]").exists())
                .andExpect(jsonPath("$.data.edges[?(@.type=='WIKI_SOURCE_ARTIFACT')]").exists());
    }

    @Test
    void knowledgeGraphShouldIgnoreCrossSpaceChatReferencesEvenIfCorruptedIdsLeakIntoRecords() throws Exception {
        String ownerName = "phase10_graph_isolation_owner_" + System.nanoTime();
        String ownerToken = registerAndGetToken(ownerName);
        Long ownerId = jdbcTemplate.queryForObject("select id from users where username = ?", Long.class, ownerName);

        Long spaceId = createTeamSpace(ownerToken, "phase10-graph-isolation-team-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase10-graph-isolation-kb-" + System.nanoTime());

        Long foreignSpaceId = createTeamSpace(ownerToken, "phase10-graph-isolation-foreign-team-" + System.nanoTime());
        Long foreignKbId = createKnowledgeBase(ownerToken, foreignSpaceId, "phase10-graph-isolation-foreign-kb-" + System.nanoTime());

        givenStubAnswer("Rollback rehearsal must finish before any production deployment.");
        Long localSessionId = createChatSession(ownerToken, spaceId, "graph isolation local session", "KNOWLEDGE_BASE", new long[]{kbId});
        Long localMessageId = askQuestion(ownerToken, localSessionId, "What must finish before deployment?");

        Long foreignSessionId = createChatSession(ownerToken, foreignSpaceId, "graph isolation foreign session", "KNOWLEDGE_BASE", new long[]{foreignKbId});
        Long foreignMessageId = askQuestion(ownerToken, foreignSessionId, "What must finish before deployment?");

        Long artifactId = insertArtifact(ownerId, spaceId, localSessionId, localMessageId, "Graph Isolation Artifact");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from artifact_source where artifact_id = ? and source_type = 'CHAT_MESSAGE'",
                Integer.class,
                artifactId
        )).isGreaterThan(0);

        JsonNode draft = readJson(mockMvc.perform(post("/api/v1/team/spaces/{spaceId}/wiki-pages", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Graph Isolation Wiki",
                                  "content": "This draft should never expose foreign chat ids."
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());
        Long wikiPageId = draft.path("data").path("id").asLong();

        jdbcTemplate.update("update artifact set created_from_message_id = ? where id = ?", foreignMessageId, artifactId);
        jdbcTemplate.update("update artifact_source set source_id = ? where artifact_id = ? and source_type = 'CHAT_MESSAGE'", foreignMessageId, artifactId);
        jdbcTemplate.update("update wiki_page set source_message_id = ? where id = ?", foreignMessageId, wikiPageId);

        JsonNode graph = readJson(mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-graph", spaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn());

        String foreignNodeId = "CHAT_MESSAGE:" + foreignMessageId;
        for (JsonNode node : graph.path("data").path("nodes")) {
            assertThat(node.path("id").asText()).isNotEqualTo(foreignNodeId);
        }
        for (JsonNode edge : graph.path("data").path("edges")) {
            assertThat(edge.path("sourceId").asText()).isNotEqualTo(foreignNodeId);
            assertThat(edge.path("targetId").asText()).isNotEqualTo(foreignNodeId);
        }
    }

    @Test
    void deletingDocumentShouldArchiveAutoMaintainedWikiPage() throws Exception {
        String ownerToken = registerAndGetToken("phase10_auto_delete_owner_" + System.nanoTime());
        Long spaceId = createTeamSpace(ownerToken, "phase10-auto-delete-team-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase10-auto-delete-kb-" + System.nanoTime());
        IndexedDocument indexedDocument = uploadAndProcess(
                ownerToken,
                spaceId,
                kbId,
                "auto-wiki-delete.txt",
                "text/plain",
                "Automatic wiki pages should disappear from active views when source documents are deleted.".getBytes(StandardCharsets.UTF_8)
        );
        Long autoWikiPageId = jdbcTemplate.queryForObject(
                "select id from wiki_page where source_document_id = ? and auto_maintained = true",
                Long.class,
                indexedDocument.documentId()
        );

        mockMvc.perform(delete("/api/v1/team/documents/{documentId}", indexedDocument.documentId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "select status from wiki_page where id = ?",
                String.class,
                autoWikiPageId
        )).isEqualTo("ARCHIVED");
        assertThat(jdbcTemplate.queryForObject(
                "select deleted_at is not null from wiki_page where id = ?",
                Boolean.class,
                autoWikiPageId
        )).isTrue();
    }

    @Test
    void publishedButUnindexedWikiShouldStayOutOfChatRetrievalUntilIndexTaskSucceedsAndFailedTaskShouldBeRetryable() throws Exception {
        String ownerToken = registerAndGetToken("phase10_retrieval_owner_" + System.nanoTime());

        Long spaceId = createTeamSpace(ownerToken, "phase10-retrieval-team-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase10-retrieval-kb-" + System.nanoTime());

        JsonNode created = readJson(mockMvc.perform(post("/api/v1/team/spaces/{spaceId}/wiki-pages", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Rollback Wiki",
                                  "content": "Rollback rehearsal must finish before any production deployment."
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());

        Long pageId = created.path("data").path("id").asLong();
        readJson(mockMvc.perform(post("/api/v1/team/wiki-pages/{pageId}/publish", pageId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "changeNote": "first publish"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());

        Long indexTaskId = jdbcTemplate.queryForObject(
                "select id from task where task_type = 'WIKI_INDEX' and target_id = ? order by created_at desc limit 1",
                Long.class,
                pageId
        );

        Long sessionId = createChatSession(ownerToken, spaceId, "wiki retrieval session", "KNOWLEDGE_BASE", new long[]{kbId});

        JsonNode beforeIndex = readJson(mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "What must finish before any production deployment?"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(beforeIndex.path("data").path("citations").isArray()).isTrue();
        assertThat(beforeIndex.path("data").path("citations").size()).isEqualTo(0);

        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(indexTaskId, TaskStatus.SUCCESS);

        givenStubAnswer("Rollback rehearsal must finish before any production deployment.");
        JsonNode afterIndex = readJson(mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "What must finish before any production deployment?"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(afterIndex.path("data").path("citations").size()).isGreaterThanOrEqualTo(1);
        assertThat(afterIndex.path("data").path("citations").get(0).path("sourceType").asText()).isEqualTo("WIKI_PAGE");
        assertThat(afterIndex.path("data").path("citations").get(0).path("sourceId").asLong()).isEqualTo(pageId);
        assertThat(afterIndex.path("data").path("citations").get(0).path("sourceVersion").asText())
                .isEqualTo(String.valueOf(jdbcTemplate.queryForObject(
                        "select published_version_id from wiki_page where id = ?",
                        Long.class,
                        pageId
                )));

        mockMvc.perform(get("/api/v1/team/spaces/{spaceId}/wiki-pages/search", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .queryParam("keyword", "production deployment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(pageId))
                .andExpect(jsonPath("$.data.items[0].title").value("Rollback Wiki"));

        // Simulate a failed index task that can be retried.
        jdbcTemplate.update(
                "update task set task_status = 'FAILED', retry_count = 0, error_message = 'forced failure' where id = ?",
                indexTaskId
        );
        mockMvc.perform(post("/api/v1/tasks/{taskId}/retry", indexTaskId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("PENDING"));
    }

    @Test
    void wikiGraphShouldBuildResolvedAndMissingLinksAndSupportSubgraphTraversal() throws Exception {
        String ownerToken = registerAndGetToken("phase10_graph_owner_" + System.nanoTime());
        String viewerName = "phase10_graph_viewer_" + System.nanoTime();
        String viewerToken = registerAndGetToken(viewerName);

        Long spaceId = createTeamSpace(ownerToken, "phase10-graph-team-" + System.nanoTime());
        addMember(ownerToken, spaceId, viewerName + "@example.com", "VIEWER");

        Long deployPageId = createWikiDraftAndPublish(ownerToken, spaceId, "Deploy Flow", """
                Deploy depends on [[Rollback Playbook]] and [[Release Checklist]].
                """);
        Long rollbackPageId = createWikiDraftAndPublish(ownerToken, spaceId, "Rollback Playbook", """
                Rollback points back to [[Deploy Flow]].
                """);
        Long checklistPageId = createWikiDraftAndPublish(ownerToken, spaceId, "Release Checklist", """
                Checklist links to [[Deploy Flow]].
                """);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wiki_page_link where source_page_id = ? and target_page_id = ? and relation_status = 'RESOLVED' and mention_count = 1",
                Integer.class,
                deployPageId,
                rollbackPageId
        )).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wiki_page_link where source_page_id = ? and target_title = ? and relation_status = 'RESOLVED'",
                Integer.class,
                deployPageId,
                "Release Checklist"
        )).isEqualTo(1);

        mockMvc.perform(get("/api/v1/team/spaces/{spaceId}/wiki-graph", spaceId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodeCount").value(3))
                .andExpect(jsonPath("$.data.edgeCount").value(4))
                .andExpect(jsonPath("$.data.summary.totalPageCount").value(3))
                .andExpect(jsonPath("$.data.summary.resolvedEdgeCount").value(4))
                .andExpect(jsonPath("$.data.summary.unresolvedLinkCount").value(0))
                .andExpect(jsonPath("$.data.summary.orphanPageCount").value(0))
                .andExpect(jsonPath("$.data.nodes[?(@.title=='Deploy Flow')].outgoingResolvedCount").value(org.hamcrest.Matchers.hasItem(2)))
                .andExpect(jsonPath("$.data.nodes[?(@.title=='Deploy Flow')].incomingResolvedCount").value(org.hamcrest.Matchers.hasItem(2)))
                .andExpect(jsonPath("$.data.nodes[?(@.title=='Deploy Flow')].linkCount").value(org.hamcrest.Matchers.hasItem(4)))
                .andExpect(jsonPath("$.data.nodes[?(@.title=='Deploy Flow')]").exists());

        mockMvc.perform(get("/api/v1/team/spaces/{spaceId}/wiki-pages", spaceId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id==%d)].relationSummary.outgoingResolvedCount".formatted(deployPageId)).value(org.hamcrest.Matchers.hasItem(2)))
                .andExpect(jsonPath("$.data[?(@.id==%d)].relationSummary.incomingResolvedCount".formatted(deployPageId)).value(org.hamcrest.Matchers.hasItem(2)))
                .andExpect(jsonPath("$.data[?(@.id==%d)].relationSummary.neighborCount".formatted(deployPageId)).value(org.hamcrest.Matchers.hasItem(2)));

        mockMvc.perform(get("/api/v1/team/wiki-pages/{pageId}/relations", deployPageId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageId").value(deployPageId))
                .andExpect(jsonPath("$.data.summary.outgoingResolvedCount").value(2))
                .andExpect(jsonPath("$.data.summary.incomingResolvedCount").value(2))
                .andExpect(jsonPath("$.data.outgoingLinks[0].title").value("Release Checklist"))
                .andExpect(jsonPath("$.data.outgoingLinks[1].title").value("Rollback Playbook"))
                .andExpect(jsonPath("$.data.incomingLinks[0].title").value("Release Checklist"))
                .andExpect(jsonPath("$.data.incomingLinks[1].title").value("Rollback Playbook"))
                .andExpect(jsonPath("$.data.neighborPages[0].bidirectional").value(true));

        JsonNode missingDraft = readJson(mockMvc.perform(post("/api/v1/team/spaces/{spaceId}/wiki-pages", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Missing Link Draft",
                                  "content": "This page references [[Ghost Note]]."
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());
        Long missingDraftId = missingDraft.path("data").path("id").asLong();

        mockMvc.perform(get("/api/v1/team/wiki-pages/{pageId}/graph", missingDraftId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("depth", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rootPageId").value(missingDraftId))
                .andExpect(jsonPath("$.data.nodeCount").value(1))
                .andExpect(jsonPath("$.data.edgeCount").value(0))
                .andExpect(jsonPath("$.data.summary.totalPageCount").value(4))
                .andExpect(jsonPath("$.data.summary.missingLinkCount").value(1))
                .andExpect(jsonPath("$.data.nodes[0].unresolvedOutgoingCount").value(1))
                .andExpect(jsonPath("$.data.unresolvedLinks[0].targetTitle").value("Ghost Note"))
                .andExpect(jsonPath("$.data.unresolvedLinks[0].relationStatus").value("MISSING"));

        mockMvc.perform(get("/api/v1/team/wiki-pages/{pageId}/relations", missingDraftId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.outgoingResolvedCount").value(0))
                .andExpect(jsonPath("$.data.summary.unresolvedOutgoingCount").value(1))
                .andExpect(jsonPath("$.data.unresolvedLinks[0].targetTitle").value("Ghost Note"))
                .andExpect(jsonPath("$.data.unresolvedLinks[0].candidatePages").isEmpty());

        mockMvc.perform(get("/api/v1/team/wiki-pages/{pageId}/graph", deployPageId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("depth", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rootPageId").value(deployPageId))
                .andExpect(jsonPath("$.data.depth").value(1))
                .andExpect(jsonPath("$.data.nodeCount").value(3))
                .andExpect(jsonPath("$.data.edgeCount").value(4));

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-graph", spaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[?(@.type=='WIKI_PAGE')]").exists())
                .andExpect(jsonPath("$.data.edges[?(@.type=='WIKI_LINK')]").exists());

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-graph", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .queryParam("nodeTypes", "WIKI_PAGE")
                        .queryParam("edgeTypes", "WIKI_LINK")
                        .queryParam("onlyPublished", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[*].type").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("WIKI_PAGE"))))
                .andExpect(jsonPath("$.data.edges[*].type").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("WIKI_LINK"))));

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-graph", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .queryParam("nodeTypes", "ARTICLE_CARD", "CONCEPT_CARD", "SYNTHESIS_CARD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes").isArray());

        mockMvc.perform(get("/api/v1/team/wiki-pages/{pageId}/knowledge-graph", deployPageId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rootNodeId").value("WIKI_PAGE:" + deployPageId))
                .andExpect(jsonPath("$.data.nodes[0].root").value(true));

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-graph/nodes/{nodeId}", spaceId, "WIKI_PAGE:" + deployPageId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("WIKI_PAGE:" + deployPageId))
                .andExpect(jsonPath("$.data.type").value("WIKI_PAGE"))
                .andExpect(jsonPath("$.data.adjacentEdges").isArray());

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-graph/neighborhood/{nodeId}", spaceId, "WIKI_PAGE:" + deployPageId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .queryParam("depth", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rootNodeId").value("WIKI_PAGE:" + deployPageId))
                .andExpect(jsonPath("$.data.nodes[0].root").value(true));

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-graph/path", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .queryParam("sourceNodeId", "WIKI_PAGE:" + rollbackPageId)
                        .queryParam("targetNodeId", "WIKI_PAGE:" + checklistPageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceNodeId").value("WIKI_PAGE:" + rollbackPageId))
                .andExpect(jsonPath("$.data.targetNodeId").value("WIKI_PAGE:" + checklistPageId))
                .andExpect(jsonPath("$.data.length").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/api/v1/team/wiki-pages/{pageId}/graph", deployPageId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("depth", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void archivedWikiShouldBeRemovedFromSearchAndChatRetrieval() throws Exception {
        String ownerToken = registerAndGetToken("phase10_archive_owner_" + System.nanoTime());

        Long spaceId = createTeamSpace(ownerToken, "phase10-archive-team-" + System.nanoTime());
        Long kbId = createKnowledgeBase(ownerToken, spaceId, "phase10-archive-kb-" + System.nanoTime());

        JsonNode created = readJson(mockMvc.perform(post("/api/v1/team/spaces/{spaceId}/wiki-pages", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Archive Me",
                                  "content": "This archived wiki should disappear from search and RAG."
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());

        Long pageId = created.path("data").path("id").asLong();
        readJson(mockMvc.perform(post("/api/v1/team/wiki-pages/{pageId}/publish", pageId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "changeNote": "publish for archive test"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());

        Long indexTaskId = jdbcTemplate.queryForObject(
                "select id from task where task_type = 'WIKI_INDEX' and target_id = ? order by created_at desc limit 1",
                Long.class,
                pageId
        );
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(indexTaskId, TaskStatus.SUCCESS);

        mockMvc.perform(get("/api/v1/team/spaces/{spaceId}/wiki-pages/search", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .queryParam("keyword", "disappear from search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(pageId));

        givenStubAnswer("This archived wiki should disappear from search and RAG.");
        Long sessionId = createChatSession(ownerToken, spaceId, "wiki archive session", "KNOWLEDGE_BASE", new long[]{kbId});
        JsonNode beforeArchive = readJson(mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "What should disappear from search and RAG?"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(beforeArchive.path("data").path("citations").size()).isGreaterThanOrEqualTo(1);

        mockMvc.perform(delete("/api/v1/team/wiki-pages/{pageId}", pageId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/team/spaces/{spaceId}/wiki-pages/search", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .queryParam("keyword", "disappear from search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());

        JsonNode afterArchive = readJson(mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "What should disappear from search and RAG?"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(afterArchive.path("data").path("citations").size()).isEqualTo(0);
    }

    private void givenStubAnswer(String answer) throws Exception {
        org.mockito.BDDMockito.given(llmClient.chat(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .willReturn(LlmResponse.builder()
                        .provider("test")
                        .model("phase10-test")
                        .content(answer)
                        .inputTokens(16)
                        .outputTokens(16)
                        .latencyMs(1L)
                        .build());
    }

    private JsonNode createArtifactFromChatMessage(String token, Long spaceId, Long sessionId, Long messageId, String topic) throws Exception {
        return readJson(mockMvc.perform(post("/api/v1/studio/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "spaceId": %d,
                                  "taskType": "ARTIFACT_GENERATE",
                                  "sourceScopeType": "CHAT_MESSAGE",
                                  "sourceIds": [%d],
                                  "createdFromSessionId": %d,
                                  "createdFromMessageId": %d,
                                  "params": {
                                    "artifactType": "FAQ",
                                    "topic": "%s"
                                  }
                                }
                                """.formatted(spaceId, messageId, sessionId, messageId, topic)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private Long insertArtifact(Long ownerId, Long spaceId, Long sessionId, Long messageId, String title) {
        String artifactType = "FAQ";
        jdbcTemplate.update("""
                insert into artifact (
                    user_id,
                    space_id,
                    created_from_session_id,
                    created_from_message_id,
                    artifact_type,
                    title,
                    source_scope_type,
                    status,
                    created_at,
                    updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                """, ownerId, spaceId, sessionId, messageId, artifactType, title, "CHAT_MESSAGE", "READY");
        Long artifactId = jdbcTemplate.queryForObject("select id from artifact where space_id = ? and title = ? order by id desc limit 1", Long.class, spaceId, title);
        jdbcTemplate.update("""
                insert into artifact_source (artifact_id, source_type, source_id, created_at, updated_at)
                values (?, ?, ?, now(), now())
                """, artifactId, "CHAT_MESSAGE", messageId);
        return artifactId;
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Long createWikiDraftAndPublish(String token, Long spaceId, String title, String content) throws Exception {
        JsonNode created = readJson(mockMvc.perform(post("/api/v1/team/spaces/{spaceId}/wiki-pages", spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", title,
                                "content", content
                        ))))
                .andExpect(status().isOk())
                .andReturn());
        Long pageId = created.path("data").path("id").asLong();

        readJson(mockMvc.perform(post("/api/v1/team/wiki-pages/{pageId}/publish", pageId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "changeNote": "graph test publish"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn());
        return pageId;
    }

    private Long askQuestion(String token, Long sessionId, String question) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/chat/sessions/{sessionId}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"%s"}
                                """.formatted(question)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("assistantMessageId").asLong();
    }

    private IndexedDocument uploadAndProcess(
            String token,
            Long spaceId,
            Long kbId,
            String fileName,
            String contentType,
            byte[] content
    ) throws Exception {
        JsonNode merged = uploadAndMerge(token, kbId, fileName, contentType, content);
        Long documentId = merged.path("data").path("documentId").asLong();
        Long taskId = merged.path("data").path("taskId").asLong();
        documentProcessingService.process(payload(taskId, documentId, spaceId, kbId, fileName, contentType));
        return new IndexedDocument(documentId, taskId);
    }

    private JsonNode uploadAndMerge(String token, Long kbId, String fileName, String contentType, byte[] content) throws Exception {
        String fileMd5 = md5Hex(content);
        Map<String, Object> init = new HashMap<>();
        init.put("fileMd5", fileMd5);
        init.put("fileName", fileName);
        init.put("contentType", contentType);
        init.put("totalSize", content.length);
        init.put("chunkSize", content.length);
        init.put("totalChunks", 1);
        MvcResult initResult = mockMvc.perform(post("/api/v1/team/knowledge-bases/{knowledgeBaseId}/documents/uploads/init", kbId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(init)))
                .andExpect(status().isOk())
                .andReturn();
        Long uploadId = objectMapper.readTree(initResult.getResponse().getContentAsString()).path("data").path("uploadId").asLong();

        MockMultipartFile chunk = new MockMultipartFile("file", "chunk-0.bin", contentType, content);
        mockMvc.perform(multipart("/api/v1/team/document-uploads/{uploadId}/chunks", uploadId)
                        .file(chunk)
                        .param("chunkIndex", "0")
                        .with(request -> {
                            request.setMethod("POST");
                            return request;
                        })
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        MvcResult mergeResult = mockMvc.perform(post("/api/v1/team/document-uploads/{uploadId}/merge", uploadId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(mergeResult.getResponse().getContentAsString());
    }

    private DocumentProcessTaskPayload payload(Long taskId, Long documentId, Long spaceId, Long kbId, String fileName, String contentType) {
        return DocumentProcessTaskPayload.builder()
                .taskId(taskId)
                .documentId(documentId)
                .spaceId(spaceId)
                .knowledgeBaseId(kbId)
                .fileName(fileName)
                .contentType(contentType)
                .build();
    }

    private Long createChatSession(String token, Long spaceId, String title, String scopeType, long[] scopeIds) throws Exception {
        String scopeIdsJson = java.util.Arrays.toString(scopeIds);
        MvcResult result = mockMvc.perform(post("/api/v1/chat/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"spaceId":%d,"sessionType":"TEAM_CHAT","sessionKind":"FORMAL","title":"%s","scopeType":"%s","scopeIds":%s}
                                """.formatted(spaceId, title, scopeType, scopeIdsJson)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private void addMember(String ownerToken, Long spaceId, String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/spaces/{spaceId}/members", spaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","role":"%s"}
                                """.formatted(email, role)))
                .andExpect(status().isOk());
    }

    private Long createKnowledgeBase(String token, Long spaceId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/team/spaces/{spaceId}/knowledge-bases", spaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"phase10 kb"}
                                """.formatted(name)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private Long createTeamSpace(String token, String spaceName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/spaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"phase10 team"}
                                """.formatted(spaceName)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private String registerAndGetToken(String username) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", username);
        payload.put("email", username + "@example.com");
        payload.put("password", "Password123!");
        payload.put("displayName", "Phase 10 User");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }

    private void waitForTaskStatus(Long taskId, TaskStatus expectedStatus) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "select task_status, error_message from task where id = ?",
                    taskId
            );
            String current = String.valueOf(row.get("task_status"));
            if (expectedStatus.name().equals(current)) {
                return;
            }
            if (TaskStatus.FAILED.name().equals(current)
                    || TaskStatus.TIMEOUT.name().equals(current)
                    || TaskStatus.CANCELLED.name().equals(current)) {
                throw new AssertionError("Task " + taskId + " ended in " + current + " with error: " + row.get("error_message"));
            }
            sleepBriefly();
        }
        throw new AssertionError("Timed out waiting for task " + taskId + " to reach " + expectedStatus);
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", ex);
        }
    }

    private String md5Hex(byte[] content) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] digest = md5.digest(content);
        StringBuilder builder = new StringBuilder();
        for (byte b : digest) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private record IndexedDocument(Long documentId, Long taskId) {
    }
}

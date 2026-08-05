package com.noteweave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.knowledge.WikiIngestService;
import com.noteweave.research.ResearchAgentProjectionService;
import com.noteweave.research.ResearchArtifactService;
import com.noteweave.research.ResearchCheckpointStore;
import com.noteweave.research.ResearchRunListQueryRepository;
import com.noteweave.research.ResearchRunQueryService;
import com.noteweave.research.ResearchSourceProvenanceEnricher;
import com.noteweave.research.ResearchSourceScopeLoader;
import com.noteweave.source.SourceCatalogVersionService;
import com.noteweave.source.SourceParseService;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.task.TaskService;
import com.noteweave.workspace.CreateWorkspaceRequest;
import com.noteweave.workspace.WorkspaceService;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestResearchOutboxPublisherConfig.class)
class ResearchRunListQueryCountTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private SourceParseService sourceParseService;

    @Autowired
    private WikiIngestService wikiIngestService;

    @Autowired
    private SourceCatalogVersionService sourceCatalogVersionService;

    @Autowired
    private ResearchAgentProjectionService researchAgentProjectionService;

    @Test
    void listQueryCountShouldNotGrowWithRunCount() {
        String workspaceId = workspaceService.createWorkspace(
                new CreateWorkspaceRequest("Research list query count", "N+1 regression fixture")
        ).workspaceId();
        insertRun(workspaceId, 1);

        JdbcTemplate countingJdbcTemplate = spy(new JdbcTemplate(jdbcTemplate.getDataSource()));
        ResearchArtifactService researchArtifactService = new ResearchArtifactService(
                countingJdbcTemplate,
                objectMapper,
                workspaceService,
                storage,
                sourceParseService,
                wikiIngestService,
                sourceCatalogVersionService
        );
        ResearchRunQueryService service = new ResearchRunQueryService(
                countingJdbcTemplate,
                objectMapper,
                workspaceService,
                taskService,
                storage,
                researchArtifactService,
                researchAgentProjectionService,
                new ResearchRunListQueryRepository(countingJdbcTemplate),
                new ResearchCheckpointStore(countingJdbcTemplate),
                new ResearchSourceProvenanceEnricher(countingJdbcTemplate),
                new ResearchSourceScopeLoader(countingJdbcTemplate, objectMapper)
        );

        assertThat(service.listRuns(workspaceId)).hasSize(1);
        long singleRunQueryCount = queryCount(countingJdbcTemplate);

        for (int index = 2; index <= 5; index++) {
            insertRun(workspaceId, index);
        }
        clearInvocations(countingJdbcTemplate);

        assertThat(service.listRuns(workspaceId)).hasSize(5);
        long fiveRunQueryCount = queryCount(countingJdbcTemplate);

        assertThat(fiveRunQueryCount).isEqualTo(singleRunQueryCount);
        assertThat(fiveRunQueryCount).isLessThanOrEqualTo(10);
    }

    @Test
    void sourceScopeHydrationShouldUseOneQueryAndPreserveDeclaredOrder() throws Exception {
        String workspaceId = workspaceService.createWorkspace(
                new CreateWorkspaceRequest("Research source scope", "Batch hydration fixture")
        ).workspaceId();
        insertReadySource(workspaceId, "scope-source-1", "Scope source one", "scope-sha-1");
        insertReadySource(workspaceId, "scope-source-2", "Scope source two", "scope-sha-2");

        TopLevelCountingJdbcTemplate countingJdbcTemplate =
                new TopLevelCountingJdbcTemplate(jdbcTemplate.getDataSource());
        ResearchSourceScopeLoader loader = new ResearchSourceScopeLoader(countingJdbcTemplate, objectMapper);

        var scope = loader.load(
                workspaceId,
                objectMapper.writeValueAsString(List.of("scope-source-2", "scope-source-1"))
        );

        assertThat(scope).extracting(item -> item.sourceId())
                .containsExactly("scope-source-2", "scope-source-1");
        assertThat(countingJdbcTemplate.queryInvocations()).isEqualTo(1);
    }

    @Test
    void sourceProvenanceShouldBatchLookupAndEnrichTheWholeReadModelTree() {
        String workspaceId = workspaceService.createWorkspace(
                new CreateWorkspaceRequest("Research provenance", "Batch enrichment fixture")
        ).workspaceId();
        insertReadySource(workspaceId, "provenance-source", "Canonical source title", "provenance-sha");
        jdbcTemplate.update("""
                update source
                set generated_by = 'research_agent', generated_ref_id = 'research-artifact-1'
                where id = 'provenance-source'
                """);
        LinkedHashMap<String, Object> firstReference = new LinkedHashMap<>();
        firstReference.put("source_id", "provenance-source");
        LinkedHashMap<String, Object> secondReference = new LinkedHashMap<>();
        secondReference.put("source_id", "provenance-source");
        LinkedHashMap<String, Object> readModel = new LinkedHashMap<>();
        readModel.put("traces", List.of(firstReference));
        readModel.put("checkpoints", List.of(secondReference));

        TopLevelCountingJdbcTemplate countingJdbcTemplate =
                new TopLevelCountingJdbcTemplate(jdbcTemplate.getDataSource());
        new ResearchSourceProvenanceEnricher(countingJdbcTemplate).enrich(readModel);

        assertThat(firstReference).containsEntry("source_title", "Canonical source title");
        assertThat(firstReference).containsEntry("generated_by", "research_agent");
        assertThat(secondReference).containsEntry("generated_ref_id", "research-artifact-1");
        assertThat(countingJdbcTemplate.queryInvocations()).isEqualTo(1);
    }

    private void insertReadySource(String workspaceId, String sourceId, String title, String sha256) {
        String fileObjectId = sourceId + "-file";
        jdbcTemplate.update("""
                insert into file_object(id, workspace_id, object_key, sha256, file_size, mime_type, ref_count)
                values (?, ?, ?, ?, 1, 'text/plain', 1)
                """, fileObjectId, workspaceId, "test/" + sourceId, sha256);
        jdbcTemplate.update("""
                insert into source(
                    id, workspace_id, file_object_id, title, source_type, status, parse_status, index_status
                ) values (?, ?, ?, ?, 'PDF', 'READY', 'COMPLETED', 'INDEXED')
                """, sourceId, workspaceId, fileObjectId, title);
    }

    private void insertRun(String workspaceId, int index) {
        String runId = "query-count-run-" + index;
        String taskId = taskService.createTask(
                workspaceId,
                "RESEARCH_RUN",
                "RESEARCH_RUN",
                runId,
                "QUEUED",
                "query count fixture"
        );
        jdbcTemplate.update("""
                insert into research_run(
                    id, workspace_id, task_id, question, profile_key, source_scope_json, status
                ) values (?, ?, ?, ?, 'DEFAULT', '[]', 'RUNNING')
                """, runId, workspaceId, taskId, "query count question " + index);
    }

    private long queryCount(JdbcTemplate template) {
        return mockingDetails(template).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().startsWith("query"))
                .map(invocation -> invocation.getArguments())
                .filter(arguments -> arguments.length > 0 && arguments[0] instanceof String)
                .map(arguments -> ((String) arguments[0]).replaceAll("\\s+", " ").trim())
                .distinct()
                .count();
    }

    private static final class TopLevelCountingJdbcTemplate extends JdbcTemplate {
        private int queryInvocations;

        private TopLevelCountingJdbcTemplate(javax.sql.DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> T query(String sql, ResultSetExtractor<T> resultSetExtractor, Object... args) {
            queryInvocations++;
            return super.query(sql, resultSetExtractor, args);
        }

        private int queryInvocations() {
            return queryInvocations;
        }
    }
}

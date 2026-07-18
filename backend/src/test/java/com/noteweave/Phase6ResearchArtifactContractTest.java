package com.noteweave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.artifact.ArtifactJobService;
import com.noteweave.artifact.ArtifactVersionDetailResponse;
import com.noteweave.answer.ConversationEventMux;
import com.noteweave.answer.ConversationLiveEvent;
import com.noteweave.infra.LocalObjectStorage;
import com.noteweave.research.ResearchCheckpointResponse;
import com.noteweave.research.ResearchCheckpointSummaryResponse;
import com.noteweave.research.ResearchRunDetailResponse;
import com.noteweave.research.ResearchRunService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestResearchOutboxPublisherConfig.class)
class Phase6ResearchArtifactContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ArtifactJobService artifactJobService;

    @Autowired
    private ConversationEventMux conversationEventMux;

    @Autowired
    private LocalObjectStorage storage;

    @Autowired
    private TestResearchOutboxPublisherConfig.RecordingResearchOutboxPublisher recordingResearchOutboxPublisher;

    @Autowired
    private TestResearchOutboxPublisherConfig.RecordingArtifactOutboxPublisher recordingArtifactOutboxPublisher;

    @Autowired
    private TestResearchOutboxPublisherConfig.RecordingArtifactWorkerControlClient recordingArtifactWorkerControlClient;

    @Autowired
    private ResearchRunService researchRunService;

    @Test
    void artifactSkillCatalogShouldExposeBuiltInSkills() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v2/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").isNotEmpty())
                .andExpect(jsonPath("$.data[0].skill_key").isNotEmpty())
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[*].skill_key").value(org.hamcrest.Matchers.hasItems(
                        "resume_highlight",
                        "study_guide",
                        "quiz_pack",
                        "wiki_page",
                        "bilibili_course_note_pdf"
                )))
                .andReturn();

        JsonNode skills = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        JsonNode resumeSkill = findByField(skills, "skill_key", "resume_highlight");
        JsonNode bilibiliSkill = findByField(skills, "skill_key", "bilibili_course_note_pdf");

        assertThat(resumeSkill.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(resumeSkill.path("input_schema").path("type").asText()).isEqualTo("object");
        assertThat(resumeSkill.path("input_schema").path("properties").has("language")).isTrue();
        assertThat(resumeSkill.path("input_schema").path("properties").path("language").path("default").asText())
                .isEqualTo("zh-CN");
        assertThat(resumeSkill.path("input_schema").path("properties").path("language").path("oneOf").isArray()).isTrue();
        assertThat(resumeSkill.path("input_schema").path("properties").path("language").path("oneOf"))
                .anyMatch(node -> "zh-CN".equals(node.path("const").asText()))
                .anyMatch(node -> "en".equals(node.path("const").asText()))
                .anyMatch(node -> "zh-EN".equals(node.path("const").asText()));
        assertThat(resumeSkill.path("default_input_hints").isArray()).isTrue();
        assertThat(resumeSkill.path("default_input_hints")).isNotEmpty();
        assertThat(resumeSkill.has("supports_url_input")).isFalse();

        assertThat(bilibiliSkill.has("supports_url_input")).isFalse();
        assertThat(bilibiliSkill.path("input_schema").path("properties").has("url")).isTrue();
        assertThat(bilibiliSkill.path("input_schema").path("properties").has("language")).isTrue();
        assertThat(bilibiliSkill.path("input_schema").path("required").isArray()).isTrue();
        assertThat(bilibiliSkill.path("input_schema").path("required"))
                .anyMatch(node -> "url".equals(node.asText()));
        assertThat(bilibiliSkill.path("default_input_hints")).isNotEmpty();

        Path contractPath = Path.of("reference", "artifact-skill-catalog-v1.json");
        if (!Files.exists(contractPath)) {
            contractPath = Path.of("..", "reference", "artifact-skill-catalog-v1.json");
        }
        JsonNode contract = objectMapper.readTree(Files.readString(contractPath, StandardCharsets.UTF_8));
        Map<String, Map<String, List<String>>> actualContract = new LinkedHashMap<>();
        for (JsonNode skill : skills) {
            ArrayList<String> properties = new ArrayList<>();
            skill.path("input_schema").path("properties").fieldNames().forEachRemaining(properties::add);
            properties.sort(String::compareTo);
            ArrayList<String> required = new ArrayList<>();
            skill.path("input_schema").path("required").forEach(node -> required.add(node.asText()));
            required.sort(String::compareTo);
            actualContract.put(skill.path("skill_key").asText(), Map.of(
                    "properties", List.copyOf(properties),
                    "required", List.copyOf(required)
            ));
        }
        Map<String, Map<String, List<String>>> expectedContract = new LinkedHashMap<>();
        contract.path("skills").fields().forEachRemaining(entry -> {
            ArrayList<String> properties = new ArrayList<>();
            entry.getValue().path("properties").forEach(node -> properties.add(node.asText()));
            properties.sort(String::compareTo);
            ArrayList<String> required = new ArrayList<>();
            entry.getValue().path("required").forEach(node -> required.add(node.asText()));
            required.sort(String::compareTo);
            expectedContract.put(entry.getKey(), Map.of(
                    "properties", List.copyOf(properties),
                    "required", List.copyOf(required)
            ));
        });
        assertThat(actualContract).isEqualTo(expectedContract);
    }

    @Test
    void artifactJobShouldCreateTaskExposeWorkerInputAndPersistVersion() throws Exception {
        String workspaceId = createWorkspace();
        String sourceId = uploadSource(workspaceId, "artifact-input.md", """
                Artifact input source for report generation.
                It explains the current workspace material pool and output expectations.
                """);

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "resume_highlight",
                                "user_requirement", "强调架构设计、异步任务与 MCP 集成，适合校招简历",
                                "inputs", Map.of(
                                        "language", "zh-CN"
                                ),
                                "source_scope_source_ids", List.of(sourceId)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skill_key").value("resume_highlight"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn();

        String artifactJobId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("artifact_job_id").asText();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_type").value("ARTIFACT_JOB"))
                .andExpect(jsonPath("$.data.task_status").value("PENDING"));

        mockMvc.perform(get("/internal/worker/artifact-tasks/{taskId}/input", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspace_id").value(workspaceId))
                .andExpect(jsonPath("$.data.target_id").value(artifactJobId))
                .andExpect(jsonPath("$.data.input_payload.skill_key").value("resume_highlight"))
                .andExpect(jsonPath("$.data.input_payload.action_key").doesNotExist())
                .andExpect(jsonPath("$.data.input_payload.user_requirement").value("强调架构设计、异步任务与 MCP 集成，适合校招简历"))
                .andExpect(jsonPath("$.data.input_payload.generation_brief").value("强调架构设计、异步任务与 MCP 集成，适合校招简历"))
                .andExpect(jsonPath("$.data.input_payload.inputs.language").value("zh-CN"))
                .andExpect(jsonPath("$.data.control_pack.pack_type").value("artifact"))
                .andExpect(jsonPath("$.data.control_pack.target_key").value("resume_highlight"))
                .andExpect(jsonPath("$.data.control_pack.task_neighborhood").value("ARTIFACT_SKILL_RESUME_HIGHLIGHT"))
                .andExpect(jsonPath("$.data.source_scope.length()").value(1));

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/progress", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phase", "COMPOSING",
                                "progress_percent", 45,
                                "message", "产物大纲已完成",
                                "metrics", Map.of("sections", 4)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "result_type", "MARKDOWN",
                                "result_title", "Alpha Report",
                                "result_payload", Map.of(
                                        "markdown", "## Alpha Report\n\nArtifact content.",
                                        "verification", Map.of(
                                                "status", "PASS",
                                                "passed_checks", java.util.List.of("artifact outline satisfied"),
                                                "repaired_checks", java.util.List.of(),
                                                "failed_checks", java.util.List.of(),
                                                "warnings", java.util.List.of()
                                        ),
                                        "generation_trace", Map.ofEntries(
                                                Map.entry("mode", "LLM_GENERATION"),
                                                Map.entry("provider", "openai-compatible"),
                                                Map.entry("model", "artifact-model"),
                                                Map.entry("attempted", true),
                                                Map.entry("applied", true),
                                                Map.entry("fallback_reason", ""),
                                                Map.entry("generated_section_count", 3),
                                                Map.entry("source_count", 1)
                                        ),
                                        "approval_trace", Map.of(
                                                "status", "SATISFIED",
                                                "decision", "ALLOW",
                                                "reason_code", "BUILTIN_SAFE",
                                                "required_capabilities", java.util.List.of("EXTRACT_TRANSCRIPT"),
                                                "pending_capabilities", java.util.List.of(),
                                                "satisfied_capabilities", java.util.List.of("EXTRACT_TRANSCRIPT"),
                                                "approval_request", Map.of(),
                                                "capability_decisions", java.util.List.of(Map.ofEntries(
                                                        Map.entry("capability_name", "EXTRACT_TRANSCRIPT"),
                                                        Map.entry("provider_id", "builtin-bilibili-mcp"),
                                                        Map.entry("server_id", "builtin-bilibili-mcp"),
                                                        Map.entry("tool_name", "get_subtitle"),
                                                        Map.entry("approval_status", "APPROVED"),
                                                        Map.entry("provider_status", "AVAILABLE"),
                                                        Map.entry("health_status", "HEALTHY"),
                                                        Map.entry("discovery_status", "DISCOVERED"),
                                                        Map.entry("selection_reason", "builtin provider selected"),
                                                        Map.entry("runtime_status", "SATISFIED")
                                                )),
                                                "notes", java.util.List.of("approval gate satisfied in test")
                                        ),
                                        "capability_union_trace", Map.ofEntries(
                                                Map.entry("status", "ALLOW"),
                                                Map.entry("decision", "ALLOW"),
                                                Map.entry("reason_code", "SKILL_SCOPED_SAFE"),
                                                Map.entry("policy_key", "artifact-union-v1"),
                                                Map.entry("action_scope", "RESUME_HIGHLIGHT"),
                                                Map.entry("skill_scope", "resume_highlight"),
                                                Map.entry("workspace_scope", "WORKSPACE"),
                                                Map.entry("capability_scope", java.util.List.of("EXTRACT_TRANSCRIPT")),
                                                Map.entry("external_network_capabilities", java.util.List.of()),
                                                Map.entry("writeback_capabilities", java.util.List.of()),
                                                Map.entry("blocked_capabilities", java.util.List.of()),
                                                Map.entry("capability_decisions", java.util.List.of(Map.ofEntries(
                                                        Map.entry("capability_name", "EXTRACT_TRANSCRIPT"),
                                                        Map.entry("scope_type", "READ"),
                                                        Map.entry("server_id", "builtin-bilibili-mcp"),
                                                        Map.entry("tool_name", "get_subtitle"),
                                                        Map.entry("provider_id", "builtin-bilibili-mcp"),
                                                        Map.entry("risk_level", "LOW"),
                                                        Map.entry("approval_mode", "AUTO"),
                                                        Map.entry("discovery_status", "DISCOVERED"),
                                                        Map.entry("provider_status", "AVAILABLE"),
                                                        Map.entry("health_status", "HEALTHY"),
                                                        Map.entry("approval_status", "APPROVED"),
                                                        Map.entry("selection_reason", "builtin provider selected"),
                                                        Map.entry("route_basis", "bilibili"),
                                                        Map.entry("action_basis", "resume_highlight"),
                                                        Map.entry("skill_graph_basis", "resume_highlight_v1"),
                                                        Map.entry("runtime_status", "ALLOWED")
                                                ))),
                                                Map.entry("notes", java.util.List.of("union policy validated in test"))
                                        ),
                                        "node_traces", java.util.List.of(
                                                Map.ofEntries(
                                                        Map.entry("node_id", "source_digest"),
                                                        Map.entry("skill_key", "resume_highlight_extractor"),
                                                        Map.entry("output_summary", "digested workspace materials into resume-ready facts"),
                                                        Map.entry("verification_status", "PASS"),
                                                        Map.entry("verification_checks", java.util.List.of("source trace captured for digested materials")),
                                                        Map.entry("repair_actions", java.util.List.of()),
                                                        Map.entry("repaired", false)
                                                ),
                                                Map.ofEntries(
                                                        Map.entry("node_id", "bullet_writer"),
                                                        Map.entry("skill_key", "resume_highlight_extractor"),
                                                        Map.entry("output_summary", "generated polished resume bullets"),
                                                        Map.entry("verification_status", "PASS_WITH_REPAIR"),
                                                        Map.entry("verification_checks", java.util.List.of("resume section contract preserved")),
                                                        Map.entry("repair_actions", java.util.List.of("resume highlight bullet backfilled at node level")),
                                                        Map.entry("repaired", true)
                                                )
                                        ),
                                        "evidence_coverage", Map.ofEntries(
                                                Map.entry("status", "PASS"),
                                                Map.entry("required_citation_density", "MEDIUM"),
                                                Map.entry("section_count", 1),
                                                Map.entry("covered_section_count", 1),
                                                Map.entry("coverage_ratio", 1.0),
                                                Map.entry("supporting_source_ids", java.util.List.of("artifact-input-source-1")),
                                                Map.entry("section_evidence", java.util.List.of(Map.ofEntries(
                                                        Map.entry("section_heading", "overview"),
                                                        Map.entry("source_refs", java.util.List.of("artifact-input.md")),
                                                        Map.entry("source_ids", java.util.List.of("artifact-input-source-1")),
                                                        Map.entry("evidence_status", "COVERED")
                                                ))),
                                                Map.entry("sections_missing_evidence", java.util.List.of()),
                                                Map.entry("notes", java.util.List.of("evidence coverage satisfied in test"))
                                        ),
                                        "writeback_preview", Map.ofEntries(
                                                Map.entry("status", "READY_FOR_HOST_WRITEBACK"),
                                                Map.entry("requested_mode", "SAVE_AS_NOTE"),
                                                Map.entry("allowed_target", "NOTE"),
                                                Map.entry("execution_mode", "HOST_MEDIATED"),
                                                Map.entry("required_capabilities", java.util.List.of("WRITE_NOTE")),
                                                Map.entry("version_id", "preview-version-1"),
                                                Map.entry("request_id", "writeback-preview-1"),
                                                Map.entry("target_locator_preview", "note://workspace/latest"),
                                                Map.entry("notes", java.util.List.of("writeback preview prepared in test"))
                                        ),
                                        "output_contract_trace", Map.ofEntries(
                                                Map.entry("status", "PASS"),
                                                Map.entry("outline_checks", java.util.List.of(Map.of(
                                                        "label", "overview",
                                                        "status", "PASS",
                                                        "detail", "section present: overview",
                                                        "metadata", Map.of("heading", "overview")
                                                ))),
                                                Map.entry("phrase_checks", java.util.List.of()),
                                                Map.entry("action_checks", java.util.List.of(Map.of(
                                                        "label", "resume highlight bullet count",
                                                        "status", "PASS",
                                                        "detail", "resume highlight bullet count satisfied",
                                                        "metadata", Map.of(
                                                                "action_key", "RESUME_HIGHLIGHT",
                                                                "bullet_count", 3
                                                        )
                                                ))),
                                                Map.entry("evidence_checks", java.util.List.of()),
                                                Map.entry("repair_summary", Map.ofEntries(
                                                        Map.entry("total_repair_count", 3),
                                                        Map.entry("local_repair_count", 1),
                                                        Map.entry("node_repair_count", 2),
                                                        Map.entry("affected_sections", java.util.List.of("overview", "highlights")),
                                                        Map.entry("affected_nodes", java.util.List.of("source_digest", "bullet_writer")),
                                                        Map.entry("local_repair_checks", java.util.List.of("fixed heading")),
                                                        Map.entry("node_repair_actions", java.util.List.of(
                                                                "resume highlight bullet backfilled at node level",
                                                                "resume keyword backfilled at node level: Capability Union Policy"
                                                        )),
                                                        Map.entry("category_counts", Map.of(
                                                                "outline", 1,
                                                                "node_output", 2
                                                        )),
                                                        Map.entry("notes", java.util.List.of("repair summary aggregated in test"))
                                                )),
                                                Map.entry("repaired_checks", java.util.List.of()),
                                                Map.entry("passed_checks", java.util.List.of("section present: overview")),
                                                Map.entry("failed_checks", java.util.List.of()),
                                                Map.entry("warnings", java.util.List.of()),
                                                Map.entry("notes", java.util.List.of("contract validated in test"))
                                        ),
                                        "lifecycle_trace", Map.of(
                                                "status", "COMPLETED",
                                                "current_phase", "EXPORTING",
                                                "steps", java.util.List.of(Map.of(
                                                        "phase", "EXPORTING",
                                                        "status", "COMPLETED",
                                                        "progress_percent", 100,
                                                        "message", "artifact draft exported",
                                                        "metrics", Map.of("sections", 1)
                                                )),
                                                "notes", java.util.List.of("verification_status=PASS")
                                        )
                                ),
                                "trace_summary", "artifact loop finished",
                                "citations", java.util.List.of(Map.of("title", "artifact-input.md"))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.progress_phase").value("ARTIFACT_VERSIONED"));

        mockMvc.perform(get("/api/v2/tasks/{taskId}/events", taskId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: task.progress")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: task.completed")));

        Integer versionCount = jdbcTemplate.queryForObject(
                "select count(*) from artifact_version where artifact_job_id = ?",
                Integer.class,
                artifactJobId
        );
        assertThat(versionCount).isNotNull().isEqualTo(1);

        String storedSkillKey = jdbcTemplate.queryForObject(
                "select skill_key from artifact_job where id = ?",
                String.class,
                artifactJobId
        );
        assertThat(storedSkillKey).isEqualTo("resume_highlight");

        String storedActionKey = jdbcTemplate.queryForObject(
                "select action_key from artifact_job where id = ?",
                String.class,
                artifactJobId
        );
        assertThat(storedActionKey).isNull();

        String markdown = jdbcTemplate.queryForObject(
                "select content_markdown from artifact_version where artifact_job_id = ? and version_no = 1",
                String.class,
                artifactJobId
        );
        assertThat(markdown).contains("Alpha Report");

        String storedVersionSkillKey = jdbcTemplate.queryForObject(
                "select skill_key from artifact_version where artifact_job_id = ? and version_no = 1",
                String.class,
                artifactJobId
        );
        assertThat(storedVersionSkillKey).isEqualTo("resume_highlight");

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].artifact_job_id").value(artifactJobId))
                .andExpect(jsonPath("$.data[0].skill_key").value("resume_highlight"))
                .andExpect(jsonPath("$.data[0].task_id").value(taskId))
                .andExpect(jsonPath("$.data[0].latest_version_no").value(1));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}", workspaceId, artifactJobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.artifact_job_id").value(artifactJobId))
                .andExpect(jsonPath("$.data.skill_key").value("resume_highlight"))
                .andExpect(jsonPath("$.data.action_key").doesNotExist())
                .andExpect(jsonPath("$.data.user_requirement").value("强调架构设计、异步任务与 MCP 集成，适合校招简历"))
                .andExpect(jsonPath("$.data.inputs.language").value("zh-CN"))
                .andExpect(jsonPath("$.data.latest_version_no").value(1));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}/versions", workspaceId, artifactJobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].artifact_job_id").value(artifactJobId))
                .andExpect(jsonPath("$.data[0].skill_key").value("resume_highlight"))
                .andExpect(jsonPath("$.data[0].version_no").value(1))
                .andExpect(jsonPath("$.data[0].title").value("Alpha Report"))
                .andExpect(jsonPath("$.data[0].action_key").doesNotExist());

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}/versions/{versionNo}", workspaceId, artifactJobId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.artifact_job_id").value(artifactJobId))
                .andExpect(jsonPath("$.data.skill_key").value("resume_highlight"))
                .andExpect(jsonPath("$.data.version_no").value(1))
                .andExpect(jsonPath("$.data.title").value("Alpha Report"))
                .andExpect(jsonPath("$.data.content_markdown").value("## Alpha Report\n\nArtifact content."))
                .andExpect(jsonPath("$.data.trace_summary").value("artifact loop finished"))
                .andExpect(jsonPath("$.data.citations[0].title").value("artifact-input.md"))
                .andExpect(jsonPath("$.data.runtime_trace.verification.status").value("PASS"))
                .andExpect(jsonPath("$.data.runtime_trace.verification.passed_checks[0]").value("artifact outline satisfied"))
                .andExpect(jsonPath("$.data.runtime_trace.generation_trace.mode").value("LLM_GENERATION"))
                .andExpect(jsonPath("$.data.runtime_trace.generation_trace.model").value("artifact-model"))
                .andExpect(jsonPath("$.data.runtime_trace.generation_trace.applied").value(true))
                .andExpect(jsonPath("$.data.runtime_trace.approval_trace.status").value("SATISFIED"))
                .andExpect(jsonPath("$.data.runtime_trace.approval_trace.capability_decisions[0].capability_name").value("EXTRACT_TRANSCRIPT"))
                .andExpect(jsonPath("$.data.runtime_trace.capability_union_trace.status").value("ALLOW"))
                .andExpect(jsonPath("$.data.runtime_trace.capability_union_trace.action_scope").doesNotExist())
                .andExpect(jsonPath("$.data.runtime_trace.capability_union_trace.capability_decisions[0].runtime_status").value("ALLOWED"))
                .andExpect(jsonPath("$.data.runtime_trace.capability_union_trace.capability_decisions[0].action_basis").doesNotExist())
                .andExpect(jsonPath("$.data.runtime_trace.capability_union_trace.capability_decisions[0].skill_graph_basis").doesNotExist())
                .andExpect(jsonPath("$.data.runtime_trace.node_traces[0].node_id").value("source_digest"))
                .andExpect(jsonPath("$.data.runtime_trace.node_traces[0].verification_status").value("PASS"))
                .andExpect(jsonPath("$.data.runtime_trace.node_traces[1].repaired").value(true))
                .andExpect(jsonPath("$.data.runtime_trace.node_traces[1].repair_actions[0]").value("resume highlight bullet backfilled at node level"))
                .andExpect(jsonPath("$.data.runtime_trace.evidence_coverage.status").value("PASS"))
                .andExpect(jsonPath("$.data.runtime_trace.evidence_coverage.required_citation_density").value("MEDIUM"))
                .andExpect(jsonPath("$.data.runtime_trace.evidence_coverage.section_evidence[0].section_heading").value("overview"))
                .andExpect(jsonPath("$.data.runtime_trace.writeback_preview.status").value("READY_FOR_HOST_WRITEBACK"))
                .andExpect(jsonPath("$.data.runtime_trace.writeback_preview.allowed_target").value("NOTE"))
                .andExpect(jsonPath("$.data.runtime_trace.writeback_preview.required_capabilities[0]").value("WRITE_NOTE"))
                .andExpect(jsonPath("$.data.runtime_trace.output_contract_trace.status").value("PASS"))
                .andExpect(jsonPath("$.data.runtime_trace.output_contract_trace.outline_checks[0].label").value("overview"))
                .andExpect(jsonPath("$.data.runtime_trace.output_contract_trace.outline_checks[0].metadata.heading").value("overview"))
                .andExpect(jsonPath("$.data.runtime_trace.output_contract_trace.contract_checks[0].label").value("resume highlight bullet count"))
                .andExpect(jsonPath("$.data.runtime_trace.output_contract_trace.contract_checks[0].metadata.bullet_count").value(3))
                .andExpect(jsonPath("$.data.runtime_trace.output_contract_trace.contract_checks[0].metadata.action_key").doesNotExist())
                .andExpect(jsonPath("$.data.runtime_trace.output_contract_trace.repair_summary.total_repair_count").value(3))
                .andExpect(jsonPath("$.data.runtime_trace.output_contract_trace.repair_summary.local_repair_count").value(1))
                .andExpect(jsonPath("$.data.runtime_trace.output_contract_trace.repair_summary.node_repair_count").value(2))
                .andExpect(jsonPath("$.data.runtime_trace.output_contract_trace.repair_summary.affected_sections[0]").value("overview"))
                .andExpect(jsonPath("$.data.runtime_trace.output_contract_trace.repair_summary.affected_nodes[1]").value("bullet_writer"))
                .andExpect(jsonPath("$.data.runtime_trace.output_contract_trace.repair_summary.local_repair_checks[0]").value("fixed heading"))
                .andExpect(jsonPath("$.data.runtime_trace.output_contract_trace.repair_summary.node_repair_actions[1]").value("resume keyword backfilled at node level: Capability Union Policy"))
                .andExpect(jsonPath("$.data.runtime_trace.lifecycle_trace.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.runtime_trace.lifecycle_trace.current_phase").value("EXPORTING"))
                .andExpect(jsonPath("$.data.runtime_trace.lifecycle_trace.steps[0].progress_percent").value(100))
                .andExpect(jsonPath("$.data.action_key").doesNotExist());

        ArtifactVersionDetailResponse versionDetail = artifactJobService.getVersionDetail(workspaceId, artifactJobId, 1);
        assertThat(versionDetail.runtimeTrace()).isNotNull();
        assertThat(versionDetail.runtimeTrace().verification()).isNotNull();
        assertThat(versionDetail.runtimeTrace().verification().status()).isEqualTo("PASS");
        assertThat(versionDetail.runtimeTrace().approvalTrace()).isNotNull();
        assertThat(versionDetail.runtimeTrace().approvalTrace().status()).isEqualTo("SATISFIED");
        assertThat(versionDetail.runtimeTrace().approvalTrace().capabilityDecisions()).hasSize(1);
        assertThat(versionDetail.runtimeTrace().capabilityUnionTrace()).isNotNull();
        assertThat(versionDetail.runtimeTrace().capabilityUnionTrace().status()).isEqualTo("ALLOW");
        assertThat(versionDetail.runtimeTrace().capabilityUnionTrace().capabilityDecisions()).hasSize(1);
        assertThat(versionDetail.runtimeTrace().nodeTraces()).hasSize(2);
        assertThat(versionDetail.runtimeTrace().nodeTraces().get(0).nodeId()).isEqualTo("source_digest");
        assertThat(versionDetail.runtimeTrace().nodeTraces().get(1).repaired()).isTrue();
        assertThat(versionDetail.runtimeTrace().evidenceCoverage()).isNotNull();
        assertThat(versionDetail.runtimeTrace().evidenceCoverage().status()).isEqualTo("PASS");
        assertThat(versionDetail.runtimeTrace().evidenceCoverage().sectionEvidence()).hasSize(1);
        assertThat(versionDetail.runtimeTrace().writebackPreview()).isNotNull();
        assertThat(versionDetail.runtimeTrace().writebackPreview().allowedTarget()).isEqualTo("NOTE");
        assertThat(versionDetail.runtimeTrace().writebackPreview().requiredCapabilities()).containsExactly("WRITE_NOTE");
        assertThat(versionDetail.runtimeTrace().outputContractTrace()).isNotNull();
        assertThat(versionDetail.runtimeTrace().outputContractTrace().outlineChecks()).hasSize(1);
        assertThat(versionDetail.runtimeTrace().outputContractTrace().contractChecks()).hasSize(1);
        assertThat(versionDetail.runtimeTrace().outputContractTrace().contractChecks().get(0).metadata())
                .containsEntry("bullet_count", 3)
                .doesNotContainKey("action_key");
        assertThat(versionDetail.runtimeTrace().outputContractTrace().repairSummary()).isNotNull();
        assertThat(versionDetail.runtimeTrace().outputContractTrace().repairSummary().totalRepairCount()).isEqualTo(3);
        assertThat(versionDetail.runtimeTrace().outputContractTrace().repairSummary().localRepairChecks())
                .containsExactly("fixed heading");
        assertThat(versionDetail.runtimeTrace().outputContractTrace().repairSummary().nodeRepairActions())
                .containsExactly(
                        "resume highlight bullet backfilled at node level",
                        "resume keyword backfilled at node level: Capability Union Policy"
                );
        assertThat(versionDetail.runtimeTrace().outputContractTrace().repairSummary().affectedNodes())
                .containsExactly("source_digest", "bullet_writer");
        assertThat(versionDetail.runtimeTrace().lifecycleTrace()).isNotNull();
        assertThat(versionDetail.runtimeTrace().lifecycleTrace().currentPhase()).isEqualTo("EXPORTING");
    }

    @Test
    void artifactWorkerInputShouldUseSourceScopeCapturedWhenJobWasCreated() throws Exception {
        String workspaceId = createWorkspace();
        String capturedSourceId = uploadSource(workspaceId, "captured-artifact-input.md", """
                This source existed before the artifact job was created.
                It must remain the only source in this job's frozen scope.
                """);
        String capturedSourceSnapshotId = jdbcTemplate.queryForObject(
                "select id from source_snapshot where source_id = ? order by version_no desc limit 1",
                String.class, capturedSourceId);

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "resume_highlight",
                                "user_requirement", "Use only the source scope captured at creation time",
                                "inputs", Map.of("language", "en"),
                                "source_scope_source_ids", List.of(capturedSourceId),
                                "upstream_refs", List.of(Map.of(
                                        "ref_type", "SOURCE_SNAPSHOT",
                                        "ref_id", capturedSourceId,
                                        "revision_id", capturedSourceSnapshotId
                                ))
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        String lateSourceId = uploadSource(workspaceId, "late-artifact-input.md", """
                This source was added after the artifact job was created.
                It must not leak into the existing job's worker input.
                """);

        mockMvc.perform(get("/internal/worker/artifact-tasks/{taskId}/input", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.input_snapshot_id").isNotEmpty())
                .andExpect(jsonPath("$.data.source_scope.length()").value(1))
                .andExpect(jsonPath("$.data.source_scope[0].source_id").value(capturedSourceId))
                .andExpect(jsonPath("$.data.source_scope[0].source_metadata.source_snapshot_id").isNotEmpty())
                .andExpect(jsonPath("$.data.upstream_refs[0].ref_type").value("SOURCE_SNAPSHOT"))
                .andExpect(jsonPath("$.data.upstream_refs[0].revision_id").value(capturedSourceSnapshotId))
                .andExpect(jsonPath("$.data.source_scope[?(@.source_id=='" + lateSourceId + "')]").isEmpty());
    }

    @Test
    void artifactJobWithoutExplicitSourceScopeMustNotDiscoverWorkspaceSources() throws Exception {
        String workspaceId = createWorkspace();
        uploadSource(workspaceId, "implicit-artifact-input.md", ""
                + "This READY source must not be discovered by an artifact job without explicit scope.\n");

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "resume_highlight",
                                "user_requirement", "Require explicit artifact source scope",
                                "inputs", Map.of("language", "en")
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(get("/internal/worker/artifact-tasks/{taskId}/input", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source_scope").isEmpty());
    }

    @Test
    void deletingArtifactSourceMustRedactFrozenBodyAndDowngradeReplay() throws Exception {
        String workspaceId = createWorkspace();
        String sourceId = uploadSource(workspaceId, "artifact-delete-input.md",
                "ArtifactDeleteSecret must disappear from frozen replay content.");
        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "report_draft",
                                "user_requirement", "Verify Artifact deletion propagation",
                                "inputs", Map.of("language", "en"),
                                "source_scope_source_ids", List.of(sourceId)
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v2/workspaces/{workspaceId}/sources/{sourceId}", workspaceId, sourceId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/internal/worker/artifact-tasks/{taskId}/input", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay_availability").value("METADATA_ONLY"))
                .andExpect(jsonPath("$.data.source_scope[0].source_id").value(sourceId))
                .andExpect(jsonPath("$.data.source_scope[0].sample_text").value(""))
                .andExpect(jsonPath("$.data.source_scope[0].summary").value(""));
    }

    @Test
    void deletingResearchReportMustDowngradeArtifactReplayThroughTypedUpstreamRef() throws Exception {
        String workspaceId = createWorkspace();
        String evidenceSourceId = uploadSource(workspaceId, "report-upstream-evidence.md",
                "Evidence used to produce a report that will later be deleted.");
        MvcResult research = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "question", "Produce a deletable upstream report",
                                "profile", "default",
                                "source_scope_source_ids", List.of(evidenceSourceId)
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode researchData = objectMapper.readTree(research.getResponse().getContentAsString()).path("data");
        String researchRunId = researchData.path("research_run_id").asText();
        String researchTaskId = researchData.path("task_id").asText();
        jdbcTemplate.update(
                "update research_run set agent_execution_mode = 'SEQUENTIAL_V1' where id = ?",
                researchRunId);
        mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", researchTaskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "result_type", "RESEARCH_REPORT",
                                "result_title", "Typed Upstream Report",
                                "result_payload", Map.of(
                                        "report_markdown", "# Typed Upstream Report\n\nReportDeleteSecret",
                                        "report_source_candidate", Map.of(
                                                "title", "Typed Upstream Report",
                                                "source_type", "GENERATED_RESEARCH_REPORT",
                                                "generated_by", "research_agent",
                                                "content_markdown", "# Typed Upstream Report\n\nReportDeleteSecret"
                                        ),
                                        "research_checkpoint_candidate", Map.of(
                                                "checkpoint_no", 1,
                                                "snapshot_type", "RESEARCH_LOOP_CHECKPOINT"
                                        )
                                ),
                                "trace_summary", "report completed",
                                "citations", List.of(Map.of("title", "report-upstream-evidence.md"))
                        ))))
                .andExpect(status().isOk());
        MvcResult saved = mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/save-report-as-source",
                        workspaceId, researchRunId))
                .andExpect(status().isOk())
                .andReturn();
        String reportSourceId = objectMapper.readTree(saved.getResponse().getContentAsString())
                .path("data").path("source_id").asText();
        String reportSnapshotId = jdbcTemplate.queryForObject(
                "select id from source_snapshot where source_id = ? order by version_no desc limit 1",
                String.class, reportSourceId);

        MvcResult artifact = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "report_draft",
                                "user_requirement", "Use the typed report ref without a source scope",
                                "inputs", Map.of("language", "en"),
                                "upstream_refs", List.of(Map.of(
                                        "ref_type", "RESEARCH_REPORT",
                                        "ref_id", researchRunId,
                                        "revision_id", reportSnapshotId
                                ))
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String artifactTaskId = objectMapper.readTree(artifact.getResponse().getContentAsString())
                .path("data").path("task_id").asText();
        mockMvc.perform(get("/internal/worker/artifact-tasks/{taskId}/input", artifactTaskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay_availability").value("FULL"))
                .andExpect(jsonPath("$.data.source_scope").isEmpty())
                .andExpect(jsonPath("$.data.upstream_refs[0].revision_id").value(reportSnapshotId));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v2/workspaces/{workspaceId}/sources/{sourceId}", workspaceId, reportSourceId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/internal/worker/artifact-tasks/{taskId}/input", artifactTaskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay_availability").value("METADATA_ONLY"));
    }

    @Test
    void researchProgressMustProjectToConversationEventStreamWithoutCreatingMessages() throws Exception {
        String workspaceId = createWorkspace();
        String sourceId = uploadSource(workspaceId, "research-progress-input.md",
                "Research progress projection uses the canonical task event path.");
        String conversationId = createConversation(workspaceId);
        MvcResult submission = mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "Track this research run",
                                "answer_mode", "DEEP_RESEARCH",
                                "client_request_id", "research-progress-" + System.nanoTime(),
                                "source_scope_source_ids", List.of(sourceId)
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String researchRunId = objectMapper.readTree(submission.getResponse().getContentAsString())
                .path("data").path("research_run_id").asText();
        String taskId = jdbcTemplate.queryForObject(
                "select task_id from research_run where id = ?", String.class, researchRunId);
        Integer messagesBefore = jdbcTemplate.queryForObject(
                "select count(*) from conversation_message where conversation_id = ?",
                Integer.class, conversationId);

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/progress", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phase", "READING",
                                "progress_percent", 40,
                                "message", "Reading selected evidence",
                                "metrics", Map.of("windows", 3),
                                "payload", Map.of()
                        ))))
                .andExpect(status().isOk());

        List<ConversationLiveEvent> events = new ArrayList<>();
        conversationEventMux.follow(conversationId, 0, events::add, Duration.ofMillis(100), false);
        assertThat(events).anySatisfy(event -> {
            assertThat(event.runId()).isEqualTo(researchRunId);
            assertThat(event.eventType()).isEqualTo("research.progress");
            assertThat(event.data()).contains("READING", "Reading selected evidence");
        });
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from conversation_message where conversation_id = ?",
                Integer.class, conversationId)).isEqualTo(messagesBefore);
    }

    @Test
    void artifactJobMustRejectExplicitSourceFromAnotherWorkspace() throws Exception {
        String workspaceId = createWorkspace();
        String otherWorkspaceId = createWorkspace();
        String foreignSourceId = uploadSource(otherWorkspaceId, "foreign-artifact-input.md",
                "This source belongs to another workspace.");

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "resume_highlight",
                                "user_requirement", "Reject a cross-workspace source",
                                "inputs", Map.of("language", "en"),
                                "source_scope_source_ids", List.of(foreignSourceId)
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ARTIFACT_SOURCE_SCOPE_INVALID"));
    }

    @Test
    void artifactJobMustRejectResearchReportRefWithoutCanonicalResearchRun() throws Exception {
        String workspaceId = createWorkspace();
        String reportSourceId = uploadSource(workspaceId, "forged-research-report.md",
                "This source metadata must not be enough to establish a Research Report ref.");
        String missingResearchRunId = "missing-research-run";
        jdbcTemplate.update("""
                update source set source_type = 'GENERATED_RESEARCH_REPORT',
                    generated_by = 'research_agent', generated_ref_id = ? where id = ?
                """, missingResearchRunId, reportSourceId);
        String snapshotId = jdbcTemplate.queryForObject(
                "select id from source_snapshot where source_id = ? order by version_no desc limit 1",
                String.class, reportSourceId);

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "report_draft",
                                "user_requirement", "Reject forged research report provenance",
                                "inputs", Map.of("language", "en"),
                                "source_scope_source_ids", List.of(reportSourceId),
                                "upstream_refs", List.of(Map.of(
                                        "ref_type", "RESEARCH_REPORT",
                                        "ref_id", missingResearchRunId,
                                        "revision_id", snapshotId
                                ))
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ARTIFACT_UPSTREAM_REF_INVALID"));
    }

    @Test
    void artifactCallbacksShouldBeIdempotentAfterTaskReachesTerminalState() throws Exception {
        String workspaceId = createWorkspace();
        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "resume_highlight",
                                "user_requirement", "Verify terminal callback idempotency",
                                "inputs", Map.of("language", "en")
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data");
        String taskId = created.path("task_id").asText();
        String artifactJobId = created.path("artifact_job_id").asText();
        Map<String, Object> completion = Map.of(
                "result_type", "MARKDOWN",
                "result_title", "Idempotent Artifact",
                "result_payload", Map.of("markdown", "# Idempotent Artifact"),
                "trace_summary", "terminal callback test",
                "citations", java.util.List.of()
        );

        MvcResult firstComplete = mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(completion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andReturn();
        String firstResultRef = objectMapper.readTree(firstComplete.getResponse().getContentAsString())
                .path("data").path("result_ref").asText();

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(completion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.result_ref").value(firstResultRef));

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/progress", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phase", "WAITING_FOR_PROVIDER",
                                "progress_percent", 35,
                                "message", "late waiting callback",
                                "metrics", Map.of()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/fail", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phase", "LATE_FAILURE",
                                "error_code", "LATE_CALLBACK",
                                "error_message", "late failure must not regress a completed task",
                                "retryable", false
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from artifact_version where artifact_job_id = ?",
                Integer.class,
                artifactJobId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select status from artifact_job where id = ?",
                String.class,
                artifactJobId
        )).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "select task_status from task where id = ?",
                String.class,
                taskId
        )).isEqualTo("COMPLETED");
    }

    @Test
    void completedArtifactVersionShouldBeSavedAsWorkspaceSourceIdempotently() throws Exception {
        String workspaceId = createWorkspace();
        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "study_guide",
                                "user_requirement", "Create a study guide that can be saved as source",
                                "inputs", Map.of("language", "en")
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data");
        String taskId = created.path("task_id").asText();
        String artifactJobId = created.path("artifact_job_id").asText();
        MvcResult completeResult = mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "result_type", "MARKDOWN",
                                "result_title", "Saved Study Guide",
                                "result_payload", Map.of("markdown", "# Saved Study Guide\n\nGrounded artifact content."),
                                "trace_summary", "save artifact source test",
                                "citations", java.util.List.of()
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String versionId = objectMapper.readTree(completeResult.getResponse().getContentAsString())
                .path("data").path("result_ref").asText();

        MvcResult firstSave = mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}/versions/{versionNo}/save-as-source",
                        workspaceId,
                        artifactJobId,
                        1
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.artifact_job_id").value(artifactJobId))
                .andExpect(jsonPath("$.data.artifact_version_id").value(versionId))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.parse_status").value("PARSED"))
                .andExpect(jsonPath("$.data.index_status").value("INDEXED"))
                .andExpect(jsonPath("$.data.generated_by").value("artifact_agent"))
                .andExpect(jsonPath("$.data.generated_ref_id").value(versionId))
                .andReturn();
        String sourceId = objectMapper.readTree(firstSave.getResponse().getContentAsString())
                .path("data").path("source_id").asText();

        mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}/versions/{versionNo}/save-as-source",
                        workspaceId,
                        artifactJobId,
                        1
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source_id").value(sourceId));

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from source
                where workspace_id = ? and generated_by = 'artifact_agent' and generated_ref_id = ?
                """, Integer.class, workspaceId, versionId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select sw.content from source_window sw join source_chunk sc on sc.id = sw.source_chunk_id where sc.source_id = ? limit 1",
                String.class,
                sourceId
        )).contains("Grounded artifact content");
    }

    @Test
    void completedArtifactVersionShouldWriteBackToNoteAndWikiThroughJavaHost() throws Exception {
        String workspaceId = createWorkspace();
        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "wiki_page",
                                "user_requirement", "Create reusable knowledge",
                                "inputs", Map.of("language", "zh-CN")
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data");
        String taskId = created.path("task_id").asText();
        String artifactJobId = created.path("artifact_job_id").asText();
        mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "result_type", "MARKDOWN",
                                "result_title", "Artifact Knowledge",
                                "result_payload", Map.of("markdown", "# Artifact Knowledge\n\nHost controlled writeback."),
                                "trace_summary", "knowledge writeback test",
                                "citations", java.util.List.of()
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}/versions/1/writeback",
                        workspaceId,
                        artifactJobId
                ).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "NOTE",
                                "title", "Artifact Note"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_type").value("NOTE"))
                .andExpect(jsonPath("$.data.title").value("Artifact Note"));

        mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}/versions/1/writeback",
                        workspaceId,
                        artifactJobId
                ).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "item_type", "WIKI",
                                "title", "Artifact Wiki"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item_type").value("WIKI"))
                .andExpect(jsonPath("$.data.title").value("Artifact Wiki"));

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from knowledge_item
                where workspace_id = ? and item_type in ('NOTE', 'WIKI') and title like 'Artifact %'
                """, Integer.class, workspaceId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from knowledge_version kv
                join knowledge_item ki on ki.id = kv.item_id
                where ki.workspace_id = ? and kv.content like '%Host controlled writeback.%'
                """, Integer.class, workspaceId)).isEqualTo(2);
    }

    @Test
    void artifactVersionsShouldMaterializeFilesRegenerateCompareAndRollbackAppendOnly() throws Exception {
        String workspaceId = createWorkspace();
        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "study_guide",
                                "user_requirement", "Create version one",
                                "inputs", Map.of("language", "en")
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data");
        String artifactJobId = created.path("artifact_job_id").asText();
        String firstTaskId = created.path("task_id").asText();
        MvcResult firstInputResult = mockMvc.perform(get(
                        "/internal/worker/artifact-tasks/{taskId}/input", firstTaskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.input_snapshot_id").isNotEmpty())
                .andReturn();
        String firstInputSnapshotId = objectMapper.readTree(
                        firstInputResult.getResponse().getContentAsString())
                .path("data").path("input_snapshot_id").asText();
        completeArtifact(firstTaskId, "Guide v1", "# Guide\n\nOriginal line.");

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}/versions/1", workspaceId, artifactJobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[0].file_format").value("MARKDOWN"))
                .andExpect(jsonPath("$.data.files[0].storage_backend").value("local"))
                .andExpect(jsonPath("$.data.files[0].checksum_sha256").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")));

        MvcResult regenerateResult = mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}/versions/1/regenerate",
                        workspaceId,
                        artifactJobId
                ).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.artifact_job_id").value(artifactJobId))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn();
        String secondTaskId = objectMapper.readTree(regenerateResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();
        assertThat(secondTaskId).isNotEqualTo(firstTaskId);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from artifact_job_run where artifact_job_id = ?",
                Integer.class,
                artifactJobId
        )).isEqualTo(2);
        mockMvc.perform(get("/internal/worker/artifact-tasks/{taskId}/input", secondTaskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.input_snapshot_id").value(firstInputSnapshotId))
                .andExpect(jsonPath("$.data.input_payload.user_requirement").value("Create version one"));
        completeArtifact(secondTaskId, "Guide v2", "# Guide\n\nOriginal line.\nAdded line.");

        mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}/versions/compare",
                        workspaceId,
                        artifactJobId
                ).param("from", "1").param("to", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.from_version_no").value(1))
                .andExpect(jsonPath("$.data.to_version_no").value(2))
                .andExpect(jsonPath("$.data.added_lines").value(1));

        mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}/versions/1/rollback",
                        workspaceId,
                        artifactJobId
                ).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version_no").value(3))
                .andExpect(jsonPath("$.data.content_markdown").value("# Guide\n\nOriginal line."))
                .andExpect(jsonPath("$.data.files[0].file_format").value("MARKDOWN"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from artifact_version where artifact_job_id = ?",
                Integer.class,
                artifactJobId
        )).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "select latest_version_no from artifact_job where id = ?",
                Integer.class,
                artifactJobId
        )).isEqualTo(3);
    }

    @Test
    void artifactVersionShouldRemainCompletedWhenPdfArchivalTemporarilyFails() throws Exception {
        String workspaceId = createWorkspace();
        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "bilibili_course_note_pdf",
                                "user_requirement", "Generate a resilient PDF artifact",
                                "inputs", Map.of(
                                        "language", "zh-CN",
                                        "url", "https://www.bilibili.com/video/BV1ArchiveFailure"
                                )
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data");
        String taskId = created.path("task_id").asText();
        String artifactJobId = created.path("artifact_job_id").asText();

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "result_type", "MARKDOWN",
                                "result_title", "Resilient Course Notes",
                                "result_payload", Map.of(
                                        "markdown", "# Resilient Course Notes\n\nThe markdown remains durable.",
                                        "export_trace", Map.of(
                                                "status", "COMPILED",
                                                "file_name", "missing-worker-export.pdf"
                                        )
                                ),
                                "trace_summary", "pdf archive compensation test",
                                "citations", java.util.List.of()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}/versions/1",
                        workspaceId,
                        artifactJobId
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content_markdown").value(org.hamcrest.Matchers.containsString("remains durable")))
                .andExpect(jsonPath("$.data.files[?(@.file_format == 'MARKDOWN')].status")
                        .value(org.hamcrest.Matchers.hasItem("READY")))
                .andExpect(jsonPath("$.data.files[?(@.file_format == 'PDF')].status")
                        .value(org.hamcrest.Matchers.hasItem("FAILED")))
                .andExpect(jsonPath("$.data.files[?(@.file_format == 'PDF')].error_message").isNotEmpty());
        assertThat(jdbcTemplate.queryForObject(
                "select task_status from task where id = ?", String.class, taskId
        )).isEqualTo("COMPLETED");
    }

    @Test
    void bilibiliSkillWorkerInputShouldPassUrlInInputsWithoutJavaSyntheticSourceInjection() throws Exception {
        String workspaceId = createWorkspace();

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "bilibili_course_note_pdf",
                                "user_requirement", "把这个 B 站视频整理成讲义 PDF",
                                "inputs", Map.of(
                                        "url", "https://www.bilibili.com/video/BV1NoteWeaveDemo",
                                        "language", "zh-CN"
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skill_key").value("bilibili_course_note_pdf"))
                .andReturn();

        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(get("/internal/worker/artifact-tasks/{taskId}/input", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.input_payload.skill_key").value("bilibili_course_note_pdf"))
                .andExpect(jsonPath("$.data.input_payload.action_key").doesNotExist())
                .andExpect(jsonPath("$.data.control_pack.target_key").value("bilibili_course_note_pdf"))
                .andExpect(jsonPath("$.data.control_pack.task_neighborhood").value("ARTIFACT_SKILL_BILIBILI_COURSE_NOTE_PDF"))
                .andExpect(jsonPath("$.data.input_payload.inputs.url").value("https://www.bilibili.com/video/BV1NoteWeaveDemo"))
                .andExpect(jsonPath("$.data.source_scope.length()").value(0));

        String storedActionKey = jdbcTemplate.queryForObject(
                "select action_key from artifact_job where id = ?",
                String.class,
                objectMapper.readTree(createResult.getResponse().getContentAsString())
                        .path("data").path("artifact_job_id").asText()
        );
        assertThat(storedActionKey).isNull();
    }

    @Test
    void artifactJobShouldRejectMissingRequiredSchemaInputs() throws Exception {
        String workspaceId = createWorkspace();

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "bilibili_course_note_pdf",
                                "user_requirement", "鎶婅繖涓?B 绔欒棰戞暣鐞嗘垚璁蹭箟 PDF",
                                "inputs", Map.of(
                                        "language", "zh-CN"
                                )
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ARTIFACT_SKILL_INPUT_REQUIRED"))
                .andExpect(jsonPath("$.message").value("产物 Skill 缺少必填输入：url"));
    }

    @Test
    void artifactJobShouldRejectLegacyOrUnknownSchemaInputs() throws Exception {
        String workspaceId = createWorkspace();

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "resume_highlight",
                                "user_requirement", "寮鸿皟鏋舵瀯璁捐銆佸紓姝ヤ换鍔′笌 MCP 闆嗘垚",
                                "inputs", Map.of(
                                        "language", "zh-CN",
                                        "action_key", "RESUME_HIGHLIGHT"
                                )
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ARTIFACT_SKILL_INPUT_UNSUPPORTED"))
                .andExpect(jsonPath("$.message").value("产物 Skill 不支持以下输入字段：action_key"));
    }

    @Test
    void artifactJobShouldRejectSchemaInputTypeMismatch() throws Exception {
        String workspaceId = createWorkspace();

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "resume_highlight",
                                "user_requirement", "寮鸿皟鏋舵瀯璁捐銆佸紓姝ヤ换鍔′笌 MCP 闆嗘垚",
                                "inputs", Map.of(
                                        "language", 123
                                )
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ARTIFACT_SKILL_INPUT_TYPE_INVALID"))
                .andExpect(jsonPath("$.message").value("产物 Skill 输入字段类型不正确：language 应为 string"));
    }

    @Test
    void artifactJobShouldRejectUnsupportedSchemaEnumValue() throws Exception {
        String workspaceId = createWorkspace();

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "resume_highlight",
                                "user_requirement", "寮鸿皟鏋舵瀯璁捐銆佸紓姝ヤ换鍔′笌 MCP 闆嗘垚",
                                "inputs", Map.of(
                                        "language", "fr-FR"
                                )
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ARTIFACT_SKILL_INPUT_ENUM_INVALID"))
                .andExpect(jsonPath("$.message").value("产物 Skill 输入字段取值不受支持：language=fr-FR"));
    }

    @Test
    void artifactJobShouldSupportWaitingProgressAndResumeToCompletion() throws Exception {
        String workspaceId = createWorkspace();

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "bilibili_course_note_pdf",
                                "user_requirement", "把这个 B 站视频整理成讲义 PDF，并等待远程字幕与 PDF 编译完成",
                                "inputs", Map.of(
                                        "url", "https://www.bilibili.com/video/BV1NoteWeaveDemo",
                                        "language", "zh-CN"
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skill_key").value("bilibili_course_note_pdf"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn();

        String artifactJobId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("artifact_job_id").asText();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/progress", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phase", "WAITING_FOR_PROVIDER",
                                "progress_percent", 52,
                                "message", "字幕抓取任务已分发，等待 provider callback",
                                "metrics", Map.of("provider_jobs", 1),
                                "payload", Map.of(
                                        "provider_job", Map.ofEntries(
                                                Map.entry("provider_id", "builtin-bilibili-mcp"),
                                                Map.entry("server_id", "builtin-bilibili-mcp"),
                                                Map.entry("tool_name", "get_subtitle"),
                                                Map.entry("capability_name", "EXTRACT_TRANSCRIPT"),
                                                Map.entry("operation_key", "EXTRACT_TRANSCRIPT"),
                                                Map.entry("status", "WAITING_FOR_PROVIDER"),
                                                Map.entry("request_id", "fetch-artifact-task-bili-1"),
                                                Map.entry("provider_receipt_id", "provider-receipt-fetch-artifact-task-bili-1"),
                                                Map.entry("provider_job_id", "provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-1"),
                                                Map.entry("delivery_id", "acq-delivery-fetch-artifact-task-bili-1-1"),
                                                Map.entry("adapter_callback_token", "adapter-callback-fetch-artifact-task-bili-1"),
                                                Map.entry("provider_status", "AVAILABLE"),
                                                Map.entry("health_status", "HEALTHY"),
                                                Map.entry("provider_job_status", "DISPATCHED"),
                                                Map.entry("callback_status", "DISPATCHED_TO_PROVIDER"),
                                                Map.entry("provider_delivery_attempts", java.util.List.of(
                                                        Map.ofEntries(
                                                                Map.entry("delivery_id", "acq-delivery-fetch-artifact-task-bili-1-1"),
                                                                Map.entry("dispatch_count", 1),
                                                                Map.entry("callback_token", "acq-callback-token-fetch-artifact-task-bili-1-1"),
                                                                Map.entry("provider_job_id", "provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-1"),
                                                                Map.entry("provider_receipt_id", "provider-receipt-fetch-artifact-task-bili-1"),
                                                                Map.entry("ack_status", "FAILED"),
                                                                Map.entry("callback_received_at", "2026-07-07T12:00:00Z"),
                                                                Map.entry("error_code", "PROVIDER_TIMEOUT"),
                                                                Map.entry("error_message", "subtitle callback timed out")
                                                        ),
                                                        Map.ofEntries(
                                                                Map.entry("delivery_id", "acq-delivery-fetch-artifact-task-bili-1-2"),
                                                                Map.entry("dispatch_count", 2),
                                                                Map.entry("callback_token", "acq-callback-token-fetch-artifact-task-bili-1-2"),
                                                                Map.entry("provider_job_id", "provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-1"),
                                                                Map.entry("provider_receipt_id", "provider-receipt-fetch-artifact-task-bili-1"),
                                                                Map.entry("ack_status", "PENDING")
                                                        )
                                                ))
                                        ),
                                        "wait_reason", Map.ofEntries(
                                                Map.entry("status", "WAITING_FOR_PROVIDER"),
                                                Map.entry("blocked_operations", java.util.List.of(Map.of(
                                                        "request_id", "fetch-artifact-task-bili-1",
                                                        "source_id", "src-bili-1",
                                                        "operation_key", "EXTRACT_TRANSCRIPT",
                                                        "capability_name", "EXTRACT_TRANSCRIPT",
                                                        "callback_status", "DISPATCHED_TO_PROVIDER"
                                                )))
                                        )
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING"));

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_status").value("WAITING"))
                .andExpect(jsonPath("$.data.progress_phase").value("WAITING_FOR_PROVIDER"))
                .andExpect(jsonPath("$.data.progress_message").value("字幕抓取任务已分发，等待 provider callback"))
                .andExpect(jsonPath("$.data.wait_context.status").value("WAITING_FOR_PROVIDER"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_id").value("builtin-bilibili-mcp"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.server_id").value("builtin-bilibili-mcp"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.tool_name").value("get_subtitle"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.capability_name").value("EXTRACT_TRANSCRIPT"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.operation_key").value("EXTRACT_TRANSCRIPT"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.request_id").value("fetch-artifact-task-bili-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_receipt_id").value("provider-receipt-fetch-artifact-task-bili-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_job_id").value("provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.delivery_id").value("acq-delivery-fetch-artifact-task-bili-1-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.adapter_callback_token").value("adapter-callback-fetch-artifact-task-bili-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.health_status").value("HEALTHY"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_job_status").value("DISPATCHED"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.dispatch_count").value(2))
                .andExpect(jsonPath("$.data.wait_context.provider_job.previous_failed_delivery_count").value(1))
                .andExpect(jsonPath("$.data.wait_context.provider_job.has_previous_failed_delivery").value(true))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_delivery_attempts.length()").value(2))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_delivery_attempts[0].ack_status").value("FAILED"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_delivery_attempts[1].dispatch_count").value(2))
                .andExpect(jsonPath("$.data.wait_context.wait_reason.status").value("WAITING_FOR_PROVIDER"))
                .andExpect(jsonPath("$.data.wait_context.wait_reason.blocked_operations[0].request_id").value("fetch-artifact-task-bili-1"))
                .andExpect(jsonPath("$.data.wait_context.wait_reason.blocked_operations[0].source_id").value("src-bili-1"))
                .andExpect(jsonPath("$.data.wait_context.wait_reason.blocked_operations[0].capability_name").value("EXTRACT_TRANSCRIPT"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.callback_status").value("DISPATCHED_TO_PROVIDER"))
                .andExpect(jsonPath("$.data.wait_context.payload").doesNotExist());

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}", workspaceId, artifactJobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skill_key").value("bilibili_course_note_pdf"))
                .andExpect(jsonPath("$.data.status").value("WAITING_FOR_PROVIDER"))
                .andExpect(jsonPath("$.data.task_status").value("WAITING"))
                .andExpect(jsonPath("$.data.progress_phase").value("WAITING_FOR_PROVIDER"))
                .andExpect(jsonPath("$.data.wait_context.status").value("WAITING_FOR_PROVIDER"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_id").value("builtin-bilibili-mcp"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.server_id").value("builtin-bilibili-mcp"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.tool_name").value("get_subtitle"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.capability_name").value("EXTRACT_TRANSCRIPT"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.operation_key").value("EXTRACT_TRANSCRIPT"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.request_id").value("fetch-artifact-task-bili-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_receipt_id").value("provider-receipt-fetch-artifact-task-bili-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_job_id").value("provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.delivery_id").value("acq-delivery-fetch-artifact-task-bili-1-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.adapter_callback_token").value("adapter-callback-fetch-artifact-task-bili-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.health_status").value("HEALTHY"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_job_status").value("DISPATCHED"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.dispatch_count").value(2))
                .andExpect(jsonPath("$.data.wait_context.provider_job.previous_failed_delivery_count").value(1))
                .andExpect(jsonPath("$.data.wait_context.provider_job.has_previous_failed_delivery").value(true))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_delivery_attempts.length()").value(2))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_delivery_attempts[0].error_code").value("PROVIDER_TIMEOUT"))
                .andExpect(jsonPath("$.data.wait_context.wait_reason.status").value("WAITING_FOR_PROVIDER"))
                .andExpect(jsonPath("$.data.wait_context.wait_reason.blocked_operations[0].request_id").value("fetch-artifact-task-bili-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.callback_status").value("DISPATCHED_TO_PROVIDER"))
                .andExpect(jsonPath("$.data.latest_version_no").value(0));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].artifact_job_id").value(artifactJobId))
                .andExpect(jsonPath("$.data[0].wait_context.status").value("WAITING_FOR_PROVIDER"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.provider_id").value("builtin-bilibili-mcp"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.server_id").value("builtin-bilibili-mcp"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.tool_name").value("get_subtitle"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.operation_key").value("EXTRACT_TRANSCRIPT"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.request_id").value("fetch-artifact-task-bili-1"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.provider_job_id").value("provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-1"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.delivery_id").value("acq-delivery-fetch-artifact-task-bili-1-1"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.adapter_callback_token").value("adapter-callback-fetch-artifact-task-bili-1"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.provider_status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.health_status").value("HEALTHY"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.provider_job_status").value("DISPATCHED"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.dispatch_count").value(2))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.previous_failed_delivery_count").value(1))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.has_previous_failed_delivery").value(true))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.provider_delivery_attempts[1].ack_status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].wait_context.wait_reason.blocked_operations[0].request_id").value("fetch-artifact-task-bili-1"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.callback_status").value("DISPATCHED_TO_PROVIDER"));

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/progress", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phase", "COMPOSING",
                                "progress_percent", 81,
                                "message", "provider callback 已到达，开始整理讲义内容",
                                "metrics", Map.of("chapters", 6)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_status").value("RUNNING"))
                .andExpect(jsonPath("$.data.progress_phase").value("COMPOSING"));

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "result_type", "MARKDOWN",
                                "result_title", "B站讲义 PDF 任务结果",
                                "result_payload", Map.of(
                                        "markdown", "## B站讲义 PDF 任务结果\n\n字幕与 PDF 编译均已完成。"
                                ),
                                "trace_summary", "artifact waiting job resumed and finalized",
                                "citations", java.util.List.of(Map.of("title", "BV1NoteWeaveDemo"))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.progress_phase").value("ARTIFACT_VERSIONED"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}", workspaceId, artifactJobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.task_status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.latest_version_no").value(1));
    }

    @Test
    void artifactWorkerResumeControlShouldResumeWaitingTaskAndPersistVersion() throws Exception {
        recordingArtifactWorkerControlClient.reset();
        String workspaceId = createWorkspace();

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "bilibili_course_note_pdf",
                                "user_requirement", "把这个 B 站视频整理成讲义 PDF，并等待远程字幕与 PDF 编译完成",
                                "inputs", Map.of(
                                        "url", "https://www.bilibili.com/video/BV1NoteWeaveDemo",
                                        "language", "zh-CN"
                                )
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String artifactJobId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("artifact_job_id").asText();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/progress", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phase", "WAITING_FOR_PROVIDER",
                                "progress_percent", 52,
                                "message", "字幕抓取任务已分发，等待 provider callback",
                                "metrics", Map.of("provider_jobs", 1),
                                "payload", Map.of(
                                        "provider_job", Map.of(
                                                "provider_id", "builtin-bilibili-mcp",
                                                "operation_key", "EXTRACT_TRANSCRIPT",
                                                "status", "WAITING_FOR_PROVIDER",
                                                "request_id", "fetch-artifact-task-bili-resume-1",
                                                "provider_job_id", "provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-resume-1",
                                                "callback_status", "DISPATCHED_TO_PROVIDER"
                                        )
                                )
                        ))))
                .andExpect(status().isOk());

        recordingArtifactWorkerControlClient.stubResume(
                taskId,
                "B站讲义 PDF 任务结果",
                "## B站讲义 PDF 任务结果\n\n字幕与 PDF 编译均已完成。",
                "artifact waiting job resumed through java control plane",
                java.util.List.of(Map.of("title", "BV1NoteWeaveDemo"))
        );

        mockMvc.perform(post("/internal/worker/artifact-tasks/{taskId}/resume", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "request_id", "fetch-artifact-task-bili-resume-1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_id").value(taskId))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.progress_events").value(1))
                .andExpect(jsonPath("$.data.result_title").value("B站讲义 PDF 任务结果"));

        assertThat(recordingArtifactWorkerControlClient.resumeInvocations()).hasSize(1);
        assertThat(recordingArtifactWorkerControlClient.resumeInvocations().get(0).taskId()).isEqualTo(taskId);
        assertThat(recordingArtifactWorkerControlClient.resumeInvocations().get(0).request().requestId())
                .isEqualTo("fetch-artifact-task-bili-resume-1");

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.progress_phase").value("ARTIFACT_VERSIONED"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}", workspaceId, artifactJobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.latest_version_no").value(1));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}/versions/{versionNo}", workspaceId, artifactJobId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.status").value("ATTACHED"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.receipt.receipt_id")
                        .value("acq-callback-fetch-artifact-task-bili-resume-1-acknowledged"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.receipt.delivery_id")
                        .value("acq-delivery-fetch-artifact-task-bili-resume-1-1"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.receipt.provider_receipt_id")
                        .value("provider-receipt-fetch-artifact-task-bili-resume-1"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.receipt.provider_job_status")
                        .value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.request_id")
                        .value("fetch-artifact-task-bili-resume-1"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.provider_job_id")
                        .value("provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-resume-1"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.capability_name")
                        .value("EXTRACT_TRANSCRIPT"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.provider_job_status")
                        .value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.callback_status")
                        .value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.delivery_id")
                        .value("acq-delivery-fetch-artifact-task-bili-resume-1-1"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.provider_receipt_id")
                        .value("provider-receipt-fetch-artifact-task-bili-resume-1"));

        Integer versionCount = jdbcTemplate.queryForObject(
                "select count(*) from artifact_version where artifact_job_id = ?",
                Integer.class,
                artifactJobId
        );
        assertThat(versionCount).isNotNull().isEqualTo(1);
    }

    @Test
    void artifactAcquisitionAckControlShouldForwardToWorkerAndCompleteWaitingJob() throws Exception {
        recordingArtifactWorkerControlClient.reset();
        String workspaceId = createWorkspace();

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "bilibili_course_note_pdf",
                                "user_requirement", "把这个 B 站视频整理成讲义 PDF，并等待远程字幕与 PDF 编译完成",
                                "inputs", Map.of(
                                        "url", "https://www.bilibili.com/video/BV1NoteWeaveDemo",
                                        "language", "zh-CN"
                                )
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String artifactJobId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("artifact_job_id").asText();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/progress", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phase", "WAITING_FOR_PROVIDER",
                                "progress_percent", 52,
                                "message", "字幕抓取任务已分发，等待 provider callback",
                                "metrics", Map.of("provider_jobs", 1),
                                "payload", Map.of(
                                        "provider_job", Map.of(
                                                "provider_id", "builtin-bilibili-mcp",
                                                "operation_key", "EXTRACT_TRANSCRIPT",
                                                "status", "WAITING_FOR_PROVIDER",
                                                "request_id", "fetch-artifact-task-bili-ack-1",
                                                "provider_job_id", "provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-ack-1",
                                                "provider_status", "AVAILABLE",
                                                "health_status", "HEALTHY",
                                                "provider_job_status", "DISPATCHED",
                                                "callback_status", "DISPATCHED_TO_PROVIDER"
                                        )
                                )
                        ))))
                .andExpect(status().isOk());

        recordingArtifactWorkerControlClient.stubResume(
                taskId,
                "B站讲义 PDF 任务结果",
                "## B站讲义 PDF 任务结果\n\n字幕与 PDF 编译均已完成。",
                "artifact acquisition ack resumed and finalized",
                java.util.List.of(Map.of("title", "BV1NoteWeaveDemo"))
        );
        recordingArtifactWorkerControlClient.stubAcquisitionAck(
                "artifact-callback-token-bili-ack-1",
                taskId,
                "fetch-artifact-task-bili-ack-1",
                "BILIBILI_TRANSCRIPT_FETCH",
                "provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-ack-1"
        );

        mockMvc.perform(post("/internal/worker/artifact-callbacks/acquisition/ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "callback_token", "artifact-callback-token-bili-ack-1",
                                "final_status", "COMPLETED",
                                "result_locator", "bilibili://subtitle/BV1NoteWeaveDemo",
                                "provider_payload", Map.of(
                                        "subtitle_segments", 128,
                                        "language", "zh-CN"
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receipt.callback_status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.receipt.provider_job_status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.receipt.request_id").value("fetch-artifact-task-bili-ack-1"))
                .andExpect(jsonPath("$.data.receipt.task_id").value(taskId))
                .andExpect(jsonPath("$.data.receipt.source_id").value("src-bili-1"))
                .andExpect(jsonPath("$.data.receipt.operation_key").value("EXTRACT_TRANSCRIPT"))
                .andExpect(jsonPath("$.data.receipt.callback_token").value("artifact-callback-token-bili-ack-1"))
                .andExpect(jsonPath("$.data.receipt.delivery_id").value("acq-delivery-fetch-artifact-task-bili-ack-1-1"))
                .andExpect(jsonPath("$.data.receipt.provider_receipt_id").value("provider-receipt-fetch-artifact-task-bili-ack-1"))
                .andExpect(jsonPath("$.data.receipt.result_locator").value("bilibili://subtitle/BV1NoteWeaveDemo"))
                .andExpect(jsonPath("$.data.receipt.completed_at").value("2026-07-07T06:00:00Z"))
                .andExpect(jsonPath("$.data.receipt.dispatch_count").value(1))
                .andExpect(jsonPath("$.data.operation.dispatch_count").value(1))
                .andExpect(jsonPath("$.data.operation.provider_delivery_attempts[0].delivery_id")
                        .value("acq-delivery-fetch-artifact-task-bili-ack-1-1"))
                .andExpect(jsonPath("$.data.operation.provider_delivery_attempts[0].dispatch_count").value(1))
                .andExpect(jsonPath("$.data.operation.provider_delivery_attempts[0].ack_status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.operation.provider_id").value("builtin-bilibili-mcp"))
                .andExpect(jsonPath("$.data.operation.server_id").value("builtin-bilibili-mcp"))
                .andExpect(jsonPath("$.data.operation.tool_name").value("get_bilibili_subtitle"))
                .andExpect(jsonPath("$.data.operation.callback_token").value("artifact-callback-token-bili-ack-1"))
                .andExpect(jsonPath("$.data.operation.provider_status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.operation.health_status").value("HEALTHY"))
                .andExpect(jsonPath("$.data.operation.provider_job_status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.operation.callback_status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.resumed_tasks.length()").value(1))
                .andExpect(jsonPath("$.data.resumed_tasks[0].task_id").value(taskId))
                .andExpect(jsonPath("$.data.resumed_tasks[0].status").value("COMPLETED"));

        assertThat(recordingArtifactWorkerControlClient.acquisitionAckRequests()).hasSize(1);
        assertThat(recordingArtifactWorkerControlClient.acquisitionAckRequests().get(0).callbackToken())
                .isEqualTo("artifact-callback-token-bili-ack-1");
        assertThat(recordingArtifactWorkerControlClient.resumeInvocations()).hasSize(1);
        assertThat(recordingArtifactWorkerControlClient.resumeInvocations().get(0).taskId()).isEqualTo(taskId);
        assertThat(recordingArtifactWorkerControlClient.resumeInvocations().get(0).request().requestId())
                .isEqualTo("fetch-artifact-task-bili-ack-1");

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.progress_phase").value("ARTIFACT_VERSIONED"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}", workspaceId, artifactJobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.latest_version_no").value(1));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}/versions/{versionNo}", workspaceId, artifactJobId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.status").value("ATTACHED"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.receipt.receipt_id")
                        .value("acq-callback-fetch-artifact-task-bili-ack-1-acknowledged"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.receipt.request_id")
                        .value("fetch-artifact-task-bili-ack-1"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.receipt.task_id")
                        .value(taskId))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.receipt.source_id")
                        .value("src-bili-1"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.receipt.operation_key")
                        .value("EXTRACT_TRANSCRIPT"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.receipt.delivery_id")
                        .value("acq-delivery-fetch-artifact-task-bili-ack-1-1"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.receipt.provider_receipt_id")
                        .value("provider-receipt-fetch-artifact-task-bili-ack-1"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.receipt.provider_job_status")
                        .value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.receipt.result_locator")
                        .value("bilibili://subtitle/BV1NoteWeaveDemo"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.receipt.completed_at")
                        .value("2026-07-07T06:00:00Z"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.receipt.dispatch_count")
                        .value(1))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.dispatch_count")
                        .value(1))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.request_id")
                        .value("fetch-artifact-task-bili-ack-1"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.provider_job_id")
                        .value("provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-ack-1"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.capability_name")
                        .value("EXTRACT_TRANSCRIPT"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.provider_id")
                        .value("builtin-bilibili-mcp"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.server_id")
                        .value("builtin-bilibili-mcp"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.tool_name")
                        .value("get_bilibili_subtitle"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.callback_token")
                        .value("artifact-callback-token-bili-ack-1"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.provider_status")
                        .value("AVAILABLE"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.health_status")
                        .value("HEALTHY"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.provider_job_status")
                        .value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.callback_status")
                        .value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.delivery_id")
                        .value("acq-delivery-fetch-artifact-task-bili-ack-1-1"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.provider_receipt_id")
                        .value("provider-receipt-fetch-artifact-task-bili-ack-1"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.provider_delivery_attempts[0].delivery_id")
                        .value("acq-delivery-fetch-artifact-task-bili-ack-1-1"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.provider_delivery_attempts[0].dispatch_count")
                        .value(1))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.provider_delivery_attempts[0].ack_status")
                        .value("ACKNOWLEDGED"));

        ArtifactVersionDetailResponse acknowledgedVersionDetail = artifactJobService.getVersionDetail(workspaceId, artifactJobId, 1);
        assertThat(acknowledgedVersionDetail.runtimeTrace()).isNotNull();
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace()).isNotNull();
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().receipt()).isNotNull();
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().operation()).isNotNull();
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().receipt().requestId())
                .isEqualTo("fetch-artifact-task-bili-ack-1");
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().receipt().taskId())
                .isEqualTo(taskId);
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().receipt().sourceId())
                .isEqualTo("src-bili-1");
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().receipt().operationKey())
                .isEqualTo("EXTRACT_TRANSCRIPT");
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().operation().requestId())
                .isEqualTo("fetch-artifact-task-bili-ack-1");
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().operation().providerJobId())
                .isEqualTo("provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-ack-1");
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().operation().capabilityName())
                .isEqualTo("EXTRACT_TRANSCRIPT");
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().operation().providerId())
                .isEqualTo("builtin-bilibili-mcp");
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().operation().serverId())
                .isEqualTo("builtin-bilibili-mcp");
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().operation().toolName())
                .isEqualTo("get_bilibili_subtitle");
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().operation().providerStatus())
                .isEqualTo("AVAILABLE");
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().operation().healthStatus())
                .isEqualTo("HEALTHY");
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().operation().callbackToken())
                .isEqualTo("artifact-callback-token-bili-ack-1");
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().operation().callbackStatus())
                .isEqualTo("ACKNOWLEDGED");
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().operation().deliveryId())
                .isEqualTo("acq-delivery-fetch-artifact-task-bili-ack-1-1");
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().operation().providerReceiptId())
                .isEqualTo("provider-receipt-fetch-artifact-task-bili-ack-1");
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().operation().dispatchCount())
                .isEqualTo(1);
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().operation().providerDeliveryAttempts())
                .hasSize(1);
        assertThat(acknowledgedVersionDetail.runtimeTrace().acquisitionCallbackTrace().operation().providerDeliveryAttempts().get(0).ackStatus())
                .isEqualTo("ACKNOWLEDGED");

        Integer versionCount = jdbcTemplate.queryForObject(
                "select count(*) from artifact_version where artifact_job_id = ?",
                Integer.class,
                artifactJobId
        );
        assertThat(versionCount).isNotNull().isEqualTo(1);
    }

    @Test
    void artifactAcquisitionAckControlShouldExposeRedeliveryAttemptHistoryFromWorkerContract() throws Exception {
        recordingArtifactWorkerControlClient.reset();
        String workspaceId = createWorkspace();

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "bilibili_course_note_pdf",
                                "user_requirement", "把这条 B 站字幕回调链路展示成正式的重投递协议轨迹",
                                "inputs", Map.of(
                                        "url", "https://www.bilibili.com/video/BV1NoteWeaveDemo",
                                        "language", "zh-CN"
                                )
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String artifactJobId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("artifact_job_id").asText();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/progress", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phase", "WAITING_FOR_PROVIDER",
                                "progress_percent", 52,
                                "message", "字幕抓取任务第一次投递失败，等待第二次 provider delivery callback",
                                "metrics", Map.of("provider_jobs", 1, "dispatch_count", 2),
                                "payload", Map.of(
                                        "provider_job", Map.of(
                                                "provider_id", "builtin-bilibili-mcp",
                                                "operation_key", "EXTRACT_TRANSCRIPT",
                                                "status", "WAITING_FOR_PROVIDER",
                                                "request_id", "fetch-artifact-task-bili-redelivery-1",
                                                "provider_job_id", "provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-redelivery-1",
                                                "provider_status", "AVAILABLE",
                                                "health_status", "HEALTHY",
                                                "provider_job_status", "DISPATCHED",
                                                "callback_status", "DISPATCHED_TO_PROVIDER"
                                        )
                                )
                        ))))
                .andExpect(status().isOk());

        recordingArtifactWorkerControlClient.stubResume(
                taskId,
                "B站讲义 PDF 重投递结果",
                "## B站讲义 PDF 重投递结果\n\n第二次 provider delivery 已恢复成功。",
                "artifact acquisition ack redelivery history resumed and finalized",
                java.util.List.of(Map.of("title", "BV1NoteWeaveDemo"))
        );
        recordingArtifactWorkerControlClient.stubAcquisitionAckWithAttemptHistory(
                "artifact-callback-token-bili-redelivery-2",
                taskId,
                "fetch-artifact-task-bili-redelivery-1",
                "BILIBILI_TRANSCRIPT_FETCH",
                "provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-redelivery-1",
                2,
                java.util.List.of(
                        Map.ofEntries(
                                Map.entry("delivery_id", "acq-delivery-fetch-artifact-task-bili-redelivery-1-1"),
                                Map.entry("dispatch_count", 1),
                                Map.entry("callback_token", "artifact-callback-token-bili-redelivery-1"),
                                Map.entry("dispatched_at", "2026-07-07T05:55:00Z"),
                                Map.entry("callback_deadline_at", "2026-07-07T05:59:00Z"),
                                Map.entry("provider_job_id", "provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-redelivery-1"),
                                Map.entry("provider_receipt_id", "provider-receipt-fetch-artifact-task-bili-redelivery-1"),
                                Map.entry("input_digest", "artifact-input-digest-fetch-artifact-task-bili-redelivery-1"),
                                Map.entry("ack_status", "FAILED"),
                                Map.entry("callback_received_at", "2026-07-07T05:58:30Z"),
                                Map.entry("result_locator", ""),
                                Map.entry("error_code", "PROVIDER_TIMEOUT"),
                                Map.entry("error_message", "first provider delivery timed out")
                        ),
                        Map.ofEntries(
                                Map.entry("delivery_id", "acq-delivery-fetch-artifact-task-bili-redelivery-1-2"),
                                Map.entry("dispatch_count", 2),
                                Map.entry("callback_token", "artifact-callback-token-bili-redelivery-2"),
                                Map.entry("dispatched_at", "2026-07-07T05:59:00Z"),
                                Map.entry("callback_deadline_at", "2026-07-07T06:05:00Z"),
                                Map.entry("provider_job_id", "provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-redelivery-1"),
                                Map.entry("provider_receipt_id", "provider-receipt-fetch-artifact-task-bili-redelivery-1"),
                                Map.entry("input_digest", "artifact-input-digest-fetch-artifact-task-bili-redelivery-1"),
                                Map.entry("ack_status", "ACKNOWLEDGED"),
                                Map.entry("callback_received_at", "2026-07-07T06:00:00Z"),
                                Map.entry("result_locator", "bilibili://subtitle/BV1NoteWeaveDemo"),
                                Map.entry("error_code", ""),
                                Map.entry("error_message", "")
                        )
                )
        );

        mockMvc.perform(post("/internal/worker/artifact-callbacks/acquisition/ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "callback_token", "artifact-callback-token-bili-redelivery-2",
                                "final_status", "COMPLETED",
                                "result_locator", "bilibili://subtitle/BV1NoteWeaveDemo",
                                "provider_payload", Map.of(
                                        "subtitle_segments", 128,
                                        "language", "zh-CN"
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receipt.dispatch_count").value(2))
                .andExpect(jsonPath("$.data.operation.dispatch_count").value(2))
                .andExpect(jsonPath("$.data.operation.provider_delivery_attempts.length()").value(2))
                .andExpect(jsonPath("$.data.operation.provider_delivery_attempts[0].dispatch_count").value(1))
                .andExpect(jsonPath("$.data.operation.provider_delivery_attempts[0].ack_status").value("FAILED"))
                .andExpect(jsonPath("$.data.operation.provider_delivery_attempts[1].dispatch_count").value(2))
                .andExpect(jsonPath("$.data.operation.provider_delivery_attempts[1].ack_status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.operation.provider_delivery_attempts[1].delivery_id")
                        .value("acq-delivery-fetch-artifact-task-bili-redelivery-1-2"))
                .andExpect(jsonPath("$.data.operation.provider_delivery_attempts[1].result_locator")
                        .value("bilibili://subtitle/BV1NoteWeaveDemo"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}/versions/{versionNo}", workspaceId, artifactJobId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.receipt.dispatch_count")
                        .value(2))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.dispatch_count")
                        .value(2))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.provider_delivery_attempts.length()")
                        .value(2))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.provider_delivery_attempts[0].ack_status")
                        .value("FAILED"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.provider_delivery_attempts[1].ack_status")
                        .value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.runtime_trace.acquisition_callback_trace.operation.provider_delivery_attempts[1].dispatch_count")
                        .value(2));

        ArtifactVersionDetailResponse versionDetail = artifactJobService.getVersionDetail(workspaceId, artifactJobId, 1);
        assertThat(versionDetail.runtimeTrace()).isNotNull();
        assertThat(versionDetail.runtimeTrace().acquisitionCallbackTrace()).isNotNull();
        assertThat(versionDetail.runtimeTrace().acquisitionCallbackTrace().receipt().dispatchCount()).isEqualTo(2);
        assertThat(versionDetail.runtimeTrace().acquisitionCallbackTrace().operation().dispatchCount()).isEqualTo(2);
        assertThat(versionDetail.runtimeTrace().acquisitionCallbackTrace().operation().providerDeliveryAttempts())
                .hasSize(2);
        assertThat(versionDetail.runtimeTrace().acquisitionCallbackTrace().operation().providerDeliveryAttempts().get(0).ackStatus())
                .isEqualTo("FAILED");
        assertThat(versionDetail.runtimeTrace().acquisitionCallbackTrace().operation().providerDeliveryAttempts().get(1).ackStatus())
                .isEqualTo("ACKNOWLEDGED");
    }

    @Test
    void artifactAcquisitionAckControlShouldMarkWaitingJobFailedWhenProviderReportsFailure() throws Exception {
        recordingArtifactWorkerControlClient.reset();
        String workspaceId = createWorkspace();

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "bilibili_course_note_pdf",
                                "user_requirement", "把这个 B 站视频整理成讲义 PDF，并等待远程字幕与 PDF 编译完成",
                                "inputs", Map.of(
                                        "url", "https://www.bilibili.com/video/BV1NoteWeaveDemo",
                                        "language", "zh-CN"
                                )
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String artifactJobId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("artifact_job_id").asText();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/progress", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phase", "WAITING_FOR_PROVIDER",
                                "progress_percent", 52,
                                "message", "字幕抓取任务已分发，等待 provider callback",
                                "metrics", Map.of("provider_jobs", 1),
                                "payload", Map.of(
                                        "provider_job", Map.of(
                                                "provider_id", "builtin-bilibili-mcp",
                                                "operation_key", "EXTRACT_TRANSCRIPT",
                                                "status", "WAITING_FOR_PROVIDER",
                                                "request_id", "fetch-artifact-task-bili-ack-fail-1",
                                                "provider_job_id", "provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-ack-fail-1",
                                                "adapter_callback_token", "adapter-callback-fetch-artifact-task-bili-ack-fail-1",
                                                "provider_status", "AVAILABLE",
                                                "health_status", "HEALTHY",
                                                "provider_job_status", "DISPATCHED",
                                                "callback_status", "DISPATCHED_TO_PROVIDER"
                                        )
                                )
                        ))))
                .andExpect(status().isOk());

        recordingArtifactWorkerControlClient.stubFailedAcquisitionAck(
                "artifact-callback-token-bili-ack-fail-1",
                taskId,
                "fetch-artifact-task-bili-ack-fail-1",
                "BILIBILI_TRANSCRIPT_FETCH",
                "provider-job-builtin-bilibili-mcp-fetch-artifact-task-bili-ack-fail-1"
        );

        mockMvc.perform(post("/internal/worker/artifact-callbacks/acquisition/ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "callback_token", "artifact-callback-token-bili-ack-fail-1",
                                "final_status", "FAILED",
                                "error_code", "PROVIDER_TIMEOUT",
                                "error_message", "subtitle provider timed out before returning transcript"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receipt.callback_status").value("FAILED"))
                .andExpect(jsonPath("$.data.receipt.provider_job_status").value("FAILED"))
                .andExpect(jsonPath("$.data.receipt.request_id").value("fetch-artifact-task-bili-ack-fail-1"))
                .andExpect(jsonPath("$.data.receipt.task_id").value(taskId))
                .andExpect(jsonPath("$.data.receipt.source_id").value("src-bili-1"))
                .andExpect(jsonPath("$.data.receipt.operation_key").value("EXTRACT_TRANSCRIPT"))
                .andExpect(jsonPath("$.data.receipt.callback_token").value("artifact-callback-token-bili-ack-fail-1"))
                .andExpect(jsonPath("$.data.receipt.delivery_id").value("acq-delivery-fetch-artifact-task-bili-ack-fail-1-1"))
                .andExpect(jsonPath("$.data.receipt.provider_receipt_id").value("provider-receipt-fetch-artifact-task-bili-ack-fail-1"))
                .andExpect(jsonPath("$.data.receipt.error_code").value("PROVIDER_TIMEOUT"))
                .andExpect(jsonPath("$.data.receipt.error_message").value("subtitle provider timed out before returning transcript"))
                .andExpect(jsonPath("$.data.receipt.completed_at").value("2026-07-07T06:00:00Z"))
                .andExpect(jsonPath("$.data.receipt.dispatch_count").value(1))
                .andExpect(jsonPath("$.data.operation.dispatch_count").value(1))
                .andExpect(jsonPath("$.data.operation.provider_delivery_attempts[0].delivery_id")
                        .value("acq-delivery-fetch-artifact-task-bili-ack-fail-1-1"))
                .andExpect(jsonPath("$.data.operation.provider_delivery_attempts[0].dispatch_count").value(1))
                .andExpect(jsonPath("$.data.operation.provider_delivery_attempts[0].ack_status").value("FAILED"))
                .andExpect(jsonPath("$.data.operation.provider_status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.operation.health_status").value("HEALTHY"))
                .andExpect(jsonPath("$.data.resumed_tasks.length()").value(0));

        assertThat(recordingArtifactWorkerControlClient.acquisitionAckRequests()).hasSize(1);
        assertThat(recordingArtifactWorkerControlClient.resumeInvocations()).isEmpty();

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_status").value("FAILED"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/artifact-jobs/{artifactJobId}", workspaceId, artifactJobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));

        Integer versionCount = jdbcTemplate.queryForObject(
                "select count(*) from artifact_version where artifact_job_id = ?",
                Integer.class,
                artifactJobId
        );
        assertThat(versionCount).isNotNull().isEqualTo(0);
    }

    @Test
    @Disabled("Removed sequential Research Worker callback contract; incremental entry is covered separately")
    void researchRunShouldCreateTaskExposeWorkerInputAndPersistFinalReport() throws Exception {
        String workspaceId = createWorkspace();
        String scopedSourceId = uploadSource(workspaceId, "research-input.md", """
                Research input source for deep analysis.
                It contains a stable project context for the research worker.
                """);

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "question", "How should AlphaResearch be summarized?",
                                "profile", "default",
                                "research_goal", "Produce a verifier-approved summary for AlphaResearch.",
                                "deliverable_format", "Evidence-backed executive brief",
                                "constraints", java.util.List.of(
                                        "Separate verified findings from unresolved conflicts.",
                                        "Anchor every conclusion to explicit evidence."
                                ),
                                "time_range", "Current project cycle",
                                "depth", "DEEP",
                                "source_scope_source_ids", java.util.List.of(scopedSourceId)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn();

        String researchRunId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("research_run_id").asText();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        uploadSource(workspaceId, "late-research-input.md", """
                Late source uploaded after the research run was created.
                It must not enter the existing run source scope snapshot.
                """);

        mockMvc.perform(get("/internal/worker/research-tasks/{taskId}/input", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspace_id").value(workspaceId))
                .andExpect(jsonPath("$.data.target_id").value(researchRunId))
                .andExpect(jsonPath("$.data.context_snapshot").doesNotExist())
                .andExpect(jsonPath("$.data.input_payload.profile_key").value("DEFAULT"))
                .andExpect(jsonPath("$.data.input_payload.context_snapshot_id").doesNotExist())
                .andExpect(jsonPath("$.data.input_payload.research_intent.research_goal").value("Produce a verifier-approved summary for AlphaResearch."))
                .andExpect(jsonPath("$.data.input_payload.research_intent.deliverable_format").value("Evidence-backed executive brief"))
                .andExpect(jsonPath("$.data.input_payload.research_intent.depth").value("DEEP"))
                .andExpect(jsonPath("$.data.input_payload.research_intent.constraints.length()").value(2))
                .andExpect(jsonPath("$.data.input_payload.research_intent.time_range").value("Current project cycle"))
                .andExpect(jsonPath("$.data.control_pack.pack_type").value("research"))
                .andExpect(jsonPath("$.data.source_scope.length()").value(1))
                .andExpect(jsonPath("$.data.source_scope[0].title").value("research-input.md"))
                .andExpect(jsonPath("$.data.source_scope[0].sample_text")
                        .value(org.hamcrest.Matchers.containsString("Research input source")));

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/progress", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phase", "WAITING_FOR_PROVIDER",
                                "progress_percent", 35,
                                "message", "外部检索 provider 已分发，等待回调补齐关键证据",
                                "metrics", Map.of("provider_jobs", 1),
                                "payload", Map.of(
                                        "provider_job", Map.ofEntries(
                                                Map.entry("provider_id", "builtin-search-provider"),
                                                Map.entry("server_id", "builtin-search-provider"),
                                                Map.entry("tool_name", "read_external_content"),
                                                Map.entry("capability_name", "READ_EXTERNAL_CONTENT"),
                                                Map.entry("operation_key", "READ_EXTERNAL_CONTENT"),
                                                Map.entry("status", "WAITING_FOR_PROVIDER"),
                                                Map.entry("request_id", "fetch-research-task-read-1"),
                                                Map.entry("provider_receipt_id", "provider-receipt-fetch-research-task-read-1"),
                                                Map.entry("provider_job_id", "provider-job-builtin-search-provider-fetch-research-task-read-1"),
                                                Map.entry("delivery_id", "acq-delivery-fetch-research-task-read-1-2"),
                                                Map.entry("adapter_callback_token", "adapter-callback-fetch-research-task-read-1"),
                                                Map.entry("provider_status", "AVAILABLE"),
                                                Map.entry("health_status", "HEALTHY"),
                                                Map.entry("provider_job_status", "DISPATCHED"),
                                                Map.entry("callback_status", "DISPATCHED_TO_PROVIDER"),
                                                Map.entry("provider_delivery_attempts", java.util.List.of(
                                                        Map.ofEntries(
                                                                Map.entry("delivery_id", "acq-delivery-fetch-research-task-read-1-1"),
                                                                Map.entry("dispatch_count", 1),
                                                                Map.entry("callback_token", "acq-callback-token-fetch-research-task-read-1-1"),
                                                                Map.entry("provider_job_id", "provider-job-builtin-search-provider-fetch-research-task-read-1"),
                                                                Map.entry("provider_receipt_id", "provider-receipt-fetch-research-task-read-1"),
                                                                Map.entry("ack_status", "FAILED"),
                                                                Map.entry("callback_received_at", "2026-07-07T12:00:00Z"),
                                                                Map.entry("error_code", "UPSTREAM_429"),
                                                                Map.entry("error_message", "search provider rate limited")
                                                        ),
                                                        Map.ofEntries(
                                                                Map.entry("delivery_id", "acq-delivery-fetch-research-task-read-1-2"),
                                                                Map.entry("dispatch_count", 2),
                                                                Map.entry("callback_token", "acq-callback-token-fetch-research-task-read-1-2"),
                                                                Map.entry("provider_job_id", "provider-job-builtin-search-provider-fetch-research-task-read-1"),
                                                                Map.entry("provider_receipt_id", "provider-receipt-fetch-research-task-read-1"),
                                                                Map.entry("ack_status", "PENDING")
                                                        )
                                                ))
                                        )
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING"));

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_status").value("WAITING"))
                .andExpect(jsonPath("$.data.progress_phase").value("WAITING_FOR_PROVIDER"))
                .andExpect(jsonPath("$.data.progress_message").value("外部检索 provider 已分发，等待回调补齐关键证据"))
                .andExpect(jsonPath("$.data.wait_context.status").value("WAITING_FOR_PROVIDER"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_id").value("builtin-search-provider"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.server_id").value("builtin-search-provider"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.tool_name").value("read_external_content"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.capability_name").value("READ_EXTERNAL_CONTENT"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.operation_key").value("READ_EXTERNAL_CONTENT"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.request_id").value("fetch-research-task-read-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_receipt_id").value("provider-receipt-fetch-research-task-read-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_job_id").value("provider-job-builtin-search-provider-fetch-research-task-read-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.delivery_id").value("acq-delivery-fetch-research-task-read-1-2"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.adapter_callback_token").value("adapter-callback-fetch-research-task-read-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.health_status").value("HEALTHY"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_job_status").value("DISPATCHED"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.dispatch_count").value(2))
                .andExpect(jsonPath("$.data.wait_context.provider_job.previous_failed_delivery_count").value(1))
                .andExpect(jsonPath("$.data.wait_context.provider_job.has_previous_failed_delivery").value(true))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_delivery_attempts.length()").value(2))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_delivery_attempts[0].error_code").value("UPSTREAM_429"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_delivery_attempts[1].ack_status").value("PENDING"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.callback_status").value("DISPATCHED_TO_PROVIDER"))
                .andExpect(jsonPath("$.data.wait_context.payload").doesNotExist());

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}", workspaceId, researchRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_FOR_PROVIDER"))
                .andExpect(jsonPath("$.data.wait_context.status").value("WAITING_FOR_PROVIDER"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_id").value("builtin-search-provider"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.server_id").value("builtin-search-provider"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.tool_name").value("read_external_content"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.capability_name").value("READ_EXTERNAL_CONTENT"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.operation_key").value("READ_EXTERNAL_CONTENT"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.request_id").value("fetch-research-task-read-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_receipt_id").value("provider-receipt-fetch-research-task-read-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_job_id").value("provider-job-builtin-search-provider-fetch-research-task-read-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.delivery_id").value("acq-delivery-fetch-research-task-read-1-2"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.adapter_callback_token").value("adapter-callback-fetch-research-task-read-1"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.health_status").value("HEALTHY"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_job_status").value("DISPATCHED"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.dispatch_count").value(2))
                .andExpect(jsonPath("$.data.wait_context.provider_job.previous_failed_delivery_count").value(1))
                .andExpect(jsonPath("$.data.wait_context.provider_job.has_previous_failed_delivery").value(true))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_delivery_attempts.length()").value(2))
                .andExpect(jsonPath("$.data.wait_context.provider_job.provider_delivery_attempts[0].ack_status").value("FAILED"))
                .andExpect(jsonPath("$.data.wait_context.provider_job.callback_status").value("DISPATCHED_TO_PROVIDER"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].research_run_id").value(researchRunId))
                .andExpect(jsonPath("$.data[0].status").value("WAITING_FOR_PROVIDER"))
                .andExpect(jsonPath("$.data[0].wait_context.status").value("WAITING_FOR_PROVIDER"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.provider_id").value("builtin-search-provider"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.server_id").value("builtin-search-provider"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.tool_name").value("read_external_content"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.capability_name").value("READ_EXTERNAL_CONTENT"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.operation_key").value("READ_EXTERNAL_CONTENT"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.request_id").value("fetch-research-task-read-1"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.provider_receipt_id").value("provider-receipt-fetch-research-task-read-1"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.provider_job_id").value("provider-job-builtin-search-provider-fetch-research-task-read-1"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.delivery_id").value("acq-delivery-fetch-research-task-read-1-2"))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.dispatch_count").value(2))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.previous_failed_delivery_count").value(1))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.has_previous_failed_delivery").value(true))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.provider_delivery_attempts[1].dispatch_count").value(2))
                .andExpect(jsonPath("$.data[0].wait_context.provider_job.callback_status").value("DISPATCHED_TO_PROVIDER"));

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/progress", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phase", "VERIFYING",
                                "progress_percent", 70,
                                "message", "关键字段验证中",
                                "metrics", Map.of("verified_cells", 3),
                                "payload", Map.of(
                                        "recovery_mode", "READ_MORE",
                                        "recovery_targets", Map.of(
                                                "requirement_ids", java.util.List.of("req-summary"),
                                                "requirement_types", java.util.List.of("GOAL_FINDING"),
                                                "requirement_labels", java.util.List.of("Executive summary"),
                                                "target_columns", java.util.List.of("claim_text"),
                                                "target_queries", java.util.List.of("AlphaResearch summary"),
                                                "target_sources", java.util.List.of("research-input.md"),
                                                "requirement_count", 1,
                                                "query_count", 1,
                                                "source_count", 1,
                                                "column_count", 1
                                        ),
                                        "source_samples", java.util.List.of(Map.of(
                                                "source_id", scopedSourceId,
                                                "source_title", "research-input.md",
                                                "read_focus", "core summary and validation notes"
                                        )),
                                        "verifier_focus", Map.of(
                                                "verifier_scope", "VERIFY",
                                                "branch_id", "branch-main",
                                                "target_requirement_id", "req-summary",
                                                "decision", "WRITE_WITH_GUARDRAILS",
                                                "source_samples", java.util.List.of(Map.of(
                                                        "source_id", scopedSourceId,
                                                        "source_title", "research-input.md"
                                                ))
                                        )
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/heartbeat", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "worker_type", "research-worker",
                                "worker_instance_id", "worker-1",
                                "phase", "VERIFYING",
                                "heartbeat_at", "2026-07-06T11:00:00Z"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HEARTBEAT_RECORDED"));

        mockMvc.perform(get("/api/v2/tasks/{taskId}/events", taskId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: task.progress")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: task.heartbeat")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"phase\":\"VERIFYING\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"progress_percent\":70")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"verified_cells\":3")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"recovery_mode\":\"READ_MORE\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"target_requirement_id\":\"req-summary\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"source_title\":\"research-input.md\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"worker_type\":\"research-worker\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"heartbeat_at\":\"2026-07-06T11:00:00Z\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"event_type\":\"TASK_PROGRESS\"")));

        Map<String, Object> researchResultPayload = Map.ofEntries(
                Map.entry("report_markdown", "## Alpha Research Report\n\nVerified findings."),
                Map.entry("read_windows", java.util.List.of(Map.ofEntries(
                        Map.entry("window_id", "rw-1"),
                        Map.entry("hit_id", "hit-1"),
                        Map.entry("source_id", scopedSourceId),
                        Map.entry("source_title", "research-input.md"),
                        Map.entry("query", "AlphaResearch summary"),
                        Map.entry("read_focus", "core summary and validation notes"),
                        Map.entry("window_text", "Verified findings."),
                        Map.entry("retention_reason", "TOP_EVIDENCE"),
                        Map.entry("token_estimate", 120),
                        Map.entry("url", "https://example.com/research-input"),
                        Map.entry("provider", "workspace"),
                        Map.entry("adapter", "workspace"),
                        Map.entry("snapshot_status", "FALLBACK"),
                        Map.entry("snapshot_key", "snapshot-rw-1")
                ))),
                Map.entry("harness_summary", Map.of(
                        "mode", "RULE",
                        "phase_count", 6,
                        "warning_count", 0
                )),
                Map.entry("harness_control_state", Map.ofEntries(
                        Map.entry("control_loop", Map.of(
                                "loop_round_count", 1,
                                "active_branch_id", "branch-main",
                                "final_loop_decision", "SYNTHESIZE_REPORT"
                        )),
                        Map.entry("verifier_gate", Map.of(
                                "local_verifier_status", "PASS",
                                "global_verifier_decision", "READY_TO_WRITE"
                        )),
                        Map.entry("branch_recovery", Map.of(
                                "active_branch_id", "branch-main",
                                "latest_branch_decision", "NO_BRANCH"
                        )),
                        Map.entry("resume_checkpoint", Map.of(
                                "resumed_from_checkpoint", false,
                                "source_research_run_id", ""
                        ))
                )),
                Map.entry("audit_summaries", Map.ofEntries(
                        Map.entry("checkpoint_summary", Map.of(
                                "checkpoint_no", 1,
                                "active_branch_id", "branch-main",
                                "final_loop_decision", "SYNTHESIZE_REPORT"
                        )),
                        Map.entry("counterfactual_summary", Map.of(
                                "has_counterfactual_recheck", false,
                                "counterfactual_branch_count", 0
                        )),
                        Map.entry("resume_summary", Map.of(
                                "resumed_from_checkpoint", false
                        )),
                        Map.entry("evidence_coverage_summary", Map.of(
                                "verified_evidence_count", 1,
                                "covered_requirement_count", 5
                        ))
                )),
                Map.entry("toolbox_summary", Map.ofEntries(
                        Map.entry("search_summary", Map.of(
                                "selected_query_count", 1,
                                "selected_queries", java.util.List.of("AlphaResearch summary")
                        )),
                        Map.entry("fetch_summary", Map.of(
                                "document_count", 0,
                                "snapshot_archive_ready_count", 0
                        )),
                        Map.entry("read_summary", Map.of(
                                "window_count", 1,
                                "snapshot_archive_ready_count", 0
                        ))
                )),
                Map.entry("evidence_cards", java.util.List.of(Map.of(
                        "evidence_id", "ev-1",
                        "window_id", "rw-1",
                        "source_id", scopedSourceId,
                        "source_title", "research-input.md",
                        "claim_text", "AlphaResearch can be summarized with verified findings.",
                        "quote_text", "Verified findings.",
                        "relation_type", "SUPPORTS",
                        "support_score", 0.95,
                        "conflict_score", 0.05
                ))),
                Map.entry("state_ledger", Map.of(
                        "active_branch_id", "branch-main",
                        "rows", java.util.List.of(Map.ofEntries(
                                Map.entry("row_id", "row-1"),
                                Map.entry("source_id", scopedSourceId),
                                Map.entry("source_title", "research-input.md"),
                                Map.entry("search_query", "AlphaResearch summary"),
                                Map.entry("read_focus", "core summary and validation notes"),
                                Map.entry("evidence_id", "ev-1"),
                                Map.entry("row_status", "VERIFIED"),
                                Map.entry("relation_type", "SUPPORTS"),
                                Map.entry("support_score", 0.95),
                                Map.entry("conflict_score", 0.05),
                                Map.entry("support_level", "STRONG"),
                                Map.entry("verification_status", "PASS"),
                                Map.entry("verifier_note", "Evidence verified for synthesis."),
                                Map.entry("branch_id", "branch-main"),
                                Map.entry("requirement_completion_status", "READY"),
                                Map.entry("matched_requirement_ids", java.util.List.of("goal_finding", "constraint_finding_2")),
                                Map.entry("ready_requirement_ids", java.util.List.of("goal_finding", "constraint_finding_2")),
                                Map.entry("required_column_count", 5),
                                Map.entry("completed_column_count", 5),
                                Map.entry("missing_columns", java.util.List.of())
                        )),
                        "cells", java.util.List.of(Map.ofEntries(
                                Map.entry("cell_id", "row-1:claim_text"),
                                Map.entry("row_id", "row-1"),
                                Map.entry("column_key", "claim_text"),
                                Map.entry("candidate_value", "AlphaResearch summary"),
                                Map.entry("status", "VERIFIED"),
                                Map.entry("confidence", 0.95),
                                Map.entry("evidence_refs", java.util.List.of("ev-1")),
                                Map.entry("branch_id", "branch-main"),
                                Map.entry("last_verifier_decision", "PASS"),
                                Map.entry("repair_count", 0),
                                Map.entry("is_required", true),
                                Map.entry("required_by_requirement_ids", java.util.List.of("goal_finding", "constraint_finding_2")),
                                Map.entry("satisfied_requirement_ids", java.util.List.of("goal_finding", "constraint_finding_2")),
                                Map.entry("requirement_completion_status", "READY")
                        )),
                        "branches", java.util.List.of(Map.of(
                                "branch_id", "branch-main",
                                "status", "MAINLINE"
                        )),
                        "verifier_decisions", java.util.List.of(Map.of(
                                "decision_scope", "LOCAL",
                                "decision_type", "PASS",
                                "evidence_ids", java.util.List.of("ev-1")
                        )),
                        "required_finding_contract", java.util.List.of(Map.of(
                                "requirement_id", "goal_finding",
                                "requirement_type", "GOAL_FINDING",
                                "label", "Goal finding: Produce a verifier-approved summary for AlphaResearch.",
                                "completion_mode", "ROW_EVIDENCE",
                                "required_columns", java.util.List.of("source_title", "claim_text", "evidence_excerpt", "support_level", "verifier_note"),
                                "accepted_row_statuses", java.util.List.of("VERIFIED"),
                                "target_row_count", 1
                        )),
                        "required_finding_progress", java.util.List.of(Map.ofEntries(
                                Map.entry("requirement_id", "goal_finding"),
                                Map.entry("requirement_type", "GOAL_FINDING"),
                                Map.entry("label", "Goal finding: Produce a verifier-approved summary for AlphaResearch."),
                                Map.entry("completion_mode", "ROW_EVIDENCE"),
                                Map.entry("target_row_count", 1),
                                Map.entry("accepted_row_statuses", java.util.List.of("VERIFIED")),
                                Map.entry("required_columns", java.util.List.of("source_title", "claim_text", "evidence_excerpt", "support_level", "verifier_note")),
                                Map.entry("matched_row_ids", java.util.List.of("row-1")),
                                Map.entry("ready_row_ids", java.util.List.of("row-1")),
                                Map.entry("partial_row_ids", java.util.List.of()),
                                Map.entry("status", "READY")
                        )),
                        "requirement_ready_row_count", 1,
                        "requirement_partial_row_count", 0,
                        "intent_completion_contract", Map.of(
                                "status", "PASS",
                                "reason_code", "INTENT_REQUIREMENTS_COMPLETE",
                                "total_requirement_count", 5,
                                "satisfied_requirement_count", 5,
                                "pending_requirement_count", 0,
                                "missing_requirement_labels", java.util.List.of(),
                                "requirements", java.util.List.of(
                                        Map.of(
                                                "requirement_id", "goal",
                                                "requirement_type", "GOAL",
                                                "label", "Research goal: Produce a verifier-approved summary for AlphaResearch.",
                                                "status", "PASS",
                                                "coverage_note", "research goal is anchored to the current ledger state"
                                        ),
                                        Map.of(
                                                "requirement_id", "constraint-1",
                                                "requirement_type", "CONSTRAINT",
                                                "label", "Constraint: Separate verified findings from unresolved conflicts.",
                                                "status", "PASS",
                                                "coverage_note", "constraint covered: Separate verified findings from unresolved conflicts."
                                        )
                                )
                        )
                )),
                Map.entry("local_verifier", Map.of(
                        "status", "PASS",
                        "research_intent_alignment", Map.of(
                                "status", "PASS",
                                "reason_code", "INTENT_ALIGNED",
                                "goal_status", "PASS",
                                "deliverable_status", "PASS",
                                "time_range_status", "PASS",
                                "depth_status", "PASS",
                                "satisfied_constraint_count", 2,
                                "total_constraint_count", 2,
                                "covered_requirements", java.util.List.of(
                                        "research goal is anchored to the current ledger state",
                                        "deliverable format was compiled into the report plan"
                                ),
                                "missing_requirements", java.util.List.of()
                        ),
                        "decision_records", java.util.List.of(Map.of(
                                "decision_scope", "LOCAL",
                                "decision_type", "PASS",
                                "reason_code", "VERIFIED_PATH"
                        ))
                )),
                Map.entry("global_verifier", Map.of(
                        "status", "PASS",
                        "decision", "READY_TO_WRITE",
                        "research_intent_alignment", Map.of(
                                "status", "PASS",
                                "reason_code", "INTENT_ALIGNED",
                                "goal_status", "PASS",
                                "deliverable_status", "PASS",
                                "time_range_status", "PASS",
                                "depth_status", "PASS",
                                "satisfied_constraint_count", 2,
                                "total_constraint_count", 2,
                                "covered_requirements", java.util.List.of(
                                        "research goal is anchored to the current ledger state",
                                        "deliverable format was compiled into the report plan"
                                ),
                                "missing_requirements", java.util.List.of()
                        ),
                        "decision_records", java.util.List.of(Map.of(
                                "decision_scope", "GLOBAL",
                                "decision_type", "READY_TO_WRITE",
                                "reason_code", "STOP_CONTRACT_SATISFIED"
                        ))
                )),
                Map.entry("report_structure", Map.ofEntries(
                        Map.entry("research_question", Map.of(
                                "original_question", "AlphaResearch should focus on what?",
                                "research_profile", "DEFAULT"
                        )),
                        Map.entry("research_intent", Map.of(
                                "research_goal", "Produce a verifier-approved summary for AlphaResearch.",
                                "deliverable_format", "Evidence-backed executive brief",
                                "constraints", java.util.List.of(
                                        "Separate verified findings from unresolved conflicts.",
                                        "Anchor every conclusion to explicit evidence."
                                ),
                                "time_range", "Current project cycle",
                                "depth", "DEEP"
                        )),
                        Map.entry("intent_completion_contract", Map.of(
                                "status", "PASS",
                                "reason_code", "INTENT_REQUIREMENTS_COMPLETE",
                                "total_requirement_count", 5,
                                "satisfied_requirement_count", 5,
                                "pending_requirement_count", 0,
                                "missing_requirement_labels", java.util.List.of(),
                                "requirements", java.util.List.of(
                                        Map.of(
                                                "requirement_id", "goal",
                                                "requirement_type", "GOAL",
                                                "label", "Research goal: Produce a verifier-approved summary for AlphaResearch.",
                                                "status", "PASS",
                                                "coverage_note", "research goal is anchored to the current ledger state"
                                        )
                                )
                        )),
                        Map.entry("research_intent_alignment", Map.of(
                                "status", "PASS",
                                "reason_code", "INTENT_ALIGNED",
                                "goal_status", "PASS",
                                "deliverable_status", "PASS",
                                "time_range_status", "PASS",
                                "depth_status", "PASS",
                                "satisfied_constraint_count", 2,
                                "total_constraint_count", 2,
                                "covered_requirements", java.util.List.of(
                                        "research goal is anchored to the current ledger state",
                                        "deliverable format was compiled into the report plan"
                                ),
                                "missing_requirements", java.util.List.of()
                        )),
                        Map.entry("key_findings", java.util.List.of(
                                "The harness completed planning, workspace search, bounded reading, evidence extraction, local verification, and global verification.",
                                "Current workspace source scope: research-input.md"
                        )),
                        Map.entry("verified_findings", java.util.List.of(Map.of(
                                "source_title", "research-input.md",
                                "read_focus", "core summary and validation notes",
                                "evidence_id", "ev-1",
                                "claim_text", "AlphaResearch summary",
                                "evidence_excerpt", "AlphaResearch summary cites the workspace source.",
                                "support_level", "STRONG",
                                "support_score", 0.95,
                                "row_status", "VERIFIED"
                        ))),
                        Map.entry("evidence_ledger", java.util.List.of(Map.of(
                                "source_title", "research-input.md",
                                "evidence_id", "ev-1",
                                "row_status", "VERIFIED"
                        ))),
                        Map.entry("closed_loop_state", Map.ofEntries(
                                Map.entry("active_branch", "branch-main"),
                                Map.entry("branch_count", 1),
                                Map.entry("verifier_decisions_count", 1),
                                Map.entry("verified_rows_count", 1),
                                Map.entry("conflicted_rows_count", 0),
                                Map.entry("requirement_ready_rows_count", 1),
                                Map.entry("requirement_partial_rows_count", 0),
                                Map.entry("intent_requirement_count", 5),
                                Map.entry("intent_satisfied_requirement_count", 5),
                                Map.entry("intent_pending_requirement_count", 0),
                                Map.entry("missing_intent_requirements", java.util.List.of()),
                                Map.entry("recovery_targets", Map.ofEntries(
                                        Map.entry("requirement_ids", java.util.List.of()),
                                        Map.entry("requirement_types", java.util.List.of()),
                                        Map.entry("requirement_labels", java.util.List.of()),
                                        Map.entry("target_columns", java.util.List.of()),
                                        Map.entry("target_queries", java.util.List.of()),
                                        Map.entry("target_sources", java.util.List.of()),
                                        Map.entry("requirement_count", 0),
                                        Map.entry("query_count", 0),
                                        Map.entry("source_count", 0),
                                        Map.entry("column_count", 0)
                                ))
                        )),
                        Map.entry("counterfactual_summary", Map.ofEntries(
                                Map.entry("has_counterfactual_recheck", false),
                                Map.entry("counterfactual_branch_count", 0),
                                Map.entry("conflicted_row_count", 0),
                                Map.entry("local_verifier_status", "PASS"),
                                Map.entry("global_verifier_decision", "READY_TO_WRITE"),
                                Map.entry("recovery_mode", ""),
                                Map.entry("counterfactual_branch_ids", java.util.List.of()),
                                Map.entry("active_counterfactual_branch_ids", java.util.List.of()),
                                Map.entry("branch_reasons", java.util.List.of()),
                                Map.entry("target_evidence_ids", java.util.List.of()),
                                Map.entry("branches", java.util.List.of())
                        )),
                        Map.entry("conflict_and_counterfactual_review", Map.of(
                                "local_verifier_status", "PASS",
                                "global_verifier_decision", "READY_TO_WRITE",
                                "evidence_policy", "Every key finding must remain anchored to workspace evidence.",
                                "conflicted_rows", java.util.List.of(),
                                "branch_decisions", java.util.List.of(Map.of(
                                        "decision", "NO_BRANCH",
                                        "branch_reason", "VERIFIED_PATH"
                                ))
                        )),
                        Map.entry("recovery_status", Map.of(
                                "local_verifier_status", "PASS",
                                "global_verifier_decision", "READY_TO_WRITE",
                                "recovery_targets", Map.ofEntries(
                                        Map.entry("requirement_ids", java.util.List.of()),
                                        Map.entry("requirement_types", java.util.List.of()),
                                        Map.entry("requirement_labels", java.util.List.of()),
                                        Map.entry("target_columns", java.util.List.of()),
                                        Map.entry("target_queries", java.util.List.of()),
                                        Map.entry("target_sources", java.util.List.of()),
                                        Map.entry("requirement_count", 0),
                                        Map.entry("query_count", 0),
                                        Map.entry("source_count", 0),
                                        Map.entry("column_count", 0)
                                ),
                                "guardrailed_rows", java.util.List.of(),
                                "unresolved_questions", java.util.List.of(),
                                "warnings", java.util.List.of()
                        )),
                        Map.entry("final_answer", Map.of(
                                "answer_status", "READY",
                                "confidence_label", "HIGH",
                                "coverage_label", "SUFFICIENT",
                                "source_basis", "Fetched-external grounded",
                                "answer_text", "AlphaResearch can be summarized with verified findings."
                        )),
                        Map.entry("source_foundation", Map.of(
                                "primary_quality", "WORKSPACE_GROUNDED",
                                "quality_mix_label", "Workspace-grounded evidence dominated",
                                "read_strategy_mix_label", "Single bounded workspace read",
                                "fetch_foundation_label", "Fallback-snippet grounded",
                                "orchestration_foundation_label", "Single-provider stable path",
                                "deep_read_count", 1,
                                "external_window_count", 0
                        )),
                        Map.entry("next_actions", java.util.List.of(
                                "Continue only if additional source expansion or clarification is needed."
                        )),
                        Map.entry("resume_checkpoint", Map.of()),
                        Map.entry("recovery_mode", ""),
                        Map.entry("control_notes", java.util.List.of("Verifier-gated synthesis only."))
                )),
                Map.entry("loop_rounds", java.util.List.of(Map.of(
                        "round_no", 1,
                        "search_hit_count", 1,
                        "read_window_count", 1,
                        "evidence_card_count", 1,
                        "search_queries", java.util.List.of("AlphaResearch summary"),
                        "evidence_ids", java.util.List.of("ev-1"),
                        "branch_decision", "NO_BRANCH",
                        "global_decision", "READY_TO_WRITE"
                ))),
                Map.entry("loop_decision", Map.of(
                        "decision", "SYNTHESIZE_REPORT",
                        "reason", "STOP_CONTRACT_SATISFIED",
                        "round_no", 1
                )),
                Map.entry("branch_decisions", java.util.List.of(Map.of(
                        "decision", "NO_BRANCH",
                        "branch_reason", "VERIFIED_PATH"
                ))),
                Map.entry("counterfactual_summary", Map.ofEntries(
                        Map.entry("has_counterfactual_recheck", false),
                        Map.entry("counterfactual_branch_count", 0),
                        Map.entry("conflicted_row_count", 0),
                        Map.entry("local_verifier_status", "PASS"),
                        Map.entry("global_verifier_decision", "READY_TO_WRITE"),
                        Map.entry("recovery_mode", ""),
                        Map.entry("counterfactual_branch_ids", java.util.List.of()),
                        Map.entry("active_counterfactual_branch_ids", java.util.List.of()),
                        Map.entry("branch_reasons", java.util.List.of()),
                        Map.entry("target_evidence_ids", java.util.List.of()),
                        Map.entry("branches", java.util.List.of())
                )),
                Map.entry("research_intent_alignment", Map.of(
                        "status", "PASS",
                        "reason_code", "INTENT_ALIGNED",
                        "goal_status", "PASS",
                        "deliverable_status", "PASS",
                        "time_range_status", "PASS",
                        "depth_status", "PASS",
                        "satisfied_constraint_count", 2,
                        "total_constraint_count", 2,
                        "covered_requirements", java.util.List.of(
                                "research goal is anchored to the current ledger state",
                                "deliverable format was compiled into the report plan"
                        ),
                        "missing_requirements", java.util.List.of()
                )),
                Map.entry("research_artifact_candidate", Map.ofEntries(
                        Map.entry("artifact_type", "DEEP_RESEARCH_REPORT"),
                        Map.entry("artifact_version", "v1"),
                        Map.entry("title", "Alpha Research Report"),
                        Map.entry("generated_ref_type", "research_run"),
                        Map.entry("generated_ref_id", researchRunId),
                        Map.entry("source_basis", "Fetched-external grounded"),
                        Map.entry("content_markdown", "# Alpha Research Report\n\nVerifier approved summary."),
                        Map.entry("citation_count", 1)
                )),
                Map.entry("research_checkpoint_candidate", Map.of(
                        "checkpoint_no", 1,
                        "snapshot_type", "RESEARCH_LOOP_CHECKPOINT",
                        "harness_control_state", Map.ofEntries(
                                Map.entry("control_loop", Map.of(
                                        "loop_round_count", 1,
                                        "active_branch_id", "branch-main",
                                        "final_loop_decision", "SYNTHESIZE_REPORT"
                                )),
                                Map.entry("verifier_gate", Map.of(
                                        "local_verifier_status", "PASS",
                                        "global_verifier_decision", "READY_TO_WRITE"
                                ))
                        ),
                        "audit_summaries", Map.of(
                                "checkpoint_summary", Map.of(
                                        "checkpoint_no", 1,
                                        "final_loop_decision", "SYNTHESIZE_REPORT"
                                )
                        ),
                        "toolbox_summary", Map.of(
                                "search_summary", Map.of(
                                        "selected_query_count", 1
                                )
                        )
                ))
        );
        Map<String, Object> completeRequest = Map.of(
                "result_type", "RESEARCH_REPORT",
                "result_title", "Alpha Research Report",
                "result_payload", researchResultPayload,
                "trace_summary", "research harness finished",
                "citations", java.util.List.of(Map.of("title", "research-input.md"))
        );

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(completeRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.result_ref").value(researchRunId));

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.progress_phase").value("RESEARCH_REPORTED"));

        String reportMarkdown = jdbcTemplate.queryForObject(
                "select final_report_markdown from research_run where id = ?",
                String.class,
                researchRunId
        );
        assertThat(reportMarkdown).contains("Alpha Research Report");
        String reportObjectKey = "workspace/%s/research/%s/report/final.md".formatted(workspaceId, researchRunId);
        assertThat(storage.exists("noteweave-source", reportObjectKey)).isTrue();
        assertThat(new String(storage.read("noteweave-source", reportObjectKey), StandardCharsets.UTF_8))
                .contains("Alpha Research Report");

        MvcResult detailResult = mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}", workspaceId, researchRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.context_snapshot_id").doesNotExist())
                .andExpect(jsonPath("$.data.research_intent.research_goal").value("Produce a verifier-approved summary for AlphaResearch."))
                .andExpect(jsonPath("$.data.research_intent.deliverable_format").value("Evidence-backed executive brief"))
                .andExpect(jsonPath("$.data.research_intent.depth").value("DEEP"))
                .andExpect(jsonPath("$.data.final_report_markdown").value(org.hamcrest.Matchers.containsString("Alpha Research Report")))
                .andExpect(jsonPath("$.data.research_artifact_candidate.title").value("Alpha Research Report"))
                .andExpect(jsonPath("$.data.research_artifact_candidate.generated_ref_id").value(researchRunId))
                .andExpect(jsonPath("$.data.research_artifact_candidate.source_basis").value("Fetched-external grounded"))
                .andExpect(jsonPath("$.data.research_artifact_candidate.citation_count").value(1))
                .andExpect(jsonPath("$.data.report_file.object_key").value(reportObjectKey))
                .andExpect(jsonPath("$.data.report_file.file_name").value("final.md"))
                .andExpect(jsonPath("$.data.report_file.mime_type").value("text/markdown"))
                .andExpect(jsonPath("$.data.report_file.content_size").value(reportMarkdown.getBytes(StandardCharsets.UTF_8).length))
                .andExpect(jsonPath("$.data.report_file.sha256").isNotEmpty())
                .andExpect(jsonPath("$.data.research_artifact.artifact_id").value(researchRunId))
                .andExpect(jsonPath("$.data.research_artifact.artifact_type").value("DEEP_RESEARCH_REPORT"))
                .andExpect(jsonPath("$.data.research_artifact.title").value("Alpha Research Report"))
                .andExpect(jsonPath("$.data.research_artifact.generated_ref_id").value(researchRunId))
                .andExpect(jsonPath("$.data.research_artifact.report_file.object_key").value(reportObjectKey))
                .andExpect(jsonPath("$.data.research_process_summary.source_scope_count").value(1))
                .andExpect(jsonPath("$.data.research_process_summary.search_read_timeline.loop_round_count").value(1))
                .andExpect(jsonPath("$.data.research_process_summary.search_read_timeline.total_search_hit_count").value(1))
                .andExpect(jsonPath("$.data.research_process_summary.search_read_timeline.total_read_window_count").value(1))
                .andExpect(jsonPath("$.data.research_process_summary.search_read_timeline.total_evidence_card_count").value(1))
                .andExpect(jsonPath("$.data.research_process_summary.search_read_timeline.all_search_queries[0]").value("AlphaResearch summary"))
                .andExpect(jsonPath("$.data.research_process_summary.search_read_timeline.final_loop_decision").value("SYNTHESIZE_REPORT"))
                .andExpect(jsonPath("$.data.research_process_summary.search_read_timeline.rounds[0].round_no").value(1))
                .andExpect(jsonPath("$.data.research_process_summary.search_read_timeline.rounds[0].branch_decision").value("NO_BRANCH"))
                .andExpect(jsonPath("$.data.research_process_summary.source_evidence_summary.source_basis").value("Fetched-external grounded"))
                .andExpect(jsonPath("$.data.research_process_summary.source_evidence_summary.primary_quality").value("WORKSPACE_GROUNDED"))
                .andExpect(jsonPath("$.data.research_process_summary.source_evidence_summary.fetch_foundation_label").value("Fallback-snippet grounded"))
                .andExpect(jsonPath("$.data.research_process_summary.source_evidence_summary.verified_finding_count").value(1))
                .andExpect(jsonPath("$.data.research_process_summary.audit_summary.local_verifier_status").value("PASS"))
                .andExpect(jsonPath("$.data.research_process_summary.audit_summary.global_verifier_decision").value("READY_TO_WRITE"))
                .andExpect(jsonPath("$.data.research_process_summary.audit_summary.final_loop_decision").value("SYNTHESIZE_REPORT"))
                .andExpect(jsonPath("$.data.research_process_summary.audit_summary.checkpoint_count").value(1))
                .andExpect(jsonPath("$.data.research_process_summary.audit_summary.blocked_row_count").value(0))
                .andExpect(jsonPath("$.data.research_process_summary.audit_summary.has_counterfactual_recheck").value(false))
                .andExpect(jsonPath("$.data.harness_control_state.control_loop.loop_round_count").value(1))
                .andExpect(jsonPath("$.data.harness_control_state.control_loop.active_branch_id").value("branch-main"))
                .andExpect(jsonPath("$.data.audit_summaries.checkpoint_summary.final_loop_decision").value("SYNTHESIZE_REPORT"))
                .andExpect(jsonPath("$.data.toolbox_summary.search_summary.selected_query_count").value(1))
                .andExpect(jsonPath("$.data.report_structure.research_question.original_question").value("AlphaResearch should focus on what?"))
                .andExpect(jsonPath("$.data.report_structure.research_intent.research_goal").value("Produce a verifier-approved summary for AlphaResearch."))
                .andExpect(jsonPath("$.data.report_structure.research_intent.deliverable_format").value("Evidence-backed executive brief"))
                .andExpect(jsonPath("$.data.report_structure.research_intent.depth").value("DEEP"))
                .andExpect(jsonPath("$.data.report_structure.final_answer.answer_status").value("READY"))
                .andExpect(jsonPath("$.data.report_structure.final_answer.source_basis").value("Fetched-external grounded"))
                .andExpect(jsonPath("$.data.report_structure.source_foundation.primary_quality").value("WORKSPACE_GROUNDED"))
                .andExpect(jsonPath("$.data.report_structure.source_foundation.fetch_foundation_label").value("Fallback-snippet grounded"))
                .andExpect(jsonPath("$.data.report_structure.intent_completion_contract.status").value("PASS"))
                .andExpect(jsonPath("$.data.report_structure.intent_completion_contract.total_requirement_count").value(5))
                .andExpect(jsonPath("$.data.report_structure.intent_completion_contract.satisfied_requirement_count").value(5))
                .andExpect(jsonPath("$.data.report_structure.research_intent_alignment.status").value("PASS"))
                .andExpect(jsonPath("$.data.report_structure.research_intent_alignment.reason_code").value("INTENT_ALIGNED"))
                .andExpect(jsonPath("$.data.report_structure.research_intent_alignment.satisfied_constraint_count").value(2))
                .andExpect(jsonPath("$.data.report_structure.research_intent_alignment.total_constraint_count").value(2))
                .andExpect(jsonPath("$.data.report_structure.verified_findings.length()").value(1))
                .andExpect(jsonPath("$.data.report_structure.verified_findings[0].evidence_id").value("ev-1"))
                .andExpect(jsonPath("$.data.report_structure.counterfactual_summary.has_counterfactual_recheck").value(false))
                .andExpect(jsonPath("$.data.report_structure.conflict_and_counterfactual_review.branch_decisions[0].decision").value("NO_BRANCH"))
                .andExpect(jsonPath("$.data.report_structure.recovery_status.global_verifier_decision").value("READY_TO_WRITE"))
                .andExpect(jsonPath("$.data.report_structure.closed_loop_state.recovery_targets.requirement_count").value(0))
                .andExpect(jsonPath("$.data.report_structure.recovery_status.recovery_targets.query_count").value(0))
                .andExpect(jsonPath("$.data.report_structure.next_actions.length()").value(1))
                .andExpect(jsonPath("$.data.counterfactual_summary.has_counterfactual_recheck").value(false))
                .andExpect(jsonPath("$.data.counterfactual_summary.counterfactual_branch_count").value(0))
                .andExpect(jsonPath("$.data.counterfactual_summary.conflicted_row_count").value(0))
                .andExpect(jsonPath("$.data.source_scope.length()").value(1))
                .andExpect(jsonPath("$.data.verifier_summary.local_verifier_status").value("PASS"))
                .andExpect(jsonPath("$.data.verifier_summary.local_verifier_reason").value("VERIFIED_PATH"))
                .andExpect(jsonPath("$.data.verifier_summary.global_verifier_decision").value("READY_TO_WRITE"))
                .andExpect(jsonPath("$.data.verifier_summary.global_verifier_reason").value("STOP_CONTRACT_SATISFIED"))
                .andExpect(jsonPath("$.data.verifier_summary.final_loop_decision").value("SYNTHESIZE_REPORT"))
                .andExpect(jsonPath("$.data.verifier_summary.final_loop_reason").value("STOP_CONTRACT_SATISFIED"))
                .andExpect(jsonPath("$.data.verifier_summary.research_intent_alignment_status").value("PASS"))
                .andExpect(jsonPath("$.data.verifier_summary.recovery_targets.requirement_count").value(0))
                .andExpect(jsonPath("$.data.verifier_summary.verifier_gated_summary.blocked_row_count").value(0))
                .andExpect(jsonPath("$.data.closed_loop_state.active_branch_id").value("branch-main"))
                .andExpect(jsonPath("$.data.closed_loop_state.local_verifier_status").value("PASS"))
                .andExpect(jsonPath("$.data.closed_loop_state.global_verifier_decision").value("READY_TO_WRITE"))
                .andExpect(jsonPath("$.data.closed_loop_state.final_loop_decision").value("SYNTHESIZE_REPORT"))
                .andExpect(jsonPath("$.data.closed_loop_state.loop_rounds_count").value(1))
                .andExpect(jsonPath("$.data.closed_loop_state.ledger_row_count").value(1))
                .andExpect(jsonPath("$.data.closed_loop_state.harness_control_state.control_loop.loop_round_count").value(1))
                .andExpect(jsonPath("$.data.closed_loop_state.audit_summaries.checkpoint_summary.final_loop_decision").value("SYNTHESIZE_REPORT"))
                .andExpect(jsonPath("$.data.closed_loop_state.toolbox_summary.search_summary.selected_query_count").value(1))
                .andExpect(jsonPath("$.data.closed_loop_state.counterfactual_summary.has_counterfactual_recheck").value(false))
                .andExpect(jsonPath("$.data.closed_loop_state.recovery_targets.requirement_count").value(0))
                .andExpect(jsonPath("$.data.closed_loop_state.branch_count").value(1))
                .andExpect(jsonPath("$.data.closed_loop_state.verifier_decision_count").value(1))
                .andExpect(jsonPath("$.data.closed_loop_state.branches.length()").value(1))
                .andExpect(jsonPath("$.data.closed_loop_state.rows.length()").value(1))
                .andExpect(jsonPath("$.data.closed_loop_state.cells.length()").value(1))
                .andExpect(jsonPath("$.data.closed_loop_state.loop_rounds[0].source_samples[0].source_title").value("research-input.md"))
                .andExpect(jsonPath("$.data.closed_loop_state.verifier_decisions.length()").value(1))
                .andExpect(jsonPath("$.data.closed_loop_state.verifier_decisions[0].source_samples[0].source_title").value("research-input.md"))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints.length()").value(1))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].summary.local_verifier.status").value("PASS"))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].summary.global_verifier.decision").value("READY_TO_WRITE"))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].summary.state_ledger.verified_row_count").value(1))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].summary.state_ledger.conflicted_row_count").value(0))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].summary.state_ledger.requirement_ready_row_count").value(1))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].summary.state_ledger.requirement_partial_row_count").value(0))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].summary.state_ledger.verified_row_samples[0].source_title").value("research-input.md"))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].summary.state_ledger.evidence_card_samples[0].source_title").value("research-input.md"))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].summary.intent_requirement_count").value(5))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].summary.intent_satisfied_requirement_count").value(5))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].summary.recovery_targets.requirement_count").value(0))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].summary.verifier_gated_summary.blocked_row_count").value(0))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].summary.verifier_gated_summary.guardrailed_row_count").value(0))
                .andExpect(jsonPath("$.data.closed_loop_state.state_ledger.intent_completion_contract.status").value("PASS"))
                .andExpect(jsonPath("$.data.closed_loop_state.state_ledger.intent_completion_contract.total_requirement_count").value(5))
                .andExpect(jsonPath("$.data.closed_loop_state.state_ledger.requirement_ready_row_count").value(1))
                .andExpect(jsonPath("$.data.closed_loop_state.state_ledger.requirement_partial_row_count").value(0))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].summary.counterfactual_summary.has_counterfactual_recheck").value(false))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].local_verifier_status").value("PASS"))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].global_verifier_decision").value("READY_TO_WRITE"))
                .andExpect(jsonPath("$.data.closed_loop_state.source_evidence.length()").value(1))
                .andExpect(jsonPath("$.data.closed_loop_state.cell_evidence.length()").value(1))
                .andExpect(jsonPath("$.data.closed_loop_state.source_evidence[0].evidence_id").value("ev-1"))
                .andExpect(jsonPath("$.data.closed_loop_state.source_evidence[0].snapshot_status").value("FALLBACK"))
                .andExpect(jsonPath("$.data.closed_loop_state.cell_evidence[0].cell_id").value("row-1:claim_text"))
                .andReturn();
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].research_run_id").value(researchRunId))
                .andExpect(jsonPath("$.data[0].task_id").value(taskId))
                .andExpect(jsonPath("$.data[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data[0].final_report_title").value("Alpha Research Report"))
                .andExpect(jsonPath("$.data[0].source_scope_count").value(1))
                .andExpect(jsonPath("$.data[0].checkpoint_count").value(1))
                .andExpect(jsonPath("$.data[0].active_branch_id").value("branch-main"))
                .andExpect(jsonPath("$.data[0].local_verifier_status").value("PASS"))
                .andExpect(jsonPath("$.data[0].local_verifier_reason").value("VERIFIED_PATH"))
                .andExpect(jsonPath("$.data[0].ledger_row_count").value(1))
                .andExpect(jsonPath("$.data[0].verified_row_count").value(1))
                .andExpect(jsonPath("$.data[0].conflicted_row_count").value(0))
                .andExpect(jsonPath("$.data[0].global_verifier_decision").value("READY_TO_WRITE"))
                .andExpect(jsonPath("$.data[0].global_verifier_reason").value("STOP_CONTRACT_SATISFIED"))
                .andExpect(jsonPath("$.data[0].final_loop_decision").value("SYNTHESIZE_REPORT"))
                .andExpect(jsonPath("$.data[0].final_loop_reason").value("STOP_CONTRACT_SATISFIED"))
                .andExpect(jsonPath("$.data[0].recovery_mode").value(""))
                .andExpect(jsonPath("$.data[0].intent_satisfied_requirement_count").value(5))
                .andExpect(jsonPath("$.data[0].intent_requirement_count").value(5))
                .andExpect(jsonPath("$.data[0].intent_pending_requirement_count").value(0))
                .andExpect(jsonPath("$.data[0].research_artifact_candidate.title").value("Alpha Research Report"))
                .andExpect(jsonPath("$.data[0].research_artifact_candidate.generated_ref_id").value(researchRunId))
                .andExpect(jsonPath("$.data[0].research_artifact_candidate.citation_count").value(1))
                .andExpect(jsonPath("$.data[0].harness_control_state.control_loop.loop_round_count").value(1))
                .andExpect(jsonPath("$.data[0].audit_summaries.checkpoint_summary.final_loop_decision").value("SYNTHESIZE_REPORT"))
                .andExpect(jsonPath("$.data[0].toolbox_summary.search_summary.selected_query_count").value(1))
                .andExpect(jsonPath("$.data[0].report_file.object_key").value(reportObjectKey))
                .andExpect(jsonPath("$.data[0].report_file.file_name").value("final.md"))
                .andExpect(jsonPath("$.data[0].report_file.content_size").value(reportMarkdown.getBytes(StandardCharsets.UTF_8).length))
                .andExpect(jsonPath("$.data[0].research_artifact.artifact_id").value(researchRunId))
                .andExpect(jsonPath("$.data[0].research_artifact.report_file.object_key").value(reportObjectKey))
                .andExpect(jsonPath("$.data[0].research_process_summary.source_scope_count").value(1))
                .andExpect(jsonPath("$.data[0].research_process_summary.search_read_timeline.loop_round_count").value(1))
                .andExpect(jsonPath("$.data[0].research_process_summary.search_read_timeline.total_search_hit_count").value(1))
                .andExpect(jsonPath("$.data[0].research_process_summary.source_evidence_summary.primary_quality").value("WORKSPACE_GROUNDED"))
                .andExpect(jsonPath("$.data[0].research_process_summary.audit_summary.global_verifier_decision").value("READY_TO_WRITE"))
                .andExpect(jsonPath("$.data[0].recovery_targets.requirement_count").value(0))
                .andExpect(jsonPath("$.data[0].counterfactual_summary.has_counterfactual_recheck").value(false))
                .andExpect(jsonPath("$.data[0].counterfactual_summary.counterfactual_branch_count").value(0));
        JsonNode detail = objectMapper.readTree(detailResult.getResponse().getContentAsString()).path("data");
        JsonNode finalTrace = findTrace(detail.path("traces"), "FINAL_REPORT");
        assertThat(finalTrace.path("payload").path("result_payload").path("evidence_cards").get(0).path("evidence_id").asText()).isEqualTo("ev-1");
        assertThat(finalTrace.path("payload").path("result_payload").path("read_windows").get(0).path("snapshot_key").asText()).isEqualTo("snapshot-rw-1");
        assertThat(finalTrace.path("payload").path("result_payload").path("branch_decisions").get(0).path("decision").asText()).isEqualTo("NO_BRANCH");
        assertThat(finalTrace.path("payload").path("result_payload").path("research_artifact_candidate").path("generated_ref_id").asText()).isEqualTo(researchRunId);
        assertThat(findTrace(detail.path("traces"), "HARNESS_SUMMARY").path("payload").path("harness_summary").path("mode").asText()).isEqualTo("RULE");
        assertThat(findTrace(detail.path("traces"), "COUNTERFACTUAL_SUMMARY").path("payload").path("counterfactual_summary").path("has_counterfactual_recheck").asBoolean()).isFalse();
        assertThat(findTrace(detail.path("traces"), "LOOP_RUNTIME").path("payload").path("loop_decision").path("decision").asText()).isEqualTo("SYNTHESIZE_REPORT");
        assertThat(findTrace(detail.path("traces"), "STATE_LEDGER").path("payload").path("state_ledger").path("active_branch_id").asText()).isEqualTo("branch-main");
        assertThat(findTrace(detail.path("traces"), "STATE_LEDGER").path("payload").path("state_ledger").path("intent_completion_contract").path("status").asText()).isEqualTo("PASS");
        assertThat(findTrace(detail.path("traces"), "LOCAL_VERIFIER").path("payload").path("local_verifier").path("status").asText()).isEqualTo("PASS");
        assertThat(findTrace(detail.path("traces"), "GLOBAL_VERIFIER").path("payload").path("global_verifier").path("decision").asText()).isEqualTo("READY_TO_WRITE");
        assertThat(findTrace(detail.path("traces"), "RESEARCH_CHECKPOINT").path("payload").path("research_checkpoint_candidate").path("checkpoint_no").asInt()).isEqualTo(1);

        Integer traceCount = jdbcTemplate.queryForObject(
                "select count(*) from research_trace where research_run_id = ?",
                Integer.class,
                researchRunId
        );
        assertThat(traceCount).isNotNull().isGreaterThanOrEqualTo(7);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_branch where research_run_id = ?",
                Integer.class,
                researchRunId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_row where research_run_id = ?",
                Integer.class,
                researchRunId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_cell where research_run_id = ?",
                Integer.class,
                researchRunId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_verifier_decision where research_run_id = ?",
                Integer.class,
                researchRunId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_execution_checkpoint where research_run_id = ?",
                Integer.class,
                researchRunId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from source_evidence where research_run_id = ?",
                Integer.class,
                researchRunId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_cell_evidence where research_run_id = ?",
                Integer.class,
                researchRunId
        )).isEqualTo(1);

        String checkpointObjectKey = jdbcTemplate.queryForObject(
                "select object_key from research_execution_checkpoint where research_run_id = ?",
                String.class,
                researchRunId
        );
        String checkpointSummaryJson = jdbcTemplate.queryForObject(
                "select summary_json from research_execution_checkpoint where research_run_id = ?",
                String.class,
                researchRunId
        );
        assertThat(checkpointObjectKey).isNotBlank();
        assertThat(storage.exists("noteweave-derived", checkpointObjectKey)).isTrue();
        String checkpointJson = new String(storage.read("noteweave-derived", checkpointObjectKey), StandardCharsets.UTF_8);
        assertThat(checkpointJson).contains("\"checkpoint_no\":1");
        assertThat(checkpointJson).contains("\"evidence_id\":\"ev-1\"");
        assertThat(checkpointSummaryJson).contains("\"local_verifier\":{");
        assertThat(checkpointSummaryJson).contains("\"status\":\"PASS\"");
        assertThat(checkpointSummaryJson).contains("\"global_verifier\":{");
        assertThat(checkpointSummaryJson).contains("\"decision\":\"READY_TO_WRITE\"");
        assertThat(checkpointSummaryJson).contains("\"verified_row_count\":1");
        assertThat(checkpointSummaryJson).contains("\"conflicted_row_count\":0");
        assertThat(checkpointSummaryJson).contains("\"counterfactual_summary\":{");
        assertThat(checkpointSummaryJson).contains("\"has_counterfactual_recheck\":false");

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/checkpoints/{checkpointNo}",
                        workspaceId, researchRunId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkpoint_no").value(1))
                .andExpect(jsonPath("$.data.snapshot_type").value("RESEARCH_LOOP_CHECKPOINT"))
                .andExpect(jsonPath("$.data.active_branch_id").value("branch-main"))
                .andExpect(jsonPath("$.data.summary.local_verifier.status").value("PASS"))
                .andExpect(jsonPath("$.data.summary.global_verifier.decision").value("READY_TO_WRITE"))
                .andExpect(jsonPath("$.data.summary.state_ledger.verified_row_count").value(1))
                .andExpect(jsonPath("$.data.summary.state_ledger.conflicted_row_count").value(0))
                .andExpect(jsonPath("$.data.summary.state_ledger.verified_row_samples[0].source_title").value("research-input.md"))
                .andExpect(jsonPath("$.data.summary.state_ledger.evidence_card_samples[0].source_title").value("research-input.md"))
                .andExpect(jsonPath("$.data.summary.verifier_gated_summary.blocked_row_count").value(0))
                .andExpect(jsonPath("$.data.summary.verifier_gated_summary.need_more_evidence_row_samples.length()").value(0))
                .andExpect(jsonPath("$.data.summary.counterfactual_summary.has_counterfactual_recheck").value(false))
                .andExpect(jsonPath("$.data.counterfactual_summary.has_counterfactual_recheck").value(false))
                .andExpect(jsonPath("$.data.counterfactual_summary.counterfactual_branch_count").value(0))
                .andExpect(jsonPath("$.data.harness_control_state.control_loop.loop_round_count").value(1))
                .andExpect(jsonPath("$.data.audit_summaries.checkpoint_summary.final_loop_decision").value("SYNTHESIZE_REPORT"))
                .andExpect(jsonPath("$.data.toolbox_summary.search_summary.selected_query_count").value(1))
                .andExpect(jsonPath("$.data.research_process_summary.search_read_timeline.loop_round_count").value(1))
                .andExpect(jsonPath("$.data.research_process_summary.search_read_timeline.total_read_window_count").value(1))
                .andExpect(jsonPath("$.data.research_process_summary.source_evidence_summary.primary_quality").value("WORKSPACE_GROUNDED"))
                .andExpect(jsonPath("$.data.research_process_summary.audit_summary.global_verifier_decision").value("READY_TO_WRITE"))
                .andExpect(jsonPath("$.data.research_process_summary.audit_summary.checkpoint_count").value(1))
                .andExpect(jsonPath("$.data.payload.state_ledger.active_branch_id").value("branch-main"))
                .andExpect(jsonPath("$.data.payload.loop_rounds[0].source_samples[0].source_title").value("research-input.md"))
                .andExpect(jsonPath("$.data.payload.read_windows[0].snapshot_key").value("snapshot-rw-1"))
                .andExpect(jsonPath("$.data.payload.evidence_cards[0].evidence_id").value("ev-1"))
                .andExpect(jsonPath("$.data.payload.global_verifier.decision").value("READY_TO_WRITE"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/checkpoints",
                        workspaceId, researchRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].checkpoint_no").value(1))
                .andExpect(jsonPath("$.data[0].snapshot_type").value("RESEARCH_LOOP_CHECKPOINT"))
                .andExpect(jsonPath("$.data[0].active_branch_id").value("branch-main"))
                .andExpect(jsonPath("$.data[0].final_loop_decision").value("SYNTHESIZE_REPORT"))
                .andExpect(jsonPath("$.data[0].local_verifier_status").value("PASS"))
                .andExpect(jsonPath("$.data[0].global_verifier_decision").value("READY_TO_WRITE"))
                .andExpect(jsonPath("$.data[0].verified_row_count").value(1))
                .andExpect(jsonPath("$.data[0].conflicted_row_count").value(0))
                .andExpect(jsonPath("$.data[0].summary.intent_requirement_count").value(5))
                .andExpect(jsonPath("$.data[0].summary.state_ledger.verified_row_count").value(1))
                .andExpect(jsonPath("$.data[0].summary.verifier_gated_summary.blocked_row_count").value(0))
                .andExpect(jsonPath("$.data[0].counterfactual_summary.has_counterfactual_recheck").value(false))
                .andExpect(jsonPath("$.data[0].created_at").exists());

        ResearchRunDetailResponse detailResponse = researchRunService.getRunDetail(workspaceId, researchRunId);
        assertThat(detailResponse.researchArtifactCandidate()).isNotNull();
        assertThat(detailResponse.researchArtifactCandidate().title()).isEqualTo("Alpha Research Report");
        assertThat(detailResponse.researchArtifactCandidate().generatedRefId()).isEqualTo(researchRunId);
        assertThat(detailResponse.researchArtifactCandidate().citationCount()).isEqualTo(1);
        assertThat(detailResponse.reportFile()).isNotNull();
        assertThat(detailResponse.reportFile().objectKey()).isEqualTo(reportObjectKey);
        assertThat(detailResponse.reportFile().contentSize()).isEqualTo(reportMarkdown.getBytes(StandardCharsets.UTF_8).length);
        assertThat(detailResponse.researchArtifact()).isNotNull();
        assertThat(detailResponse.researchArtifact().artifactId()).isEqualTo(researchRunId);
        assertThat(detailResponse.researchArtifact().reportFile().objectKey()).isEqualTo(reportObjectKey);
        assertThat(detailResponse.researchProcessSummary()).isNotNull();
        assertThat(detailResponse.researchProcessSummary().searchReadTimeline().loopRoundCount()).isEqualTo(1);
        assertThat(detailResponse.researchProcessSummary().searchReadTimeline().allSearchQueries())
                .containsExactly("AlphaResearch summary");
        assertThat(detailResponse.researchProcessSummary().sourceEvidenceSummary().primaryQuality())
                .isEqualTo("WORKSPACE_GROUNDED");
        assertThat(detailResponse.researchProcessSummary().auditSummary().globalVerifierDecision())
                .isEqualTo("READY_TO_WRITE");
        @SuppressWarnings("unchecked")
        Map<String, Object> detailControlLoop = (Map<String, Object>) detailResponse.harnessControlState().get("control_loop");
        @SuppressWarnings("unchecked")
        Map<String, Object> detailSearchSummary = (Map<String, Object>) detailResponse.toolboxSummary().get("search_summary");
        assertThat(detailResponse.harnessControlState()).containsKey("control_loop");
        assertThat(detailControlLoop)
                .containsEntry("loop_round_count", 1)
                .containsEntry("active_branch_id", "branch-main");
        assertThat(detailResponse.auditSummaries()).containsKey("checkpoint_summary");
        assertThat(detailSearchSummary)
                .containsEntry("selected_query_count", 1);
        assertThat(detailResponse.closedLoopState().stateLedger().verifiedRowCount()).isEqualTo(1);
        assertThat(detailResponse.closedLoopState().stateLedger().intentCompletionContract())
                .containsEntry("status", "PASS");
        assertThat(detailResponse.closedLoopState().stateLedger().rows()).hasSize(1);
        assertThat(detailResponse.closedLoopState().harnessControlState()).containsKey("control_loop");
        assertThat(detailResponse.closedLoopState().auditSummaries()).containsKey("checkpoint_summary");
        assertThat(detailResponse.closedLoopState().toolboxSummary()).containsKey("search_summary");
        assertThat(detailResponse.verifierSummary().verifierGatedSummary().blockedRowCount()).isZero();
        assertThat(detailResponse.verifierSummary().verifierGatedSummary().guardrailedRowCount()).isZero();
        assertThat(detailResponse.verifierSummary().verifierGatedSummary().blockedRowSamples()).isEmpty();
        assertThat(detailResponse.closedLoopState().checkpoints()).hasSize(1);
        assertThat(detailResponse.closedLoopState().checkpoints().get(0).summary().stateLedger().verifiedRowCount())
                .isEqualTo(1);
        assertThat(detailResponse.closedLoopState().checkpoints().get(0).summary().stateLedger().verifiedRowSamples())
                .hasSize(1);
        assertThat(detailResponse.closedLoopState().checkpoints().get(0).summary().verifierGatedSummary().blockedRowCount())
                .isZero();

        ResearchCheckpointResponse checkpointResponse = researchRunService.getCheckpoint(workspaceId, researchRunId, 1);
        assertThat(checkpointResponse.summary().stateLedger().verifiedRowCount()).isEqualTo(1);
        assertThat(checkpointResponse.summary().stateLedger().evidenceCardSamples()).hasSize(1);
        assertThat(checkpointResponse.summary().verifierGatedSummary().needMoreEvidenceRowSamples()).isEmpty();
        assertThat(checkpointResponse.researchProcessSummary()).isNotNull();
        assertThat(checkpointResponse.researchProcessSummary().searchReadTimeline().loopRoundCount()).isEqualTo(1);
        assertThat(checkpointResponse.researchProcessSummary().auditSummary().globalVerifierDecision())
                .isEqualTo("READY_TO_WRITE");
        assertThat(checkpointResponse.harnessControlState()).containsKey("control_loop");
        assertThat(checkpointResponse.auditSummaries()).containsKey("checkpoint_summary");
        assertThat(checkpointResponse.toolboxSummary()).containsKey("search_summary");

        List<ResearchCheckpointSummaryResponse> checkpointSummaries = researchRunService.listCheckpoints(workspaceId, researchRunId);
        assertThat(checkpointSummaries).hasSize(1);
        assertThat(checkpointSummaries.get(0).summary().stateLedger().verifiedRowCount()).isEqualTo(1);
        assertThat(checkpointSummaries.get(0).summary().stateLedger().intentCompletionContract())
                .containsEntry("status", "PASS");
        assertThat(checkpointSummaries.get(0).summary().verifierGatedSummary().blockedRowCount()).isZero();

        assertThat(researchRunService.listRuns(workspaceId)).hasSize(1);
        assertThat(researchRunService.listRuns(workspaceId).get(0).researchArtifactCandidate()).isNotNull();
        assertThat(researchRunService.listRuns(workspaceId).get(0).researchArtifactCandidate().title())
                .isEqualTo("Alpha Research Report");
        assertThat(researchRunService.listRuns(workspaceId).get(0).researchArtifactCandidate().generatedRefId())
                .isEqualTo(researchRunId);
        assertThat(researchRunService.listRuns(workspaceId).get(0).reportFile()).isNotNull();
        assertThat(researchRunService.listRuns(workspaceId).get(0).reportFile().objectKey()).isEqualTo(reportObjectKey);
        assertThat(researchRunService.listRuns(workspaceId).get(0).researchArtifact()).isNotNull();
        assertThat(researchRunService.listRuns(workspaceId).get(0).researchArtifact().reportFile().objectKey())
                .isEqualTo(reportObjectKey);
        assertThat(researchRunService.listRuns(workspaceId).get(0).harnessControlState()).containsKey("control_loop");
        assertThat(researchRunService.listRuns(workspaceId).get(0).auditSummaries()).containsKey("checkpoint_summary");
        assertThat(researchRunService.listRuns(workspaceId).get(0).toolboxSummary()).containsKey("search_summary");
        assertThat(researchRunService.listRuns(workspaceId).get(0).researchProcessSummary()).isNotNull();
        assertThat(researchRunService.listRuns(workspaceId).get(0).researchProcessSummary().auditSummary().checkpointCount())
                .isEqualTo(1);
    }

    @Test
    @Disabled("Removed sequential Research Worker callback contract; incremental entry is covered separately")
    void researchRunShouldNotInheritWorkspaceSourcesWhenScopeIsNotExplicitlySelected() throws Exception {
        String workspaceId = createWorkspace();
        uploadSource(workspaceId, "workspace-only-input.md", """
                This source exists in the workspace before the run starts.
                It must not be auto-inherited by the Deep Research input contract.
                """);

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "question", "Should unselected workspace sources be inherited?",
                                "profile", "default"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn();

        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(get("/internal/worker/research-tasks/{taskId}/input", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source_scope.length()").value(0));
    }

    @Test
    @Disabled("Legacy callback-based resume contract is superseded by durable coordinator recovery")
    void researchRunShouldCreateResumeRunFromCheckpointAndExposeResumePayloadToWorker() throws Exception {
        String workspaceId = createWorkspace();
        String scopedSourceId = uploadSource(workspaceId, "resume-input.md", """
                Resume source for checkpoint recovery verification.
                It proves a resumed research run can continue from a persisted checkpoint.
                """);

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "question", "How should checkpoint recovery continue?",
                                "profile", "default",
                                "research_goal", "Continue the interrupted checkpoint recovery path.",
                                "deliverable_format", "Recovery continuation brief",
                                "constraints", java.util.List.of("Preserve the existing checkpoint lineage."),
                                "time_range", "Current recovery attempt",
                                "depth", "STANDARD",
                                "source_scope_source_ids", java.util.List.of(scopedSourceId)
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String researchRunId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("research_run_id").asText();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        Map<String, Object> resumeSeedResultPayload = Map.ofEntries(
                Map.entry("report_markdown", "# Resume Seed Report\n\nCheckpoint persisted."),
                Map.entry("read_windows", java.util.List.of(Map.ofEntries(
                        Map.entry("window_id", "rw-resume-1"),
                        Map.entry("hit_id", "hit-resume-1"),
                        Map.entry("source_id", scopedSourceId),
                        Map.entry("query", "checkpoint recovery"),
                        Map.entry("read_focus", "resume continuity"),
                        Map.entry("window_text", "Checkpoint persisted."),
                        Map.entry("retention_reason", "RECOVERY_ANCHOR"),
                        Map.entry("token_estimate", 64),
                        Map.entry("url", "https://example.com/resume"),
                        Map.entry("provider", "workspace"),
                        Map.entry("adapter", "workspace"),
                        Map.entry("snapshot_status", "FALLBACK"),
                        Map.entry("snapshot_key", "snapshot-resume-1")
                ))),
                Map.entry("evidence_cards", java.util.List.of(Map.ofEntries(
                        Map.entry("evidence_id", "ev-resume-1"),
                        Map.entry("window_id", "rw-resume-1"),
                        Map.entry("source_id", scopedSourceId),
                        Map.entry("claim_text", "Recovery should continue from persisted checkpoint."),
                        Map.entry("quote_text", "Checkpoint persisted."),
                        Map.entry("relation_type", "SUPPORTS"),
                        Map.entry("support_score", 0.91),
                        Map.entry("conflict_score", 0.03)
                ))),
                Map.entry("state_ledger", Map.ofEntries(
                        Map.entry("active_branch_id", "branch-main"),
                        Map.entry("rows", java.util.List.of(Map.ofEntries(
                                Map.entry("row_id", "row-resume-1"),
                                Map.entry("source_id", scopedSourceId),
                                Map.entry("search_query", "checkpoint recovery"),
                                Map.entry("read_focus", "resume continuity"),
                                Map.entry("evidence_id", "ev-resume-1"),
                                Map.entry("row_status", "VERIFIED"),
                                Map.entry("relation_type", "SUPPORTS"),
                                Map.entry("support_score", 0.91),
                                Map.entry("conflict_score", 0.03),
                                Map.entry("support_level", "STRONG"),
                                Map.entry("verification_status", "PASS"),
                                Map.entry("verifier_note", "Checkpoint is valid for resume."),
                                Map.entry("branch_id", "branch-main")
                        ))),
                        Map.entry("cells", java.util.List.of(Map.ofEntries(
                                Map.entry("cell_id", "row-resume-1:claim_text"),
                                Map.entry("row_id", "row-resume-1"),
                                Map.entry("column_key", "claim_text"),
                                Map.entry("candidate_value", "Checkpoint recovery"),
                                Map.entry("status", "VERIFIED"),
                                Map.entry("confidence", 0.91),
                                Map.entry("evidence_refs", java.util.List.of("ev-resume-1")),
                                Map.entry("branch_id", "branch-main"),
                                Map.entry("last_verifier_decision", "PASS"),
                                Map.entry("repair_count", 0)
                        ))),
                        Map.entry("branches", java.util.List.of(Map.of(
                                "branch_id", "branch-main",
                                "status", "MAINLINE"
                        ))),
                        Map.entry("verifier_decisions", java.util.List.of(Map.ofEntries(
                                Map.entry("decision_scope", "GLOBAL"),
                                Map.entry("decision_type", "READY_TO_WRITE"),
                                Map.entry("reason_code", "CHECKPOINT_VALID")
                        ))),
                        Map.entry("intent_completion_contract", Map.of(
                                "status", "PASS",
                                "reason_code", "INTENT_REQUIREMENTS_COMPLETE",
                                "total_requirement_count", 3,
                                "satisfied_requirement_count", 3,
                                "pending_requirement_count", 0,
                                "missing_requirement_labels", java.util.List.of(),
                                "requirements", java.util.List.of(Map.of(
                                        "requirement_id", "goal",
                                        "requirement_type", "GOAL",
                                        "label", "Research goal: Continue the interrupted checkpoint recovery path.",
                                        "status", "PASS",
                                        "coverage_note", "research goal is anchored to the current ledger state"
                                ))
                        ))
                )),
                Map.entry("local_verifier", Map.of(
                        "status", "PASS",
                        "research_intent_alignment", Map.of(
                                "status", "PASS",
                                "reason_code", "INTENT_ALIGNED",
                                "goal_status", "PASS",
                                "deliverable_status", "PASS",
                                "time_range_status", "PASS",
                                "depth_status", "PASS",
                                "satisfied_constraint_count", 0,
                                "total_constraint_count", 0,
                                "covered_requirements", java.util.List.of("research goal is anchored to the current ledger state"),
                                "missing_requirements", java.util.List.of()
                        ),
                        "decision_records", java.util.List.of(Map.ofEntries(
                                Map.entry("decision_scope", "LOCAL"),
                                Map.entry("decision_type", "PASS"),
                                Map.entry("reason_code", "CHECKPOINT_VALID")
                        ))
                )),
                Map.entry("global_verifier", Map.of(
                        "status", "PASS",
                        "decision", "READY_TO_WRITE",
                        "research_intent_alignment", Map.of(
                                "status", "PASS",
                                "reason_code", "INTENT_ALIGNED",
                                "goal_status", "PASS",
                                "deliverable_status", "PASS",
                                "time_range_status", "PASS",
                                "depth_status", "PASS",
                                "satisfied_constraint_count", 0,
                                "total_constraint_count", 0,
                                "covered_requirements", java.util.List.of("research goal is anchored to the current ledger state"),
                                "missing_requirements", java.util.List.of()
                        ),
                        "decision_records", java.util.List.of(Map.ofEntries(
                                Map.entry("decision_scope", "GLOBAL"),
                                Map.entry("decision_type", "READY_TO_WRITE"),
                                Map.entry("reason_code", "CHECKPOINT_VALID")
                        ))
                )),
                Map.entry("intent_completion_contract", Map.of(
                        "status", "PASS",
                        "reason_code", "INTENT_REQUIREMENTS_COMPLETE",
                        "total_requirement_count", 3,
                        "satisfied_requirement_count", 3,
                        "pending_requirement_count", 0,
                        "missing_requirement_labels", java.util.List.of(),
                        "requirements", java.util.List.of(Map.of(
                                "requirement_id", "goal",
                                "requirement_type", "GOAL",
                                "label", "Research goal: Continue the interrupted checkpoint recovery path.",
                                "status", "PASS",
                                "coverage_note", "research goal is anchored to the current ledger state"
                        ))
                )),
                Map.entry("research_intent_alignment", Map.of(
                        "status", "PASS",
                        "reason_code", "INTENT_ALIGNED",
                        "goal_status", "PASS",
                        "deliverable_status", "PASS",
                        "time_range_status", "PASS",
                        "depth_status", "PASS",
                        "satisfied_constraint_count", 0,
                        "total_constraint_count", 0,
                        "covered_requirements", java.util.List.of("research goal is anchored to the current ledger state"),
                        "missing_requirements", java.util.List.of()
                )),
                Map.entry("loop_rounds", java.util.List.of(Map.of(
                        "round_no", 1,
                        "search_hit_count", 1,
                        "read_window_count", 1,
                        "evidence_card_count", 1,
                        "search_queries", java.util.List.of("checkpoint recovery"),
                        "evidence_ids", java.util.List.of("ev-resume-1"),
                        "branch_decision", "NO_BRANCH",
                        "global_decision", "READY_TO_WRITE"
                ))),
                Map.entry("loop_decision", Map.of(
                        "decision", "SYNTHESIZE_REPORT",
                        "reason", "CHECKPOINT_VALID",
                        "round_no", 1
                )),
                Map.entry("branch_decisions", java.util.List.of(Map.of(
                        "decision", "NO_BRANCH",
                        "branch_reason", "CHECKPOINT_VALID"
                ))),
                Map.entry("research_checkpoint_candidate", Map.of(
                        "checkpoint_no", 1,
                        "snapshot_type", "RESEARCH_LOOP_CHECKPOINT"
                ))
        );
        Map<String, Object> resumeSeedCompleteRequest = Map.of(
                "result_type", "RESEARCH_REPORT",
                "result_title", "Resume Seed Report",
                "result_payload", resumeSeedResultPayload,
                "trace_summary", "resume seed checkpoint persisted",
                "citations", java.util.List.of(Map.of("title", "resume-input.md"))
        );

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resumeSeedCompleteRequest)))
                .andExpect(status().isOk());

        MvcResult savedResumeSeedResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/save-report-as-source",
                        workspaceId, researchRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generated_ref_id").value(researchRunId))
                .andReturn();

        String resumeSeedSourceId = objectMapper.readTree(savedResumeSeedResult.getResponse().getContentAsString())
                .path("data").path("source_id").asText();
        String resumeSeedReportObjectKey = "workspace/%s/research/%s/report/final.md".formatted(workspaceId, researchRunId);

        MvcResult resumeResult = mockMvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/resume-from-checkpoint/{checkpointNo}",
                        workspaceId, researchRunId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn();

        String resumedResearchRunId = objectMapper.readTree(resumeResult.getResponse().getContentAsString())
                .path("data").path("research_run_id").asText();
        String resumedTaskId = objectMapper.readTree(resumeResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(get("/internal/worker/research-tasks/{taskId}/input", resumedTaskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.target_id").value(resumedResearchRunId))
                .andExpect(jsonPath("$.data.input_payload.research_intent.research_goal").value("Continue the interrupted checkpoint recovery path."))
                .andExpect(jsonPath("$.data.input_payload.resume_checkpoint.source_research_run_id").value(researchRunId))
                .andExpect(jsonPath("$.data.input_payload.resume_checkpoint.checkpoint_no").value(1))
                .andExpect(jsonPath("$.data.input_payload.resume_checkpoint.active_branch_id").value("branch-main"))
                .andExpect(jsonPath("$.data.input_payload.resume_checkpoint.payload.state_ledger.active_branch_id").value("branch-main"))
                .andExpect(jsonPath("$.data.input_payload.resume_checkpoint.payload.loop_rounds[0].source_samples[0].source_title").value("resume-input.md"))
                .andExpect(jsonPath("$.data.input_payload.resume_checkpoint.payload.state_ledger.rows[0].source_title").value("resume-input.md"))
                .andExpect(jsonPath("$.data.input_payload.resume_checkpoint.payload.evidence_cards[0].source_title").value("resume-input.md"))
                .andExpect(jsonPath("$.data.input_payload.resume_checkpoint.payload.read_windows[0].source_title").value("resume-input.md"))
                .andExpect(jsonPath("$.data.input_payload.resume_checkpoint.payload.evidence_cards[0].evidence_id").value("ev-resume-1"))
                .andExpect(jsonPath("$.data.input_payload.resume_checkpoint.payload.loop_decision.decision").value("SYNTHESIZE_REPORT"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}", workspaceId, resumedResearchRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumed_from_research_run_id").value(researchRunId))
                .andExpect(jsonPath("$.data.resumed_from_checkpoint_no").value(1))
                .andExpect(jsonPath("$.data.resume_checkpoint.source_research_run_id").value(researchRunId))
                .andExpect(jsonPath("$.data.resume_checkpoint.checkpoint_no").value(1))
                .andExpect(jsonPath("$.data.resume_checkpoint.snapshot_type").value("RESEARCH_LOOP_CHECKPOINT"))
                .andExpect(jsonPath("$.data.resume_checkpoint.active_branch_id").value("branch-main"))
                .andExpect(jsonPath("$.data.resume_checkpoint.report_file.object_key").value(resumeSeedReportObjectKey))
                .andExpect(jsonPath("$.data.resume_checkpoint.saved_report_source.source_id").value(resumeSeedSourceId))
                .andExpect(jsonPath("$.data.resume_checkpoint.saved_report_source.generated_ref_id").value(researchRunId))
                .andExpect(jsonPath("$.data.resume_checkpoint.research_artifact.artifact_id").value(researchRunId))
                .andExpect(jsonPath("$.data.resume_checkpoint.research_artifact.saved_report_source.source_id").value(resumeSeedSourceId))
                .andExpect(jsonPath("$.data.resume_checkpoint.summary.state_ledger.verified_row_count").value(1))
                .andExpect(jsonPath("$.data.research_intent.research_goal").value("Continue the interrupted checkpoint recovery path."))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.counterfactual_summary.has_counterfactual_recheck").value(false))
                .andExpect(jsonPath("$.data.counterfactual_summary.counterfactual_branch_count").value(0));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].research_run_id").value(resumedResearchRunId))
                .andExpect(jsonPath("$.data[0].resumed_from_research_run_id").value(researchRunId))
                .andExpect(jsonPath("$.data[0].resumed_from_checkpoint_no").value(1))
                .andExpect(jsonPath("$.data[0].resume_checkpoint.source_research_run_id").value(researchRunId))
                .andExpect(jsonPath("$.data[0].resume_checkpoint.checkpoint_no").value(1))
                .andExpect(jsonPath("$.data[0].resume_checkpoint.report_file.object_key").value(resumeSeedReportObjectKey))
                .andExpect(jsonPath("$.data[0].resume_checkpoint.saved_report_source.source_id").value(resumeSeedSourceId))
                .andExpect(jsonPath("$.data[0].resume_checkpoint.research_artifact.artifact_id").value(researchRunId))
                .andExpect(jsonPath("$.data[0].resume_checkpoint.summary.state_ledger.verified_row_count").value(1))
                .andExpect(jsonPath("$.data[0].local_verifier_reason").value(""))
                .andExpect(jsonPath("$.data[0].ledger_row_count").value(0))
                .andExpect(jsonPath("$.data[0].global_verifier_reason").value(""))
                .andExpect(jsonPath("$.data[0].final_loop_reason").value(""))
                .andExpect(jsonPath("$.data[0].recovery_mode").value(""))
                .andExpect(jsonPath("$.data[0].counterfactual_summary.has_counterfactual_recheck").value(false))
                .andExpect(jsonPath("$.data[1].research_run_id").value(researchRunId))
                .andExpect(jsonPath("$.data[1].local_verifier_status").value("PASS"))
                .andExpect(jsonPath("$.data[1].global_verifier_reason").value("CHECKPOINT_VALID"))
                .andExpect(jsonPath("$.data[1].research_intent_alignment_status").value("PASS"))
                .andExpect(jsonPath("$.data[1].research_intent_alignment_reason").value("INTENT_ALIGNED"))
                .andExpect(jsonPath("$.data[1].intent_satisfied_constraint_count").value(0))
                .andExpect(jsonPath("$.data[1].intent_constraint_count").value(0))
                .andExpect(jsonPath("$.data[1].verified_row_count").value(1))
                .andExpect(jsonPath("$.data[1].checkpoint_count").value(1));

        Map<String, Object> resumedResultPayload = new LinkedHashMap<>(resumeSeedResultPayload);
        resumedResultPayload.put("report_markdown", "# Resumed Research Report\n\nRecovery completed.");
        resumedResultPayload.put("resume_context_summary", Map.ofEntries(
                Map.entry("source_research_run_id", researchRunId),
                Map.entry("checkpoint_no", 1),
                Map.entry("snapshot_type", "RESEARCH_LOOP_CHECKPOINT"),
                Map.entry("active_branch_id", "branch-main"),
                Map.entry("final_loop_decision", "SYNTHESIZE_REPORT"),
                Map.entry("restored_loop_round_count", 1)
        ));
        resumedResultPayload.put("research_artifact_candidate", Map.ofEntries(
                Map.entry("artifact_type", "DEEP_RESEARCH_REPORT"),
                Map.entry("artifact_version", "v1"),
                Map.entry("title", "Resumed Research Report"),
                Map.entry("generated_ref_type", "research_run"),
                Map.entry("generated_ref_id", resumedResearchRunId),
                Map.entry("source_basis", "Checkpoint-grounded"),
                Map.entry("content_markdown", "# Resumed Research Report\n\nRecovery completed."),
                Map.entry("resume_context_summary", Map.of(
                        "source_research_run_id", researchRunId,
                        "checkpoint_no", 1
                )),
                Map.entry("citation_count", 1)
        ));

        Map<String, Object> resumedCompleteRequest = Map.of(
                "result_type", "RESEARCH_REPORT",
                "result_title", "Resumed Research Report",
                "result_payload", resumedResultPayload,
                "trace_summary", "resume checkpoint completed",
                "citations", java.util.List.of(Map.of("title", "resume-input.md"))
        );

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", resumedTaskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resumedCompleteRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.result_ref").value(resumedResearchRunId));

        MvcResult resumedDetailResult = mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}", workspaceId, resumedResearchRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.resumed_from_research_run_id").value(researchRunId))
                .andExpect(jsonPath("$.data.resumed_from_checkpoint_no").value(1))
                .andExpect(jsonPath("$.data.resume_checkpoint.source_research_run_id").value(researchRunId))
                .andExpect(jsonPath("$.data.resume_checkpoint.checkpoint_no").value(1))
                .andExpect(jsonPath("$.data.resume_checkpoint.summary.state_ledger.verified_row_count").value(1))
                .andExpect(jsonPath("$.data.research_artifact_candidate.title").value("Resumed Research Report"))
                .andExpect(jsonPath("$.data.research_artifact_candidate.generated_ref_id").value(resumedResearchRunId))
                .andExpect(jsonPath("$.data.resume_context_summary.source_research_run_id").value(researchRunId))
                .andExpect(jsonPath("$.data.resume_context_summary.checkpoint_no").value(1))
                .andExpect(jsonPath("$.data.resume_context_summary.restored_loop_round_count").value(1))
                .andExpect(jsonPath("$.data.verifier_summary.local_verifier_status").value("PASS"))
                .andExpect(jsonPath("$.data.verifier_summary.local_verifier_reason").value("CHECKPOINT_VALID"))
                .andExpect(jsonPath("$.data.verifier_summary.global_verifier_decision").value("READY_TO_WRITE"))
                .andExpect(jsonPath("$.data.verifier_summary.global_verifier_reason").value("CHECKPOINT_VALID"))
                .andExpect(jsonPath("$.data.verifier_summary.final_loop_reason").value("CHECKPOINT_VALID"))
                .andExpect(jsonPath("$.data.verifier_summary.recovery_targets").isMap())
                .andReturn();

        JsonNode resumedDetail = objectMapper.readTree(resumedDetailResult.getResponse().getContentAsString()).path("data");
        JsonNode resumedFinalTrace = findTrace(resumedDetail.path("traces"), "FINAL_REPORT");
        assertThat(resumedFinalTrace.path("payload").path("result_payload").path("research_artifact_candidate").path("generated_ref_id").asText())
                .isEqualTo(resumedResearchRunId);
        assertThat(resumedFinalTrace.path("payload").path("result_payload").path("resume_context_summary").path("source_research_run_id").asText())
                .isEqualTo(researchRunId);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/checkpoints",
                        workspaceId, resumedResearchRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].checkpoint_no").value(1))
                .andExpect(jsonPath("$.data[0].active_branch_id").value("branch-main"))
                .andExpect(jsonPath("$.data[0].final_loop_decision").value("SYNTHESIZE_REPORT"))
                .andExpect(jsonPath("$.data[0].summary.intent_requirement_count").value(3))
                .andExpect(jsonPath("$.data[0].summary.state_ledger.verified_row_count").value(1))
                .andExpect(jsonPath("$.data[0].counterfactual_summary.has_counterfactual_recheck").value(false));

        assertThat(researchRunService.getRunDetail(workspaceId, resumedResearchRunId).resumeCheckpoint()).isNotNull();
        assertThat(researchRunService.getRunDetail(workspaceId, resumedResearchRunId).resumeCheckpoint().sourceResearchRunId())
                .isEqualTo(researchRunId);
        assertThat(researchRunService.getRunDetail(workspaceId, resumedResearchRunId).resumeCheckpoint().reportFile()).isNotNull();
        assertThat(researchRunService.getRunDetail(workspaceId, resumedResearchRunId).resumeCheckpoint().reportFile().objectKey())
                .isEqualTo(resumeSeedReportObjectKey);
        assertThat(researchRunService.getRunDetail(workspaceId, resumedResearchRunId).resumeCheckpoint().savedReportSource()).isNotNull();
        assertThat(researchRunService.getRunDetail(workspaceId, resumedResearchRunId).resumeCheckpoint().savedReportSource().sourceId())
                .isEqualTo(resumeSeedSourceId);
        assertThat(researchRunService.getRunDetail(workspaceId, resumedResearchRunId).resumeCheckpoint().researchArtifact()).isNotNull();
        assertThat(researchRunService.getRunDetail(workspaceId, resumedResearchRunId).resumeCheckpoint().researchArtifact().artifactId())
                .isEqualTo(researchRunId);
        assertThat(researchRunService.getRunDetail(workspaceId, resumedResearchRunId).resumeContextSummary()).isNotNull();
        assertThat(researchRunService.getRunDetail(workspaceId, resumedResearchRunId).resumeContextSummary().sourceResearchRunId())
                .isEqualTo(researchRunId);
        assertThat(researchRunService.getRunDetail(workspaceId, resumedResearchRunId).resumeContextSummary().restoredLoopRoundCount())
                .isEqualTo(1);
        assertThat(researchRunService.getRunDetail(workspaceId, resumedResearchRunId).researchArtifactCandidate()).isNotNull();
        assertThat(researchRunService.getRunDetail(workspaceId, resumedResearchRunId).researchArtifactCandidate().resumeContextSummary()).isNotNull();
        assertThat(researchRunService.getRunDetail(workspaceId, resumedResearchRunId).researchArtifactCandidate().resumeContextSummary().checkpointNo())
                .isEqualTo(1);
        assertThat(researchRunService.listRuns(workspaceId).get(0).resumeCheckpoint()).isNotNull();
        assertThat(researchRunService.listRuns(workspaceId).get(0).resumeCheckpoint().summary().stateLedger().verifiedRowCount())
                .isEqualTo(1);
        assertThat(researchRunService.listRuns(workspaceId).get(0).resumeCheckpoint().savedReportSource()).isNotNull();
        assertThat(researchRunService.listRuns(workspaceId).get(0).resumeCheckpoint().savedReportSource().sourceId())
                .isEqualTo(resumeSeedSourceId);
        assertThat(researchRunService.listRuns(workspaceId).get(0).researchArtifactCandidate()).isNotNull();
        assertThat(researchRunService.listRuns(workspaceId).get(0).researchArtifactCandidate().generatedRefId())
                .isEqualTo(resumedResearchRunId);

        Map<String, Object> resumedRow = jdbcTemplate.queryForMap("""
                select resumed_from_research_run_id, resumed_from_checkpoint_no
                from research_run
                where id = ?
                """, resumedResearchRunId);
        assertThat(resumedRow.get("resumed_from_research_run_id")).isEqualTo(researchRunId);
        assertThat(((Number) resumedRow.get("resumed_from_checkpoint_no")).intValue()).isEqualTo(1);
    }

    @Test
    @Disabled("Legacy result projection is superseded by atomic quorum and verifier-decision repair tests")
    void conflictingResearchStateShouldPersistCounterfactualBranchAndVerifierDecision() throws Exception {
        String workspaceId = createWorkspace();
        String scopedSourceId = uploadSource(workspaceId, "conflict-input.md", """
                Conflict source for branch persistence verification.
                It simulates a claim that requires counterfactual recheck.
                """);

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "question", "How should conflicting evidence be handled?",
                                "profile", "default",
                                "source_scope_source_ids", java.util.List.of(scopedSourceId)
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String researchRunId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("research_run_id").asText();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        Map<String, Object> conflictPayload = Map.ofEntries(
                Map.entry("report_markdown", "# Conflict Report\n\nConflict kept visible."),
                Map.entry("state_ledger", Map.of(
                        "active_branch_id", "branch-recovery-1",
                        "rows", java.util.List.of(Map.ofEntries(
                                Map.entry("row_id", "row-conflict-1"),
                                Map.entry("source_id", "src-conflict"),
                                Map.entry("source_title", "Conflict Source"),
                                Map.entry("evidence_id", "ev-conflict-1"),
                                Map.entry("row_status", "CONFLICTED"),
                                Map.entry("relation_type", "CONFLICTS"),
                                Map.entry("support_score", 0.58),
                                Map.entry("conflict_score", 0.55),
                                Map.entry("support_level", "CONFLICTING"),
                                Map.entry("verification_status", "COUNTERFACTUAL_REQUIRED"),
                                Map.entry("verifier_note", "Needs counterfactual branch."),
                                Map.entry("repair_hint", "Run counterfactual branch before synthesis."),
                                Map.entry("branch_id", "branch-recovery-1")
                        )),
                        "cells", java.util.List.of(Map.of(
                                "cell_id", "row-conflict-1:claim_text",
                                "row_id", "row-conflict-1",
                                "column_key", "claim_text",
                                "candidate_value", "Conflict claim",
                                "status", "CONFLICTED",
                                "confidence", 0.58,
                                "evidence_refs", java.util.List.of("ev-conflict-1"),
                                "branch_id", "branch-recovery-1",
                                "last_verifier_decision", "COUNTERFACTUAL_RECHECK",
                                "repair_count", 1
                        )),
                        "branches", java.util.List.of(
                                Map.of(
                                        "branch_id", "branch-main",
                                        "parent_branch_id", "",
                                        "branch_reason", "MAINLINE",
                                        "status", "MAINLINE",
                                        "hypothesis_summary", "Primary branch",
                                        "created_round", 1
                                ),
                                Map.of(
                                        "branch_id", "branch-recovery-1",
                                        "parent_branch_id", "branch-main",
                                        "branch_reason", "CONFLICTING_EVIDENCE",
                                        "status", "ACTIVE_BRANCH",
                                        "hypothesis_summary", "Counterfactual recheck branch",
                                        "target_evidence_ids", java.util.List.of("ev-conflict-1"),
                                        "created_round", 1
                                )
                        ),
                        "verifier_decisions", java.util.List.of(Map.of(
                                "decision_scope", "BRANCH",
                                "decision_type", "COUNTERFACTUAL_RECHECK",
                                "reason_code", "CONFLICTING_EVIDENCE",
                                "target_id", "branch-recovery-1",
                                "evidence_ids", java.util.List.of("ev-conflict-1"),
                                "action", "open an alternative source window for the conflicting claim",
                                "status", "WARN",
                                "notes", java.util.List.of("Mainline evidence is contested.")
                        ))
                )),
                Map.entry("local_verifier", Map.of(
                        "status", "WARN",
                        "decision_records", java.util.List.of(Map.of(
                                "decision_scope", "LOCAL",
                                "decision_type", "BRANCH",
                                "reason_code", "CONFLICTING_EVIDENCE"
                        ))
                )),
                Map.entry("global_verifier", Map.of(
                        "status", "WARN",
                        "decision", "WRITE_WITH_GUARDRAILS",
                        "decision_records", java.util.List.of(Map.of(
                                "decision_scope", "GLOBAL",
                                "decision_type", "WRITE_WITH_GUARDRAILS",
                                "reason_code", "COUNTERFACTUAL_OR_LOW_CONFIDENCE"
                        ))
                )),
                Map.entry("report_structure", Map.of(
                        "counterfactual_summary", Map.ofEntries(
                                Map.entry("has_counterfactual_recheck", true),
                                Map.entry("counterfactual_branch_count", 1),
                                Map.entry("conflicted_row_count", 1),
                                Map.entry("local_verifier_status", "WARN"),
                                Map.entry("global_verifier_decision", "WRITE_WITH_GUARDRAILS"),
                                Map.entry("recovery_mode", "COUNTERFACTUAL_RECHECK"),
                                Map.entry("counterfactual_branch_ids", java.util.List.of("branch-recovery-1")),
                                Map.entry("active_counterfactual_branch_ids", java.util.List.of("branch-recovery-1")),
                                Map.entry("branch_reasons", java.util.List.of("CONFLICTING_EVIDENCE")),
                                Map.entry("target_evidence_ids", java.util.List.of("ev-conflict-1")),
                                Map.entry("branches", java.util.List.of(Map.of(
                                        "branch_id", "branch-recovery-1",
                                        "parent_branch_id", "branch-main",
                                        "branch_reason", "CONFLICTING_EVIDENCE",
                                        "branch_status", "ACTIVE_BRANCH",
                                        "decision", "COUNTERFACTUAL_RECHECK",
                                        "verifier_scope", "VERIFY",
                                        "hypothesis_summary", "Counterfactual recheck branch",
                                        "target_evidence_ids", java.util.List.of("ev-conflict-1")
                                )))
                        ),
                        "conflict_and_counterfactual_review", Map.of(
                                "local_verifier_status", "WARN",
                                "global_verifier_decision", "WRITE_WITH_GUARDRAILS",
                                "recovery_mode", "COUNTERFACTUAL_RECHECK",
                                "conflicted_rows", java.util.List.of(Map.of(
                                        "row_id", "row-conflict-1",
                                        "source_title", "Conflict Source",
                                        "evidence_id", "ev-conflict-1",
                                        "row_status", "CONFLICTED"
                                )),
                                "branch_decisions", java.util.List.of(Map.of(
                                        "branch_id", "branch-recovery-1",
                                        "decision", "COUNTERFACTUAL_RECHECK",
                                        "branch_reason", "CONFLICTING_EVIDENCE",
                                        "verifier_scope", "VERIFY",
                                        "target_evidence_ids", java.util.List.of("ev-conflict-1")
                                ))
                        ),
                        "recovery_mode", "COUNTERFACTUAL_RECHECK"
                )),
                Map.entry("branch_decisions", java.util.List.of(Map.of(
                        "branch_id", "branch-recovery-1",
                        "decision", "COUNTERFACTUAL_RECHECK",
                        "branch_reason", "CONFLICTING_EVIDENCE",
                        "verifier_scope", "VERIFY",
                        "target_evidence_ids", java.util.List.of("ev-conflict-1"),
                        "branch_status", "ACTIVE_BRANCH"
                ))),
                Map.entry("counterfactual_summary", Map.ofEntries(
                        Map.entry("has_counterfactual_recheck", true),
                        Map.entry("counterfactual_branch_count", 1),
                        Map.entry("conflicted_row_count", 1),
                        Map.entry("local_verifier_status", "WARN"),
                        Map.entry("global_verifier_decision", "WRITE_WITH_GUARDRAILS"),
                        Map.entry("recovery_mode", "COUNTERFACTUAL_RECHECK"),
                        Map.entry("counterfactual_branch_ids", java.util.List.of("branch-recovery-1")),
                        Map.entry("active_counterfactual_branch_ids", java.util.List.of("branch-recovery-1")),
                        Map.entry("branch_reasons", java.util.List.of("CONFLICTING_EVIDENCE")),
                        Map.entry("target_evidence_ids", java.util.List.of("ev-conflict-1")),
                        Map.entry("branches", java.util.List.of(Map.of(
                                "branch_id", "branch-recovery-1",
                                "parent_branch_id", "branch-main",
                                "branch_reason", "CONFLICTING_EVIDENCE",
                                "branch_status", "ACTIVE_BRANCH",
                                "decision", "COUNTERFACTUAL_RECHECK",
                                "verifier_scope", "VERIFY",
                                "hypothesis_summary", "Counterfactual recheck branch",
                                "target_evidence_ids", java.util.List.of("ev-conflict-1")
                        )))
                )),
                Map.entry("loop_rounds", java.util.List.of(Map.of(
                        "round_no", 1,
                        "branch_decision", "COUNTERFACTUAL_RECHECK",
                        "global_decision", "WRITE_WITH_GUARDRAILS"
                ))),
                Map.entry("loop_decision", Map.of(
                        "decision", "WRITE_WITH_GUARDRAILS",
                        "reason", "CONFLICT_RECHECK_BUDGET_EXHAUSTED",
                        "round_no", 2
                )),
                Map.entry("research_checkpoint_candidate", Map.of(
                        "checkpoint_no", 1,
                        "snapshot_type", "RESEARCH_LOOP_CHECKPOINT"
                ))
        );

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "result_type", "RESEARCH_REPORT",
                                "result_title", "Conflict Branch Report",
                                "result_payload", conflictPayload,
                                "trace_summary", "conflict branch persisted",
                                "citations", java.util.List.of(Map.of("title", "conflict-input.md"))
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}", workspaceId, researchRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closed_loop_state.active_branch_id").value("branch-recovery-1"))
                .andExpect(jsonPath("$.data.closed_loop_state.counterfactual_summary.has_counterfactual_recheck").value(true))
                .andExpect(jsonPath("$.data.closed_loop_state.branches.length()").value(2))
                .andExpect(jsonPath("$.data.closed_loop_state.rows[0].row_status").value("CONFLICTED"))
                .andExpect(jsonPath("$.data.closed_loop_state.verifier_decisions[0].decision_type").value("COUNTERFACTUAL_RECHECK"))
                .andExpect(jsonPath("$.data.counterfactual_summary.has_counterfactual_recheck").value(true))
                .andExpect(jsonPath("$.data.counterfactual_summary.counterfactual_branch_count").value(1))
                .andExpect(jsonPath("$.data.counterfactual_summary.conflicted_row_count").value(1))
                .andExpect(jsonPath("$.data.counterfactual_summary.counterfactual_branch_ids[0]").value("branch-recovery-1"))
                .andExpect(jsonPath("$.data.counterfactual_summary.active_counterfactual_branch_ids[0]").value("branch-recovery-1"))
                .andExpect(jsonPath("$.data.counterfactual_summary.branch_reasons[0]").value("CONFLICTING_EVIDENCE"))
                .andExpect(jsonPath("$.data.counterfactual_summary.target_evidence_ids[0]").value("ev-conflict-1"))
                .andExpect(jsonPath("$.data.report_structure.counterfactual_summary.has_counterfactual_recheck").value(true))
                .andExpect(jsonPath("$.data.counterfactual_summary.branches[0].branch_id").value("branch-recovery-1"))
                .andExpect(jsonPath("$.data.counterfactual_summary.branches[0].decision").value("COUNTERFACTUAL_RECHECK"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].research_run_id").value(researchRunId))
                .andExpect(jsonPath("$.data[0].local_verifier_status").value("WARN"))
                .andExpect(jsonPath("$.data[0].local_verifier_reason").value("CONFLICTING_EVIDENCE"))
                .andExpect(jsonPath("$.data[0].ledger_row_count").value(1))
                .andExpect(jsonPath("$.data[0].verified_row_count").value(0))
                .andExpect(jsonPath("$.data[0].conflicted_row_count").value(1))
                .andExpect(jsonPath("$.data[0].global_verifier_reason").value("COUNTERFACTUAL_OR_LOW_CONFIDENCE"))
                .andExpect(jsonPath("$.data[0].final_loop_reason").value("CONFLICT_RECHECK_BUDGET_EXHAUSTED"))
                .andExpect(jsonPath("$.data[0].recovery_mode").value("COUNTERFACTUAL_RECHECK"))
                .andExpect(jsonPath("$.data[0].counterfactual_summary.has_counterfactual_recheck").value(true))
                .andExpect(jsonPath("$.data[0].counterfactual_summary.counterfactual_branch_count").value(1))
                .andExpect(jsonPath("$.data[0].counterfactual_summary.target_evidence_ids[0]").value("ev-conflict-1"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/checkpoints/{checkpointNo}",
                        workspaceId, researchRunId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.local_verifier.status").value("WARN"))
                .andExpect(jsonPath("$.data.summary.global_verifier.decision").value("WRITE_WITH_GUARDRAILS"))
                .andExpect(jsonPath("$.data.summary.state_ledger.conflicted_row_count").value(1))
                .andExpect(jsonPath("$.data.summary.counterfactual_summary.has_counterfactual_recheck").value(true))
                .andExpect(jsonPath("$.data.counterfactual_summary.has_counterfactual_recheck").value(true))
                .andExpect(jsonPath("$.data.counterfactual_summary.counterfactual_branch_count").value(1))
                .andExpect(jsonPath("$.data.counterfactual_summary.target_evidence_ids[0]").value("ev-conflict-1"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_branch where research_run_id = ?",
                Integer.class,
                researchRunId
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_verifier_decision where research_run_id = ?",
                Integer.class,
                researchRunId
        )).isEqualTo(1);
    }

    @Test
    void researchRunShouldRejectUnknownExplicitSourceScopeItems() throws Exception {
        String workspaceId = createWorkspace();

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "question", "Should invalid source scope be rejected?",
                                "profile", "default",
                                "source_scope_source_ids", java.util.List.of("src-missing")
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESEARCH_SOURCE_SCOPE_INVALID"));
    }

    @Test
    @Disabled("Legacy research.run outbox was removed; agent command outbox has dedicated coverage")
    void researchOutboxDispatcherShouldPublishKafkaMessageAndMarkOutboxSent() throws Exception {
        recordingResearchOutboxPublisher.reset();
        jdbcTemplate.update("update task_outbox set status = 'SENT', sent_at = current_timestamp where topic = 'noteweave.research.run'");
        String workspaceId = createWorkspace();
        String scopedSourceId = uploadSource(workspaceId, "dispatch-research-input.md", """
                Dispatch source for the research worker.
                It proves the outbox can publish a Kafka research message.
                """);

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "question", "Can the research worker be dispatched?",
                                "profile", "default",
                                "source_scope_source_ids", java.util.List.of(scopedSourceId)
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();
        String researchRunId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("research_run_id").asText();

        mockMvc.perform(post("/internal/worker/research-outbox/dispatch")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dispatched_count").value(1));

        assertThat(recordingResearchOutboxPublisher.messages()).hasSize(1);
        TestResearchOutboxPublisherConfig.PublishedMessage message = recordingResearchOutboxPublisher.messages().get(0);
        assertThat(message.topic()).isEqualTo("noteweave.research.run");
        assertThat(message.messageKey()).isEqualTo(researchRunId);
        JsonNode payload = objectMapper.readTree(message.payloadJson());
        assertThat(payload.path("task_id").asText()).isEqualTo(taskId);
        assertThat(payload.path("target_id").asText()).isEqualTo(researchRunId);
        String outboxStatus = jdbcTemplate.queryForObject(
                "select status from task_outbox where task_id = ? and topic = 'noteweave.research.run'",
                String.class,
                taskId
        );
        assertThat(outboxStatus).isEqualTo("SENT");
    }

    @Test
    void artifactOutboxDispatcherShouldInvokeArtifactWorkerAndMarkOutboxSent() throws Exception {
        recordingArtifactOutboxPublisher.reset();
        jdbcTemplate.update("update task_outbox set status = 'SENT', sent_at = current_timestamp where topic = 'noteweave.artifact.job'");
        String workspaceId = createWorkspace();
        uploadSource(workspaceId, "dispatch-artifact-input.md", """
                Dispatch source for the artifact worker.
                It proves the outbox can invoke the skill-first artifact runtime.
                """);

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "resume_highlight",
                                "user_requirement", "Can the artifact worker be dispatched through the outbox?",
                                "inputs", Map.of(
                                        "language", "zh-CN"
                                )
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();
        String artifactJobId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("artifact_job_id").asText();

        mockMvc.perform(post("/internal/worker/artifact-outbox/dispatch")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dispatched_count").value(1));

        assertThat(recordingArtifactOutboxPublisher.messages()).hasSize(1);
        TestResearchOutboxPublisherConfig.PublishedMessage message = recordingArtifactOutboxPublisher.messages().get(0);
        assertThat(message.topic()).isEqualTo("noteweave.artifact.job");
        assertThat(message.messageKey()).isEqualTo(artifactJobId);
        JsonNode payload = objectMapper.readTree(message.payloadJson());
        assertThat(payload.path("task_id").asText()).isEqualTo(taskId);
        assertThat(payload.path("target_id").asText()).isEqualTo(artifactJobId);
        String outboxStatus = jdbcTemplate.queryForObject(
                "select status from task_outbox where task_id = ? and topic = 'noteweave.artifact.job'",
                String.class,
                taskId
        );
        assertThat(outboxStatus).isEqualTo("SENT");
    }

    @Test
    void artifactOutboxDispatcherShouldReleaseFailedClaimAndRetryWithBackoff() throws Exception {
        recordingArtifactOutboxPublisher.reset();
        jdbcTemplate.update("update task_outbox set status = 'SENT', sent_at = current_timestamp where topic = 'noteweave.artifact.job'");
        String workspaceId = createWorkspace();
        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "resume_highlight",
                                "user_requirement", "Retry the reliable artifact outbox dispatch",
                                "inputs", Map.of("language", "en")
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();
        recordingArtifactOutboxPublisher.failNextPublish();

        mockMvc.perform(post("/internal/worker/artifact-outbox/dispatch").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dispatched_count").value(0));

        Map<String, Object> failedAttempt = jdbcTemplate.queryForMap("""
                select status, attempt_count, last_error, next_attempt_at, claimed_at
                from task_outbox where task_id = ? and topic = 'noteweave.artifact.job'
                """, taskId);
        assertThat(failedAttempt.get("status")).isEqualTo("READY");
        assertThat(failedAttempt.get("attempt_count")).isEqualTo(1);
        assertThat(failedAttempt.get("last_error")).asString().contains("simulated artifact worker outage");
        assertThat(failedAttempt.get("next_attempt_at")).isNotNull();
        assertThat(failedAttempt.get("claimed_at")).isNull();

        jdbcTemplate.update("""
                update task_outbox set next_attempt_at = timestamp '2000-01-01 00:00:00'
                where task_id = ? and topic = 'noteweave.artifact.job'
                """, taskId);
        mockMvc.perform(post("/internal/worker/artifact-outbox/dispatch").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dispatched_count").value(1));

        Map<String, Object> successfulRetry = jdbcTemplate.queryForMap("""
                select status, attempt_count, last_error, next_attempt_at, claimed_at
                from task_outbox where task_id = ? and topic = 'noteweave.artifact.job'
                """, taskId);
        assertThat(successfulRetry.get("status")).isEqualTo("SENT");
        assertThat(successfulRetry.get("attempt_count")).isEqualTo(2);
        assertThat(successfulRetry.get("last_error")).isNull();
        assertThat(successfulRetry.get("next_attempt_at")).isNull();
        assertThat(successfulRetry.get("claimed_at")).isNull();
        assertThat(recordingArtifactOutboxPublisher.messages()).hasSize(1);
    }

    @Test
    void artifactOutboxShouldDeadLetterExposeMetricsAndSupportManualRedrive() throws Exception {
        recordingArtifactOutboxPublisher.reset();
        jdbcTemplate.update("update task_outbox set status = 'SENT', sent_at = current_timestamp where topic = 'noteweave.artifact.job'");
        String workspaceId = createWorkspace();
        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "resume_highlight",
                                "user_requirement", "Dead letter this dispatch",
                                "inputs", Map.of("language", "en")
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();
        String outboxId = jdbcTemplate.queryForObject(
                "select id from task_outbox where task_id = ?",
                String.class,
                taskId
        );
        jdbcTemplate.update("""
                update task_outbox
                set attempt_count = 4, next_attempt_at = timestamp '2000-01-01 00:00:00'
                where id = ?
                """, outboxId);
        recordingArtifactOutboxPublisher.failNextPublish();

        mockMvc.perform(post("/internal/worker/artifact-outbox/dispatch").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dispatched_count").value(0));
        mockMvc.perform(get("/internal/worker/artifact-outbox/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dead_letter_count").value(1))
                .andExpect(jsonPath("$.data.exhausted_count").value(1));
        mockMvc.perform(get("/internal/worker/artifact-outbox/dead-letters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].outbox_id").value(outboxId))
                .andExpect(jsonPath("$.data[0].attempt_count").value(5));
        assertThat(jdbcTemplate.queryForObject(
                "select task_status from task where id = ?", String.class, taskId
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "select status from artifact_job where task_id = ?", String.class, taskId
        )).isEqualTo("FAILED");

        mockMvc.perform(post("/internal/worker/artifact-outbox/dead-letters/{outboxId}/redrive", outboxId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dead_letter_count").value(0))
                .andExpect(jsonPath("$.data.ready_count").value(1));
        Map<String, Object> redriven = jdbcTemplate.queryForMap(
                "select status, attempt_count, dead_lettered_at from task_outbox where id = ?",
                outboxId
        );
        assertThat(redriven.get("status")).isEqualTo("READY");
        assertThat(redriven.get("attempt_count")).isEqualTo(0);
        assertThat(redriven.get("dead_lettered_at")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "select task_status from task where id = ?", String.class, taskId
        )).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "select status from artifact_job where task_id = ?", String.class, taskId
        )).isEqualTo("QUEUED");
    }

    @Test
    @Disabled("Legacy callback finalization was removed; canonical incremental finalization owns reports")
    void completedResearchReportShouldBeSavedAsWorkspaceSource() throws Exception {
        String workspaceId = createWorkspace();
        String scopedSourceId = uploadSource(workspaceId, "save-report-input.md", """
                Source for report save-as-source.
                It proves generated research reports can join the workspace material pool.
                """);

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "question", "How should the saved report behave?",
                                "profile", "default",
                                "source_scope_source_ids", java.util.List.of(scopedSourceId)
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String researchRunId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("research_run_id").asText();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "result_type", "RESEARCH_REPORT",
                                "result_title", "Saved Research Report",
                                "result_payload", Map.of(
                                        "report_markdown", "# Saved Research Report\n\nGenerated report body.",
                                        "report_source_candidate", Map.of(
                                                "title", "Saved Research Report",
                                                "source_type", "GENERATED_RESEARCH_REPORT",
                                                "generated_by", "research_agent",
                                                "content_markdown", "# Saved Research Report\n\nGenerated report body."
                                        ),
                                        "research_checkpoint_candidate", Map.of(
                                                "checkpoint_no", 1,
                                                "snapshot_type", "RESEARCH_LOOP_CHECKPOINT"
                                        )
                                ),
                                "trace_summary", "research harness finished",
                                "citations", java.util.List.of(Map.of("title", "save-report-input.md"))
                        ))))
                .andExpect(status().isOk());

        MvcResult saveResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/save-report-as-source", workspaceId, researchRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.source_type").value("GENERATED_RESEARCH_REPORT"))
                .andReturn();

        String sourceId = objectMapper.readTree(saveResult.getResponse().getContentAsString())
                .path("data").path("source_id").asText();

        Map<String, Object> sourceRow = jdbcTemplate.queryForMap("""
                select source_type, generated_by, generated_ref_id, status, parse_status, index_status
                from source
                where id = ?
                """, sourceId);
        assertThat(sourceRow.get("source_type")).isEqualTo("GENERATED_RESEARCH_REPORT");
        assertThat(sourceRow.get("generated_by")).isEqualTo("research_agent");
        assertThat(sourceRow.get("generated_ref_id")).isEqualTo(researchRunId);
        assertThat(sourceRow.get("status")).isEqualTo("READY");
        assertThat(sourceRow.get("parse_status")).isEqualTo("PARSED");
        assertThat(sourceRow.get("index_status")).isEqualTo("INDEXED");

        String reportSourceId = jdbcTemplate.queryForObject(
                "select report_source_id from research_run where id = ?",
                String.class,
                researchRunId
        );
        assertThat(reportSourceId).isEqualTo(sourceId);

        Integer chunkCount = jdbcTemplate.queryForObject(
                "select count(*) from source_chunk where source_id = ?",
                Integer.class,
                sourceId
        );
        assertThat(chunkCount).isNotNull().isGreaterThanOrEqualTo(1);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/sources", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("Saved Research Report"))
                .andExpect(jsonPath("$.data[0].source_type").value("GENERATED_RESEARCH_REPORT"))
                .andExpect(jsonPath("$.data[0].generated_by").value("research_agent"))
                .andExpect(jsonPath("$.data[0].generated_ref_id").value(researchRunId));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}", workspaceId, researchRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved_report_source.source_id").value(sourceId))
                .andExpect(jsonPath("$.data.saved_report_source.source_type").value("GENERATED_RESEARCH_REPORT"))
                .andExpect(jsonPath("$.data.saved_report_source.generated_by").value("research_agent"))
                .andExpect(jsonPath("$.data.saved_report_source.generated_ref_id").value(researchRunId))
                .andExpect(jsonPath("$.data.saved_report_source.index_status").value("INDEXED"))
                .andExpect(jsonPath("$.data.research_artifact.artifact_id").value(researchRunId))
                .andExpect(jsonPath("$.data.research_artifact.saved_report_source.source_id").value(sourceId))
                .andExpect(jsonPath("$.data.research_artifact.saved_report_source.index_status").value("INDEXED"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/checkpoints/{checkpointNo}",
                        workspaceId, researchRunId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.report_file.object_key").value("workspace/%s/research/%s/report/final.md".formatted(workspaceId, researchRunId)))
                .andExpect(jsonPath("$.data.research_artifact.artifact_id").value(researchRunId))
                .andExpect(jsonPath("$.data.research_artifact.report_file.object_key").value("workspace/%s/research/%s/report/final.md".formatted(workspaceId, researchRunId)))
                .andExpect(jsonPath("$.data.saved_report_source.source_id").value(sourceId))
                .andExpect(jsonPath("$.data.saved_report_source.generated_ref_id").value(researchRunId))
                .andExpect(jsonPath("$.data.research_artifact.saved_report_source.source_id").value(sourceId));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].research_run_id").value(researchRunId))
                .andExpect(jsonPath("$.data[0].saved_report_source.source_id").value(sourceId))
                .andExpect(jsonPath("$.data[0].saved_report_source.source_type").value("GENERATED_RESEARCH_REPORT"))
                .andExpect(jsonPath("$.data[0].saved_report_source.generated_ref_id").value(researchRunId))
                .andExpect(jsonPath("$.data[0].research_artifact.saved_report_source.source_id").value(sourceId));

        assertThat(researchRunService.listRuns(workspaceId)).hasSize(1);
        assertThat(researchRunService.listRuns(workspaceId).get(0).savedReportSource()).isNotNull();
        assertThat(researchRunService.listRuns(workspaceId).get(0).savedReportSource().sourceId()).isEqualTo(sourceId);
        assertThat(researchRunService.listRuns(workspaceId).get(0).researchArtifact().savedReportSource().sourceId())
                .isEqualTo(sourceId);
        assertThat(researchRunService.getCheckpoint(workspaceId, researchRunId, 1).reportFile()).isNotNull();
        assertThat(researchRunService.getCheckpoint(workspaceId, researchRunId, 1).savedReportSource()).isNotNull();
        assertThat(researchRunService.getCheckpoint(workspaceId, researchRunId, 1).researchArtifact()).isNotNull();
        assertThat(researchRunService.getCheckpoint(workspaceId, researchRunId, 1).savedReportSource().sourceId()).isEqualTo(sourceId);
    }

    @Test
    @Disabled("Legacy callback finalization was removed; canonical report citation coverage is separate")
    void savedResearchReportShouldRemainIdentifiableInChatCitations() throws Exception {
        String workspaceId = createWorkspace();
        String scopedSourceId = uploadSource(workspaceId, "citation-origin-input.md", """
                Workspace source used to bootstrap the research run.
                The downstream QA should prefer the saved report when the unique report token is queried.
                """);

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "question", "How should citation origin be carried forward?",
                                "profile", "default",
                                "source_scope_source_ids", java.util.List.of(scopedSourceId)
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String researchRunId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("research_run_id").asText();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "result_type", "RESEARCH_REPORT",
                                "result_title", "Citation Origin Research Report",
                                "result_payload", Map.of(
                                        "report_markdown", "# Citation Origin Research Report\n\nResearchReportCitationToken is only present in the saved report output.",
                                        "report_source_candidate", Map.of(
                                                "title", "Citation Origin Research Report",
                                                "source_type", "GENERATED_RESEARCH_REPORT",
                                                "generated_by", "research_agent",
                                                "content_markdown", "# Citation Origin Research Report\n\nResearchReportCitationToken is only present in the saved report output."
                                        )
                                ),
                                "trace_summary", "research harness finished",
                                "citations", java.util.List.of(Map.of("title", "citation-origin-input.md"))
                        ))))
                .andExpect(status().isOk());

        MvcResult saveReportResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/save-report-as-source", workspaceId, researchRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generated_by").value("research_agent"))
                .andExpect(jsonPath("$.data.generated_ref_id").value(researchRunId))
                .andReturn();

        String reportSourceId = objectMapper.readTree(saveReportResult.getResponse().getContentAsString())
                .path("data").path("source_id").asText();

        String conversationId = createConversation(workspaceId);
        JsonNode message = sendMessage(conversationId, "QA", "请总结 ResearchReportCitationToken 的含义");
        String requestId = message.path("data").path("assistant_request_id").asText();

        ChatStreamTestSupport.perform(mockMvc, requestId)
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:chat.citation")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "来源覆盖：1 个资料来源（Citation Origin Research Report · Research Report(" + researchRunId + ")）"
                )))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Citation Origin Research Report")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Research Report(" + researchRunId + ")")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ResearchReportCitationToken")));

        JsonNode noteMessage = sendMessage(conversationId, "NOTE", "继续整理 ResearchReportCitationToken 的资料定位");
        String noteRequestId = noteMessage.path("data").path("assistant_request_id").asText();

        ChatStreamTestSupport.perform(mockMvc, noteRequestId)
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("【候选资料】")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Citation Origin Research Report · Research Report(" + researchRunId + ")"
                )))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ResearchReportCitationToken")));

        MvcResult downstreamRunResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "question", "How should saved research evidence appear inside closed-loop state?",
                                "profile", "default",
                                "source_scope_source_ids", java.util.List.of(reportSourceId)
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String downstreamRunId = objectMapper.readTree(downstreamRunResult.getResponse().getContentAsString())
                .path("data").path("research_run_id").asText();
        String downstreamTaskId = objectMapper.readTree(downstreamRunResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(get("/internal/worker/research-tasks/{taskId}/input", downstreamTaskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source_scope[0].source_id").value(reportSourceId))
                .andExpect(jsonPath("$.data.source_scope[0].source_type").value("GENERATED_RESEARCH_REPORT"))
                .andExpect(jsonPath("$.data.source_scope[0].generated_by").value("research_agent"))
                .andExpect(jsonPath("$.data.source_scope[0].generated_ref_id").value(researchRunId))
                .andExpect(jsonPath("$.data.source_scope[0].source_metadata.research_artifact.artifact_id").value(researchRunId))
                .andExpect(jsonPath("$.data.source_scope[0].source_metadata.research_artifact.artifact_type").value("DEEP_RESEARCH_REPORT"));

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", downstreamTaskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.ofEntries(
                                Map.entry("result_type", "RESEARCH_REPORT"),
                                Map.entry("result_title", "Downstream Closed Loop Report"),
                                Map.entry("trace_summary", "downstream research harness finished"),
                                Map.entry("result_payload", Map.ofEntries(
                                        Map.entry("report_markdown", "# Downstream Closed Loop Report\n\nResearchReportCitationToken remains the key evidence."),
                                        Map.entry("read_windows", java.util.List.of(Map.ofEntries(
                                                Map.entry("window_id", "rw-downstream-1"),
                                                Map.entry("hit_id", "hit-downstream-1"),
                                                Map.entry("source_id", reportSourceId),
                                                Map.entry("source_title", "Citation Origin Research Report"),
                                                Map.entry("query", "ResearchReportCitationToken"),
                                                Map.entry("read_focus", "downstream closed-loop provenance"),
                                                Map.entry("window_text", "ResearchReportCitationToken remains the key evidence."),
                                                Map.entry("retention_reason", "TOP_EVIDENCE"),
                                                Map.entry("token_estimate", 96),
                                                Map.entry("url", "https://example.com/downstream-report"),
                                                Map.entry("provider", "workspace"),
                                                Map.entry("adapter", "workspace"),
                                                Map.entry("snapshot_status", "FALLBACK"),
                                                Map.entry("snapshot_key", "snapshot-downstream-1")
                                        ))),
                                        Map.entry("evidence_cards", java.util.List.of(Map.ofEntries(
                                                Map.entry("evidence_id", "ev-downstream-1"),
                                                Map.entry("window_id", "rw-downstream-1"),
                                                Map.entry("source_id", reportSourceId),
                                                Map.entry("source_title", "Citation Origin Research Report"),
                                                Map.entry("claim_text", "ResearchReportCitationToken remains traceable in downstream closed-loop state."),
                                                Map.entry("quote_text", "ResearchReportCitationToken remains the key evidence."),
                                                Map.entry("relation_type", "SUPPORTS"),
                                                Map.entry("support_score", 0.93),
                                                Map.entry("conflict_score", 0.02)
                                        ))),
                                        Map.entry("state_ledger", Map.ofEntries(
                                                Map.entry("active_branch_id", "branch-main"),
                                                Map.entry("rows", java.util.List.of(Map.ofEntries(
                                                        Map.entry("row_id", "row-downstream-1"),
                                                        Map.entry("source_id", reportSourceId),
                                                        Map.entry("source_title", "Citation Origin Research Report"),
                                                        Map.entry("search_query", "ResearchReportCitationToken"),
                                                        Map.entry("read_focus", "downstream closed-loop provenance"),
                                                        Map.entry("evidence_id", "ev-downstream-1"),
                                                        Map.entry("row_status", "VERIFIED"),
                                                        Map.entry("relation_type", "SUPPORTS"),
                                                        Map.entry("support_score", 0.93),
                                                        Map.entry("conflict_score", 0.02),
                                                        Map.entry("support_level", "STRONG"),
                                                        Map.entry("verification_status", "PASS"),
                                                        Map.entry("verifier_note", "Downstream provenance verified.")
                                                ))),
                                                Map.entry("cells", java.util.List.of(Map.ofEntries(
                                                        Map.entry("cell_id", "row-downstream-1:claim_text"),
                                                        Map.entry("row_id", "row-downstream-1"),
                                                        Map.entry("column_key", "claim_text"),
                                                        Map.entry("candidate_value", "ResearchReportCitationToken remains traceable in downstream closed-loop state."),
                                                        Map.entry("status", "VERIFIED"),
                                                        Map.entry("confidence", 0.93),
                                                        Map.entry("evidence_refs", java.util.List.of("ev-downstream-1"))
                                                ))),
                                                Map.entry("verifier_decisions", java.util.List.of(Map.of(
                                                        "decision_scope", "GLOBAL",
                                                        "decision_type", "READY_TO_WRITE",
                                                        "reason_code", "DOWNSTREAM_PROVENANCE_VERIFIED",
                                                        "evidence_ids", java.util.List.of("ev-downstream-1")
                                                )))
                                        )),
                                        Map.entry("local_verifier", Map.of(
                                                "status", "PASS"
                                        )),
                                        Map.entry("global_verifier", Map.of(
                                                "status", "PASS",
                                                "decision", "READY_TO_WRITE"
                                        )),
                                        Map.entry("loop_decision", Map.of(
                                                "decision", "SYNTHESIZE_REPORT",
                                                "reason", "DOWNSTREAM_PROVENANCE_VERIFIED",
                                                "round_no", 1
                                        )),
                                        Map.entry("loop_rounds", java.util.List.of(Map.of(
                                                "round_no", 1,
                                                "search_hit_count", 1,
                                                "read_window_count", 1,
                                                "evidence_card_count", 1,
                                                "search_queries", java.util.List.of("ResearchReportCitationToken"),
                                                "evidence_ids", java.util.List.of("ev-downstream-1"),
                                                "global_decision", "READY_TO_WRITE"
                                        ))),
                                        Map.entry("research_checkpoint_candidate", Map.of(
                                                "checkpoint_no", 1,
                                                "snapshot_type", "RESEARCH_LOOP_CHECKPOINT"
                                        )),
                                        Map.entry("report_structure", Map.ofEntries(
                                                Map.entry("research_question", Map.of(
                                                        "original_question", "How should saved research evidence appear inside closed-loop state?",
                                                        "research_profile", "DEFAULT"
                                                )),
                                                Map.entry("research_intent", Map.of(
                                                        "research_goal", "Verify downstream closed-loop provenance.",
                                                        "deliverable_format", "Structured provenance brief",
                                                        "constraints", java.util.List.of("Preserve research-source identity."),
                                                        "depth", "STANDARD"
                                                )),
                                                Map.entry("verified_findings", java.util.List.of(Map.of(
                                                        "source_id", reportSourceId,
                                                        "source_title", "Citation Origin Research Report",
                                                        "evidence_id", "ev-downstream-1",
                                                        "claim_text", "ResearchReportCitationToken remains traceable in downstream closed-loop state.",
                                                        "support_level", "STRONG",
                                                        "row_status", "VERIFIED"
                                                ))),
                                                Map.entry("conflict_and_counterfactual_review", Map.of(
                                                        "local_verifier_status", "PASS",
                                                        "global_verifier_decision", "READY_TO_WRITE",
                                                        "evidence_policy", "Preserve research-source identity.",
                                                        "conflicted_rows", java.util.List.of(),
                                                        "branch_decisions", java.util.List.of()
                                                )),
                                                Map.entry("recovery_status", Map.of(
                                                        "local_verifier_status", "PASS",
                                                        "global_verifier_decision", "READY_TO_WRITE",
                                                        "guardrailed_rows", java.util.List.of(),
                                                        "unresolved_questions", java.util.List.of(),
                                                        "warnings", java.util.List.of()
                                                )),
                                                Map.entry("closed_loop_state", Map.of(
                                                        "active_branch", "branch-main",
                                                        "verified_rows_count", 1,
                                                        "conflicted_rows_count", 0
                                                )),
                                                Map.entry("next_actions", java.util.List.of("Continue only if more provenance checks are required."))
                                        ))
                                ))
                        ))))
                .andExpect(status().isOk());

        MvcResult downstreamDetailResult = mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}", workspaceId, downstreamRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source_scope[0].source_id").value(reportSourceId))
                .andExpect(jsonPath("$.data.source_scope[0].source_type").value("GENERATED_RESEARCH_REPORT"))
                .andExpect(jsonPath("$.data.source_scope[0].generated_by").value("research_agent"))
                .andExpect(jsonPath("$.data.source_scope[0].generated_ref_id").value(researchRunId))
                .andExpect(jsonPath("$.data.source_scope[0].source_metadata.research_artifact.artifact_id").value(researchRunId))
                .andExpect(jsonPath("$.data.report_structure.verified_findings[0].generated_by").value("research_agent"))
                .andExpect(jsonPath("$.data.report_structure.verified_findings[0].generated_ref_id").value(researchRunId))
                .andExpect(jsonPath("$.data.closed_loop_state.rows[0].generated_by").value("research_agent"))
                .andExpect(jsonPath("$.data.closed_loop_state.rows[0].generated_ref_id").value(researchRunId))
                .andExpect(jsonPath("$.data.closed_loop_state.source_evidence[0].generated_by").value("research_agent"))
                .andExpect(jsonPath("$.data.closed_loop_state.source_evidence[0].generated_ref_id").value(researchRunId))
                .andExpect(jsonPath("$.data.closed_loop_state.cell_evidence[0].generated_by").value("research_agent"))
                .andExpect(jsonPath("$.data.closed_loop_state.cell_evidence[0].generated_ref_id").value(researchRunId))
                .andExpect(jsonPath("$.data.closed_loop_state.loop_rounds[0].source_samples[0].generated_by").value("research_agent"))
                .andExpect(jsonPath("$.data.closed_loop_state.loop_rounds[0].source_samples[0].generated_ref_id").value(researchRunId))
                .andExpect(jsonPath("$.data.closed_loop_state.verifier_decisions[0].source_samples[0].generated_by").value("research_agent"))
                .andExpect(jsonPath("$.data.closed_loop_state.verifier_decisions[0].source_samples[0].generated_ref_id").value(researchRunId))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints.length()").value(1))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].summary.state_ledger.verified_row_samples[0].generated_by").value("research_agent"))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].summary.state_ledger.verified_row_samples[0].generated_ref_id").value(researchRunId))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].summary.state_ledger.evidence_card_samples[0].generated_by").value("research_agent"))
                .andExpect(jsonPath("$.data.closed_loop_state.checkpoints[0].summary.state_ledger.evidence_card_samples[0].generated_ref_id").value(researchRunId))
                .andReturn();

        JsonNode downstreamDetail = objectMapper.readTree(downstreamDetailResult.getResponse().getContentAsString()).path("data");
        JsonNode finalReportTrace = findTrace(downstreamDetail.path("traces"), "FINAL_REPORT");
        assertThat(finalReportTrace.path("payload").path("result_payload").path("report_structure")
                .path("verified_findings").path(0).path("generated_by").asText()).isEqualTo("research_agent");
        assertThat(finalReportTrace.path("payload").path("result_payload").path("report_structure")
                .path("verified_findings").path(0).path("generated_ref_id").asText()).isEqualTo(researchRunId);
    }

    @Test
    @Disabled("Legacy no-source research run contract was removed; incremental creation fails fast")
    void unfinishedResearchReportShouldNotBeSavedAsWorkspaceSource() throws Exception {
        String workspaceId = createWorkspace();
        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "question", "Should unfinished reports be saved?",
                                "profile", "default"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String researchRunId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("research_run_id").asText();

        mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/save-report-as-source", workspaceId, researchRunId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESEARCH_REPORT_NOT_READY"));
    }

    @Test
    void workerFailShouldMarkArtifactTaskAndJobAsFailed() throws Exception {
        String workspaceId = createWorkspace();

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "skill_key", "faq_draft",
                                "user_requirement", "输出面向用户帮助中心的 FAQ 草稿",
                                "inputs", Map.of(
                                        "language", "zh-CN"
                                )
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String artifactJobId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("artifact_job_id").asText();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/fail", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phase", "VERIFYING",
                                "error_code", "SCHEMA_INVALID",
                                "error_message", "schema gate rejected current artifact output",
                                "retryable", false
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_status").value("FAILED"))
                .andExpect(jsonPath("$.data.progress_phase").value("VERIFYING"));

        String artifactStatus = jdbcTemplate.queryForObject(
                "select status from artifact_job where id = ?",
                String.class,
                artifactJobId
        );
        assertThat(artifactStatus).isEqualTo("FAILED");
    }

    @Test
    @Disabled("Legacy parent Worker failure callback was replaced by per-agent lifecycle recovery")
    void workerFailShouldMarkResearchTaskAndRunAsFailed() throws Exception {
        String workspaceId = createWorkspace();

        MvcResult createResult = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/research-runs", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "question", "Which research direction should fail safely?",
                                "profile", "default"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String researchRunId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("research_run_id").asText();
        String taskId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .path("data").path("task_id").asText();

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/fail", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "phase", "VERIFYING",
                                "error_code", "GLOBAL_VERIFIER_REJECTED",
                                "error_message", "global verifier rejected current research report",
                                "retryable", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_status").value("FAILED"))
                .andExpect(jsonPath("$.data.progress_phase").value("VERIFYING"));

        String researchStatus = jdbcTemplate.queryForObject(
                "select status from research_run where id = ?",
                String.class,
                researchRunId
        );
        assertThat(researchStatus).isEqualTo("FAILED");

        Integer failTraceCount = jdbcTemplate.queryForObject(
                "select count(*) from research_trace where research_run_id = ? and trace_type = 'FAILED'",
                Integer.class,
                researchRunId
        );
        assertThat(failTraceCount).isNotNull().isEqualTo(1);
    }

    private void completeArtifact(String taskId, String title, String markdown) throws Exception {
        mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "result_type", "MARKDOWN",
                                "result_title", title,
                                "result_payload", Map.of("markdown", markdown),
                                "trace_summary", "version lifecycle test",
                                "citations", java.util.List.of()
                        ))))
                .andExpect(status().isOk());
    }

    private String createWorkspace() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Agent 工作台",
                                "description", "用于 Research / Artifact 契约测试"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String workspaceId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("workspace_id").asText();
        jdbcTemplate.update(
                "update workspace set retrieval_strategy_v2_enabled = true where id = ?",
                workspaceId);
        return workspaceId;
    }

    private String createConversation(String workspaceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Research Citation Chat",
                                "conversation_type", "WORKSPACE_CHAT"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("conversation_id").asText();
    }

    private String uploadSource(String workspaceId, String fileName, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        MvcResult init = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/uploads", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "file_name", fileName,
                                "file_size", bytes.length,
                                "mime_type", "text/markdown",
                                "chunk_size", bytes.length,
                                "total_chunks", 1
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String uploadId = objectMapper.readTree(init.getResponse().getContentAsString()).path("data").path("upload_id").asText();
        mockMvc.perform(put("/api/v2/uploads/{uploadId}/chunks/{chunkIndex}", uploadId, 0)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(bytes))
                .andExpect(status().isOk());
        MvcResult completeResult = mockMvc.perform(post("/api/v2/uploads/{uploadId}/complete", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parse_status").value(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.equalTo("PARSED"), org.hamcrest.Matchers.equalTo("PARSING_QUEUED"))))
                .andExpect(jsonPath("$.data.index_status").value(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.equalTo("INDEXED"), org.hamcrest.Matchers.equalTo("INDEX_QUEUED"))))
                .andReturn();
        return objectMapper.readTree(completeResult.getResponse().getContentAsString())
                .path("data").path("source_id").asText();
    }

    private JsonNode sendMessage(String conversationId, String answerMode, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", content,
                                "answer_mode", answerMode,
                                "client_request_id", answerMode + "-" + System.nanoTime()
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode findTrace(JsonNode traces, String traceType) {
        for (JsonNode trace : traces) {
            if (traceType.equals(trace.path("trace_type").asText())) {
                return trace;
            }
        }
        throw new AssertionError("trace not found: " + traceType);
    }

    private JsonNode findByField(JsonNode items, String fieldName, String expectedValue) {
        for (JsonNode item : items) {
            if (expectedValue.equals(item.path(fieldName).asText())) {
                return item;
            }
        }
        throw new AssertionError("item not found for " + fieldName + "=" + expectedValue);
    }
}

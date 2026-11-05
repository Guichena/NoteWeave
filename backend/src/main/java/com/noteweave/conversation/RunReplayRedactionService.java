package com.noteweave.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class RunReplayRedactionService {

    private static final String EMPTY_CONTENT_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ContextV2ShadowSnapshotService shadowSnapshots;

    public RunReplayRedactionService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this(jdbcTemplate, objectMapper, null);
    }

    @Autowired
    public RunReplayRedactionService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                     ContextV2ShadowSnapshotService shadowSnapshots) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.shadowSnapshots = shadowSnapshots;
    }

    public void redactDeletedSource(String workspaceId, String sourceId) {
        redactAffectedAnswerRuns(workspaceId, sourceId);
        jdbcTemplate.update("""
                update answer_run_evidence_manifest
                set excerpt = '', content_hash = ?, character_cost = 0
                where workspace_id = ? and source_id = ?
                """, EMPTY_CONTENT_SHA256, workspaceId, sourceId);
        jdbcTemplate.update("""
                update run_input_snapshot
                set replay_availability = 'METADATA_ONLY'
                where workspace_id = ? and execution_kind = 'ANSWER'
                  and replay_availability = 'FULL'
                  and answer_run_id in (
                    select answer_run_id
                    from answer_run_evidence_manifest
                    where workspace_id = ? and source_id = ?
                  )
                """, workspaceId, workspaceId, sourceId);
        redactArtifactSnapshots(workspaceId, sourceId);
        jdbcTemplate.update("""
                update research_evidence_manifest_item
                set excerpt = '', content_hash = ?
                where source_id = ?
                  and manifest_id in (
                    select id from research_evidence_manifest where workspace_id = ?
                  )
                """, EMPTY_CONTENT_SHA256, sourceId, workspaceId);
        jdbcTemplate.update("""
                update run_input_snapshot
                set replay_availability = 'METADATA_ONLY'
                where workspace_id = ? and execution_kind = 'RESEARCH'
                  and replay_availability = 'FULL'
                  and research_run_id in (
                    select manifest.research_run_id
                    from research_evidence_manifest manifest
                    join research_evidence_manifest_item item on item.manifest_id = manifest.id
                    where manifest.workspace_id = ? and item.source_id = ?
                  )
                """, workspaceId, workspaceId, sourceId);
    }

    /** 来源父行优先，再锁全部受影响会话/run/知识对象，最后才处理citation正文。 */
    private void redactAffectedAnswerRuns(String workspaceId, String sourceId) {
        jdbcTemplate.queryForList("select id from source where workspace_id=? and id=? for update", String.class,workspaceId,sourceId);
        jdbcTemplate.queryForList("select id from source_snapshot where source_id=? order by id for update",String.class,sourceId);
        jdbcTemplate.queryForList("select id from source_chunk where workspace_id=? and source_id=? order by id for update",String.class,workspaceId,sourceId);
        boolean compliance = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select case when delete_reason='COMPLIANCE' then true else false end from source where workspace_id=? and id=?",
                Boolean.class,workspaceId,sourceId));
        List<String> versions = jdbcTemplate.queryForList("""
                select distinct kvc.knowledge_version_id from knowledge_version_citation kvc
                join citation c on c.id=kvc.citation_id where c.workspace_id=? and c.source_id=?
                order by kvc.knowledge_version_id
                """,String.class,workspaceId,sourceId);
        java.util.TreeSet<String> runIds = new java.util.TreeSet<>(jdbcTemplate.queryForList("""
                select distinct m.answer_run_id from answer_run_evidence_manifest m
                where m.workspace_id=? and (m.source_id=? or m.knowledge_version_id in (
                    select kvc.knowledge_version_id from knowledge_version_citation kvc
                    join citation c on c.id=kvc.citation_id where c.workspace_id=? and c.source_id=?))
                """,String.class,workspaceId,sourceId,workspaceId,sourceId));
        List<String> researchIds = jdbcTemplate.queryForList("""
                select r.id from research_run r where r.workspace_id=? and (
                    r.source_scope_json like ? or exists(select 1 from source_evidence e where e.research_run_id=r.id and e.source_id=?)
                    or exists(select 1 from research_evidence_manifest m join research_evidence_manifest_item i on i.manifest_id=m.id
                              where m.research_run_id=r.id and m.workspace_id=? and i.source_id=?))
                """,String.class,workspaceId,"%\""+sourceId+"\"%",sourceId,workspaceId,sourceId);
        for (String researchId : researchIds) {
            runIds.addAll(jdbcTemplate.queryForList("select distinct answer_run_id from answer_run_evidence_manifest where workspace_id=? and evidence_kind='RESEARCH_EVIDENCE' and evidence_id like ?",
                    String.class,workspaceId,"research-evidence:"+researchId+":%"));
        }
        java.util.TreeSet<String> conversations = new java.util.TreeSet<>();
        for(String runId:runIds) conversations.addAll(jdbcTemplate.queryForList("select conversation_id from answer_run where workspace_id=? and id=?",String.class,workspaceId,runId));
        for(String id:conversations) jdbcTemplate.queryForList("select id from conversation where workspace_id=? and id=? for update",String.class,workspaceId,id);
        for(String id:runIds) jdbcTemplate.queryForList("select id from answer_run where workspace_id=? and id=? for update",String.class,workspaceId,id);
        java.util.TreeSet<String> itemIds = new java.util.TreeSet<>();
        for(String version:versions) itemIds.addAll(jdbcTemplate.queryForList("select item_id from knowledge_version where id=?",String.class,version));
        for(String id:itemIds) jdbcTemplate.queryForList("select id from knowledge_item where workspace_id=? and id=? for update",String.class,workspaceId,id);
        for(String runId:runIds) {
            String state=jdbcTemplate.queryForObject("select status from answer_run where workspace_id=? and id=?",String.class,workspaceId,runId);
            if(!compliance && List.of("COMPLETED","FAILED","CANCELLED").contains(state)) continue;
            jdbcTemplate.update("""
                    update answer_run set status='CANCELLED',error_code='ANSWER_EVIDENCE_REVOKED',error_message='来源已撤销',
                        stream_owner=null,stream_lease_until=null,finished_at=coalesce(finished_at,current_timestamp),
                        version=version+1,updated_at=current_timestamp where workspace_id=? and id=?
                    """,workspaceId,runId);
            redactAnswerRun(workspaceId,runId);
        }
        if(compliance) {
            for(String version:versions) {
                jdbcTemplate.update("update knowledge_version set content='',summary='' where id=?",version);
                if(wikiPageVersionCache!=null) {
                    String item=jdbcTemplate.queryForObject("select item_id from knowledge_version where id=?",String.class,version);
                    String itemId = item;
                    String versionId = version;
                    if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                                new org.springframework.transaction.support.TransactionSynchronization() {
                                    @Override public void afterCommit() {
                                        wikiPageVersionCache.invalidate(workspaceId, itemId, versionId);
                                    }
                                });
                    } else {
                        wikiPageVersionCache.invalidate(workspaceId, itemId, versionId);
                    }
                }
            }
        }
    }

    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Autowired(required=false)
    void setEventPublisher(org.springframework.context.ApplicationEventPublisher publisher) {
        this.eventPublisher = publisher;
    }

    private com.noteweave.knowledge.WikiPageVersionCache wikiPageVersionCache;
    @Autowired(required=false)
    void setWikiPageVersionCache(com.noteweave.knowledge.WikiPageVersionCache cache) {
        this.wikiPageVersionCache=cache;
    }

    public void redactDeletedConversationMessage(String workspaceId, String messageId) {
        List<String> runIds = jdbcTemplate.queryForList("""
                select id from answer_run where workspace_id=?
                  and (query_message_id=? or answer_message_id=? or id in (
                      select answer_run_id from run_input_snapshot where workspace_id=?
                        and execution_kind='ANSWER' and snapshot_json like ?)) order by id
                """, String.class, workspaceId, messageId, messageId, workspaceId, "%"+messageId+"%");
        for (String runId : runIds) redactAnswerRun(workspaceId, runId);
        redactFrozenResearchProjection("MESSAGE", messageId);
        String reference = "%" + messageId + "%";
        jdbcTemplate.update("""
                update run_input_snapshot set replay_availability = 'METADATA_ONLY'
                where workspace_id = ? and replay_availability = 'FULL'
                  and (query_message_id = ? or assistant_message_id = ? or snapshot_json like ?)
                """, workspaceId, messageId, messageId, reference);
        if (shadowSnapshots != null) shadowSnapshots.redactReference("MESSAGE", messageId);
    }

    /** 保留身份、hash、计数；正文副本必须物理清理，不能只依赖接口CASE隐藏。 */
    public void redactAnswerRun(String workspaceId, String runId) {
        jdbcTemplate.update("update message_revision set content='',status='PARTIAL',updated_at=current_timestamp where workspace_id=? and answer_run_id=?", workspaceId,runId);
        jdbcTemplate.update("""
                update answer_run_evidence_manifest set title='',excerpt='',location_info=null,
                    graph_path_json=null,character_cost=0 where workspace_id=? and answer_run_id=?
                """,workspaceId,runId);
        jdbcTemplate.update("""
                update conversation_message set content='',content_hash=?
                where workspace_id=? and id in (select answer_message_id from answer_run where workspace_id=? and id=?)
                """,EMPTY_CONTENT_SHA256,workspaceId,workspaceId,runId);
        String contract=jdbcTemplate.query("select generation_contract_json from answer_run where workspace_id=? and id=?",
                rs->rs.next()?rs.getString(1):null,workspaceId,runId);
        if(contract!=null && !contract.isBlank()) {
            try {
                JsonNode node=objectMapper.readTree(contract);
                if(!(node instanceof ObjectNode object)) throw new IllegalStateException("生成契约不是对象");
                // 契约按全局 snake_case 写库。此前这里写的是驼峰键，原有的 system_prompt 与
                // allowed_page_titles 原样保留，脱敏没有生效，只是多出两个读取时被忽略的字段。
                object.remove("systemPrompt");
                object.remove("allowedPageTitles");
                object.put("system_prompt", "");
                object.putArray("allowed_page_titles");
                jdbcTemplate.update("update answer_run set generation_contract_json=? where workspace_id=? and id=?",
                        objectMapper.writeValueAsString(object),workspaceId,runId);
            } catch(JsonProcessingException ex) {
                throw new IllegalStateException("生成契约无法脱敏",ex);
            }
        }
        jdbcTemplate.update("""
                update answer_event set payload_json='{}'
                where workspace_id=? and answer_run_id=? and event_type in ('citation.upsert','answer.delta','evidence.ready')
                """,workspaceId,runId);
        List<String> messages=jdbcTemplate.queryForList("select answer_message_id from answer_run where workspace_id=? and id=? and answer_message_id is not null",String.class,workspaceId,runId);
        var bindings=new com.noteweave.chat.CitationBindingService(jdbcTemplate);
        for(String message:messages) bindings.unbindAndRedactOwned(workspaceId,message);
        if (eventPublisher != null) {
            String conversationId = jdbcTemplate.queryForObject(
                    "select conversation_id from answer_run where workspace_id=? and id=?",
                    String.class, workspaceId, runId);
            // 监听器必须AFTER_COMMIT：回滚不能清理仍合法的实时缓冲。
            eventPublisher.publishEvent(new com.noteweave.answer.AnswerReplayRevoked(
                    workspaceId, conversationId, runId));
        }
    }

    public void redactDeletedSummaryRevision(String revisionId) {
        redactFrozenResearchProjection("TOPIC_SUMMARY", revisionId);
        jdbcTemplate.update("""
                update run_input_snapshot set replay_availability = 'METADATA_ONLY'
                where replay_availability = 'FULL' and snapshot_json like ?
                """, "%" + revisionId + "%");
        if (shadowSnapshots != null) shadowSnapshots.redactReference("TOPIC_SUMMARY", revisionId);
    }

    public void redactDeletedMemoryRevision(String revisionId) {
        redactFrozenResearchProjection("MEMORY_REVISION", revisionId);
        stripCompiledMemoryText(revisionId);
        jdbcTemplate.update("""
                update artifact_run_input_snapshot
                set control_pack_json = null, replay_availability = 'METADATA_ONLY'
                where id in (
                    select r.input_snapshot_id from artifact_job_run r
                    join memory_usage_log u on u.target_id = r.task_id
                    where u.target_type = 'ARTIFACT_JOB_RUN' and u.memory_revision_id = ?
                )
                """, revisionId);
        jdbcTemplate.update("""
                update artifact_job_run set control_pack_json = null
                where task_id in (
                    select target_id from memory_usage_log
                    where target_type = 'ARTIFACT_JOB_RUN' and memory_revision_id = ?
                )
                """, revisionId);
        jdbcTemplate.update("""
                update artifact_job set control_pack_json = null
                where task_id in (
                    select target_id from memory_usage_log
                    where target_type = 'ARTIFACT_JOB_RUN' and memory_revision_id = ?
                )
                """, revisionId);
        if (shadowSnapshots != null) shadowSnapshots.redactReference("MEMORY_REVISION", revisionId);
    }

    /**
     * 撤销后的快照仍保留记忆版本号，但去掉编入时记下的展示文本。
     * 回放接口在非 FULL 时也不再返回这段文本。
     */
    private void stripCompiledMemoryText(String revisionId) {
        List<ResearchProjectionRow> rows = jdbcTemplate.query("""
                select id, snapshot_json from run_input_snapshot
                where replay_availability = 'FULL' and snapshot_json like ?
                """, (rs, index) -> new ResearchProjectionRow(rs.getString(1), rs.getString(2)),
                "%" + revisionId + "%");
        for (ResearchProjectionRow row : rows) {
            ObjectNode snapshot;
            try {
                snapshot = (ObjectNode) objectMapper.readTree(row.snapshotJson());
            } catch (JsonProcessingException | ClassCastException ex) {
                throw new IllegalStateException("记忆撤销后的输入快照无法脱敏", ex);
            }
            removeDisplayText(snapshot, revisionId);
            try {
                jdbcTemplate.update("""
                        update run_input_snapshot
                        set snapshot_json = ?, replay_availability = 'METADATA_ONLY'
                        where id = ? and replay_availability = 'FULL'
                        """, objectMapper.writeValueAsString(snapshot), row.id());
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("记忆撤销后的输入快照无法写回", ex);
            }
        }
    }

    private void removeDisplayText(JsonNode node, String revisionId) {
        if (node instanceof ObjectNode object) {
            if (revisionId.equals(object.path("memory_version_id").asText()) && object.has("display_text")) {
                object.remove("display_text");
            }
            object.fields().forEachRemaining(field -> removeDisplayText(field.getValue(), revisionId));
            return;
        }
        if (node instanceof ArrayNode array) {
            array.forEach(child -> removeDisplayText(child, revisionId));
        }
    }

    /** Remove only frozen v2 projection text; preserve Research output and audit records. */
    private void redactFrozenResearchProjection(String refType, String refId) {
        List<ResearchProjectionRow> rows = jdbcTemplate.query("""
                select id, snapshot_json from run_input_snapshot
                where execution_kind = 'RESEARCH' and replay_availability = 'FULL'
                  and compiler_version = ? and snapshot_json like ?
                """, (rs, index) -> new ResearchProjectionRow(rs.getString(1), rs.getString(2)),
                ContextWindowPlannerV2.COMPILER_VERSION, "%" + refId + "%");
        for (ResearchProjectionRow row : rows) {
            ObjectNode snapshot;
            ContextProjectionV2 projection;
            try {
                snapshot = (ObjectNode) objectMapper.readTree(row.snapshotJson());
                projection = objectMapper.treeToValue(snapshot.path("context_v2_projection"),
                        ContextProjectionV2.class);
            } catch (JsonProcessingException | ClassCastException ex) {
                throw new IllegalStateException("Stored Research Context projection is invalid", ex);
            }
            boolean referenced = switch (refType) {
                case "MESSAGE" -> projection.rawTail().stream()
                        .anyMatch(message -> refId.equals(message.messageId()))
                        || projection.constraints().stream()
                        .anyMatch(rule -> refId.equals(rule.sourceMessageId()));
                case "TOPIC_SUMMARY" -> projection.topicSummaries().stream()
                        .anyMatch(summary -> refId.equals(summary.revisionId()));
                case "MEMORY_REVISION" -> projection.memoryRevisions().stream()
                        .anyMatch(memory -> refId.equals(memory.revisionId()));
                default -> false;
            };
            if (!referenced) continue;
            ContextProjectionV2 redacted = projection.redacted();
            snapshot.set("context_v2_projection", objectMapper.valueToTree(redacted));
            if ("MEMORY_REVISION".equals(refType)) {
                removeDisplayText(snapshot, refId);
            }
            try {
                String redactedJson = objectMapper.writeValueAsString(redacted);
                snapshot.put("context_v2_projection_sha256", sha256(redactedJson));
                snapshot.put("context_v2_redacted_reason", "SENSITIVE_REFERENCE_REDACTED");
                jdbcTemplate.update("""
                        update run_input_snapshot
                        set snapshot_json = ?, replay_availability = 'METADATA_ONLY'
                        where id = ? and replay_availability = 'FULL'
                        """, objectMapper.writeValueAsString(snapshot), row.id());
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Cannot redact Research Context projection", ex);
            }
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record ResearchProjectionRow(String id, String snapshotJson) {}

    private void redactArtifactSnapshots(String workspaceId, String sourceId) {
        DeletedSourceIdentity deleted = loadDeletedSourceIdentity(workspaceId, sourceId);
        List<String> references = new ArrayList<>();
        references.add(sourceId);
        references.addAll(deleted.snapshotIds());
        if (deleted.generatedRefId() != null && !deleted.generatedRefId().isBlank()) {
            references.add(deleted.generatedRefId());
        }
        String predicate = String.join(" or ", references.stream()
                .map(ignored -> "source_scope_snapshot_json like ? or upstream_refs_json like ?")
                .toList());
        List<Object> parameters = new ArrayList<>();
        parameters.add(workspaceId);
        for (String reference : references) {
            parameters.add("%" + reference + "%");
            parameters.add("%" + reference + "%");
        }
        List<ArtifactSnapshotRow> rows = jdbcTemplate.query("""
                select id, source_scope_snapshot_json, upstream_refs_json
                from artifact_run_input_snapshot
                where workspace_id = ? and (%s)
                """.formatted(predicate), (rs, rowNum) -> new ArtifactSnapshotRow(
                rs.getString("id"), rs.getString("source_scope_snapshot_json"),
                rs.getString("upstream_refs_json")), parameters.toArray());
        for (ArtifactSnapshotRow row : rows) {
            boolean sourceScopeMatch = artifactSourceScopeContains(row.sourceScopeJson(), sourceId);
            boolean upstreamMatch = artifactUpstreamReferences(
                    row.upstreamRefsJson(), sourceId, deleted.generatedRefId(), deleted.snapshotIds());
            if (!sourceScopeMatch && !upstreamMatch) {
                continue;
            }
            String redactedSourceScope = sourceScopeMatch
                    ? redactArtifactSourceBody(row.sourceScopeJson(), sourceId)
                    : row.sourceScopeJson();
            jdbcTemplate.update("""
                    update artifact_run_input_snapshot
                    set source_scope_snapshot_json = ?, replay_availability = 'METADATA_ONLY'
                    where id = ?
                    """, redactedSourceScope, row.id());
        }
    }

    private DeletedSourceIdentity loadDeletedSourceIdentity(String workspaceId, String sourceId) {
        String generatedRefId = jdbcTemplate.query("""
                select generated_ref_id from source where workspace_id = ? and id = ?
                """, rs -> rs.next() ? rs.getString("generated_ref_id") : null, workspaceId, sourceId);
        List<String> snapshotIds = jdbcTemplate.query(
                "select id from source_snapshot where source_id = ?",
                (rs, rowNum) -> rs.getString("id"), sourceId);
        return new DeletedSourceIdentity(generatedRefId, Set.copyOf(snapshotIds));
    }

    private boolean artifactSourceScopeContains(String json, String sourceId) {
        ArrayNode array = readArtifactArray(json, "source scope");
        for (JsonNode item : array) {
            requireArtifactObject(item, "source scope");
            if (sourceId.equals(item.path("source_id").asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean artifactUpstreamReferences(
            String json,
            String sourceId,
            String generatedRefId,
            Set<String> snapshotIds
    ) {
        ArrayNode array = readArtifactArray(json, "upstream refs");
        for (JsonNode item : array) {
            requireArtifactObject(item, "upstream refs");
            String refType = item.path("ref_type").asText();
            String refId = item.path("ref_id").asText();
            String revisionId = item.path("revision_id").asText();
            if ("SOURCE_SNAPSHOT".equals(refType)
                    && sourceId.equals(refId)
                    && snapshotIds.contains(revisionId)) {
                return true;
            }
            if ("RESEARCH_REPORT".equals(refType)
                    && generatedRefId != null
                    && generatedRefId.equals(refId)
                    && snapshotIds.contains(revisionId)) {
                return true;
            }
        }
        return false;
    }

    private String redactArtifactSourceBody(String json, String sourceId) {
        ArrayNode array = readArtifactArray(json, "source scope");
        for (JsonNode item : array) {
            requireArtifactObject(item, "source scope");
            ObjectNode source = (ObjectNode) item;
            if (sourceId.equals(source.path("source_id").asText())) {
                source.put("summary", "");
                source.put("sample_text", "");
            }
        }
        try {
            return objectMapper.writeValueAsString(array);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored Artifact input snapshot JSON is invalid", ex);
        }
    }

    private ArrayNode readArtifactArray(String json, String field) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("Stored Artifact " + field + " JSON is empty");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!(root instanceof ArrayNode array)) {
                throw new IllegalStateException("Stored Artifact " + field + " JSON must be an array");
            }
            return array;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored Artifact " + field + " JSON is invalid", ex);
        }
    }

    private void requireArtifactObject(JsonNode item, String field) {
        if (!(item instanceof ObjectNode)) {
            throw new IllegalStateException("Stored Artifact " + field + " JSON contains a non-object item");
        }
    }

    private record ArtifactSnapshotRow(String id, String sourceScopeJson, String upstreamRefsJson) {
    }

    private record DeletedSourceIdentity(String generatedRefId, Set<String> snapshotIds) {
    }
}

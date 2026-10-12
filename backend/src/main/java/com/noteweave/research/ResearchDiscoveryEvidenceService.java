package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** 校验真实检索片段，服务端计算提案分数，不授予正式证据资格。 */
final class ResearchDiscoveryEvidenceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    ResearchDiscoveryEvidenceService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    Map<String, Observation> persist(ResearchAgentCompletionCommitter.TaskRow task, Map<String, Object> result) {
        JsonNode context = read(task.executionContextJson());
        JsonNode policy = context.path("source_policy"), input = context.path("query_policy").path("discovery_input");
        JsonNode queries = mapper.valueToTree(result.get("queries"));
        if (!queries.isArray() || queries.isEmpty() || queries.size() > Math.min(3, input.path("query_limit").asInt(3))) throw invalid();
        Set<String> executed = new HashSet<>();
        for (JsonNode query : queries) {
            text(query, 200);
            if (query.asText().chars().anyMatch(c -> c < 32) || !executed.add(query.asText())) throw invalid();
        }
        JsonNode observations = mapper.valueToTree(result.get("search_observations"));
        if (!observations.isArray() || observations.size() > 24) throw invalid();
        Map<String, Observation> resultMap = new HashMap<>();
        for (JsonNode observation : observations) {
            if (!fields(observation).equals(Set.of("observation_key", "query", "source_id", "source_title", "source_url", "source_window_id", "snippet", "source_domain", "lineage_digest"))) throw invalid();
            for (String field : fields(observation)) if (!observation.path(field).isTextual()) throw invalid();
            String key = text(observation.path("observation_key"), 64), query = text(observation.path("query"), 200);
            String title = text(observation.path("source_title"), 500), snippet = text(observation.path("snippet"), 3000);
            String sourceId = text(observation.path("source_id"), 160), url = observation.path("source_url").asText();
            if (!key.matches("[a-z0-9][a-z0-9._:-]*") || !executed.contains(query) || resultMap.containsKey(key)) throw invalid();
            String domain, lineage;
            if (url.isBlank()) {
                JsonNode source = null;
                for (JsonNode candidate : policy.path("source_scope")) if (sourceId.equals(candidate.path("source_id").asText())) source = candidate;
                if (source == null || source.path("source_snapshot_id").asText().isBlank()) throw invalid();
                List<Map<String, Object>> windows = jdbc.query("""
                        select sw.content, s.title from source_window sw join source_chunk sc on sc.id = sw.source_chunk_id
                        join source s on s.id = sc.source_id join research_run rr on rr.workspace_id = sc.workspace_id
                        where rr.id = ? and sc.source_id = ? and sc.source_snapshot_id = ? and sw.id = ?
                        """, (rs, n) -> Map.of("content", rs.getString(1), "title", rs.getString(2)), task.runId(), sourceId,
                        source.path("source_snapshot_id").asText(), observation.path("source_window_id").asText());
                if (windows.size() != 1 || !title.equals(windows.get(0).get("title")) || !String.valueOf(windows.get(0).get("content")).contains(snippet)) throw invalid();
                domain = "workspace-source:" + sourceId;
                lineage = hash(source.path("source_snapshot_id").asText());
            } else {
                if (!policy.path("allow_external_search").asBoolean() || "SOURCES_ONLY".equals(policy.path("retrieval_mode").asText()) || url.length() > 2048) throw invalid();
                try {
                    URI uri = URI.create(url);
                    if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(java.util.Locale.ROOT)) || uri.getHost() == null || uri.getUserInfo() != null
                            || ResearchExternalSnapshotArchiveService.nonPublicLiteralHost(uri.getHost())) throw invalid();
                    domain = ResearchSourceIndependence.registeredDomain(uri.getHost());
                    JsonNode allowed = policy.path("allowed_external_domains");
                    if (allowed.isArray() && !allowed.isEmpty()) {
                        boolean matched = false;
                        for (JsonNode entry : allowed) matched |= uri.getHost().equalsIgnoreCase(entry.asText()) || uri.getHost().toLowerCase(java.util.Locale.ROOT).endsWith("." + entry.asText().toLowerCase(java.util.Locale.ROOT));
                        if (!matched) throw invalid();
                    }
                } catch (IllegalArgumentException exception) { throw invalid(); }
                lineage = hash(snippet);
            }
            resultMap.put(key, new Observation(key, domain, lineage, snippet));
            jdbc.update("insert into research_discovery_observation(id, research_run_id, task_id, observation_key, query_text, source_domain, lineage_digest, payload_json) values (?, ?, ?, ?, ?, ?, ?, ?)",
                    Ids.newId(), task.runId(), task.id(), key, query, domain, lineage, Json.write(mapper, observation));
        }
        return resultMap;
    }

    Score score(Map<String, Object> proposal, Map<String, Observation> observations, JsonNode authoritative) {
        JsonNode supports = mapper.valueToTree(proposal.get("supports"));
        if (!supports.isArray() || supports.isEmpty() || supports.size() > 8) throw invalid();
        List<ResearchSourceIndependence.Source> sources = new ArrayList<>();
        Set<String> keys = new HashSet<>(), authority = new HashSet<>(), matched = new HashSet<>();
        authoritative.forEach(domain -> authority.add(ResearchSourceIndependence.registeredDomain(domain.asText())));
        for (JsonNode support : supports) {
            if (!fields(support).equals(Set.of("observation_key", "quote_text"))) throw invalid();
            String key = text(support.path("observation_key"), 64), quote = text(support.path("quote_text"), 3000);
            Observation observation = observations.get(key);
            if (observation == null || !keys.add(key) || !observation.snippet().contains(quote)) throw invalid();
            sources.add(new ResearchSourceIndependence.Source(observation.domain(), observation.lineage(), observation.snippet(), 0));
            if (authority.contains(observation.domain())) matched.add(observation.domain());
        }
        int count = ResearchSourceIndependence.score(sources).independentSources();
        int weight = count + Math.min(count, matched.size());
        return new Score(count, Math.min(950000, weight * 1000000 / (weight + 1)));
    }
    JsonNode read(String value) {
        try { JsonNode node = mapper.readTree(value); return node.isTextual() ? mapper.readTree(node.asText()) : node; }
        catch (Exception exception) { throw invalid(); }
    }
    private static Set<String> fields(JsonNode node) { Set<String> fields = new HashSet<>(); node.fieldNames().forEachRemaining(fields::add); return fields; }
    private static String text(JsonNode node, int cap) { if (!node.isTextual() || node.asText().isBlank() || node.asText().length() > cap) throw invalid(); return node.asText(); }
    private static String hash(String text) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private static BusinessException invalid() { return new BusinessException("RESEARCH_DISCOVERY_EVIDENCE_INVALID", "宽搜索片段、来源或逐字支撑引用不符合冻结任务"); }
    record Observation(String key, String domain, String lineage, String snippet) { }
    record Score(int domains, int confidence) { }
}

package com.noteweave.research;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * First MVC boundary for the three retired split-result writes.  It runs
 * before request-body conversion and Bean Validation, so a real MA4G task
 * cannot reach an old write primitive even when the remainder of its request
 * is incomplete or malformed.
 */
@Component
final class ResearchAgentLegacyResultRouteGuardInterceptor implements HandlerInterceptor {

    private static final String EVIDENCE_ROUTE = "/internal/research-agent/workspace-evidence-batches";
    private static final String CANDIDATE_ROUTE = "/internal/research-agent/candidate-batches";
    private static final String SUBMIT_ROUTE_PREFIX = "/internal/research-agent-tasks/";
    private static final String SUBMIT_ROUTE_SUFFIX = "/submit";
    private static final String LEGACY_ROUTE_METRIC = "research.agent.legacy.result.route.total";
    private static final String SNAPSHOT_SCHEMA = "research-agent-task-snapshot.v1";
    private static final Pattern CANONICAL_TASK_ID = Pattern.compile(
            "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");
    private static final Pattern RAW_TASK_ID = Pattern.compile(
            "\"task_id\"[\\t\\n\\r ]*:[\\t\\n\\r ]*\"([0-9A-Fa-f-]{36})\"");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    ResearchAgentLegacyResultRouteGuardInterceptor(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return true;

        String path = routePath(request);
        LegacyRoute route = legacyRoute(path);
        if (route == null) return true;
        BodyTaskIdentity bodyIdentity = Set.of(EVIDENCE_ROUTE, CANDIDATE_ROUTE).contains(path)
                ? extractBodyTaskIdentity(request)
                : BodyTaskIdentity.empty();
        Set<String> taskIds = bodyIdentity.taskIds().isEmpty()
                ? pathTaskIds(extractPathTaskId(request))
                : bodyIdentity.taskIds();
        Set<TaskSchema> schemas = new LinkedHashSet<>();
        for (String taskId : taskIds) {
            schemas.add(taskSchema(taskId));
        }
        if (schemas.contains(TaskSchema.ATOMIC_V1)) {
            recordRoute(route, TaskSchema.ATOMIC_V1);
            throw atomicCompletionRequired();
        }
        TaskSchema observedSchema = schemas.contains(TaskSchema.LEGACY_NON_SNAPSHOT)
                ? TaskSchema.LEGACY_NON_SNAPSHOT
                : schemas.contains(TaskSchema.UNKNOWN) ? TaskSchema.UNKNOWN : TaskSchema.MISSING_OR_INVALID;
        recordRoute(route, observedSchema);
        throw atomicCompletionRequired();
    }

    private BodyTaskIdentity extractBodyTaskIdentity(HttpServletRequest request) {
        Object cached = request.getAttribute(ResearchAgentLegacyResultRawBodyFilter.RAW_BODY_ATTRIBUTE);
        if (!(cached instanceof byte[] raw) || raw.length == 0) return BodyTaskIdentity.empty();
        Set<String> taskIds = new LinkedHashSet<>();
        boolean taskIdSeen = false;
        try (JsonParser parser = objectMapper.getFactory().createParser(raw)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return malformedIdentity(raw, taskIds);
            JsonToken token;
            while ((token = parser.nextToken()) != null && token != JsonToken.END_OBJECT) {
                if (token != JsonToken.FIELD_NAME) return malformedIdentity(raw, taskIds);
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (value == null) return malformedIdentity(raw, taskIds);
                if ("task_id".equals(field)) {
                    boolean duplicate = taskIdSeen;
                    taskIdSeen = true;
                    if (value == JsonToken.VALUE_STRING) {
                        String taskId = canonicalTaskId(parser.getValueAsString());
                        if (taskId != null) taskIds.add(taskId);
                    }
                    if (duplicate) return new BodyTaskIdentity(Set.copyOf(taskIds), true);
                }
                parser.skipChildren();
            }
            return new BodyTaskIdentity(Set.copyOf(taskIds), false);
        } catch (IOException | RuntimeException ignored) {
            // For malformed JSON, a bounded ASCII scan can still recover a
            // canonical task UUID without treating the remainder as valid.
            return malformedIdentity(raw, taskIds);
        }
    }

    private BodyTaskIdentity malformedIdentity(byte[] raw, Set<String> parserTaskIds) {
        BodyTaskIdentity lexical = extractMalformedBodyTaskIdentity(raw);
        return lexical.taskIds().isEmpty() && !lexical.duplicateTaskId()
                ? new BodyTaskIdentity(Set.copyOf(parserTaskIds), false)
                : lexical;
    }

    private BodyTaskIdentity extractMalformedBodyTaskIdentity(byte[] raw) {
        java.util.regex.Matcher matcher = RAW_TASK_ID.matcher(new String(raw, StandardCharsets.ISO_8859_1));
        Set<String> taskIds = new LinkedHashSet<>();
        int matches = 0;
        while (matcher.find()) {
            matches++;
            String taskId = canonicalTaskId(matcher.group(1));
            if (taskId != null) taskIds.add(taskId);
            if (matches == 2) break;
        }
        return new BodyTaskIdentity(Set.copyOf(taskIds), matches > 1);
    }

    private Set<String> pathTaskIds(String taskId) {
        return taskId == null ? Set.of() : Set.of(taskId);
    }

    private String extractPathTaskId(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables instanceof Map<?, ?> map) {
            Object taskId = map.get("taskId");
            if (taskId instanceof String value) return canonicalTaskId(value);
        }
        String path = routePath(request);
        if (!path.startsWith(SUBMIT_ROUTE_PREFIX) || !path.endsWith(SUBMIT_ROUTE_SUFFIX)) return null;
        return canonicalTaskId(path.substring(
                SUBMIT_ROUTE_PREFIX.length(), path.length() - SUBMIT_ROUTE_SUFFIX.length()));
    }

    private String routePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context)
                ? uri.substring(context.length())
                : uri;
    }

    private String canonicalTaskId(String value) {
        return value != null && CANONICAL_TASK_ID.matcher(value).matches()
                ? value.toLowerCase(Locale.ROOT)
                : null;
    }

    private TaskSchema taskSchema(String taskId) {
        if (taskId == null) return TaskSchema.MISSING_OR_INVALID;
        List<TaskSchema> schemas = jdbcTemplate.query("""
                select rat.role, rat.snapshot_schema_version, rr.agent_execution_mode
                from research_agent_task rat
                join research_run rr on rr.id = rat.research_run_id
                where rat.id = ?
                """, (rs, rowNum) -> "DEEP_CELL".equals(rs.getString("role"))
                        && SNAPSHOT_SCHEMA.equals(rs.getString("snapshot_schema_version"))
                        && "INCREMENTAL_V1".equals(rs.getString("agent_execution_mode"))
                        ? TaskSchema.ATOMIC_V1 : TaskSchema.LEGACY_NON_SNAPSHOT,
                taskId);
        return schemas.isEmpty() ? TaskSchema.UNKNOWN : schemas.get(0);
    }

    private LegacyRoute legacyRoute(String path) {
        if (EVIDENCE_ROUTE.equals(path)) return LegacyRoute.EVIDENCE;
        if (CANDIDATE_ROUTE.equals(path)) return LegacyRoute.CANDIDATE;
        if (path.startsWith(SUBMIT_ROUTE_PREFIX) && path.endsWith(SUBMIT_ROUTE_SUFFIX)) {
            return LegacyRoute.SUBMIT;
        }
        return null;
    }

    private void recordRoute(LegacyRoute route, TaskSchema taskSchema) {
        Counter.builder(LEGACY_ROUTE_METRIC)
                .tag("route", route.tagValue)
                .tag("task_schema", taskSchema.tagValue)
                .register(meterRegistry)
                .increment();
    }

    private BusinessException atomicCompletionRequired() {
        return new BusinessException("RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED",
                "Legacy split-result routes are retired; use the atomic completion endpoint");
    }

    private record BodyTaskIdentity(Set<String> taskIds, boolean duplicateTaskId) {
        private BodyTaskIdentity {
            taskIds = Set.copyOf(taskIds);
        }

        private static BodyTaskIdentity empty() {
            return new BodyTaskIdentity(Set.of(), false);
        }
    }

    private enum LegacyRoute {
        EVIDENCE("evidence"),
        CANDIDATE("candidate"),
        SUBMIT("submit");

        private final String tagValue;

        LegacyRoute(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    private enum TaskSchema {
        ATOMIC_V1("atomic_v1"),
        LEGACY_NON_SNAPSHOT("legacy_non_snapshot"),
        UNKNOWN("unknown"),
        MISSING_OR_INVALID("missing_or_invalid");

        private final String tagValue;

        TaskSchema(String tagValue) {
            this.tagValue = tagValue;
        }
    }
}

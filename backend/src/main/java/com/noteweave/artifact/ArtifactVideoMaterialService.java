package com.noteweave.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable subtitle-stage material; visual files require a separate publication gate. */
@Service
public class ArtifactVideoMaterialService {
    private static final Pattern BVID = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private final ArtifactJobReadRepository jobs;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ArtifactVideoMaterialService(ArtifactJobReadRepository jobs, JdbcTemplate jdbc,
                                        ObjectMapper mapper) {
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Receipt submit(String taskId, Submission submission) {
        ArtifactJobTaskRow run = jobs.findByTaskId(taskId);
        if (!"bilibili_course_note_pdf".equals(run.skillKey())) {
            throw invalid("only the Bilibili PDF Skill accepts video material");
        }
        if (submission == null || submission.bundle() == null) throw invalid("video material body is missing");
        Map<String, Object> bundle = submission.bundle();
        validateBundle(run, bundle);
        String digest = digest(bundle);
        if (!digest.equals(submission.contentDigest())) {
            throw invalid("video material digest does not match its frozen content");
        }
        jdbc.queryForObject("select id from artifact_job where id = ? for update",
                String.class, run.artifactJobId());
        List<Receipt> previous = jdbc.query("""
                select id, bundle_id, bundle_version, content_digest, workspace_id, task_id
                from artifact_video_material_bundle where task_id = ? and bundle_version = 1
                """, (rs, index) -> new Receipt(rs.getString("id"), rs.getString("bundle_id"),
                rs.getInt("bundle_version"), rs.getString("content_digest"),
                rs.getString("workspace_id"), rs.getString("task_id")), taskId);
        if (!previous.isEmpty()) {
            if (previous.get(0).contentDigest().equals(digest)) return previous.get(0);
            throw new BusinessException("VIDEO_MATERIAL_CONFLICT",
                    "同一任务已冻结不同的素材包", HttpStatus.CONFLICT);
        }
        String id = Ids.newId();
        String bundleId = string(bundle.get("bundle_id"));
        jdbc.update("""
                insert into artifact_video_material_bundle(
                    id, workspace_id, artifact_job_id, task_id, bundle_id, bundle_version,
                    bvid, part_no, input_digest, content_digest, material_json
                ) values (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?)
                """, id, run.workspaceId(), run.artifactJobId(), taskId, bundleId,
                string(bundle.get("bvid")), number(bundle.get("part")),
                string(bundle.get("input_digest")), digest, json(bundle));
        return new Receipt(id, bundleId, 1, digest, run.workspaceId(), taskId);
    }

    public Map<String, Object> read(String taskId) {
        jobs.findByTaskId(taskId);
        return jdbc.query("""
                select material_json from artifact_video_material_bundle
                where task_id = ? and bundle_version = 1
                """, rs -> {
            if (!rs.next()) throw new BusinessException("VIDEO_MATERIAL_NOT_FOUND",
                    "素材包尚未冻结", HttpStatus.NOT_FOUND);
            try {
                return mapper.readValue(rs.getString("material_json"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception ex) {
                throw new IllegalStateException("stored video material is invalid", ex);
            }
        }, taskId);
    }

    private void validateBundle(ArtifactJobTaskRow run, Map<String, Object> bundle) {
        if (bundle == null || !bundle.keySet().equals(Set.of(
                "schema_version", "bundle_id", "bundle_version", "workspace_id", "bvid", "part",
                "duration_ms", "input_digest", "subtitle_source", "transcript_original",
                "transcript_corrected", "transcript_segments", "frames", "knowledge_nodes",
                "files", "coverage_gaps"))
                || !"video-material-v1".equals(bundle.get("schema_version"))
                || number(bundle.get("bundle_version")) != 1
                || !run.workspaceId().equals(bundle.get("workspace_id"))
                || string(bundle.get("bundle_id")).isBlank()
                || string(bundle.get("bundle_id")).length() > 120
                || !BVID.matcher(string(bundle.get("bvid"))).matches()
                || !SHA256.matcher(string(bundle.get("input_digest"))).matches()
                || number(bundle.get("part")) < 1 || number(bundle.get("part")) > 1_000
                || number(bundle.get("duration_ms")) < 1 || number(bundle.get("duration_ms")) > 86_400_000) {
            throw invalid("video material identity, scope, or duration is invalid");
        }
        Map<String, Object> inputs;
        try {
            inputs = mapper.readValue(run.inputsJson(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ex) {
            throw invalid("frozen video URL is unavailable");
        }
        Matcher source = Pattern.compile("https?://(?:www\\.)?bilibili\\.com/video/(BV[0-9A-Za-z]{10})")
                .matcher(string(inputs.get("url")));
        if (!source.find() || !source.group(1).equals(bundle.get("bvid"))) {
            throw invalid("video material belongs to another frozen URL");
        }
        String expectedInputDigest = hash(run.inputSnapshotId() + ":" + json(canonical(inputs)));
        if (!expectedInputDigest.equals(bundle.get("input_digest"))) {
            throw invalid("video material does not match the frozen input snapshot");
        }
        String url = string(inputs.get("url"));
        Matcher requestedPart = Pattern.compile("[?&]p=([0-9]+)(?:&|$)").matcher(url);
        int part;
        try { part = requestedPart.find() ? Integer.parseInt(requestedPart.group(1)) : 1; }
        catch (NumberFormatException ex) { throw invalid("video part is invalid"); }
        if (number(bundle.get("part")) != part) throw invalid("video material belongs to another part");
        if (!(bundle.get("frames") instanceof List<?> frames) || !frames.isEmpty()
                || !(bundle.get("files") instanceof List<?> files) || !files.isEmpty()
                || !(bundle.get("knowledge_nodes") instanceof List<?> nodes) || !nodes.isEmpty()
                || !(bundle.get("coverage_gaps") instanceof List<?> gaps)
                || !gaps.contains("NO_FRAMES")) {
            throw invalid("subtitle-stage material must declare its missing visual evidence");
        }
        String sourceType = string(bundle.get("subtitle_source"));
        if (!Set.of("MANUAL", "AI_CAPTION", "ASR", "NONE").contains(sourceType)
                || !(bundle.get("transcript_segments") instanceof List<?> rawSegments)) {
            throw invalid("video subtitle source or segments are invalid");
        }
        if ("NONE".equals(sourceType)) {
            if (!rawSegments.isEmpty() || !gaps.contains("NO_SUBTITLE")
                    || !string(bundle.get("transcript_original")).isBlank()
                    || !string(bundle.get("transcript_corrected")).isBlank()) {
                throw invalid("subtitle-free material must declare a gap and contain no transcript");
            }
            return;
        }
        if (rawSegments.isEmpty() || gaps.contains("NO_SUBTITLE")) throw invalid("subtitle coverage contradicts cues");
        Set<String> ids = new HashSet<>();
        ArrayList<String> original = new ArrayList<>();
        ArrayList<String> corrected = new ArrayList<>();
        for (Object rawSegment : rawSegments) {
            if (!(rawSegment instanceof Map<?, ?> segment)
                    || !segment.keySet().equals(Set.of("segment_id", "part", "start_ms", "end_ms",
                            "original_text", "corrected_text"))
                    || !ids.add(string(segment.get("segment_id")))
                    || string(segment.get("segment_id")).isBlank()
                    || number(segment.get("part")) != part
                    || number(segment.get("start_ms")) < 0
                    || number(segment.get("end_ms")) <= number(segment.get("start_ms"))
                    || number(segment.get("end_ms")) > number(bundle.get("duration_ms"))
                    || string(segment.get("original_text")).isBlank()
                    || string(segment.get("corrected_text")).isBlank()) {
                throw invalid("subtitle cue is invalid or belongs to another part");
            }
            original.add(string(segment.get("original_text")));
            corrected.add(string(segment.get("corrected_text")));
        }
        if (!String.join("\n", original).equals(bundle.get("transcript_original"))
                || !String.join("\n", corrected).equals(bundle.get("transcript_corrected"))) {
            throw invalid("full transcript does not match its timed cues");
        }
    }

    private String digest(Map<String, Object> bundle) {
        return hash(json(canonical(bundle)));
    }

    private String hash(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw invalid("video material cannot be canonicalized");
        }
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> sorted = new TreeMap<>();
            raw.forEach((key, child) -> sorted.put(String.valueOf(key), canonical(child)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        return value;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw invalid("video material cannot be serialized"); }
    }

    private static int number(Object value) {
        return value instanceof Integer integer ? integer : -1;
    }

    private static String string(Object value) {
        return value instanceof String text ? text : "";
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("VIDEO_MATERIAL_INVALID", message, HttpStatus.CONFLICT);
    }

    public record Submission(Map<String, Object> bundle, String contentDigest) {}
    public record Receipt(String id, String bundleId, int bundleVersion, String contentDigest,
                          String workspaceId, String taskId) {}
}

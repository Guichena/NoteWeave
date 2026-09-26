package com.noteweave.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.imageio.ImageIO;

/** Immutable video material with separately stored and verified frame bytes. */
@Service
public class ArtifactVideoMaterialService {
    private static final Logger log = LoggerFactory.getLogger(ArtifactVideoMaterialService.class);
    private static final Pattern BVID = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private final ArtifactJobReadRepository jobs;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ArtifactWorkerExportClient workerFiles;
    private final ObjectStorage storage;
    private final String bucket;
    private String orphanScanCursor = "";

    public ArtifactVideoMaterialService(ArtifactJobReadRepository jobs, JdbcTemplate jdbc,
                                        ObjectMapper mapper, ArtifactWorkerExportClient workerFiles,
                                        ObjectStorage storage, NoteWeaveProperties properties) {
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.workerFiles = workerFiles;
        this.storage = storage;
        this.bucket = properties.storage().minio().bucketExport();
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
        storeFrameFiles(taskId, id, bundle);
        return new Receipt(id, bundleId, 1, digest, run.workspaceId(), taskId);
    }

    /** Never expose Worker paths; a file must belong to the frozen bundle for this task. */
    public MaterialBytes readFile(String taskId, String fileId) {
        jobs.findByTaskId(taskId);
        List<MaterialBytes> rows = jdbc.query("""
                select f.media_type, f.bucket_name, f.object_key, f.checksum_sha256, f.size_bytes
                from artifact_video_material_file f
                join artifact_video_material_bundle b on b.id = f.bundle_id
                where b.task_id = ? and b.bundle_version = 1 and f.file_id = ?
                """, (rs, index) -> {
            byte[] bytes = storage.read(rs.getString("bucket_name"), rs.getString("object_key"));
            if (bytes.length != rs.getLong("size_bytes")
                    || !hash(bytes).equals(rs.getString("checksum_sha256"))) {
                throw new BusinessException("VIDEO_MATERIAL_FILE_DEGRADED",
                        "素材文件摘要不匹配", HttpStatus.CONFLICT);
            }
            return new MaterialBytes(rs.getString("media_type"), bytes);
        }, taskId, fileId);
        if (rows.size() != 1) throw new BusinessException("VIDEO_MATERIAL_FILE_NOT_FOUND",
                "素材文件不存在", HttpStatus.NOT_FOUND);
        return rows.get(0);
    }

    /** Current Run may consume exactly the Bundle ID frozen in its own input snapshot. */
    public Map<String, Object> readReferenced(String taskId, String bundleRowId) {
        resolveReferenced(taskId, bundleRowId);
        String material = jdbc.queryForObject("""
                select material_json from artifact_video_material_bundle where id = ?
                """, String.class, bundleRowId);
        try {
            return mapper.readValue(material, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("stored referenced video material is invalid", ex);
        }
    }

    public MaterialBytes readReferencedFile(String taskId, String bundleRowId, String fileId) {
        resolveReferenced(taskId, bundleRowId);
        List<MaterialBytes> rows = jdbc.query("""
                select media_type, bucket_name, object_key, checksum_sha256, size_bytes
                from artifact_video_material_file where bundle_id = ? and file_id = ?
                """, (rs, index) -> {
            byte[] bytes = storage.read(rs.getString("bucket_name"), rs.getString("object_key"));
            if (bytes.length != rs.getLong("size_bytes")
                    || !hash(bytes).equals(rs.getString("checksum_sha256"))) {
                throw new BusinessException("VIDEO_MATERIAL_FILE_DEGRADED",
                        "素材文件摘要不匹配", HttpStatus.CONFLICT);
            }
            return new MaterialBytes(rs.getString("media_type"), bytes);
        }, bundleRowId, fileId);
        if (rows.size() != 1) throw new BusinessException("VIDEO_MATERIAL_FILE_NOT_FOUND",
                "素材文件不存在", HttpStatus.NOT_FOUND);
        return rows.get(0);
    }

    private Receipt resolveReferenced(String taskId, String bundleRowId) {
        ArtifactJobTaskRow run = jobs.findByTaskId(taskId);
        Map<String, Object> inputs;
        try {
            inputs = mapper.readValue(run.inputsJson(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ex) {
            throw scopeInvalid();
        }
        if (!"bilibili_course_note_pdf".equals(run.skillKey())
                || bundleRowId == null || bundleRowId.isBlank()
                || !bundleRowId.equals(inputs.get("video_material_bundle_id"))) throw scopeInvalid();
        List<Receipt> rows = jdbc.query("""
                select id, bundle_id, bundle_version, content_digest, workspace_id, task_id,
                    bvid, part_no
                from artifact_video_material_bundle where id = ? and bundle_version = 1
                """, (rs, index) -> {
            if (!run.workspaceId().equals(rs.getString("workspace_id"))) throw scopeInvalid();
            ArtifactJobTaskRow parentRun = jobs.findByTaskId(rs.getString("task_id"));
            Map<String, Object> parentInputs;
            try {
                parentInputs = mapper.readValue(parentRun.inputsJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<>() {});
            } catch (Exception ex) {
                throw scopeInvalid();
            }
            if (!acquisitionInputs(parentInputs).equals(acquisitionInputs(inputs))) throw scopeInvalid();
            String url = string(inputs.get("url"));
            Matcher video = Pattern.compile("^https?://(?:www\\.)?bilibili\\.com/video/"
                    + "(BV[0-9A-Za-z]{10})(?:\\?[^#]*)?$").matcher(url);
            if (!video.matches() || !video.group(1).equals(rs.getString("bvid"))) throw scopeInvalid();
            Matcher requestedPart = Pattern.compile("[?&]p=([0-9]+)(?:&|$)").matcher(url);
            int part;
            try { part = requestedPart.find() ? Integer.parseInt(requestedPart.group(1)) : 1; }
            catch (NumberFormatException ex) { throw scopeInvalid(); }
            if (part != rs.getInt("part_no")) throw scopeInvalid();
            return new Receipt(rs.getString("id"), rs.getString("bundle_id"),
                    rs.getInt("bundle_version"), rs.getString("content_digest"),
                    rs.getString("workspace_id"), rs.getString("task_id"));
        }, bundleRowId);
        if (rows.size() != 1) throw scopeInvalid();
        return rows.get(0);
    }

    private static BusinessException scopeInvalid() {
        return new BusinessException("VIDEO_MATERIAL_SCOPE_INVALID",
                "素材包不属于本次冻结输入和 Workspace", HttpStatus.CONFLICT);
    }

    private static Map<String, Object> acquisitionInputs(Map<String, Object> inputs) {
        Map<String, Object> acquisition = new TreeMap<>(inputs);
        acquisition.remove("video_material_bundle_id");
        return acquisition;
    }

    /** Reconcile process crashes after object write and before the Bundle transaction commits. */
    @Scheduled(fixedDelayString = "${noteweave.artifact.video-material-cleanup-delay-ms:3600000}")
    public synchronized int cleanupOrphanedFrameFiles() {
        String prefix = "artifacts/video-material/";
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        int removed = 0;
        int visited = 0;
        while (visited < 1_000) {
            List<ObjectStorage.StoredObject> page = storage.list(bucket, prefix, orphanScanCursor, 100);
            if (page.isEmpty()) {
                orphanScanCursor = "";
                break;
            }
            for (ObjectStorage.StoredObject object : page) {
                orphanScanCursor = object.key();
                visited++;
                if (!object.key().startsWith(prefix) || object.lastModified() == null
                        || !object.lastModified().isBefore(cutoff)) continue;
                Integer references = jdbc.queryForObject("""
                        select count(*) from artifact_video_material_file
                        where bucket_name = ? and object_key = ?
                        """, Integer.class, bucket, object.key());
                if (references != null && references > 0) continue;
                try {
                    storage.delete(bucket, object.key());
                    removed++;
                } catch (RuntimeException ex) {
                    log.warn("Video material orphan cleanup failed; key={}", object.key(), ex);
                }
            }
            if (page.size() < 100) {
                orphanScanCursor = "";
                break;
            }
        }
        return removed;
    }

    private void storeFrameFiles(String taskId, String bundleRowId, Map<String, Object> bundle) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> files =
                (List<Map<String, Object>>) bundle.get("files");
        for (Map<String, Object> file : files) {
            String fileId = string(file.get("file_id"));
            String mediaType = string(file.get("media_type"));
            String fileName = fileId + ("image/png".equals(mediaType) ? ".png" : ".jpg");
            byte[] bytes = workerFiles.fetch(taskId, fileName);
            if (bytes == null || bytes.length != number(file.get("size_bytes"))
                    || bytes.length > 16_000_000 || !hash(bytes).equals(file.get("checksum_sha256"))) {
                throw invalid("material frame bytes do not match the file manifest");
            }
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                if (input == null || !("image/png".equals(mediaType) ? png(bytes) : jpeg(bytes))) {
                    throw invalid("material frame is not a valid image");
                }
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw invalid("material frame is not decodable");
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    if ((long) reader.getWidth(0) * reader.getHeight(0) > 50_000_000
                            || reader.getWidth(0) < 1 || reader.getHeight(0) < 1
                            || !("image/png".equals(mediaType) ? "png" : "jpeg")
                                    .equalsIgnoreCase(reader.getFormatName())
                            || reader.read(0) == null) {
                        throw invalid("material frame is not a valid image");
                    }
                } finally {
                    reader.dispose();
                }
            } catch (java.io.IOException ex) {
                throw invalid("material frame is not decodable");
            }
            String objectKey = "artifacts/video-material/" + bundleRowId + "/" + fileName;
            storage.write(bucket, objectKey, bytes);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        try {
                            storage.delete(bucket, objectKey);
                        } catch (RuntimeException ex) {
                            log.warn("Video material rollback cleanup failed; key={}", objectKey, ex);
                        }
                    }
                }
            });
            if (!hash(storage.read(bucket, objectKey)).equals(file.get("checksum_sha256"))) {
                throw invalid("stored material frame digest differs from Worker bytes");
            }
            jdbc.update("""
                    insert into artifact_video_material_file(id, bundle_id, file_id, media_type,
                        storage_backend, bucket_name, object_key, size_bytes, checksum_sha256)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), bundleRowId, fileId, mediaType, storage.backendName(),
                    bucket, objectKey, bytes.length, string(file.get("checksum_sha256")));
        }
    }

    private static boolean png(byte[] bytes) {
        return bytes.length >= 8 && java.util.Arrays.equals(java.util.Arrays.copyOf(bytes, 8),
                new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
    }

    private static boolean jpeg(byte[] bytes) {
        return bytes.length >= 4 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216
                && (bytes[bytes.length - 2] & 255) == 255 && (bytes[bytes.length - 1] & 255) == 217;
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

    /** A published material must be cited by the Candidate that consumes it. */
    void validateCandidateReference(String taskId, Map<String, Object> resultPayload) {
        List<Receipt> frozen = jdbc.query("""
                select id, bundle_id, bundle_version, content_digest, workspace_id, task_id
                from artifact_video_material_bundle where task_id = ? and bundle_version = 1
                """, (rs, index) -> new Receipt(rs.getString("id"), rs.getString("bundle_id"),
                rs.getInt("bundle_version"), rs.getString("content_digest"),
                rs.getString("workspace_id"), rs.getString("task_id")), taskId);
        Object candidate = resultPayload == null ? null : resultPayload.get("candidate");
        Object rawReference = candidate instanceof Map<?, ?> value ? value.get("video_material") : null;
        ArtifactJobTaskRow run = jobs.findByTaskId(taskId);
        Map<String, Object> inputs;
        try {
            inputs = mapper.readValue(run.inputsJson(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ex) {
            throw scopeInvalid();
        }
        String referencedId = string(inputs.get("video_material_bundle_id"));
        if (frozen.isEmpty() && referencedId.isBlank() && rawReference == null) return;
        if ((!referencedId.isBlank() && !frozen.isEmpty())
                || (referencedId.isBlank() && frozen.size() != 1)
                || !(rawReference instanceof Map<?, ?> reference)
                || !reference.keySet().equals(Set.of("id", "bundle_id", "bundle_version", "content_digest"))) {
            throw new BusinessException("VIDEO_MATERIAL_REFERENCE_INVALID",
                    "Candidate must cite its frozen video material", HttpStatus.CONFLICT);
        }
        Receipt receipt = referencedId.isBlank() ? frozen.get(0) : resolveReferenced(taskId, referencedId);
        if (!receipt.id().equals(reference.get("id"))
                || !receipt.bundleId().equals(reference.get("bundle_id"))
                || receipt.bundleVersion() != number(reference.get("bundle_version"))
                || !receipt.contentDigest().equals(reference.get("content_digest"))) {
            throw new BusinessException("VIDEO_MATERIAL_REFERENCE_INVALID",
                    "Candidate video material reference does not match the frozen bundle", HttpStatus.CONFLICT);
        }
        if (!(resultPayload.get("content_ir") instanceof Map<?, ?>)
                || !(candidate instanceof Map<?, ?> bound)
                || !(bound.get("content_ir_digest") instanceof String)) {
            throw new BusinessException("ARTIFACT_CONTENT_IR_REQUIRED",
                    "Frozen video material requires typed content IR", HttpStatus.CONFLICT);
        }
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
        if (!(bundle.get("frames") instanceof List<?> frames)
                || !(bundle.get("files") instanceof List<?> files)
                || !(bundle.get("knowledge_nodes") instanceof List<?> nodes)
                || !(bundle.get("coverage_gaps") instanceof List<?> gaps)) throw invalid("material collections are invalid");
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
            validateVisuals(bundle, frames, files, nodes, gaps);
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
        validateVisuals(bundle, frames, files, nodes, gaps);
    }

    private void validateVisuals(Map<String, Object> bundle, List<?> frames, List<?> files,
                                 List<?> nodes, List<?> gaps) {
        if (files.size() > 32 || frames.size() > 32 || nodes.size() > 128
                || (frames.isEmpty() != gaps.contains("NO_FRAMES")))
            throw invalid("frame coverage or count is invalid");
        Map<String, Map<?, ?>> fileById = new java.util.HashMap<>();
        long totalBytes = 0;
        for (Object raw : files) {
            if (!(raw instanceof Map<?, ?> file)
                    || !file.keySet().equals(Set.of("file_id", "role", "media_type", "size_bytes", "checksum_sha256"))
                    || !string(file.get("file_id")).matches("[A-Za-z0-9_-]{1,100}")
                    || fileById.putIfAbsent(string(file.get("file_id")), file) != null
                    || !"VIDEO_FRAME".equals(file.get("role"))
                    || !Set.of("image/png", "image/jpeg").contains(file.get("media_type"))
                    || number(file.get("size_bytes")) < 1 || number(file.get("size_bytes")) > 16_000_000
                    || !SHA256.matcher(string(file.get("checksum_sha256"))).matches()) {
                throw invalid("material file manifest is invalid");
            }
            totalBytes += number(file.get("size_bytes"));
        }
        if (totalBytes > 100_000_000) throw invalid("material frame files exceed bundle limit");
        Map<String, Map<?, ?>> frameById = new java.util.HashMap<>();
        Set<String> usedFiles = new HashSet<>();
        for (Object raw : frames) {
            if (!(raw instanceof Map<?, ?> frame)
                    || !frame.keySet().equals(Set.of("frame_id", "part", "at_ms", "file_id", "checksum_sha256", "dedupe_of"))
                    || string(frame.get("frame_id")).isBlank()
                    || frameById.putIfAbsent(string(frame.get("frame_id")), frame) != null
                    || number(frame.get("part")) != number(bundle.get("part"))
                    || number(frame.get("at_ms")) < 0
                    || number(frame.get("at_ms")) >= number(bundle.get("duration_ms"))
                    || !fileById.containsKey(string(frame.get("file_id")))
                    || !fileById.get(string(frame.get("file_id"))).get("checksum_sha256")
                            .equals(frame.get("checksum_sha256"))) {
                throw invalid("material frame reference is invalid");
            }
            usedFiles.add(string(frame.get("file_id")));
        }
        if (usedFiles.size() != files.size()) throw invalid("unreferenced material file");
        for (Map<?, ?> frame : frameById.values()) {
            Set<String> visited = new HashSet<>();
            String parent = string(frame.get("dedupe_of"));
            while (!parent.isBlank()) {
                if (!visited.add(parent) || !frameById.containsKey(parent)
                        || parent.equals(frame.get("frame_id"))) throw invalid("frame dedupe reference is invalid");
                parent = string(frameById.get(parent).get("dedupe_of"));
            }
        }
        Map<String, Map<?, ?>> segments = new java.util.HashMap<>();
        for (Object raw : (List<?>) bundle.get("transcript_segments")) {
            Map<?, ?> segment = (Map<?, ?>) raw;
            segments.put(string(segment.get("segment_id")), segment);
        }
        Set<String> nodeIds = new HashSet<>();
        Set<String> nodeTitles = new HashSet<>();
        Set<String> assignedFrames = new HashSet<>();
        for (Object raw : nodes) {
            if (!(raw instanceof Map<?, ?> node)
                    || !node.keySet().equals(Set.of("node_id", "title", "start_ms", "end_ms",
                            "transcript_segment_ids", "frame_ids", "missing"))
                    || string(node.get("node_id")).isBlank()
                    || !nodeIds.add(string(node.get("node_id")))
                    || string(node.get("title")).isBlank()
                    || !nodeTitles.add(string(node.get("title")).trim().toLowerCase(java.util.Locale.ROOT))
                    || number(node.get("start_ms")) < 0
                    || number(node.get("end_ms")) <= number(node.get("start_ms"))
                    || number(node.get("end_ms")) > number(bundle.get("duration_ms"))
                    || !(node.get("transcript_segment_ids") instanceof List<?> segmentRefs)
                    || !(node.get("frame_ids") instanceof List<?> frameRefs)
                    || !(node.get("missing") instanceof List<?> missing)
                    || (segmentRefs.isEmpty() && frameRefs.isEmpty() && missing.isEmpty())) {
                throw invalid("knowledge node is invalid");
            }
            for (Object ref : segmentRefs) {
                Map<?, ?> segment = segments.get(ref);
                if (segment == null || number(segment.get("end_ms")) < number(node.get("start_ms"))
                        || number(segment.get("start_ms")) > number(node.get("end_ms"))) {
                    throw invalid("knowledge node subtitle reference is outside its time range");
                }
            }
            for (Object ref : frameRefs) {
                Map<?, ?> frame = frameById.get(ref);
                if (frame == null || number(frame.get("at_ms")) < number(node.get("start_ms"))
                        || number(frame.get("at_ms")) > number(node.get("end_ms"))
                        || !assignedFrames.add(string(ref))) {
                    throw invalid("knowledge node frame reference is outside its time range");
                }
            }
        }
        if (!assignedFrames.equals(frameById.keySet())) throw invalid("video frame has no knowledge node");
    }

    private String digest(Map<String, Object> bundle) {
        return hash(json(canonical(bundle)));
    }

    private String hash(String content) {
        return hash(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String hash(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content));
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
    public record MaterialBytes(String mediaType, byte[] bytes) {}
    public record Receipt(String id, String bundleId, int bundleVersion, String contentDigest,
                          String workspaceId, String taskId) {}
}

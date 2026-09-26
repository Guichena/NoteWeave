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
        validateBundle(run.workspaceId(), run.inputSnapshotId(), run.taskId(),
                run.inputsJson(), bundle);
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

    /** Material-only parent origin; no placeholder PDF Job is created. */
    @Transactional
    public Receipt submitParentMaterial(String taskId, Submission submission) {
        ParentTask parent = requireParentTask(taskId);
        if (submission == null || submission.bundle() == null) {
            throw invalid("video material body is missing");
        }
        Map<String, Object> inputs = parent.inputs();
        Map<String, Object> bundle = submission.bundle();
        validateBundle(parent.workspaceId(), parent.requestId(), taskId, json(inputs), bundle);
        String digest = digest(bundle);
        if (!digest.equals(submission.contentDigest())) {
            throw invalid("video material digest does not match its frozen content");
        }
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
                    id, workspace_id, video_learning_request_id, task_id,
                    bundle_id, bundle_version, bvid, part_no, input_digest,
                    content_digest, material_json
                ) values (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?)
                """, id, parent.workspaceId(), parent.requestId(), taskId,
                bundleId, string(bundle.get("bvid")), number(bundle.get("part")),
                string(bundle.get("input_digest")), digest, json(bundle));
        storeFrameFiles(taskId, id, bundle);
        return new Receipt(id, bundleId, 1, digest, parent.workspaceId(), taskId);
    }

    private ParentTask requireParentTask(String taskId) {
        List<ParentTask> parents = jdbc.query("""
                select r.id, r.workspace_id, r.video_url, r.part_no, r.language,
                       r.frame_density, r.asr_fallback
                from video_learning_request r
                join task t on t.id = r.material_task_id
                join workspace w on w.id = r.workspace_id and w.status = 'ACTIVE'
                join users u on u.id = r.actor_user_id and u.status = 'ACTIVE'
                join workspace_member m on m.workspace_id = r.workspace_id
                    and m.user_id = r.actor_user_id and m.status = 'ACTIVE'
                    and m.role in ('OWNER', 'EDITOR')
                where t.id = ? and t.workspace_id = r.workspace_id
                  and t.task_type = 'VIDEO_MATERIAL'
                  and t.target_type = 'VIDEO_LEARNING_REQUEST' and t.target_id = r.id
                  and t.task_status in ('RUNNING', 'WAITING')
                  and r.material_state in ('QUEUED', 'RUNNING')
                  and r.cancellation_requested = false
                for update
                """, (rs, index) -> new ParentTask(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6),
                rs.getString(7)), taskId);
        if (parents.size() != 1) throw scopeInvalid();
        return parents.get(0);
    }

    private record ParentTask(String requestId, String workspaceId, String url, int part,
                              String language, String frameDensity, String asrFallback) {
        Map<String, Object> inputs() {
            return Map.of("url", url, "part", String.valueOf(part), "language", language,
                    "frame_density", frameDensity, "asr_fallback", asrFallback);
        }
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
            byte[] bytes = readVerifiedFile(rs.getString("bucket_name"),
                    rs.getString("object_key"), rs.getLong("size_bytes"),
                    rs.getString("checksum_sha256"));
            return new MaterialBytes(rs.getString("media_type"), bytes);
        }, taskId, fileId);
        if (rows.size() != 1) throw new BusinessException("VIDEO_MATERIAL_FILE_NOT_FOUND",
                "素材文件不存在", HttpStatus.NOT_FOUND);
        return rows.get(0);
    }

    /** Current Run may consume exactly the Bundle ID frozen in its own input snapshot. */
    public Map<String, Object> readReferenced(String taskId, String bundleRowId) {
        resolveReferenced(taskId, bundleRowId);
        return readBundleById(bundleRowId);
    }

    /** A semantic plan is immutable and bound to one already-frozen Bundle digest. */
    @Transactional
    public KnowledgeReceipt submitKnowledgePlan(String taskId, KnowledgeSubmission submission) {
        ArtifactJobTaskRow run = jobs.findByTaskId(taskId);
        if (!"bilibili_course_note_pdf".equals(run.skillKey())
                || submission == null || submission.plan() == null) {
            throw invalid("knowledge plan source task or body is invalid");
        }
        List<Receipt> bundles = jdbc.query("""
                select id, bundle_id, bundle_version, content_digest, workspace_id, task_id
                from artifact_video_material_bundle where task_id = ? and bundle_version = 1
                """, (rs, index) -> new Receipt(rs.getString("id"), rs.getString("bundle_id"),
                rs.getInt("bundle_version"), rs.getString("content_digest"),
                rs.getString("workspace_id"), rs.getString("task_id")), taskId);
        if (bundles.size() != 1 || !bundles.get(0).id().equals(submission.bundleRowId())) {
            throw invalid("knowledge plan has no frozen source Bundle");
        }
        Receipt source = bundles.get(0);
        Map<String, Object> bundle = read(taskId);
        VideoKnowledgePlanValidator.validate(submission.plan(), bundle, source.contentDigest());
        String planDigest = digest(submission.plan());
        if (!planDigest.equals(submission.contentDigest())) {
            throw invalid("knowledge plan digest does not match its content");
        }
        jdbc.queryForObject("select id from artifact_job where id = ? for update",
                String.class, run.artifactJobId());
        List<KnowledgeReceipt> previous = jdbc.query("""
                select id, bundle_id, content_digest from artifact_video_knowledge_plan
                where bundle_id = ?
                """, (rs, index) -> new KnowledgeReceipt(rs.getString("id"),
                rs.getString("bundle_id"), rs.getString("content_digest")), source.id());
        if (!previous.isEmpty()) {
            if (previous.get(0).contentDigest().equals(planDigest)) return previous.get(0);
            throw new BusinessException("VIDEO_KNOWLEDGE_PLAN_CONFLICT",
                    "同一素材包已冻结不同的知识规划", HttpStatus.CONFLICT);
        }
        String id = Ids.newId();
        jdbc.update("""
                insert into artifact_video_knowledge_plan(id, bundle_id, content_digest, plan_json)
                values (?, ?, ?, ?)
                """, id, source.id(), planDigest, json(submission.plan()));
        return new KnowledgeReceipt(id, source.id(), planDigest);
    }

    @Transactional
    public KnowledgeReceipt submitParentKnowledgePlan(String taskId, KnowledgeSubmission submission) {
        ParentTask parent = requireParentTask(taskId);
        if (submission == null || submission.plan() == null) {
            throw invalid("knowledge plan body is missing");
        }
        List<Receipt> bundles = jdbc.query("""
                select id, bundle_id, bundle_version, content_digest, workspace_id, task_id
                from artifact_video_material_bundle
                where task_id = ? and video_learning_request_id = ? and bundle_version = 1
                """, (rs, index) -> new Receipt(rs.getString("id"), rs.getString("bundle_id"),
                rs.getInt("bundle_version"), rs.getString("content_digest"),
                rs.getString("workspace_id"), rs.getString("task_id")), taskId, parent.requestId());
        if (bundles.size() != 1 || !bundles.get(0).id().equals(submission.bundleRowId())) {
            throw invalid("knowledge plan has no frozen parent Bundle");
        }
        Receipt source = bundles.get(0);
        Map<String, Object> bundle = readBundleById(source.id());
        VideoKnowledgePlanValidator.validate(submission.plan(), bundle, source.contentDigest());
        String planDigest = digest(submission.plan());
        if (!planDigest.equals(submission.contentDigest())) {
            throw invalid("knowledge plan digest does not match its content");
        }
        List<KnowledgeReceipt> previous = jdbc.query("""
                select id, bundle_id, content_digest from artifact_video_knowledge_plan
                where bundle_id = ?
                """, (rs, index) -> new KnowledgeReceipt(rs.getString("id"),
                rs.getString("bundle_id"), rs.getString("content_digest")), source.id());
        if (!previous.isEmpty()) {
            if (previous.get(0).contentDigest().equals(planDigest)) return previous.get(0);
            throw new BusinessException("VIDEO_KNOWLEDGE_PLAN_CONFLICT",
                    "同一素材包已冻结不同的知识规划", HttpStatus.CONFLICT);
        }
        String id = Ids.newId();
        jdbc.update("""
                insert into artifact_video_knowledge_plan(id, bundle_id, content_digest, plan_json)
                values (?, ?, ?, ?)
                """, id, source.id(), planDigest, json(submission.plan()));
        return new KnowledgeReceipt(id, source.id(), planDigest);
    }

    public Map<String, Object> readKnowledgePlan(String taskId, String bundleRowId) {
        jobs.findByTaskId(taskId);
        List<String> sources = jdbc.query("""
                select id from artifact_video_material_bundle
                where task_id = ? and bundle_version = 1 and id = ?
                """, (rs, index) -> rs.getString("id"), taskId, bundleRowId);
        if (sources.size() != 1) throw scopeInvalid();
        return readPlanByBundleId(bundleRowId);
    }

    public Map<String, Object> readReferencedKnowledgePlan(String taskId, String bundleRowId) {
        resolveReferenced(taskId, bundleRowId);
        return readPlanByBundleId(bundleRowId);
    }

    private Map<String, Object> readPlanByBundleId(String bundleRowId) {
        List<Map<String, String>> plans = jdbc.query("""
                select plan_json, content_digest from artifact_video_knowledge_plan where bundle_id = ?
                """, (rs, index) -> Map.of("json", rs.getString("plan_json"),
                        "digest", rs.getString("content_digest")), bundleRowId);
        if (plans.size() != 1) throw new BusinessException("VIDEO_KNOWLEDGE_PLAN_NOT_FOUND",
                "知识规划尚未冻结", HttpStatus.NOT_FOUND);
        try {
            Map<String, Object> plan = mapper.readValue(plans.get(0).get("json"),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            if (!digest(plan).equals(plans.get(0).get("digest"))) {
                throw new BusinessException("VIDEO_KNOWLEDGE_PLAN_DEGRADED",
                        "知识规划摘要不匹配", HttpStatus.CONFLICT);
            }
            return plan;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("stored knowledge plan is invalid", ex);
        }
    }

    /** Parent READY gate: a Plan and Bundle must match the frozen acquisition identity. */
    ParentMaterialIdentity requireParentMaterial(String workspaceId, String bundleRowId, String planId,
                               String requestId, String materialTaskId, String videoUrl, int part,
                               String frameDensity, String asrFallback) {
        List<ParentMaterialRow> rows = jdbc.query("""
                select b.bvid, b.part_no, b.content_digest, p.content_digest,
                       r.inputs_json, b.video_learning_request_id, b.task_id
                from artifact_video_material_bundle b
                join artifact_video_knowledge_plan p on p.bundle_id = b.id
                left join artifact_job_run r on r.task_id = b.task_id
                where b.id = ? and p.id = ? and b.workspace_id = ? and b.bundle_version = 1
                """, (rs, index) -> new ParentMaterialRow(rs.getString(1), rs.getInt(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7)), bundleRowId, planId, workspaceId);
        if (rows.size() != 1) throw scopeInvalid();
        ParentMaterialRow row = rows.get(0);
        if (materialTaskId != null && (!requestId.equals(row.parentRequestId())
                || !materialTaskId.equals(row.taskId()))) throw scopeInvalid();
        if (materialTaskId == null && row.parentRequestId() != null) throw scopeInvalid();
        if (materialTaskId != null) {
            List<String> completed = jdbc.query("""
                    select id from task where id = ? and workspace_id = ?
                      and task_type = 'VIDEO_MATERIAL' and task_status = 'COMPLETED'
                    """, (rs, index) -> rs.getString(1), materialTaskId, workspaceId);
            if (completed.size() != 1) throw scopeInvalid();
        }
        Matcher video = Pattern.compile("^https://www\\.bilibili\\.com/video/"
                + "(BV[0-9A-Za-z]{10})(?:\\?[^#]*)?$").matcher(videoUrl);
        if (!video.matches() || !video.group(1).equals(row.bvid()) || part != row.part()) {
            throw scopeInvalid();
        }
        try {
            if (row.inputsJson() == null) {
                if (materialTaskId == null) throw scopeInvalid();
            } else {
            Map<String, Object> original = mapper.readValue(row.inputsJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            if (!frameDensity.equals(string(original.getOrDefault("frame_density", "STANDARD")))
                    || !asrFallback.equals(string(original.getOrDefault("asr_fallback", "ALLOW")))) {
                throw scopeInvalid();
            }
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw scopeInvalid();
        }
        Map<String, Object> bundle = readBundleById(bundleRowId);
        Map<String, Object> plan = readPlanByBundleId(bundleRowId);
        VideoKnowledgePlanValidator.validate(plan, bundle, row.contentDigest());
        return new ParentMaterialIdentity(row.contentDigest(), row.planDigest());
    }

    record ParentMaterialIdentity(String bundleDigest, String planDigest) {}
    private record ParentMaterialRow(String bvid, int part, String contentDigest, String planDigest,
                                     String inputsJson, String parentRequestId, String taskId) {}

    public MaterialBytes readReferencedFile(String taskId, String bundleRowId, String fileId) {
        resolveReferenced(taskId, bundleRowId);
        List<MaterialBytes> rows = jdbc.query("""
                select media_type, bucket_name, object_key, checksum_sha256, size_bytes
                from artifact_video_material_file where bundle_id = ? and file_id = ?
                """, (rs, index) -> {
            byte[] bytes = readVerifiedFile(rs.getString("bucket_name"),
                    rs.getString("object_key"), rs.getLong("size_bytes"),
                    rs.getString("checksum_sha256"));
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
        if (!Set.of("bilibili_course_note_pdf", "knowledge_blog", "interview_qa",
                "video_learning_deck")
                    .contains(run.skillKey())
                || bundleRowId == null || bundleRowId.isBlank()
                || !bundleRowId.equals(inputs.get("video_material_bundle_id"))) throw scopeInvalid();
        List<Receipt> rows = jdbc.query("""
                select id, bundle_id, bundle_version, content_digest, workspace_id, task_id,
                    bvid, part_no, video_learning_request_id
                from artifact_video_material_bundle where id = ? and bundle_version = 1
                """, (rs, index) -> {
            if (!run.workspaceId().equals(rs.getString("workspace_id"))) throw scopeInvalid();
            Map<String, Object> parentInputs;
            String parentRequestId = rs.getString("video_learning_request_id");
            if (parentRequestId == null) {
                ArtifactJobTaskRow parentRun = jobs.findByTaskId(rs.getString("task_id"));
                try {
                    parentInputs = mapper.readValue(parentRun.inputsJson(),
                            new com.fasterxml.jackson.core.type.TypeReference<>() {});
                } catch (Exception ex) {
                    throw scopeInvalid();
                }
            } else {
                List<ParentTask> parents = jdbc.query("""
                        select r.id, r.workspace_id, r.video_url, r.part_no, r.language,
                               r.frame_density, r.asr_fallback
                        from video_learning_request r
                        where r.id = ? and r.workspace_id = ? and r.material_task_id = ?
                          and r.material_bundle_id = ? and r.material_content_digest = ?
                          and r.material_state = 'READY'
                        """, (parentRs, parentIndex) -> new ParentTask(parentRs.getString(1),
                        parentRs.getString(2), parentRs.getString(3), parentRs.getInt(4),
                        parentRs.getString(5), parentRs.getString(6), parentRs.getString(7)),
                        parentRequestId, run.workspaceId(), rs.getString("task_id"),
                        bundleRowId, rs.getString("content_digest"));
                if (parents.size() != 1) throw scopeInvalid();
                parentInputs = parents.get(0).inputs();
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
        // Output language changes the rendered artifact, not the acquired transcript or frames.
        acquisition.remove("language");
        String url = string(acquisition.get("url"));
        Matcher partInUrl = Pattern.compile("[?&]p=([0-9]+)(?:&|$)").matcher(url);
        String effectivePart = partInUrl.find() ? partInUrl.group(1) : "1";
        if (effectivePart.equals(acquisition.get("part"))) acquisition.remove("part");
        // Earlier frozen Runs predate these explicit defaults; preserve their reuse identity.
        if ("STANDARD".equals(acquisition.get("frame_density"))) acquisition.remove("frame_density");
        if ("ALLOW".equals(acquisition.get("asr_fallback"))) acquisition.remove("asr_fallback");
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
        Map<String, Map<?, ?>> observations = new java.util.HashMap<>();
        if (bundle.get("frame_observations") instanceof List<?> observed) {
            for (Object raw : observed) {
                Map<?, ?> item = (Map<?, ?>) raw;
                observations.put(string(item.get("file_id")), item);
            }
        }
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
                    Map<?, ?> observation = observations.get(fileId);
                    if ((long) reader.getWidth(0) * reader.getHeight(0) > 50_000_000
                            || reader.getWidth(0) < 1 || reader.getHeight(0) < 1
                            || (observation != null && (reader.getWidth(0) != number(observation.get("width"))
                                    || reader.getHeight(0) != number(observation.get("height"))))
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
        List<String> ids = jdbc.query("""
                select id from artifact_video_material_bundle
                where task_id = ? and bundle_version = 1
                """, (rs, index) -> rs.getString("id"), taskId);
        if (ids.size() != 1) throw new BusinessException("VIDEO_MATERIAL_NOT_FOUND",
                "素材包尚未冻结", HttpStatus.NOT_FOUND);
        return readBundleById(ids.get(0));
    }

    private Map<String, Object> readBundleById(String bundleRowId) {
        List<Map<String, String>> rows = jdbc.query("""
                select material_json, content_digest from artifact_video_material_bundle where id = ?
                """, (rs, index) -> Map.of("json", rs.getString("material_json"),
                        "digest", rs.getString("content_digest")), bundleRowId);
        if (rows.size() != 1) throw new BusinessException("VIDEO_MATERIAL_NOT_FOUND",
                "素材包尚未冻结", HttpStatus.NOT_FOUND);
        try {
            Map<String, Object> bundle = mapper.readValue(rows.get(0).get("json"),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            if (!digest(bundle).equals(rows.get(0).get("digest"))) {
                throw new BusinessException("VIDEO_MATERIAL_DEGRADED",
                        "素材包摘要不匹配", HttpStatus.CONFLICT);
            }
            return bundle;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("stored video material is invalid", ex);
        }
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
        if (Set.of("knowledge_blog", "interview_qa").contains(run.skillKey())) {
            Map<String, Object> plan = readPlanByBundleId(receipt.id());
            VideoDerivedTextValidator.validate(run.skillKey(), resultPayload,
                    readBundleById(receipt.id()), plan, receipt.contentDigest(), digest(plan),
                    string(inputs.get("language")));
        } else if ("video_learning_deck".equals(run.skillKey())) {
            Map<String, Object> plan = readPlanByBundleId(receipt.id());
            VideoDeckValidator.validate(resultPayload, readBundleById(receipt.id()), plan,
                    receipt.contentDigest(), digest(plan), string(inputs.get("language")));
        }
    }

    private void validateBundle(String workspaceId, String inputSnapshotId, String taskId,
                                String inputsJson, Map<String, Object> bundle) {
        Set<String> legacyFields = Set.of(
                "schema_version", "bundle_id", "bundle_version", "workspace_id", "bvid", "part",
                "duration_ms", "input_digest", "subtitle_source", "transcript_original",
                "transcript_corrected", "transcript_segments", "frames", "knowledge_nodes",
                "files", "coverage_gaps");
        Set<String> fields = bundle == null ? Set.of() : bundle.keySet();
        if (bundle == null || !(fields.equals(legacyFields)
                || (fields.size() == legacyFields.size() + 1
                    && fields.containsAll(legacyFields) && fields.contains("frame_observations")))
                || !"video-material-v1".equals(bundle.get("schema_version"))
                || number(bundle.get("bundle_version")) != 1
                || !workspaceId.equals(bundle.get("workspace_id"))
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
            inputs = mapper.readValue(inputsJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ex) {
            throw invalid("frozen video URL is unavailable");
        }
        Matcher source = Pattern.compile("https?://(?:www\\.)?bilibili\\.com/video/(BV[0-9A-Za-z]{10})")
                .matcher(string(inputs.get("url")));
        if (!source.find() || !source.group(1).equals(bundle.get("bvid"))) {
            throw invalid("video material belongs to another frozen URL");
        }
        String expectedInputDigest = hash(inputSnapshotId + ":" + json(canonical(inputs)));
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
            validateVisuals(taskId, bundle, frames, files, nodes, gaps);
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
        validateVisuals(taskId, bundle, frames, files, nodes, gaps);
    }

    private void validateVisuals(String taskId, Map<String, Object> bundle, List<?> frames, List<?> files,
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
        if (bundle.containsKey("frame_observations")) {
            validateFrameObservations(taskId, bundle.get("frame_observations"), fileById);
        }
    }

    private void validateFrameObservations(String taskId, Object rawObservations,
                                           Map<String, Map<?, ?>> files) {
        if (!(rawObservations instanceof List<?> observations)
                || observations.size() != files.size()) {
            throw invalid("frame observations do not cover the material files");
        }
        Set<String> seen = new HashSet<>();
        for (Object raw : observations) {
            if (!(raw instanceof Map<?, ?> item)
                    || !item.keySet().equals(Set.of("schema_version", "task_id", "file_id",
                            "checksum_sha256", "media_type", "width", "height",
                            "observations", "coverage_gaps"))
                    || !"frame-observation-v1".equals(item.get("schema_version"))
                    || !taskId.equals(item.get("task_id"))
                    || !seen.add(string(item.get("file_id")))
                    || !files.containsKey(string(item.get("file_id")))
                    || !files.get(string(item.get("file_id"))).get("checksum_sha256")
                            .equals(item.get("checksum_sha256"))
                    || !files.get(string(item.get("file_id"))).get("media_type")
                            .equals(item.get("media_type"))
                    || number(item.get("width")) < 1 || number(item.get("height")) < 1
                    || (long) number(item.get("width")) * number(item.get("height")) > 50_000_000
                    || !(item.get("observations") instanceof List<?> texts)
                    || texts.size() > 64
                    || !(item.get("coverage_gaps") instanceof List<?> gaps)
                    || gaps.stream().anyMatch(gap -> !(gap instanceof String))
                    || gaps.size() != Set.copyOf(gaps).size()
                    || !gaps.contains("VISUAL_SEMANTICS_UNVERIFIED")
                    || !Set.of("NO_READABLE_TEXT", "VISUAL_SEMANTICS_UNVERIFIED").containsAll(gaps)
                    || (texts.isEmpty() != gaps.contains("NO_READABLE_TEXT"))) {
                throw invalid("frame observation identity or coverage is invalid");
            }
            for (Object rawText : texts) {
                if (!(rawText instanceof Map<?, ?> text)
                        || !text.keySet().equals(Set.of("kind", "text", "confidence", "uncertain"))
                        || !"TEXT".equals(text.get("kind"))
                        || string(text.get("text")).isBlank()
                        || string(text.get("text")).length() > 1_000
                        || !(text.get("confidence") instanceof Number score)
                        || !Double.isFinite(score.doubleValue())
                        || score.doubleValue() < 0 || score.doubleValue() > 100
                        || !(text.get("uncertain") instanceof Boolean uncertain)
                        || uncertain != (score.doubleValue() < 80)) {
                    throw invalid("frame observation text is invalid");
                }
            }
        }
        if (!seen.equals(files.keySet())) throw invalid("frame observation file set is incomplete");
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

    private byte[] readVerifiedFile(String bucketName, String objectKey,
                                    long expectedSize, String expectedDigest) {
        byte[] bytes;
        try {
            bytes = storage.read(bucketName, objectKey);
        } catch (RuntimeException missing) {
            throw new BusinessException("VIDEO_MATERIAL_FILE_DEGRADED",
                    "素材文件不可读取", HttpStatus.CONFLICT);
        }
        if (bytes == null || bytes.length != expectedSize || !hash(bytes).equals(expectedDigest)) {
            throw new BusinessException("VIDEO_MATERIAL_FILE_DEGRADED",
                    "素材文件摘要不匹配", HttpStatus.CONFLICT);
        }
        return bytes;
    }

    public record Submission(Map<String, Object> bundle, String contentDigest) {}
    public record KnowledgeSubmission(String bundleRowId, Map<String, Object> plan,
                                      String contentDigest) {}
    public record KnowledgeReceipt(String id, String bundleRowId, String contentDigest) {}
    public record MaterialBytes(String mediaType, byte[] bytes) {}
    public record Receipt(String id, String bundleId, int bundleVersion, String contentDigest,
                          String workspaceId, String taskId) {}
}

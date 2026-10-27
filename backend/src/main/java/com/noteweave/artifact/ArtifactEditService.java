package com.noteweave.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.security.CurrentUserProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 产物正文编辑：只新建不可变版本，不篡改原生成版本。
 *
 * <p>幂等身份是 (workspace, job, requestId) 的持久唯一行，不是 JSON LIKE 匹配；
 * requestDigest 覆盖 baseVersionNo、正文与编辑原因，同身份不同内容返回明确冲突。
 * 权限与基础版本可读性先于重放判定，撤销后的请求不能借 replay 取回旧事件。
 * commit 段在短事务内 CAS 校验 base、复核可读性、追加不可变版本并写入
 * artifact_edit_event 事件行；事件写入失败则版本事务一并回滚。
 * actingUserId 来自服务端认证上下文，不信任客户端提交。</p>
 */
@Service
public class ArtifactEditService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactEditService.class);

    /** 编辑幂等键的允许字符与长度；防止 LIKE/JSON 注入与超长键。 */
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{8,128}");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ArtifactJobService artifactJobService;
    private final ArtifactJobWriteRepository writeRepository;
    private final ArtifactExportService exportService;
    private final ApplicationEventPublisher eventPublisher;
    private final ArtifactEditEventStore eventStore;
    private final CurrentUserProvider currentUserProvider;

    public ArtifactEditService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ArtifactJobService artifactJobService,
            ArtifactJobWriteRepository writeRepository,
            ArtifactExportService exportService,
            ApplicationEventPublisher eventPublisher,
            ArtifactEditEventStore eventStore,
            CurrentUserProvider currentUserProvider
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.artifactJobService = artifactJobService;
        this.writeRepository = writeRepository;
        this.exportService = exportService;
        this.eventPublisher = eventPublisher;
        this.eventStore = eventStore;
        this.currentUserProvider = currentUserProvider;
    }

    public ArtifactVersionEditResponse editVersion(
            String workspaceId,
            String artifactJobId,
            int baseVersionNo,
            ArtifactVersionEditRequest request
    ) {
        if (request == null || request.requestId() == null
                || !REQUEST_ID_PATTERN.matcher(request.requestId()).matches()) {
            throw new BusinessException("ARTIFACT_EDIT_REQUEST_INVALID",
                    "编辑请求缺少有效幂等键（8-128 位字母数字._-）", HttpStatus.BAD_REQUEST);
        }
        if (request.markdown() == null || request.markdown().isBlank()) {
            throw new BusinessException("ARTIFACT_EDIT_REQUEST_INVALID", "编辑请求缺少正文",
                    HttpStatus.BAD_REQUEST);
        }
        // 认证与基础版本可读性先于重放判定：撤销后不能通过重放旧请求绕过
        String actingUserId = currentUserProvider.requireUserId();
        artifactJobService.getVersionDetail(workspaceId, artifactJobId, baseVersionNo);
        String requestDigest = requestDigest(baseVersionNo, request);
        ArtifactEditEventStore.ArtifactEditEventRecord existing =
                eventStore.findByRequestId(workspaceId, artifactJobId, request.requestId());
        if (existing != null) {
            if (!existing.requestDigest().equals(requestDigest)) {
                throw new BusinessException("ARTIFACT_EDIT_REQUEST_CONFLICT",
                        "同一编辑请求身份已提交过不同内容，请更换幂等键", HttpStatus.CONFLICT);
            }
            return replayResponse(existing);
        }
        PreparedEdit prepared = prepareEdit(workspaceId, artifactJobId, baseVersionNo, request);
        if (!prepared.changed()) {
            // 内容无变化不入队，也不生成编辑事件
            return new ArtifactVersionEditResponse(prepared.baseVersionId(), baseVersionNo, baseVersionNo,
                    false, "UNCHANGED", "NOT_APPLICABLE", "");
        }
        // V04：编辑版本的 Markdown 导出文件与版本同事务发布（复用回滚同款 staged 机制），
        // 消除「版本行存在但下载缺 MD 文件」的窗口；编辑版本不携带 PDF/PPTX（已拦截）
        byte[] markdownBytes = request.markdown().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        List<ArtifactExportService.StagedFile> stagedMarkdown = exportService.prepareStagedFiles(
                prepared.skillKey(), prepared.originTaskId(), java.util.UUID.randomUUID().toString(),
                List.of(new ArtifactExportService.PreparedFile(
                        "MARKDOWN",
                        prepared.title().replaceAll("[^\\p{IsHan}a-zA-Z0-9._-]+", "-").trim() + ".md",
                        "text/markdown; charset=UTF-8",
                        markdownBytes,
                        ArtifactVersionDiff.sha256(request.markdown()))));
        return commitEdit(workspaceId, artifactJobId, baseVersionNo, request, prepared, actingUserId, stagedMarkdown);
    }

    /** prepare 段：读取 base 正文与依赖资格，计算全文摘要与行级差异；无数据库事务。 */
    public PreparedEdit prepareEdit(
            String workspaceId,
            String artifactJobId,
            int baseVersionNo,
            ArtifactVersionEditRequest request
    ) {
        artifactJobService.getVersionDetail(workspaceId, artifactJobId, baseVersionNo);
        EditableBaseRow base = loadEditableBase(workspaceId, artifactJobId, baseVersionNo);
        if (base.contentMarkdown() == null || base.contentMarkdown().isBlank()) {
            throw new BusinessException("ARTIFACT_EDIT_CONTENT_EMPTY", "基础版本没有可编辑的 Markdown 正文",
                    HttpStatus.CONFLICT);
        }
        if (hasBinaryArtifactFiles(base.versionId())) {
            throw new BusinessException("ARTIFACT_EDIT_TYPE_UNSUPPORTED",
                    "该版本包含二进制产物文件（PDF/PPTX），不能通过 Markdown 编辑修改源产物",
                    HttpStatus.CONFLICT);
        }
        String beforeDigest = ArtifactVersionDiff.sha256(base.contentMarkdown());
        String afterDigest = ArtifactVersionDiff.sha256(request.markdown());
        ArtifactVersionDiffV2.DiffResult diff = ArtifactVersionDiffV2.compute(
                base.contentMarkdown(), request.markdown());
        return new PreparedEdit(
                base.versionId(),
                base.skillKey(),
                base.title(),
                base.originTaskId(),
                base.citationsJson(),
                base.resultPayloadJson(),
                beforeDigest,
                afterDigest,
                diff);
    }

    /** commit 段：短事务内 CAS 校验 base、复核可读性后追加新版本，并与事件行同事务提交。 */
    public ArtifactVersionEditResponse commitEdit(
            String workspaceId,
            String artifactJobId,
            int baseVersionNo,
            ArtifactVersionEditRequest request,
            PreparedEdit prepared,
            String actingUserId
    ) {
        return commitEdit(workspaceId, artifactJobId, baseVersionNo, request, prepared, actingUserId, List.of());
    }

    public ArtifactVersionEditResponse commitEdit(
            String workspaceId,
            String artifactJobId,
            int baseVersionNo,
            ArtifactVersionEditRequest request,
            PreparedEdit prepared,
            String actingUserId,
            List<ArtifactExportService.StagedFile> stagedFiles
    ) {
        String editEventId = Ids.newId();
        String versionId = Ids.newId();
        Map<String, Object> payload = new LinkedHashMap<>(
                readPayloadMap(prepared.resultPayloadJson()));
        payload.put("edit_of_version_no", baseVersionNo);
        payload.put("edit_request_id", request.requestId());
        payload.put("edit_event_id", editEventId);
        payload.put("before_digest", prepared.beforeDigest());
        payload.put("after_digest", prepared.afterDigest());
        payload.put("changed_sections", prepared.diff().changedSectionIds());
        payload.put("edit_reason", request.editReason() == null ? "" : request.editReason());
        payload.put("edited_by_user", true);
        // 用户修改过的正文不能继承原逐句审计通过标记
        payload.put("verification_state", "USER_EDITED_UNVERIFIED");
        String payloadJson = Json.write(objectMapper, payload);
        String requestDigest = requestDigest(baseVersionNo, request);
        String eventPayloadDigest = ArtifactVersionDiff.sha256(payloadJson);
        int nextVersionNo = exportService.runInShortTransaction(() -> {
            Integer latest = jdbcTemplate.queryForObject(
                    "select latest_version_no from artifact_job where id = ? and workspace_id = ? for update",
                    Integer.class, artifactJobId, workspaceId);
            if (latest == null || latest != baseVersionNo) {
                throw new BusinessException("ARTIFACT_EDIT_BASE_CONFLICT",
                        "基础版本已变化，请刷新后基于最新版本重新编辑", HttpStatus.CONFLICT);
            }
            // 提交前复核基础版本可读性：prepare 之后来源可能已被撤销
            exportService.requireVersionReadable(workspaceId, artifactJobId, baseVersionNo,
                    com.noteweave.source.eligibility.AccessPurpose.CURRENT_EVIDENCE);
            int versionNo = latest + 1;
            writeRepository.appendEditVersion(
                    versionId,
                    artifactJobId,
                    prepared.skillKey(),
                    versionNo,
                    prepared.title(),
                    request.markdown(),
                    payloadJson,
                    "user edit of version " + baseVersionNo + "（未重新核验）",
                    prepared.citationsJson(),
                    prepared.originTaskId(),
                    workspaceId);
            if (!stagedFiles.isEmpty()) {
                // V04：编辑版本的 MD 导出文件与版本/事件同事务发布，
                // 消除「版本行存在但下载缺文件」的窗口
                exportService.commitStagedFiles(versionId, stagedFiles);
            }
            // 事件与版本同事务：插入失败（含唯一身份冲突）时版本一并回滚
            eventStore.insertEvent(new ArtifactEditEventStore.ArtifactEditEventRecord(
                    editEventId, workspaceId, artifactJobId, request.requestId(), requestDigest,
                    versionId, prepared.baseVersionId(), baseVersionNo, prepared.originTaskId(),
                    actingUserId, prepared.beforeDigest(), prepared.afterDigest(),
                    prepared.diff().changedSectionIds(), prepared.diff().fragments(),
                    prepared.diff().diffStats().changedChars(), prepared.diff().diffStats().totalChars(),
                    prepared.diff().diffStats().algorithm(), payloadJson, eventPayloadDigest,
                    java.time.Instant.now().toString()));
            return versionNo;
        });
        // 进程内事件仅作加速唤醒；持久事实是 artifact_edit_event 行
        eventPublisher.publishEvent(new ArtifactEditEvent(
                editEventId, workspaceId, artifactJobId, versionId, prepared.baseVersionId(),
                baseVersionNo, prepared.originTaskId(), actingUserId,
                prepared.beforeDigest(), prepared.afterDigest(),
                prepared.diff().changedSectionIds(), 2));
        log.info("Artifact version edited: workspaceId={}, jobId={}, baseVersionNo={}, newVersionNo={}, "
                        + "editEventId={}, changedChars={}, fragments={}",
                workspaceId, artifactJobId, baseVersionNo, nextVersionNo, editEventId,
                prepared.diff().diffStats().changedChars(),
                prepared.diff().fragments().size());
        return new ArtifactVersionEditResponse(versionId, nextVersionNo, baseVersionNo, true,
                "DEFERRED", "EVENT_STORED", editEventId);
    }

    private ArtifactVersionEditResponse replayResponse(
            ArtifactEditEventStore.ArtifactEditEventRecord existing) {
        Integer versionNo = jdbcTemplate.query(
                "select version_no from artifact_version where id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, existing.artifactVersionId());
        int editedVersionNo = versionNo == null ? existing.baseVersionNo() + 1 : versionNo;
        return new ArtifactVersionEditResponse(existing.artifactVersionId(), editedVersionNo,
                existing.baseVersionNo(), false, "REPLAY", "EVENT_STORED", existing.editEventId());
    }

    /** 请求摘要覆盖 base 版本、规范化正文与行为参数；不含随机时间。 */
    private String requestDigest(int baseVersionNo, ArtifactVersionEditRequest request) {
        String normalized = request.markdown() == null ? "" : request.markdown().replace("\r\n", "\n");
        return ArtifactVersionDiff.sha256(baseVersionNo + "\u0000" + normalized + "\u0000"
                + (request.editReason() == null ? "" : request.editReason()));
    }

    private EditableBaseRow loadEditableBase(String workspaceId, String artifactJobId, int versionNo) {
        return jdbcTemplate.query("""
                select v.id as version_id, v.skill_key, v.title, v.content_markdown,
                       v.citations_json, v.result_payload_json, v.origin_task_id
                from artifact_version v
                join artifact_job j on j.id = v.artifact_job_id
                where j.id = ? and j.workspace_id = ? and v.version_no = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException("ARTIFACT_VERSION_NOT_FOUND", "基础版本不存在", HttpStatus.NOT_FOUND);
            }
            return new EditableBaseRow(
                    rs.getString("version_id"),
                    rs.getString("skill_key"),
                    rs.getString("title"),
                    rs.getString("content_markdown"),
                    rs.getString("citations_json"),
                    rs.getString("result_payload_json"),
                    rs.getString("origin_task_id"));
        }, artifactJobId, workspaceId, versionNo);
    }

    /** PDF/PPTX 等编译产物版本不能通过 Markdown 编辑修改源产物；Markdown 原生类型仍可编辑。 */
    private boolean hasBinaryArtifactFiles(String versionId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from artifact_file
                where artifact_version_id = ?
                  and file_role in ('PRIMARY_PPTX', 'PRIMARY_PDF', 'SLIDE_PREVIEW')
                  and status = 'READY'
                """, Integer.class, versionId);
        return count != null && count > 0;
    }

    /** 原 payload 解析；损坏时按空 map 处理，编辑元数据仍可写入。 */
    private Map<String, Object> readPayloadMap(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (Exception ex) {
            log.warn("Artifact edit base payload unreadable; starting from empty payload");
            return Map.of();
        }
    }

    /** 编辑准备结果；changed=false 表示正文无变化。 */
    public record PreparedEdit(
            String baseVersionId,
            String skillKey,
            String title,
            String originTaskId,
            String citationsJson,
            String resultPayloadJson,
            String beforeDigest,
            String afterDigest,
            ArtifactVersionDiffV2.DiffResult diff
    ) {
        private boolean changed() {
            return !beforeDigest.equals(afterDigest());
        }
    }

    private record EditableBaseRow(
            String versionId,
            String skillKey,
            String title,
            String contentMarkdown,
            String citationsJson,
            String resultPayloadJson,
            String originTaskId
    ) {
    }
}

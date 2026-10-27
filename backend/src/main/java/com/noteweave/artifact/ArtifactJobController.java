package com.noteweave.artifact;

import com.noteweave.common.ApiResponse;
import com.noteweave.knowledge.KnowledgeItemResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/artifact-jobs")
public class ArtifactJobController {

    private final ArtifactJobService artifactJobService;
    private final ArtifactExportService artifactExportService;
    private final ArtifactEditService artifactEditService;

    public ArtifactJobController(
            ArtifactJobService artifactJobService,
            ArtifactExportService artifactExportService,
            ArtifactEditService artifactEditService
    ) {
        this.artifactJobService = artifactJobService;
        this.artifactExportService = artifactExportService;
        this.artifactEditService = artifactEditService;
    }

    @PostMapping
    ApiResponse<ArtifactJobResponse> createArtifactJob(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateArtifactJobRequest request
    ) {
        return ApiResponse.success(artifactJobService.createJob(workspaceId, request));
    }

    @GetMapping
    ApiResponse<List<ArtifactJobSummaryResponse>> listArtifactJobs(@PathVariable String workspaceId) {
        return ApiResponse.success(artifactJobService.listJobs(workspaceId));
    }

    @GetMapping("/{artifactJobId}")
    ApiResponse<ArtifactJobDetailResponse> getArtifactJob(
            @PathVariable String workspaceId,
            @PathVariable String artifactJobId
    ) {
        return ApiResponse.success(artifactJobService.getJob(workspaceId, artifactJobId));
    }

    @GetMapping("/{artifactJobId}/versions")
    ApiResponse<List<ArtifactVersionSummaryResponse>> listArtifactVersions(
            @PathVariable String workspaceId,
            @PathVariable String artifactJobId
    ) {
        return ApiResponse.success(artifactJobService.listVersions(workspaceId, artifactJobId));
    }

    @GetMapping("/{artifactJobId}/versions/{versionNo}")
    ApiResponse<ArtifactVersionDetailResponse> getArtifactVersion(
            @PathVariable String workspaceId,
            @PathVariable String artifactJobId,
            @PathVariable int versionNo
    ) {
        return ApiResponse.success(artifactJobService.getVersionDetail(workspaceId, artifactJobId, versionNo));
    }

    @PostMapping("/{artifactJobId}/versions/{versionNo}/regenerate")
    ApiResponse<ArtifactJobResponse> regenerateArtifactVersion(
            @PathVariable String workspaceId,
            @PathVariable String artifactJobId,
            @PathVariable int versionNo,
            @Valid @RequestBody(required = false) RegenerateArtifactVersionRequest request
    ) {
        RegenerateArtifactVersionRequest effectiveRequest = request == null
                ? new RegenerateArtifactVersionRequest(null, null)
                : request;
        return ApiResponse.success(artifactJobService.regenerateVersion(
                workspaceId, artifactJobId, versionNo, effectiveRequest
        ));
    }

    @GetMapping("/{artifactJobId}/versions/compare")
    ApiResponse<ArtifactVersionComparisonResponse> compareArtifactVersions(
            @PathVariable String workspaceId,
            @PathVariable String artifactJobId,
            @RequestParam(name = "from") int fromVersionNo,
            @RequestParam(name = "to") int toVersionNo
    ) {
        return ApiResponse.success(artifactJobService.compareVersions(
                workspaceId, artifactJobId, fromVersionNo, toVersionNo
        ));
    }

    /**
     * 回滚：控制器不再包长事务；staged 文件准备在事务外，
     * 版本插入与文件元数据由服务在同一个短事务内提交。
     */
    @PostMapping("/{artifactJobId}/versions/{versionNo}/rollback")
    public ApiResponse<ArtifactVersionDetailResponse> rollbackArtifactVersion(
            @PathVariable String workspaceId,
            @PathVariable String artifactJobId,
            @PathVariable int versionNo,
            @Valid @RequestBody(required = false) RollbackArtifactVersionRequest request
    ) {
        String sourceVersionId = artifactJobService
                .getVersionDetail(workspaceId, artifactJobId, versionNo)
                .versionId();
        List<ArtifactExportService.PreparedFile> preparedFiles =
                artifactExportService.prepareRollbackFiles(sourceVersionId);
        ArtifactVersionDetailResponse rolledBack = artifactJobService.rollbackVersion(
                workspaceId, artifactJobId, versionNo, request, preparedFiles
        );
        return ApiResponse.success(artifactJobService.getVersionDetail(
                workspaceId, artifactJobId, rolledBack.versionNo()
        ));
    }

    @PostMapping("/{artifactJobId}/versions/{versionNo}/save-as-source")
    ApiResponse<ArtifactSavedSourceResponse> saveArtifactVersionAsSource(
            @PathVariable String workspaceId,
            @PathVariable String artifactJobId,
            @PathVariable int versionNo
    ) {
        return ApiResponse.success(artifactJobService.saveVersionAsSource(workspaceId, artifactJobId, versionNo));
    }

    /**
     * 版本正文编辑：只追加新的不可变版本；expectedBaseVersionNo 作为并发 CAS，
     * requestId 是 workspace/job 范围内的幂等键。二进制产物版本不可编辑。
     */
    @PostMapping("/{artifactJobId}/versions/{versionNo}/edits")
    ApiResponse<ArtifactVersionEditResponse> editArtifactVersion(
            @PathVariable String workspaceId,
            @PathVariable String artifactJobId,
            @PathVariable int versionNo,
            @Valid @RequestBody ArtifactVersionEditRequest request
    ) {
        return ApiResponse.success(artifactEditService.editVersion(
                workspaceId, artifactJobId, versionNo, request));
    }

    @PostMapping("/{artifactJobId}/versions/{versionNo}/writeback")
    ApiResponse<KnowledgeItemResponse> writeArtifactVersionToKnowledge(
            @PathVariable String workspaceId,
            @PathVariable String artifactJobId,
            @PathVariable int versionNo,
            @Valid @RequestBody ArtifactKnowledgeWritebackRequest request
    ) {
        return ApiResponse.success(artifactJobService.writeVersionToKnowledge(
                workspaceId,
                artifactJobId,
                versionNo,
                request
        ));
    }

    @GetMapping("/{artifactJobId}/versions/{versionNo}/export.pdf")
    ResponseEntity<byte[]> downloadArtifactPdf(
            @PathVariable String workspaceId,
            @PathVariable String artifactJobId,
            @PathVariable int versionNo
    ) {
        ArtifactExportFile file = artifactExportService.downloadPdf(workspaceId, artifactJobId, versionNo);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.fileName(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .body(file.content());
    }

    @GetMapping("/{artifactJobId}/versions/{versionNo}/files")
    ApiResponse<List<ArtifactFileMetadataResponse>> listArtifactFiles(
            @PathVariable String workspaceId, @PathVariable String artifactJobId,
            @PathVariable int versionNo) {
        return ApiResponse.success(artifactExportService.listVersionFiles(
                workspaceId, artifactJobId, versionNo));
    }

    @GetMapping("/{artifactJobId}/versions/{versionNo}/files/{fileId}")
    ResponseEntity<byte[]> downloadArtifactFile(
            @PathVariable String workspaceId, @PathVariable String artifactJobId,
            @PathVariable int versionNo, @PathVariable String fileId) {
        ArtifactDownloadFile file = artifactExportService.downloadFile(
                workspaceId, artifactJobId, versionNo, fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8)
                                .build().toString())
                .body(file.content());
    }
}

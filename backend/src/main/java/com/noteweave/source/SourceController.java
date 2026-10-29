package com.noteweave.source;

import com.noteweave.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/sources")
public class SourceController {

    private final SourceService sourceService;
    private final SourceReprocessService sourceReprocessService;
    private final SourceLinkService links;
    private final SourceFrameService frames;
    private final com.noteweave.security.WorkspaceAccessGuard access;

    public SourceController(SourceService sourceService, SourceReprocessService sourceReprocessService,
                            SourceLinkService links, SourceFrameService frames,
                            com.noteweave.security.WorkspaceAccessGuard access) {
        this.sourceService = sourceService;
        this.sourceReprocessService = sourceReprocessService;
        this.links = links;
        this.frames = frames;
        this.access = access;
    }

    @PostMapping("/links/preview")
    ApiResponse<SourceLinkWorkerClient.VideoInfo> previewLink(@PathVariable String workspaceId,
            @org.springframework.web.bind.annotation.RequestBody SourceLinkService.LinkRequest request) {
        return ApiResponse.success(links.preview(workspaceId, request.sourceUrl()));
    }

    @PostMapping("/links")
    ApiResponse<java.util.Map<String, String>> createLink(@PathVariable String workspaceId,
            @org.springframework.web.bind.annotation.RequestBody SourceLinkService.LinkRequest request) {
        return ApiResponse.success(links.create(workspaceId, request));
    }

    @GetMapping("/{sourceId}/media")
    ApiResponse<SourceMediaDocument> readMedia(@PathVariable String workspaceId, @PathVariable String sourceId) {
        access.requirePermission(workspaceId, com.noteweave.security.WorkspacePermission.WORKSPACE_READ);
        return ApiResponse.success(frames.readDocument(workspaceId, sourceId));
    }

    @GetMapping("/{sourceId}/frames/{frameId}")
    org.springframework.http.ResponseEntity<byte[]> readFrame(@PathVariable String workspaceId,
            @PathVariable String sourceId, @PathVariable String frameId) {
        access.requirePermission(workspaceId, com.noteweave.security.WorkspacePermission.WORKSPACE_READ);
        var image = frames.readFrame(workspaceId, sourceId, frameId);
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType(image.mediaType()))
                .header("Cache-Control", "private, no-store").body(image.content());
    }

    @GetMapping
    ApiResponse<List<SourceResponse>> listSources(@PathVariable String workspaceId) {
        return ApiResponse.success(sourceService.listSources(workspaceId));
    }

    /**
     * 删除资料。reason 可选，默认 USER；非法值返回 400。
     * USER 表示用户主动删除，COMPLIANCE 表示合规删除。
     */
    @DeleteMapping("/{sourceId}")
    ApiResponse<DeleteSourceResponse> deleteSource(
            @PathVariable String workspaceId,
            @PathVariable String sourceId,
            @RequestParam(name = "reason", required = false) String reason
    ) {
        // 先校验参数，非法值直接返回 400
        String deleteReason = SourceService.parseDeleteReason(reason);
        return ApiResponse.success(sourceService.deleteSource(workspaceId, sourceId, deleteReason));
    }

    /** 重新处理单个失败的资料：解析失败从解析阶段开始，只有索引失败时从向量化阶段开始。 */
    @PostMapping("/{sourceId}/reprocess")
    ApiResponse<SourceReprocessResponse> reprocessSource(
            @PathVariable String workspaceId,
            @PathVariable String sourceId
    ) {
        return ApiResponse.success(sourceReprocessService.reprocess(workspaceId, sourceId));
    }
}

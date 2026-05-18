package com.noteweave.admin.controller;

import com.noteweave.admin.dto.AuditLogQuery;
import com.noteweave.admin.dto.AuditLogResponse;
import com.noteweave.admin.dto.CleanupExecuteRequest;
import com.noteweave.admin.dto.CleanupScanRequest;
import com.noteweave.admin.dto.DashboardSummaryResponse;
import com.noteweave.admin.dto.OpsCleanupJobQuery;
import com.noteweave.admin.dto.OpsCleanupJobResponse;
import com.noteweave.admin.dto.SystemHealthResponse;
import com.noteweave.admin.model.SystemHealthComponent;
import com.noteweave.admin.service.AdminDashboardService;
import com.noteweave.admin.service.AuditLogService;
import com.noteweave.admin.service.ResourceCleanupService;
import com.noteweave.admin.service.SystemHealthService;
import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.api.PageResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.permission.service.ResourceAccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminOpsController {

    private final CurrentUserProvider currentUserProvider;
    private final ResourceAccessService resourceAccessService;
    private final ResourceCleanupService resourceCleanupService;
    private final SystemHealthService systemHealthService;
    private final AdminDashboardService adminDashboardService;
    private final AuditLogService auditLogService;

    @PostMapping("/cleanup/scan")
    public ApiResponse<OpsCleanupJobResponse> scan(@Valid @RequestBody CleanupScanRequest request) {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireAdmin(userId);
        return ApiResponse.success(resourceCleanupService.scan(userId, request));
    }

    @PostMapping("/cleanup/execute")
    public ApiResponse<OpsCleanupJobResponse> execute(@Valid @RequestBody CleanupExecuteRequest request) {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireAdmin(userId);
        return ApiResponse.success(resourceCleanupService.execute(userId, request));
    }

    @GetMapping("/cleanup/jobs")
    public ApiResponse<PageResponse<OpsCleanupJobResponse>> listCleanupJobs(@Valid @ModelAttribute OpsCleanupJobQuery query) {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireAdmin(userId);
        return ApiResponse.success(resourceCleanupService.listJobs(query));
    }

    @GetMapping("/cleanup/jobs/{jobId}")
    public ApiResponse<OpsCleanupJobResponse> getCleanupJob(@PathVariable Long jobId) {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireAdmin(userId);
        return ApiResponse.success(resourceCleanupService.getJob(jobId));
    }

    @GetMapping("/health")
    public ApiResponse<SystemHealthResponse> checkHealth() {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireAdmin(userId);
        return ApiResponse.success(systemHealthService.checkAll());
    }

    @GetMapping("/health/{component}")
    public ApiResponse<com.noteweave.admin.dto.ComponentHealthResponse> checkHealthComponent(@PathVariable String component) {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireAdmin(userId);
        return ApiResponse.success(systemHealthService.check(SystemHealthComponent.valueOf(component.trim().toUpperCase())));
    }

    @GetMapping("/dashboard/summary")
    public ApiResponse<DashboardSummaryResponse> summary() {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireAdmin(userId);
        return ApiResponse.success(adminDashboardService.summary());
    }

    @GetMapping("/audit-logs")
    public ApiResponse<PageResponse<AuditLogResponse>> auditLogs(@Valid @ModelAttribute AuditLogQuery query) {
        Long userId = currentUserProvider.getCurrentUserId();
        resourceAccessService.requireAdmin(userId);
        return ApiResponse.success(auditLogService.search(query));
    }
}

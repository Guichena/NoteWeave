package com.noteweave.task;

public record WaitContextResponse(
        String status,
        WaitProviderJobResponse providerJob,
        WaitApprovalRequestResponse approvalRequest,
        WaitReasonResponse waitReason
) {
}

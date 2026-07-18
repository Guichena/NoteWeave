import { describe, expect, it } from "vitest";
import {
  buildWaitContextDetailLines,
  buildWaitContextNarrative,
  buildWaitContextSignalChips,
  resolveRunStatus,
  resolveRunTone,
  summarizeRunStatus
} from "./runStatus";

describe("run status helpers", () => {
  it("should preserve detailed waiting statuses when task status is only WAITING", () => {
    expect(resolveRunStatus("WAITING", "WAITING_FOR_PROVIDER")).toBe("WAITING_FOR_PROVIDER");
    expect(resolveRunStatus("WAITING", "WAITING_FOR_APPROVAL")).toBe("WAITING_FOR_APPROVAL");
    expect(resolveRunStatus("WAITING", "WAITING_FOR_CAPABILITY")).toBe("WAITING_FOR_CAPABILITY");
  });

  it("should map waiting, running, failed and completed states to stable presentation tones", () => {
    expect(resolveRunTone("WAITING_FOR_PROVIDER")).toBe("waiting");
    expect(resolveRunTone("WAITING")).toBe("waiting");
    expect(resolveRunTone("RUNNING")).toBe("active");
    expect(resolveRunTone("FAILED")).toBe("danger");
    expect(resolveRunTone("COMPLETED")).toBe("stable");
    expect(resolveRunTone("READY")).toBe("stable");
  });

  it("should summarize internal statuses into user-facing labels", () => {
    expect(summarizeRunStatus("WAITING_FOR_PROVIDER")).toBe("等待 Provider");
    expect(summarizeRunStatus("WAITING_FOR_APPROVAL")).toBe("等待审批");
    expect(summarizeRunStatus("RUNNING")).toBe("运行中");
    expect(summarizeRunStatus("FAILED")).toBe("失败");
    expect(summarizeRunStatus("COMPLETED")).toBe("已完成");
  });

  it("should build wait context narratives for provider, approval and capability waiting", () => {
    expect(buildWaitContextNarrative({
      status: "WAITING_FOR_PROVIDER",
      provider_job: {
        provider_id: "builtin-bilibili-mcp",
        operation_key: "EXTRACT_TRANSCRIPT",
        request_id: "fetch-task-a-waiting-input-url-1-extract_transcript",
        provider_job_id: "provider-job-builtin-bilibili-mcp-fetch-task-a-waiting-input-url-1-extract_transcript",
        delivery_id: "acq-delivery-fetch-task-a-waiting-input-url-1-extract_transcript-2",
        dispatch_count: 2,
        previous_failed_delivery_count: 1,
        has_previous_failed_delivery: true,
        provider_status: "AVAILABLE",
        health_status: "HEALTHY",
        provider_job_status: "DISPATCHED",
        callback_status: "DISPATCHED_TO_PROVIDER"
      }
    })).toBe(
      "等待 Provider · builtin-bilibili-mcp / EXTRACT_TRANSCRIPT · "
      + "req=fetch-task-a-waiting-input-url-1-extract_transcript · "
      + "job=provider-job-builtin-bilibili-mcp-fetch-task-a-waiting-input-url-1-extract_transcript · "
      + "delivery=acq-delivery-fetch-task-a-waiting-input-url-1-extract_transcript-2 · "
      + "attempt=2 · "
      + "failed_before=1 · "
      + "provider=AVAILABLE/HEALTHY · "
      + "status=DISPATCHED"
    );

    expect(buildWaitContextNarrative({
      status: "WAITING_FOR_APPROVAL",
      provider_job: {
        capability_name: "EXTRACT_TRANSCRIPT"
      },
      approval_request: {
        request_id: "approval-123"
      }
    })).toBe("等待审批 · EXTRACT_TRANSCRIPT / approval-123");

    expect(buildWaitContextNarrative({
      status: "WAITING_FOR_CAPABILITY",
      wait_reason: {
        capability_name: "READ_WEB_PAGE",
        provider_id: "builtin-network"
      }
    })).toBe("等待能力 · READ_WEB_PAGE / builtin-network");
  });

  it("should build retry-aware wait signal chips for provider waiting", () => {
    expect(buildWaitContextSignalChips({
      status: "WAITING_FOR_PROVIDER",
      provider_job: {
        dispatch_count: 2,
        previous_failed_delivery_count: 1,
        has_previous_failed_delivery: true,
        provider_status: "AVAILABLE",
        health_status: "HEALTHY",
        provider_job_status: "DISPATCHED"
      }
    })).toEqual([
      { label: "Delivery", value: "#2", tone: "neutral" },
      { label: "Retry", value: "failed 1", tone: "conflict" },
      { label: "Provider", value: "AVAILABLE/HEALTHY", tone: "stable" },
      { label: "State", value: "DISPATCHED", tone: "neutral" }
    ]);

    expect(buildWaitContextSignalChips({
      status: "WAITING_FOR_APPROVAL",
      approval_request: {
        request_id: "approval-123",
        capability_name: "EXTRACT_TRANSCRIPT",
        provider_id: "builtin-bilibili-mcp"
      },
      provider_job: {
        dispatch_count: 3,
        previous_failed_delivery_count: 2
      }
    })).toEqual([
      { label: "Capability", value: "EXTRACT_TRANSCRIPT", tone: "neutral" },
      { label: "Approval", value: "#approval-123", tone: "neutral" },
      { label: "Provider", value: "builtin-bilibili-mcp", tone: "neutral" }
    ]);

    expect(buildWaitContextSignalChips({
      status: "WAITING_FOR_PROVIDER",
      provider_job: {
        provider_status: "UNAVAILABLE",
        health_status: "DEGRADED",
        provider_job_status: "PENDING_UPSTREAM"
      }
    })).toEqual([
      { label: "Provider", value: "UNAVAILABLE/DEGRADED", tone: "conflict" },
      { label: "State", value: "PENDING_UPSTREAM", tone: "neutral" }
    ]);

    expect(buildWaitContextSignalChips({
      status: "WAITING_FOR_CAPABILITY",
      wait_reason: {
        capability_name: "READ_WEB_PAGE",
        provider_id: "builtin-network"
      }
    })).toEqual([
      { label: "Capability", value: "READ_WEB_PAGE", tone: "conflict" },
      { label: "Provider", value: "builtin-network", tone: "conflict" }
    ]);
  });

  it("should derive delivery and retry counts from provider delivery attempts when explicit counters are missing", () => {
    expect(buildWaitContextNarrative({
      status: "WAITING_FOR_PROVIDER",
      provider_job: {
        provider_id: "builtin-bilibili-mcp",
        operation_key: "EXTRACT_TRANSCRIPT",
        provider_status: "AVAILABLE",
        health_status: "HEALTHY",
        provider_job_status: "DISPATCHED",
        provider_delivery_attempts: [
          {
            delivery_id: "acq-delivery-1",
            dispatch_count: 1,
            ack_status: "FAILED",
            error_code: "PROVIDER_TIMEOUT"
          },
          {
            delivery_id: "acq-delivery-2",
            dispatch_count: 2,
            ack_status: "PENDING"
          }
        ]
      }
    })).toBe(
      "等待 Provider · builtin-bilibili-mcp / EXTRACT_TRANSCRIPT · "
      + "attempt=2 · "
      + "failed_before=1 · "
      + "provider=AVAILABLE/HEALTHY · "
      + "status=DISPATCHED"
    );

    expect(buildWaitContextSignalChips({
      status: "WAITING_FOR_PROVIDER",
      provider_job: {
        provider_delivery_attempts: [
          {
            delivery_id: "acq-delivery-1",
            dispatch_count: 1,
            ack_status: "FAILED"
          },
          {
            delivery_id: "acq-delivery-2",
            dispatch_count: 2,
            ack_status: "PENDING"
          }
        ],
        provider_status: "AVAILABLE",
        health_status: "HEALTHY",
        provider_job_status: "DISPATCHED"
      }
    })).toEqual([
      { label: "Delivery", value: "#2", tone: "neutral" },
      { label: "Retry", value: "failed 1", tone: "conflict" },
      { label: "Provider", value: "AVAILABLE/HEALTHY", tone: "stable" },
      { label: "State", value: "DISPATCHED", tone: "neutral" }
    ]);
  });

  it("should build wait detail lines for provider attempts and approval waits", () => {
    expect(buildWaitContextDetailLines({
      status: "WAITING_FOR_PROVIDER",
      provider_job: {
        request_id: "fetch-task-a",
        provider_job_id: "provider-job-a",
        provider_receipt_id: "provider-receipt-a",
        provider_delivery_attempts: [
          {
            delivery_id: "delivery-1",
            dispatch_count: 1,
            ack_status: "FAILED",
            error_code: "PROVIDER_TIMEOUT"
          },
          {
            delivery_id: "delivery-2",
            dispatch_count: 2,
            ack_status: "PENDING"
          }
        ]
      }
    })).toEqual([
      { label: "Request", value: "fetch-task-a" },
      { label: "Provider Job", value: "provider-job-a" },
      { label: "Receipt", value: "provider-receipt-a" },
      { label: "Latest Attempt", value: "PENDING / #2 / delivery=delivery-2" },
      { label: "Previous Attempt", value: "FAILED / #1 / error=PROVIDER_TIMEOUT" }
    ]);

    expect(buildWaitContextDetailLines({
      status: "WAITING_FOR_APPROVAL",
      approval_request: {
        request_id: "approval-123",
        capability_name: "EXTRACT_TRANSCRIPT",
        provider_id: "builtin-bilibili-mcp"
      }
    })).toEqual([
      { label: "Approval Request", value: "approval-123" },
      { label: "Capability", value: "EXTRACT_TRANSCRIPT" },
      { label: "Provider", value: "builtin-bilibili-mcp" }
    ]);
  });
});

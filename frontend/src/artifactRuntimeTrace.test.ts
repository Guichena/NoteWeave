import { describe, expect, it } from "vitest";
import { buildArtifactRuntimeSummary, type ArtifactRuntimeTrace } from "./artifactRuntimeTrace";

describe("artifact runtime trace helpers", () => {
  it("should summarize key runtime audit fields for artifact versions", () => {
    expect(buildArtifactRuntimeSummary({
      verification: {
        status: "PASS_WITH_REPAIR"
      },
      approval_trace: {
        status: "SATISFIED",
        satisfied_capabilities: ["EXTRACT_TRANSCRIPT"],
        pending_capabilities: []
      },
      capability_union_trace: {
        status: "ALLOW",
        blocked_capabilities: [],
        external_network_capabilities: ["READ_WEB_PAGE"]
      },
      evidence_coverage: {
        status: "PASS",
        section_count: 4,
        covered_section_count: 4,
        sections_missing_evidence: []
      },
      writeback_preview: {
        status: "READY",
        requested_mode: "SAVE_AS_SOURCE",
        execution_mode: "PENDING_DISPATCH"
      },
      node_traces: [
        {
          node_id: "digest",
          verification_status: "PASS",
          repaired: false
        },
        {
          node_id: "writer",
          verification_status: "PASS_WITH_REPAIR",
          repaired: true
        }
      ],
      output_contract_trace: {
        status: "PASS",
        repaired_checks: ["fixed heading"],
        failed_checks: []
      },
      lifecycle_trace: {
        status: "COMPLETED",
        current_phase: "RESUMING"
      },
      acquisition_callback_trace: {
        status: "ATTACHED",
        receipt: {
          provider_job_status: "SUCCEEDED",
          callback_status: "ACKNOWLEDGED",
          provider_receipt_id: "provider-receipt-1",
          result_locator: "provider://builtin-bilibili-mcp/get_subtitle/fetch-artifact-task-bili-ack-1"
        },
        operation: {
          request_id: "fetch-artifact-task-bili-ack-1",
          provider_status: "AVAILABLE",
          health_status: "HEALTHY",
          dispatchCount: 2,
          providerDeliveryAttempts: [
            {
              deliveryId: "acq-delivery-fetch-artifact-task-bili-ack-1-1",
              dispatchCount: 1,
              ackStatus: "FAILED"
            },
            {
              deliveryId: "acq-delivery-fetch-artifact-task-bili-ack-1-2",
              dispatchCount: 2,
              ackStatus: "ACKNOWLEDGED"
            }
          ]
        }
      }
    })).toEqual([
      { label: "Verifier", value: "PASS_WITH_REPAIR" },
      { label: "Approval", value: "SATISFIED · ok=1 · pending=0" },
      { label: "Policy", value: "ALLOW · blocked=0 · external=1" },
      { label: "Evidence", value: "PASS · covered=4/4 · missing=0" },
      { label: "Writeback", value: "SAVE_AS_SOURCE / PENDING_DISPATCH" },
      { label: "Nodes", value: "count=2 · repaired=1 · fail=0" },
      { label: "Contract", value: "PASS · repair=1 · fail=0" },
      { label: "Lifecycle", value: "COMPLETED / RESUMING" },
      {
        label: "Callback",
        value: "SUCCEEDED / fetch-artifact-task-bili-ack-1 / provider=AVAILABLE/HEALTHY / delivery=2 / retry=1 / receipt=provider-receipt-1 / result=provider://builtin-bilibili-mcp/get_subtitle/fetch-artifact-task-bili-ack-1"
      }
    ]);
  });

  it("should ignore missing or invalid runtime trace branches", () => {
    expect(buildArtifactRuntimeSummary({
      verification: "PASS",
      approval_trace: {
        status: "WAITING_FOR_APPROVAL",
        pending_capabilities: ["WRITE_NOTE"],
        satisfied_capabilities: []
      },
      capability_union_trace: {
        status: "BLOCKED",
        blocked_capabilities: ["WRITE_NOTE"],
        external_network_capabilities: []
      },
      evidence_coverage: {
        status: "WARN",
        section_count: 3,
        covered_section_count: 1,
        sections_missing_evidence: ["证据综述", "建议方案"]
      },
      writeback_preview: {
        status: "SKIPPED"
      },
      node_traces: [
        {
          node_id: "digest",
          verification_status: "PASS",
          repaired: false
        },
        {
          node_id: "writer",
          verification_status: "FAIL",
          repaired: false
        }
      ],
      output_contract_trace: null,
      lifecycle_trace: {
        status: "WAITING_FOR_PROVIDER"
      },
      acquisition_callback_trace: {
        status: "ATTACHED",
        receipt: {
          request_id: "fetch-artifact-task-bili-ack-2",
          provider_job_status: "FAILED",
          callback_status: "FAILED",
          provider_receipt_id: "provider-receipt-2",
          error_code: "PROVIDER_TIMEOUT",
          error_message: "provider timed out",
          dispatch_count: 1
        },
        operation: {
          request_id: "fetch-artifact-task-bili-ack-2",
          provider_job_status: "FAILED"
        }
      }
    } as unknown as ArtifactRuntimeTrace)).toEqual([
      { label: "Approval", value: "WAITING_FOR_APPROVAL · ok=0 · pending=1" },
      { label: "Policy", value: "BLOCKED · blocked=1 · external=0" },
      { label: "Evidence", value: "WARN · covered=1/3 · missing=2" },
      { label: "Writeback", value: "SKIPPED" },
      { label: "Nodes", value: "count=2 · repaired=0 · fail=1" },
      { label: "Lifecycle", value: "WAITING_FOR_PROVIDER" },
      {
        label: "Callback",
        value: "FAILED / fetch-artifact-task-bili-ack-2 / delivery=1 / receipt=provider-receipt-2 / error=PROVIDER_TIMEOUT"
      }
    ]);

    expect(buildArtifactRuntimeSummary()).toEqual([]);
  });
});

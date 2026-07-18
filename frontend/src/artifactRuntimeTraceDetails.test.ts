import { describe, expect, it } from "vitest";
import { buildArtifactRuntimeDetailSections } from "./artifactRuntimeTraceDetails";

describe("artifact runtime trace detail helpers", () => {
  it("should build stable detail sections from runtime audit traces", () => {
    expect(buildArtifactRuntimeDetailSections({
      verification: {
        status: "PASS_WITH_REPAIR",
        passed_checks: ["outline preserved", "facts aligned"],
        repaired_checks: ["fixed heading"],
        failed_checks: [],
        warnings: ["wording slightly verbose"]
      },
      approval_trace: {
        status: "SATISFIED",
        required_capabilities: ["EXTRACT_TRANSCRIPT"],
        pending_capabilities: [],
        satisfied_capabilities: ["EXTRACT_TRANSCRIPT"]
      },
      capability_union_trace: {
        status: "ALLOW",
        skill_scope: "resume_highlight",
        external_network_capabilities: ["READ_WEB_PAGE"],
        blocked_capabilities: []
      },
      evidence_coverage: {
        status: "PASS",
        section_count: 4,
        covered_section_count: 4,
        supporting_source_ids: ["src-1", "src-2"],
        sections_missing_evidence: []
      },
      writeback_preview: {
        status: "READY",
        requested_mode: "SAVE_AS_SOURCE",
        execution_mode: "PENDING_DISPATCH",
        target_locator_preview: "workspace-source://resume-highlight-output.md"
      },
      node_traces: [
        {
          node_id: "source_digest",
          verification_status: "PASS",
          repaired: false,
          verification_checks: ["source coverage captured"]
        },
        {
          node_id: "bullet_writer",
          verification_status: "PASS_WITH_REPAIR",
          repaired: true,
          verification_checks: ["keyword present", "impact phrasing aligned"],
          repair_actions: ["added missing keyword", "tightened action wording"]
        }
      ],
      output_contract_trace: {
        status: "PASS",
        outline_checks: [
          {
            label: "summary",
            status: "PASS"
          }
        ],
        phrase_checks: [
          {
            label: "Controlled Agentic Graph Harness",
            status: "PASS"
          }
        ],
        contract_checks: [
          {
            label: "resume highlight bullet count",
            status: "PASS"
          },
          {
            label: "resume focus point coverage",
            status: "PASS"
          }
        ],
        evidence_checks: [
          {
            label: "evidence coverage",
            status: "PASS"
          }
        ],
        repaired_checks: ["fixed heading"],
        failed_checks: [],
        repair_summary: {
          total_repair_count: 3,
          local_repair_count: 1,
          node_repair_count: 2,
          affected_sections: ["summary", "keywords"],
          affected_nodes: ["resume_highlight_extractor", "resume_bullet_writer"],
          local_repair_checks: ["fixed heading"],
          node_repair_actions: ["added missing keyword", "tightened action wording"],
          category_counts: {
            keyword: 1,
            node_resume_highlight_candidate: 1,
            node_resume_keyword: 1
          }
        }
      },
      lifecycle_trace: {
        status: "COMPLETED",
        current_phase: "RESUMING",
        steps: [
          { phase: "PLANNING", status: "COMPLETED" },
          {
            phase: "RESUMING",
            status: "COMPLETED",
            progress_percent: 100,
            message: "resumed artifact run completed"
          }
        ],
        resume_scope: {
          matched_request_id: "fetch-artifact-task-bili-ack-1",
          matched_operation_key: "EXTRACT_TRANSCRIPT",
          matched_source_id: "src-bili-1"
        }
      },
      acquisition_callback_trace: {
        status: "ATTACHED",
        receipt: {
          request_id: "fetch-artifact-task-bili-ack-1",
          task_id: "artifact-task-bili-ack-1",
          source_id: "src-bili-1",
          operation_key: "EXTRACT_TRANSCRIPT",
          provider_job_status: "SUCCEEDED",
          callback_status: "ACKNOWLEDGED",
          delivery_id: "delivery-1",
          provider_receipt_id: "provider-receipt-1",
          provider_job_id: "provider-job-1",
          result_locator: "provider://builtin-bilibili-mcp/get_subtitle/fetch-artifact-task-bili-ack-1",
          completed_at: "2026-07-07T06:00:00Z",
          dispatch_count: 1
        },
        operation: {
          request_id: "fetch-artifact-task-bili-ack-1",
          capability_name: "EXTRACT_TRANSCRIPT",
          provider_id: "builtin-bilibili-mcp",
          server_id: "builtin-bilibili-mcp",
          tool_name: "get_bilibili_subtitle",
          callback_token: "acq-callback-token-fetch-artifact-task-bili-ack-1-1",
          provider_status: "AVAILABLE",
          health_status: "HEALTHY",
          provider_job_status: "SUCCEEDED",
          callback_status: "ACKNOWLEDGED",
          delivery_id: "delivery-1",
          provider_receipt_id: "provider-receipt-1"
        }
      }
    })).toEqual([
      {
        title: "Verifier",
        lines: [
          "status=PASS_WITH_REPAIR",
          "passed=2 · repaired=1 · failed=0 · warnings=1",
          "passed_checks=outline preserved / facts aligned",
          "repaired_checks=fixed heading",
          "warnings=wording slightly verbose",
        ]
      },
      {
        title: "Approval",
        lines: [
          "status=SATISFIED",
          "required=EXTRACT_TRANSCRIPT",
          "satisfied=EXTRACT_TRANSCRIPT"
        ]
      },
      {
        title: "Policy",
        lines: [
          "status=ALLOW",
          "skill=resume_highlight",
          "external=READ_WEB_PAGE"
        ]
      },
      {
        title: "Evidence",
        lines: [
          "coverage=4/4",
          "sources=src-1 / src-2"
        ]
      },
      {
        title: "Writeback",
        lines: [
          "status=READY",
          "mode=SAVE_AS_SOURCE",
          "execution=PENDING_DISPATCH",
          "target=workspace-source://resume-highlight-output.md"
        ]
      },
      {
        title: "Nodes",
        lines: [
          "source_digest · PASS",
          "checks=source coverage captured",
          "bullet_writer · PASS_WITH_REPAIR · repaired",
          "checks=keyword present / impact phrasing aligned",
          "repairs=added missing keyword / tightened action wording"
        ]
      },
      {
        title: "Contract",
        lines: [
          "status=PASS",
          "repaired=1 · failed=0",
          "repair_summary=total=3 · local=1 · node=2",
          "repair_sections=summary / keywords",
          "repair_nodes=resume_highlight_extractor / resume_bullet_writer",
          "local_repairs=fixed heading",
          "node_repairs=added missing keyword / tightened action wording",
          "repair_categories=keyword=1 / node_resume_highlight_candidate=1 / node_resume_keyword=1",
          "outline_checks=PASS / summary",
          "phrase_checks=PASS / Controlled Agentic Graph Harness",
          "contract_checks=PASS / resume highlight bullet count / PASS / resume focus point coverage",
          "evidence_checks=PASS / evidence coverage",
          "repaired_checks=fixed heading"
        ]
      },
      {
        title: "Lifecycle",
        lines: [
          "status=COMPLETED",
          "phase=RESUMING",
          "steps=PLANNING / RESUMING",
          "step_detail=RESUMING / COMPLETED / progress=100% / resumed artifact run completed",
          "resume_scope=fetch-artifact-task-bili-ack-1 / EXTRACT_TRANSCRIPT / src-bili-1"
        ]
      },
      {
        title: "Callback",
        lines: [
          "status=SUCCEEDED",
          "request=fetch-artifact-task-bili-ack-1",
          "capability=EXTRACT_TRANSCRIPT",
          "provider=builtin-bilibili-mcp / builtin-bilibili-mcp / get_bilibili_subtitle",
          "provider_state=AVAILABLE/HEALTHY",
          "callback=ACKNOWLEDGED",
          "receipt_scope=fetch-artifact-task-bili-ack-1 / artifact-task-bili-ack-1 / src-bili-1 / EXTRACT_TRANSCRIPT",
          "callback_token=acq-callback-token-fetch-artifact-task-bili-ack-1-1",
          "provider_job=provider-job-1",
          "delivery=delivery-1",
          "provider_receipt=provider-receipt-1",
          "result=provider://builtin-bilibili-mcp/get_subtitle/fetch-artifact-task-bili-ack-1",
          "completed_at=2026-07-07T06:00:00Z",
          "dispatch_count=1"
        ]
      }
    ]);
  });

  it("should ignore empty branches and remain tolerant to partial traces", () => {
    expect(buildArtifactRuntimeDetailSections({
      evidence_coverage: {
        status: "WARN",
        section_count: 3,
        covered_section_count: 1,
        sections_missing_evidence: ["璇佹嵁缁艰堪", "寤鸿鏂规"]
      },
      node_traces: [
        {
          node_id: "writer",
          verification_status: "FAIL",
          repaired: false
        }
      ],
      acquisition_callback_trace: {
        status: "ATTACHED",
        operation: {
          request_id: "fetch-artifact-task-bili-ack-2",
          capability_name: "READ_WEB_PAGE",
          provider_job_status: "FAILED",
          callback_status: "FAILED",
          delivery_id: "delivery-2",
          provider_receipt_id: "provider-receipt-2"
        },
        receipt: {
          request_id: "fetch-artifact-task-bili-ack-2",
          task_id: "artifact-task-bili-ack-2",
          source_id: "src-bili-2",
          operation_key: "READ_WEB_PAGE",
          provider_job_status: "FAILED",
          callback_status: "FAILED",
          delivery_id: "delivery-2",
          provider_receipt_id: "provider-receipt-2",
          error_code: "PROVIDER_TIMEOUT",
          error_message: "provider timed out",
          completed_at: "2026-07-07T07:00:00Z",
          dispatch_count: 1
        }
      }
    })).toEqual([
      {
        title: "Evidence",
        lines: [
          "coverage=1/3",
          "missing=璇佹嵁缁艰堪 / 寤鸿鏂规"
        ]
      },
      {
        title: "Nodes",
        lines: [
          "writer · FAIL"
        ]
      },
      {
        title: "Callback",
        lines: [
          "status=FAILED",
          "request=fetch-artifact-task-bili-ack-2",
          "capability=READ_WEB_PAGE",
          "receipt_scope=fetch-artifact-task-bili-ack-2 / artifact-task-bili-ack-2 / src-bili-2 / READ_WEB_PAGE",
          "delivery=delivery-2",
          "provider_receipt=provider-receipt-2",
          "error=PROVIDER_TIMEOUT: provider timed out",
          "completed_at=2026-07-07T07:00:00Z",
          "dispatch_count=1"
        ]
      }
    ]);

    expect(buildArtifactRuntimeDetailSections()).toEqual([]);
  });

  it("should expose provider redelivery attempt history in callback details", () => {
    expect(buildArtifactRuntimeDetailSections({
      acquisition_callback_trace: {
        status: "ATTACHED",
        receipt: {
          request_id: "fetch-artifact-task-bili-redelivery-1",
          task_id: "artifact-task-bili-redelivery-1",
          source_id: "src-bili-redelivery-1",
          operation_key: "EXTRACT_TRANSCRIPT",
          provider_job_status: "SUCCEEDED",
          callback_status: "ACKNOWLEDGED",
          delivery_id: "acq-delivery-fetch-artifact-task-bili-redelivery-1-2",
          completed_at: "2026-07-07T06:00:00Z"
        },
        operation: {
          request_id: "fetch-artifact-task-bili-redelivery-1",
          capability_name: "EXTRACT_TRANSCRIPT",
          provider_job_status: "SUCCEEDED",
          callback_status: "ACKNOWLEDGED",
          dispatchCount: 2,
          providerDeliveryAttempts: [
            {
              deliveryId: "acq-delivery-fetch-artifact-task-bili-redelivery-1-1",
              dispatchCount: 1,
              ackStatus: "FAILED",
              errorCode: "PROVIDER_TIMEOUT",
              errorMessage: "provider timed out"
            },
            {
              deliveryId: "acq-delivery-fetch-artifact-task-bili-redelivery-1-2",
              dispatchCount: 2,
              ackStatus: "ACKNOWLEDGED",
              resultLocator: "bilibili://subtitle/BV1NoteWeaveDemo",
              callbackReceivedAt: "2026-07-07T06:00:00Z"
            }
          ]
        }
      }
    })).toEqual([
      {
        title: "Callback",
        lines: [
          "status=SUCCEEDED",
          "request=fetch-artifact-task-bili-redelivery-1",
          "capability=EXTRACT_TRANSCRIPT",
          "callback=ACKNOWLEDGED",
          "receipt_scope=fetch-artifact-task-bili-redelivery-1 / artifact-task-bili-redelivery-1 / src-bili-redelivery-1 / EXTRACT_TRANSCRIPT",
          "delivery=acq-delivery-fetch-artifact-task-bili-redelivery-1-2",
          "completed_at=2026-07-07T06:00:00Z",
          "dispatch_count=2",
          "attempts=2",
          "attempt#1=FAILED / dispatch=1 / delivery=acq-delivery-fetch-artifact-task-bili-redelivery-1-1 / error=PROVIDER_TIMEOUT: provider timed out",
          "attempt#2=ACKNOWLEDGED / dispatch=2 / delivery=acq-delivery-fetch-artifact-task-bili-redelivery-1-2 / result=bilibili://subtitle/BV1NoteWeaveDemo / callback_at=2026-07-07T06:00:00Z"
        ]
      }
    ]);
  });

  it("should expose node verifier checks, repair actions and repair category counts", () => {
    expect(buildArtifactRuntimeDetailSections({
      node_traces: [
        {
          node_id: "resume_verifier",
          verification_status: "PASS_WITH_REPAIR",
          repaired: true,
          verification_checks: ["keyword present", "impact phrasing aligned"],
          repair_actions: ["added missing keyword", "tightened action wording"]
        }
      ],
      output_contract_trace: {
        status: "PASS",
        repaired_checks: ["fixed heading"],
        failed_checks: [],
        repair_summary: {
          total_repair_count: 3,
          local_repair_count: 1,
          node_repair_count: 2,
          local_repair_checks: ["fixed heading"],
          node_repair_actions: ["added missing keyword", "tightened action wording"],
          category_counts: {
            keyword: 1,
            node_resume_highlight_candidate: 1,
            node_resume_keyword: 1
          }
        }
      }
    })).toEqual([
      {
        title: "Nodes",
        lines: [
          "resume_verifier · PASS_WITH_REPAIR · repaired",
          "checks=keyword present / impact phrasing aligned",
          "repairs=added missing keyword / tightened action wording"
        ]
      },
      {
        title: "Contract",
        lines: [
          "status=PASS",
          "repaired=1 · failed=0",
          "repair_summary=total=3 · local=1 · node=2",
          "local_repairs=fixed heading",
          "node_repairs=added missing keyword / tightened action wording",
          "repair_categories=keyword=1 / node_resume_highlight_candidate=1 / node_resume_keyword=1",
          "repaired_checks=fixed heading"
        ]
      }
    ]);
  });

  it("should expose detailed verifier check names and warnings", () => {
    expect(buildArtifactRuntimeDetailSections({
      verification: {
        status: "PASS_WITH_REPAIR",
        passed_checks: ["outline preserved", "facts aligned"],
        repaired_checks: ["fixed heading"],
        failed_checks: [],
        warnings: ["wording slightly verbose"]
      }
    })).toEqual([
      {
        title: "Verifier",
        lines: [
          "status=PASS_WITH_REPAIR",
          "passed=2 · repaired=1 · failed=0 · warnings=1",
          "passed_checks=outline preserved / facts aligned",
          "repaired_checks=fixed heading",
          "warnings=wording slightly verbose"
        ]
      }
    ]);
  });

  it("should expose node output summaries and contract check names", () => {
    expect(buildArtifactRuntimeDetailSections({
      node_traces: [
        {
          node_id: "bullet_writer",
          output_summary: "generated polished resume bullets",
          verification_status: "PASS_WITH_REPAIR",
          repaired: true,
          verification_checks: ["keyword present"],
          repair_actions: ["added missing keyword"]
        }
      ],
      output_contract_trace: {
        status: "FAIL",
        contract_checks: [
          {
            label: "resume highlight bullet count",
            status: "FAIL"
          }
        ],
        passed_checks: ["outline preserved"],
        repaired_checks: ["fixed heading"],
        failed_checks: ["missing evidence block"],
        warnings: ["tone slightly verbose"]
      }
    })).toEqual([
      {
        title: "Nodes",
        lines: [
          "bullet_writer · PASS_WITH_REPAIR · repaired",
          "summary=generated polished resume bullets",
          "checks=keyword present",
          "repairs=added missing keyword"
        ]
      },
      {
        title: "Contract",
        lines: [
          "status=FAIL",
          "repaired=1 · failed=1",
          "contract_checks=FAIL / resume highlight bullet count",
          "passed_checks=outline preserved",
          "repaired_checks=fixed heading",
          "failed_checks=missing evidence block",
          "warnings=tone slightly verbose"
        ]
      }
    ]);
  });

  it("should fall back to legacy action_checks when contract_checks are absent", () => {
    expect(buildArtifactRuntimeDetailSections({
      output_contract_trace: {
        status: "PASS",
        action_checks: [
          {
            label: "resume highlight bullet count",
            status: "PASS"
          }
        ],
        repaired_checks: [],
        failed_checks: []
      }
    })).toEqual([
      {
        title: "Contract",
        lines: [
          "status=PASS",
          "repaired=0 · failed=0",
          "contract_checks=PASS / resume highlight bullet count"
        ]
      }
    ]);
  });
});





// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("./useMemoryReview", async () => {
  const React = await import("react");
  const item = {
    revision_id: "revision-1",
    memory_item_id: "memory-1",
    review_kind: "PROPOSAL" as const,
    status: "PROPOSED",
    display_text: "Prefer source-grounded answers",
    provenance_ref: "memory-observation:test",
    conflict_status: "NO_CONFLICT",
    utility_score: 0.5,
    review_status: "APPROVED",
    lifecycle_status: "EMPTY"
  };
  return {
    useMemoryReview: () => {
      const [queue, setQueue] = React.useState([item]);
      const [selectedRevisionId, setSelectedRevisionId] = React.useState("");
      const [lastDecision, setLastDecision] = React.useState<null | {
        revision_id: string;
        memory_item_id: string;
        status: string;
        revoked_memory_item_ids: string[];
      }>(null);
      return {
        queue,
        selectedRevisionId,
        selectedItem: queue.find((entry) => entry.revision_id === selectedRevisionId) ?? null,
        queueLoading: false,
        mutating: false,
        error: "",
        lastDecision,
        refreshQueue: vi.fn(),
        selectReview: (entry: typeof item) => setSelectedRevisionId(entry.revision_id),
        decide: async () => {
          const result = {
            revision_id: item.revision_id,
            memory_item_id: item.memory_item_id,
            status: "ACTIVE",
            revoked_memory_item_ids: []
          };
          setLastDecision(result);
          setQueue([]);
          return result;
        }
      };
    }
  };
});

import { MemoryReviewWorkbench } from "./MemoryReviewWorkbench";

afterEach(() => {
  cleanup();
});

describe("MemoryReviewWorkbench", () => {
  it("keeps the decision confirmation visible after the reviewed item leaves the queue", async () => {
    render(<MemoryReviewWorkbench workspaceId="workspace" />);

    fireEvent.click(await screen.findByRole("button", { name: /Prefer source-grounded answers/ }));
    fireEvent.click(screen.getByRole("button", { name: "接受 Revision" }));

    await waitFor(() => expect(screen.getByLabelText("0 条待审核")).toBeTruthy());
    expect(screen.getByRole("status").textContent).toContain("审核已提交：ACCEPT → ACTIVE");
    expect(screen.getByText(/最近决策：ACTIVE/)).toBeTruthy();
  });
});

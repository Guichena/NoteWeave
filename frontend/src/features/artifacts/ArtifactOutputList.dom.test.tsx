// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ArtifactOutputList } from "./ArtifactOutputList";
import type { ArtifactOutputItem } from "./artifactOutputs";

afterEach(cleanup);

function item(overrides: Partial<ArtifactOutputItem> = {}): ArtifactOutputItem {
  return {
    key: "artifact-job-1",
    artifactJobId: "job-1",
    skillKey: "briefing",
    title: "简报",
    typeLabel: "简报",
    versionNo: 1,
    state: "ready",
    statusLabel: "",
    phaseIndex: -1,
    detail: "",
    technicalDetail: "",
    queuePosition: null,
    updatedAt: "2026-11-04T00:00:00Z",
    ...overrides
  };
}

describe("ArtifactOutputList", () => {
  it("shows load more only when the server has older artifacts", async () => {
    const onLoadMore = vi.fn(async () => []);
    const { rerender } = render(
      <ArtifactOutputList items={[item()]} loading={false} hasMore onLoadMore={onLoadMore}
        formatRelativeTime={() => "刚刚"} onOpen={vi.fn()} />
    );

    fireEvent.click(screen.getByRole("button", { name: "加载更早的产物" }));
    await waitFor(() => expect(onLoadMore).toHaveBeenCalledOnce());

    rerender(
      <ArtifactOutputList items={[item()]} loading={false} hasMore={false} onLoadMore={onLoadMore}
        formatRelativeTime={() => "刚刚"} onOpen={vi.fn()} />
    );
    expect(screen.queryByRole("button", { name: "加载更早的产物" })).toBeNull();
  });

  it("shows the queue position for queued artifacts", () => {
    render(
      <ArtifactOutputList
        items={[item({ state: "queued", statusLabel: "排队中 · 第 2 位", versionNo: 0, queuePosition: 2 })]}
        loading={false} formatRelativeTime={() => "刚刚"} onOpen={vi.fn()} />
    );
    expect(screen.getByText("排队中 · 第 2 位")).toBeTruthy();
  });
});

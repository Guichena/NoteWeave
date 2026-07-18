import { describe, expect, it } from "vitest";
import {
  createArtifactServerState,
  LatestArtifactRequestGate,
  reduceArtifactServerState
} from "./state";

describe("Artifact server state", () => {
  it("clears job, version and receipt projections on workspace replacement", () => {
    const state = reduceArtifactServerState(
      createArtifactServerState("workspace-a"),
      { type: "saved-source", key: "job:1", sourceId: "source" }
    );
    const changed = reduceArtifactServerState(state, { type: "workspace", workspaceId: "workspace-b" });

    expect(changed).toEqual(createArtifactServerState("workspace-b"));
  });

  it("keeps source and writeback receipts deduplicated", () => {
    const initial = createArtifactServerState("workspace");
    const saved = reduceArtifactServerState(initial, { type: "saved-source", key: "job:1", sourceId: "source" });
    const written = reduceArtifactServerState(saved, { type: "writeback", key: "job:1", itemType: "WIKI" });
    const deduplicated = reduceArtifactServerState(written, { type: "writeback", key: "job:1", itemType: "WIKI" });

    expect(deduplicated.artifactSavedSourceByVersionId).toEqual({ "job:1": "source" });
    expect(deduplicated.artifactWritebackByVersionId).toEqual({ "job:1": ["WIKI"] });
  });

  it("aborts stale history requests and all requests on workspace replacement", () => {
    const gate = new LatestArtifactRequestGate();
    gate.setWorkspace("workspace-a");
    const first = gate.begin("history", "workspace-a");
    const second = gate.begin("history", "workspace-a");

    expect(first.signal.aborted).toBe(true);
    expect(second.isCurrent()).toBe(true);

    gate.setWorkspace("workspace-b");
    expect(second.signal.aborted).toBe(true);
  });
});

// @vitest-environment jsdom

import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useArtifactComposerActions } from "./useArtifactComposerActions";
import type { ArtifactStudioSkill } from "./artifactStudio";

const skill = {
  key: "mindmap_from_workspace",
  title: "思维导图",
  inputFields: []
} as unknown as ArtifactStudioSkill;

function createInput(createJob: () => Promise<never>) {
  return {
    workspaceId: "workspace-1",
    sourceIds: ["source-1"],
    run: async (_label: string, action: () => Promise<void>) => {
      try {
        await action();
      } catch {
        // useShellBusy owns the global error; the composer keeps a local copy.
      }
    },
    setStatus: vi.fn(),
    setView: vi.fn(),
    setChatMode: vi.fn(),
    setQuestion: vi.fn(),
    appendSystemMessage: vi.fn(),
    catalog: {
      formValues: {},
      customInstruction: "",
      setComposerOpen: vi.fn(),
      setCustomInstruction: vi.fn(),
      setFormValues: vi.fn()
    },
    loadJobs: vi.fn(),
    api: { createJob } as never
  };
}

describe("useArtifactComposerActions", () => {
  it("keeps a failed create request visible inside the composer", async () => {
    const input = createInput(vi.fn().mockRejectedValue(new Error("资料范围不可用")));
    const { result } = renderHook(() => useArtifactComposerActions(input));

    await act(async () => {
      await result.current.launchPrompt(skill);
    });

    expect(result.current.composerError).toBe("资料范围不可用");
    expect(input.catalog.setComposerOpen).not.toHaveBeenCalledWith(false);
  });

  it("clears a previous composer error before retrying", async () => {
    const createJob = vi.fn().mockRejectedValue(new Error("临时失败"));
    const input = createInput(createJob);
    const { result } = renderHook(() => useArtifactComposerActions(input));

    await act(async () => {
      await result.current.launchPrompt(skill);
    });
    expect(result.current.composerError).toBe("临时失败");

    act(() => result.current.clearComposerError());
    expect(result.current.composerError).toBe("");
  });
});

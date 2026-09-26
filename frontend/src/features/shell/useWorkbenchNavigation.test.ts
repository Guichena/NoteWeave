import { describe, expect, it, vi } from "vitest";
import { useWorkbenchNavigation } from "./useWorkbenchNavigation";

describe("useWorkbenchNavigation", () => {
  it("clears a previous surface status when returning to chat", () => {
    const setView = vi.fn();
    const setStatus = vi.fn();
    const navigation = useWorkbenchNavigation({
      view: "memory",
      setView,
      hasWorkspace: true,
      setStatus,
      openResearch: vi.fn(),
      openWiki: vi.fn()
    });

    navigation.navigateWorkbenchView("chat");

    expect(setView).toHaveBeenCalledWith("chat");
    expect(setStatus).toHaveBeenCalledWith("准备就绪");
  });

  it("opens the dedicated library route from another workbench surface", () => {
    const setView = vi.fn();
    const setStatus = vi.fn();
    const navigation = useWorkbenchNavigation({
      view: "research",
      setView,
      hasWorkspace: true,
      setStatus,
      openResearch: vi.fn(),
      openWiki: vi.fn()
    });

    navigation.navigateWorkbenchView("library");

    expect(setView).toHaveBeenCalledWith("library");
    expect(setStatus).toHaveBeenCalledWith("已打开工作台资料库");
  });
});

import type { AppView } from "./viewRoute";
import { navigateAppView } from "./viewRoute";

type UseWorkbenchNavigationInput = {
  view: AppView;
  setView: (view: AppView) => void;
  hasWorkspace: boolean;
  setStatus: (status: string) => void;
  openResearch: () => void | Promise<void>;
  openWiki: () => void | Promise<void>;
};

export function useWorkbenchNavigation({
  view,
  setView,
  hasWorkspace,
  setStatus,
  openResearch,
  openWiki
}: UseWorkbenchNavigationInput) {
  function openMemoryWorkbench() {
    if (!hasWorkspace) {
      setStatus("请先创建工作台");
      return;
    }
    setView("memory");
    navigateAppView("memory");
    setStatus("已打开 Memory 人工审核工作台");
  }

  function navigateWorkbenchView(next: AppView) {
    if (next === view) {
      return;
    }
    if (next === "chat") {
      setView("chat");
      navigateAppView("chat");
      setStatus("准备就绪");
      return;
    }
    if (next === "research") {
      void openResearch();
      return;
    }
    if (next === "library") {
      if (!hasWorkspace) {
        setStatus("请先创建工作台");
        return;
      }
      setView("library");
      navigateAppView("library");
      setStatus("已打开工作台资料库");
      return;
    }
    if (next === "memory") {
      openMemoryWorkbench();
      return;
    }
    void openWiki();
  }

  return {
    openMemoryWorkbench,
    navigateWorkbenchView
  };
}

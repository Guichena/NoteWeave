import { Component, type ErrorInfo, type ReactNode } from "react";

type ChunkLoadBoundaryProps = {
  label: string;
  children: ReactNode;
};

type ChunkLoadBoundaryState = {
  hasError: boolean;
  autoReloading: boolean;
};

const CHUNK_RELOAD_STORAGE_KEY = "noteweave:chunk-reload-at";
const CHUNK_RELOAD_COOLDOWN_MS = 60_000;

export function isChunkLoadError(error: unknown): boolean {
  const message = error instanceof Error ? `${error.name} ${error.message}` : String(error ?? "");
  return /ChunkLoadError|Loading chunk .+ failed|Failed to fetch dynamically imported module|Importing a module script failed/i.test(message);
}

export function canAutoReloadChunk(lastReloadAt: string | null, now = Date.now()): boolean {
  const timestamp = Number(lastReloadAt);
  return !Number.isFinite(timestamp) || timestamp <= 0 || now - timestamp > CHUNK_RELOAD_COOLDOWN_MS;
}

export class ChunkLoadBoundary extends Component<ChunkLoadBoundaryProps, ChunkLoadBoundaryState> {
  state: ChunkLoadBoundaryState = { hasError: false, autoReloading: false };

  static getDerivedStateFromError(): ChunkLoadBoundaryState {
    return { hasError: true, autoReloading: false };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    if (isChunkLoadError(error)) {
      const lastReloadAt = window.sessionStorage.getItem(CHUNK_RELOAD_STORAGE_KEY);
      if (canAutoReloadChunk(lastReloadAt)) {
        window.sessionStorage.setItem(CHUNK_RELOAD_STORAGE_KEY, String(Date.now()));
        this.setState({ autoReloading: true });
        window.location.reload();
        return;
      }
    }
    console.error("Workbench module failed to load", error, info.componentStack);
  }

  render() {
    if (!this.state.hasError) {
      return this.props.children;
    }

    if (this.state.autoReloading) {
      return (
        <section className="workbench-page view-loading chunk-load-error" role="status" aria-live="polite">
          <p className="section-label">{this.props.label}</p>
          <h2>正在同步最新页面</h2>
          <p>检测到工作台资源已经更新，正在自动恢复当前页面。</p>
        </section>
      );
    }

    return (
      <section className="workbench-page view-loading chunk-load-error" role="alert">
        <p className="section-label">{this.props.label}</p>
        <h2>工作台暂时无法加载</h2>
        <p>页面资源版本刚刚更新，重新加载后即可继续使用。</p>
        <button type="button" onClick={() => window.location.reload()}>
          重新加载页面
        </button>
      </section>
    );
  }
}

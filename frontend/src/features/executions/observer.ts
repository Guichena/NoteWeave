import { ReconnectingSseClient, type RawSseEvent } from "../../shared/event-stream";

type ExecutionObserverOptions = {
  taskId: string;
  refresh: () => Promise<unknown>;
  pollIntervalMs: number;
  onStreamEvent?: (event: RawSseEvent) => void;
  fetcher?: typeof fetch;
};

/**
 * Observes one task through SSE. Periodic HTTP refresh is armed only while the
 * stream is disconnected, so it is a recovery path rather than a concurrent
 * second source of truth.
 */
export class ExecutionObserver {
  private readonly stream: ReconnectingSseClient;
  private fallbackTimer: ReturnType<typeof globalThis.setTimeout> | undefined;
  private stopped = true;
  private connected = false;

  constructor(private readonly options: ExecutionObserverOptions) {
    this.stream = new ReconnectingSseClient(
      `/api/v2/tasks/${encodeURIComponent(options.taskId)}/events`,
      {
        fetcher: options.fetcher,
        onEvent: (event) => {
          options.onStreamEvent?.(event);
          void options.refresh().catch(() => undefined);
        },
        onConnectionChange: (connected) => {
          this.connected = connected;
          if (connected) {
            this.clearFallback();
          } else {
            this.armFallback();
          }
        },
        onError: () => this.armFallback()
      }
    );
  }

  start() {
    if (!this.stopped) {
      return;
    }
    this.stopped = false;
    this.armFallback();
    this.stream.start();
  }

  stop() {
    this.stopped = true;
    this.clearFallback();
    this.stream.stop();
  }

  private armFallback() {
    if (this.stopped || this.connected || this.options.pollIntervalMs <= 0 || this.fallbackTimer) {
      return;
    }
    this.fallbackTimer = globalThis.setTimeout(async () => {
      this.fallbackTimer = undefined;
      if (this.stopped || this.connected) {
        return;
      }
      await this.options.refresh().catch(() => undefined);
      this.armFallback();
    }, this.options.pollIntervalMs);
  }

  private clearFallback() {
    if (this.fallbackTimer !== undefined) {
      globalThis.clearTimeout(this.fallbackTimer);
      this.fallbackTimer = undefined;
    }
  }
}

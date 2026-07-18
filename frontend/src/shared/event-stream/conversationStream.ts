import { apiClient, type ApiClient } from "../api";
import { parseRawSseEvents, type RawSseEvent } from "./sse";

type ConversationStreamOptions = {
  client?: ApiClient;
  fetcher?: typeof fetch;
  onEvent: (event: RawSseEvent) => void;
  onConnectionChange?: (connected: boolean) => void;
  onError?: (error: unknown) => void;
  minReconnectMs?: number;
  maxReconnectMs?: number;
};

export class ConversationStreamClient {
  private readonly client: ApiClient;
  private readonly fetcher?: typeof fetch;
  private readonly onEvent: (event: RawSseEvent) => void;
  private readonly onConnectionChange: (connected: boolean) => void;
  private readonly onError: (error: unknown) => void;
  private readonly minReconnectMs: number;
  private readonly maxReconnectMs: number;
  private stopped = true;
  private abortController: AbortController | null = null;
  private cursor = "";

  constructor(private readonly path: string, options: ConversationStreamOptions) {
    this.client = options.client ?? apiClient;
    this.fetcher = options.fetcher;
    this.onEvent = options.onEvent;
    this.onConnectionChange = options.onConnectionChange ?? (() => undefined);
    this.onError = options.onError ?? (() => undefined);
    this.minReconnectMs = options.minReconnectMs ?? 250;
    this.maxReconnectMs = options.maxReconnectMs ?? 5_000;
  }

  start() {
    if (!this.stopped) {
      return;
    }
    this.stopped = false;
    void this.run();
  }

  stop() {
    this.stopped = true;
    this.abortController?.abort();
    this.abortController = null;
    this.onConnectionChange(false);
  }

  currentCursor() {
    return this.cursor;
  }

  private async run() {
    let reconnectMs = this.minReconnectMs;
    while (!this.stopped) {
      this.abortController = new AbortController();
      try {
        const headers: Record<string, string> = { Accept: "text/event-stream" };
        if (this.cursor) {
          headers["Last-Event-ID"] = this.cursor;
        }
        const response = this.fetcher
          ? await this.fetcher(this.path, { headers, signal: this.abortController.signal })
          : await this.client.raw(this.path, { headers, signal: this.abortController.signal });
        if (!response.ok || !response.body) {
          throw new Error(`Conversation event stream failed: ${response.status}`);
        }
        this.onConnectionChange(true);
        reconnectMs = this.minReconnectMs;
        await this.consume(response.body);
        if (!this.stopped) {
          throw new Error("Conversation event stream closed");
        }
      } catch (error) {
        if (this.stopped || isAbortError(error)) {
          break;
        }
        this.onConnectionChange(false);
        this.onError(error);
        await delay(reconnectMs);
        reconnectMs = Math.min(this.maxReconnectMs, reconnectMs * 2);
      }
    }
  }

  private async consume(body: ReadableStream<Uint8Array>) {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (!this.stopped) {
      const { done, value } = await reader.read();
      buffer += decoder.decode(value, { stream: !done }).replaceAll("\r\n", "\n");
      let boundary = buffer.indexOf("\n\n");
      while (boundary >= 0) {
        this.emit(buffer.slice(0, boundary));
        buffer = buffer.slice(boundary + 2);
        boundary = buffer.indexOf("\n\n");
      }
      if (done) {
        if (buffer.trim()) {
          this.emit(buffer);
        }
        return;
      }
    }
    await reader.cancel();
  }

  private emit(block: string) {
    for (const event of parseRawSseEvents(`${block}\n\n`)) {
      if (event.id) {
        this.cursor = event.id;
      }
      this.onEvent(event);
    }
  }
}

function delay(milliseconds: number) {
  return new Promise((resolve) => globalThis.setTimeout(resolve, milliseconds));
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === "AbortError";
}

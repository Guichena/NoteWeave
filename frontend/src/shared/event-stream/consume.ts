import { apiClient, type ApiClient } from "../api";
import { parseRawSseEvents, type RawSseEvent } from "./sse";

export async function consumeSse(
  path: string,
  onEvent: (event: RawSseEvent) => void,
  signal?: AbortSignal,
  client: ApiClient = apiClient
) {
  const response = await client.raw(path, {
    headers: { Accept: "text/event-stream" },
    signal
  });
  await consumeSseResponse(response, onEvent, signal);
}

export async function consumeSseResponse(
  response: Response,
  onEvent: (event: RawSseEvent) => void,
  signal?: AbortSignal
) {
  if (!response.ok || !response.body) {
    throw new Error(`Event stream failed: ${response.status}`);
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  const cancelReader = () => void reader.cancel();
  signal?.addEventListener("abort", cancelReader, { once: true });
  try {
    while (!signal?.aborted) {
      const { done, value } = await reader.read();
      buffer += decoder.decode(value, { stream: !done }).replaceAll("\r\n", "\n");
      let boundary = buffer.indexOf("\n\n");
      while (boundary >= 0 && !signal?.aborted) {
        emitBlock(buffer.slice(0, boundary), onEvent);
        buffer = buffer.slice(boundary + 2);
        boundary = buffer.indexOf("\n\n");
      }
      if (done) {
        if (buffer.trim() && !signal?.aborted) {
          emitBlock(buffer, onEvent);
        }
        return;
      }
    }
  } finally {
    signal?.removeEventListener("abort", cancelReader);
  }
}

function emitBlock(block: string, onEvent: (event: RawSseEvent) => void) {
  parseRawSseEvents(`${block}\n\n`).forEach(onEvent);
}

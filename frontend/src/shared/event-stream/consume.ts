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
  if (!response.body) {
    throw new Error("浏览器不支持流式响应");
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value, { stream: !done }).replaceAll("\r\n", "\n");
    let boundary = buffer.indexOf("\n\n");
    while (boundary >= 0) {
      emitBlock(buffer.slice(0, boundary), onEvent);
      buffer = buffer.slice(boundary + 2);
      boundary = buffer.indexOf("\n\n");
    }
    if (done) {
      if (buffer.trim()) {
        emitBlock(buffer, onEvent);
      }
      return;
    }
  }
}

function emitBlock(block: string, onEvent: (event: RawSseEvent) => void) {
  parseRawSseEvents(`${block}\n\n`).forEach(onEvent);
}

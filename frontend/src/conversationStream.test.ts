import { describe, expect, it, vi } from "vitest";
import { ConversationStreamClient } from "./shared/event-stream";

describe("ConversationStreamClient", () => {
  it("parses chunked events and advances the conversation cursor", async () => {
    const events: Array<{ event: string; id: string; data: string }> = [];
    let client: ConversationStreamClient;
    const fetcher = vi.fn(async () => responseFromChunks([
      "event:conversation.snapshot\ndata:{}\n\nid:1\nevent:answer.del",
      "ta\ndata:{\"run_id\":\"run-1\",\"data\":\"hello\"}\n\n",
      "id:2\nevent:answer.completed\ndata:{\"run_id\":\"run-1\"}\n\n"
    ]));
    client = new ConversationStreamClient("/events", {
      fetcher: fetcher as unknown as typeof fetch,
      onEvent: (event) => {
        events.push(event);
        if (event.event === "answer.completed") {
          client.stop();
        }
      }
    });

    client.start();
    await vi.waitFor(() => expect(events).toHaveLength(3));

    expect(events.map((event) => event.event)).toEqual([
      "conversation.snapshot", "answer.delta", "answer.completed"
    ]);
    expect(client.currentCursor()).toBe("2");
  });

  it("sends Last-Event-ID when reconnecting", async () => {
    const requests: RequestInit[] = [];
    let calls = 0;
    let client: ConversationStreamClient;
    const fetcher = vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
      requests.push(init ?? {});
      calls += 1;
      if (calls === 1) {
        return responseFromChunks(["id:7\nevent:answer.delta\ndata:x\n\n"]);
      }
      return responseFromChunks(["id:8\nevent:answer.completed\ndata:done\n\n"]);
    });
    client = new ConversationStreamClient("/events", {
      fetcher: fetcher as unknown as typeof fetch,
      minReconnectMs: 1,
      maxReconnectMs: 1,
      onEvent: (event) => {
        if (event.id === "8") {
          client.stop();
        }
      }
    });

    client.start();
    await vi.waitFor(() => expect(requests).toHaveLength(2));

    expect((requests[1].headers as Record<string, string>)["Last-Event-ID"]).toBe("7");
  });
});

function responseFromChunks(chunks: string[]) {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
      controller.close();
    }
  });
  return new Response(body, { status: 200 });
}

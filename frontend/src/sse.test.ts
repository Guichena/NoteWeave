import { describe, expect, it } from "vitest";
import { parseRawSseEvents } from "./shared/event-stream";

describe("parseRawSseEvents", () => {
  it("accepts standard fields with or without a space", () => {
    expect(parseRawSseEvents("id:1\nevent:answer.delta\ndata:hello\n\n")).toEqual([
      { id: "1", event: "answer.delta", data: "hello" }
    ]);
    expect(parseRawSseEvents("id: 2\nevent: answer.completed\ndata: done\n\n")[0]).toEqual({
      id: "2",
      event: "answer.completed",
      data: "done"
    });
  });

  it("joins multiline data according to the SSE protocol", () => {
    expect(parseRawSseEvents("event:answer.delta\ndata:first\ndata:second\n\n")[0].data)
      .toBe("first\nsecond");
  });

  it("preserves literal escaped newlines inside an SSE data field", () => {
    expect(parseRawSseEvents("event:answer.delta\ndata:C:\\new\\nvalue\n\n")[0].data)
      .toBe("C:\\new\\nvalue");
  });

  it("parses multiple events and ignores incomplete blocks", () => {
    const events = parseRawSseEvents([
      "id:1\nevent:answer.delta\ndata:a",
      "id:2\nevent:citation.upsert\ndata:b",
      "data:missing-event"
    ].join("\n\n"));
    expect(events.map((event) => event.event)).toEqual(["answer.delta", "citation.upsert"]);
  });
});

import { describe, expect, it } from "vitest";
import { parseRawSseEvents } from "./shared/event-stream";

describe("parseRawSseEvents", () => {
  it("accepts standard fields with or without a space", () => {
    expect(parseRawSseEvents("id:1\nevent:chat.delta\ndata:hello\n\n")).toEqual([
      { id: "1", event: "chat.delta", data: "hello" }
    ]);
    expect(parseRawSseEvents("id: 2\nevent: chat.completed\ndata: done\n\n")[0]).toEqual({
      id: "2",
      event: "chat.completed",
      data: "done"
    });
  });

  it("joins multiline data according to the SSE protocol", () => {
    expect(parseRawSseEvents("event:chat.delta\ndata:first\ndata:second\n\n")[0].data)
      .toBe("first\nsecond");
  });

  it("parses multiple events and ignores incomplete blocks", () => {
    const events = parseRawSseEvents([
      "id:1\nevent:chat.delta\ndata:a",
      "id:2\nevent:chat.citation\ndata:b",
      "data:missing-event"
    ].join("\n\n"));
    expect(events.map((event) => event.event)).toEqual(["chat.delta", "chat.citation"]);
  });
});

export type RawSseEvent = {
  event: string;
  data: string;
  id: string;
};

export function parseRawSseEvents(stream: string): RawSseEvent[] {
  return stream.replaceAll("\r\n", "\n")
    .split("\n\n")
    .map(parseBlock)
    .filter((event): event is RawSseEvent => event !== null);
}

function parseBlock(block: string): RawSseEvent | null {
  const lines = block.split("\n");
  const event = readSingleField(lines, "event");
  if (!event) {
    return null;
  }
  return {
    event,
    id: readSingleField(lines, "id"),
    data: lines
      .filter((line) => line.startsWith("data:"))
      .map((line) => stripOptionalSpace(line.slice("data:".length)))
      .join("\n")
  };
}

function readSingleField(lines: string[], field: string) {
  const prefix = `${field}:`;
  const line = lines.find((candidate) => candidate.startsWith(prefix));
  return line ? stripOptionalSpace(line.slice(prefix.length)) : "";
}

function stripOptionalSpace(value: string) {
  return value.startsWith(" ") ? value.slice(1) : value;
}

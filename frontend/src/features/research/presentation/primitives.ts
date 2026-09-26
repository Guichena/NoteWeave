export function summarizeText(text: string, limit = 140) {
  if (text.length <= limit) {
    return text;
  }
  return `${text.slice(0, limit).trimEnd()}...`;
}

export function normalizeSignalValue(value: unknown) {
  return typeof value === "string" ? value.trim() : "";
}

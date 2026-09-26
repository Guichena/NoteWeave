export const SUPPORTED_SOURCE_FORMATS = ["PDF", "MD", "TXT", "JSON", "CSV"] as const;
export const SOURCE_FILE_ACCEPT =
  ".pdf,.md,.markdown,.txt,.json,.csv,application/pdf,text/markdown,text/plain,application/json,text/csv";

const SUPPORTED_SOURCE_EXTENSIONS = new Set(["pdf", "md", "markdown", "txt", "json", "csv"]);
const SUPPORTED_SOURCE_MIME_TYPES = new Set([
  "application/pdf",
  "text/markdown",
  "text/plain",
  "application/json",
  "text/csv",
  "application/csv"
]);
const MAX_SOURCE_FILE_BYTES = 128 * 1024 * 1024;

export function validateSourceFile(file: File) {
  if (file.size > MAX_SOURCE_FILE_BYTES) {
    return `《${file.name}》超过 128 MB，未开始上传。`;
  }
  const extension = file.name.includes(".") ? file.name.split(".").pop()?.toLocaleLowerCase() ?? "" : "";
  const mimeType = file.type.toLocaleLowerCase();
  if (!SUPPORTED_SOURCE_EXTENSIONS.has(extension) && !SUPPORTED_SOURCE_MIME_TYPES.has(mimeType)) {
    return `《${file.name}》格式不受支持，请选择 PDF、MD、TXT、JSON 或 CSV 文件。`;
  }
  return "";
}

/** 按扩展名 / 类型给来源一个稳定的视觉类别，用于图标颜色。 */
export function sourceKind(source: { title: string; source_type?: string }): "pdf" | "doc" | "data" | "web" | "note" {
  const type = (source.source_type || "").toUpperCase();
  const extension = source.title.includes(".") ? source.title.split(".").pop()?.toLowerCase() ?? "" : "";
  if (type.includes("PDF") || extension === "pdf") return "pdf";
  if (type.includes("CSV") || type.includes("JSON") || extension === "csv" || extension === "json") return "data";
  if (type.includes("WEB") || type.includes("URL") || type.includes("HTML")) return "web";
  if (type.includes("NOTE") || type.includes("ANSWER") || type.includes("RESEARCH")) return "note";
  return "doc";
}

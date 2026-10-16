/** 后端支持的资料格式：扩展名（不含点）与规范 MIME 类型。 */
export type SourceFormat = {
  extension: string;
  mime_type: string;
};

/** 后端 `GET /uploads/formats` 不可用时使用的内置列表，与后端 SourceMediaTypes 一致。 */
export const DEFAULT_SOURCE_FORMATS: SourceFormat[] = [
  { extension: "pdf", mime_type: "application/pdf" },
  { extension: "md", mime_type: "text/markdown" },
  { extension: "markdown", mime_type: "text/markdown" },
  { extension: "txt", mime_type: "text/plain" },
  { extension: "json", mime_type: "application/json" },
  { extension: "csv", mime_type: "text/csv" },
  { extension: "mp3", mime_type: "audio/mpeg" },
  { extension: "m4a", mime_type: "audio/mp4" },
  { extension: "wav", mime_type: "audio/wav" },
  { extension: "ogg", mime_type: "audio/ogg" },
  { extension: "flac", mime_type: "audio/flac" },
  { extension: "webm", mime_type: "audio/webm" },
  { extension: "mp4", mime_type: "video/mp4" }
];

/** 音视频上传后在解析阶段通过 MCP 转写工具生成带时间戳的文字稿。 */
export const MEDIA_SOURCE_EXTENSIONS = new Set(["mp3", "m4a", "wav", "ogg", "flac", "webm", "mp4"]);
export const MAX_SOURCE_FILE_BYTES = 128 * 1024 * 1024;

function fileExtension(fileName: string) {
  return fileName.includes(".") ? fileName.split(".").pop()?.toLocaleLowerCase() ?? "" : "";
}

/** 文件选择器的 accept 属性：扩展名加 MIME 类型。 */
export function buildSourceAccept(formats: SourceFormat[]) {
  const extensions = formats.map((format) => `.${format.extension}`);
  const mimeTypes = formats.map((format) => format.mime_type);
  return Array.from(new Set([...extensions, ...mimeTypes])).join(",");
}

/** 展示给用户的格式名，例如 PDF、MD、MP3。markdown 与 md 视为同一种。 */
export function sourceFormatNames(formats: SourceFormat[]) {
  return Array.from(new Set(formats
    .map((format) => (format.extension === "markdown" ? "md" : format.extension).toUpperCase())));
}

export function describeSourceFormats(formats: SourceFormat[]) {
  return sourceFormatNames(formats).join("、");
}

/** 按扩展名找后端认可的 MIME 类型；不支持的格式返回空字符串。 */
export function resolveSourceMimeType(fileName: string, formats: SourceFormat[] = DEFAULT_SOURCE_FORMATS) {
  const extension = fileExtension(fileName);
  return formats.find((format) => format.extension === extension)?.mime_type ?? "";
}

export function validateSourceFile(file: File, formats: SourceFormat[] = DEFAULT_SOURCE_FORMATS) {
  if (file.size > MAX_SOURCE_FILE_BYTES) {
    return `《${file.name}》超过 128 MB，未开始上传。`;
  }
  if (!resolveSourceMimeType(file.name, formats)) {
    return `《${file.name}》格式不受支持，目前支持 ${describeSourceFormats(formats)}。`;
  }
  return "";
}

/** 按扩展名 / 类型给来源一个稳定的视觉类别，用于图标颜色。 */
export function sourceKind(source: { title: string; source_type?: string }): "pdf" | "doc" | "data" | "web" | "note" | "media" {
  const type = (source.source_type || "").toUpperCase();
  const extension = fileExtension(source.title);
  if (MEDIA_SOURCE_EXTENSIONS.has(extension)) return "media";
  if (type.includes("PDF") || extension === "pdf") return "pdf";
  if (type.includes("CSV") || type.includes("JSON") || extension === "csv" || extension === "json") return "data";
  if (type.includes("WEB") || type.includes("URL") || type.includes("HTML")) return "web";
  if (type.includes("NOTE") || type.includes("ANSWER") || type.includes("RESEARCH")) return "note";
  return "doc";
}

// 各工作台从后端读到的格式列表，上传校验与文件选择器共用
const formatsByWorkspace = new Map<string, SourceFormat[]>();

export function cachedSourceFormats(workspaceId: string) {
  return formatsByWorkspace.get(workspaceId) ?? DEFAULT_SOURCE_FORMATS;
}

export function cacheSourceFormats(workspaceId: string, formats: SourceFormat[]) {
  formatsByWorkspace.set(workspaceId, formats);
}

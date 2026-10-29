import { useEffect, useState } from "react";
import { SourcesApi } from "./api";
import { mediaClock } from "./SourceLinkForm";
import type { SourceMediaDocument, SourceMediaFrame } from "./model";
import "./sourceMedia.css";

const api = new SourcesApi();

export function SourceMediaPreview({ workspaceId, sourceId }: { workspaceId: string; sourceId: string }) {
  const [document, setDocument] = useState<SourceMediaDocument | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    let cancelled = false;
    setDocument(null);
    setError("");
    api.readMedia(workspaceId, sourceId).then((result) => { if (!cancelled) setDocument(result); })
      .catch((reason: unknown) => { if (!cancelled) setError(reason instanceof Error ? reason.message : "图文解析结果暂时无法读取。"); });
    return () => { cancelled = true; };
  }, [workspaceId, sourceId]);
  if (error) return <p role="alert">{error}</p>;
  if (!document) return <p role="status">正在读取图文解析结果</p>;
  return (
    <section className="source-media-preview" aria-label="音视频图文解析结果">
      <header>
        <strong>图文解析</strong>
        <span>候选画面 {document.stats.candidate_frames} 张，去重后 {document.stats.kept_frames} 张</span>
        <span>转写来源：{document.stats.transcript_origin === "SUBTITLE" ? "字幕" : document.stats.transcript_origin === "MIXED" ? "字幕与语音识别" : "语音识别"}</span>
        {document.source_url ? <a href={document.source_url} target="_blank" rel="noreferrer">查看来源视频</a> : null}
      </header>
      {document.stats.vision_status === "NOT_CONFIGURED" ? <p>画面描述暂未配置，以下显示转写文字和 OCR 结果。</p> : null}
      <ol>
        {document.segments.map((segment, index) => (
          <li key={`${segment.part}-${segment.start_ms}-${index}`}>
            <small>P{segment.part} · {mediaClock(segment.start_ms)}–{mediaClock(segment.end_ms)}</small>
            {segment.text ? <p>{segment.text}</p> : null}
            {segment.frames.map((frame) => <MediaFrame key={frame.frame_id} workspaceId={workspaceId} sourceId={sourceId} frame={frame} />)}
          </li>
        ))}
      </ol>
    </section>
  );
}

function MediaFrame({ workspaceId, sourceId, frame }: { workspaceId: string; sourceId: string; frame: SourceMediaFrame }) {
  const [imageUrl, setImageUrl] = useState("");
  const [error, setError] = useState("");
  useEffect(() => {
    let disposed = false;
    let ownedUrl = "";
    setImageUrl("");
    setError("");
    api.readFrame(workspaceId, sourceId, frame.frame_id).then((blob) => {
      if (disposed) return;
      ownedUrl = URL.createObjectURL(blob);
      setImageUrl(ownedUrl);
    }).catch(() => { if (!disposed) setError("关键帧图片暂时无法读取"); });
    return () => { disposed = true; if (ownedUrl) URL.revokeObjectURL(ownedUrl); };
  }, [workspaceId, sourceId, frame.frame_id]);
  return (
    <figure>
      {imageUrl ? <img src={imageUrl} alt={frame.caption || `视频关键帧 ${mediaClock(frame.at_ms)}`} loading="lazy" /> : <p role="status">{error || "正在读取关键帧"}</p>}
      <figcaption>{mediaClock(frame.at_ms)}{frame.caption ? ` · ${frame.caption}` : ""}</figcaption>
      {frame.ocr_text ? <p className="source-frame-ocr">{frame.ocr_text}</p> : null}
    </figure>
  );
}

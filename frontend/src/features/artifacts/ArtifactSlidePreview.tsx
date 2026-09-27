import { useEffect, useState } from "react";
import { artifactsApi, type ArtifactsApi } from "./api";
import type { ArtifactFileMetadata } from "./model";

type SlidePreviewProps = {
  workspaceId: string;
  artifactJobId: string;
  versionNo: number;
  files: ArtifactFileMetadata[];
  api?: Pick<ArtifactsApi, "downloadFile">;
};

export function ArtifactSlidePreview({ workspaceId, artifactJobId, versionNo, files,
  api = artifactsApi }: SlidePreviewProps) {
  const slides = files.filter((file) => file.file_format === "PNG" && file.status === "READY")
    .sort((left, right) => left.file_name.localeCompare(right.file_name, undefined, { numeric: true }));
  const [open, setOpen] = useState(false);
  const [index, setIndex] = useState(0);
  const [imageUrl, setImageUrl] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const file = slides[index];

  useEffect(() => {
    if (!open || !file) return;
    let cancelled = false;
    let url = "";
    setLoading(true);
    setImageUrl("");
    setError("");
    void api.downloadFile(workspaceId, artifactJobId, versionNo, file.file_id)
      .then((blob) => {
        if (cancelled) return;
        url = URL.createObjectURL(blob);
        setImageUrl(url);
      })
      .catch((failure: unknown) => {
        if (!cancelled) setError(failure instanceof Error ? failure.message : "页面预览加载失败");
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => {
      cancelled = true;
      if (url) URL.revokeObjectURL(url);
    };
  }, [open, file?.file_id, workspaceId, artifactJobId, versionNo, api]);

  if (!workspaceId || slides.length === 0) return null;
  return (
    <section className="artifact-slide-preview" aria-label="演示文稿逐页预览">
      <button className="secondary-button" type="button" onClick={() => setOpen((value) => !value)}>
        {open ? "收起逐页预览" : `预览逐页画面（${slides.length} 页）`}
      </button>
      {open && file ? (
        <div className="artifact-slide-preview-content">
          <div className="artifact-slide-preview-nav">
            <button className="secondary-button" type="button" disabled={index === 0}
              onClick={() => setIndex((value) => value - 1)}>上一页</button>
            <span>第 {index + 1} / {slides.length} 页</span>
            <button className="secondary-button" type="button" disabled={index === slides.length - 1}
              onClick={() => setIndex((value) => value + 1)}>下一页</button>
          </div>
          {loading ? <p role="status">正在加载页面</p> : null}
          {error ? <p role="alert">{error}</p> : null}
          {imageUrl ? <img src={imageUrl} alt={`演示文稿第 ${index + 1} 页：${file.file_name}`} /> : null}
        </div>
      ) : null}
    </section>
  );
}

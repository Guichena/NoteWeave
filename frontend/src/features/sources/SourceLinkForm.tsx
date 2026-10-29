import { useEffect, useRef, useState } from "react";
import { SourcesApi } from "./api";
import type { SourceVideoInfo } from "./model";
import "./sourceMedia.css";

const api = new SourcesApi();

export function SourceLinkForm({ workspaceId, refreshSources }: { workspaceId: string; refreshSources: () => Promise<void> }) {
  const [url, setUrl] = useState("");
  const [info, setInfo] = useState<SourceVideoInfo | null>(null);
  const [parts, setParts] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const generation = useRef(0);
  useEffect(() => () => { generation.current += 1; }, []);

  async function preview() {
    const current = ++generation.current;
    setBusy(true);
    setMessage("");
    try {
      const result = await api.previewLink(workspaceId, url.trim());
      if (current !== generation.current) return;
      setInfo(result);
      setParts(result.parts.slice(0, 1).map((part) => part.part));
    } catch (error) {
      if (current === generation.current) setMessage(error instanceof Error ? error.message : "视频预览失败，请稍后重试。");
    } finally {
      if (current === generation.current) setBusy(false);
    }
  }

  async function submit() {
    if (!info || !parts.length) return;
    const current = ++generation.current;
    setBusy(true);
    setMessage("");
    try {
      await api.addLink(workspaceId, info.source_url, parts);
      if (current !== generation.current) return;
      setMessage("视频已加入资料库，正在后台解析。");
      setUrl("");
      setInfo(null);
      await refreshSources();
    } catch (error) {
      if (current === generation.current) setMessage(error instanceof Error ? error.message : "添加视频失败，请稍后重试。");
    } finally {
      if (current === generation.current) setBusy(false);
    }
  }

  return (
    <details className="source-paste-disclosure source-link-form">
      <summary>粘贴 B 站视频链接</summary>
      <label className="input-block">
        <span>视频链接</span>
        <input type="url" placeholder="https://www.bilibili.com/video/BV…" value={url} disabled={busy}
          onChange={(event) => { setUrl(event.target.value); setInfo(null); setMessage(""); }} />
      </label>
      <button type="button" className="secondary-button" onClick={() => void preview()} disabled={busy || !url.trim()}>
        {busy ? "处理中" : "预览视频"}
      </button>
      {info ? (
        <div className="source-link-preview">
          <strong>{info.title}</strong>
          <small>总时长 {mediaClock(info.duration_ms)} · {info.parts.length} 个分 P</small>
          {info.parts.length > 1 ? (
            <fieldset>
              <legend>选择要解析的分 P</legend>
              {info.parts.map((part) => (
                <label key={part.part}>
                  <input type="checkbox" checked={parts.includes(part.part)} disabled={busy}
                    onChange={(event) => setParts((previous) => event.target.checked ? [...previous, part.part] : previous.filter((item) => item !== part.part))} />
                  <span>P{part.part} {part.title} · {mediaClock(part.duration_ms)}</span>
                </label>
              ))}
            </fieldset>
          ) : null}
          <button type="button" className="primary-action" disabled={busy || parts.length === 0} onClick={() => void submit()}>加入资料库</button>
        </div>
      ) : null}
      {message ? <p role="status">{message}</p> : null}
    </details>
  );
}

export function mediaClock(ms: number) {
  const seconds = Math.max(0, Math.floor(ms / 1000));
  return [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60].map((value) => String(value).padStart(2, "0")).join(":");
}

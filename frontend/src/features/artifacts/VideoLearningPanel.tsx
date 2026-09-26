import { useEffect, useRef, useState, type FormEvent } from "react";
import { ChevronDown, ChevronRight, Video } from "lucide-react";
import { artifactsApi, type ArtifactsApi } from "./api";
import type {
  CreateVideoLearningInput, VideoLearningOverview, VideoLearningRequest
} from "./model";

const OUTPUTS = [
  { key: "knowledge_blog", title: "知识博客", format: "Markdown" },
  { key: "interview_qa", title: "面试问答", format: "Markdown" },
  { key: "video_learning_deck", title: "学习演示文稿", format: "PPTX" },
  { key: "bilibili_course_note_pdf", title: "图文讲义", format: "PDF" }
] as const;

const LABELS: Record<string, string> = {
  QUEUED: "排队中", RUNNING: "制作中", WAITING: "等待资料",
  READY: "已就绪", COMPLETED: "已完成", FAILED: "失败",
  CANCELLED: "已取消", NOT_STARTED: "等待素材", DEGRADED: "文件异常"
};

const ACTIVE = new Set(["QUEUED", "RUNNING", "WAITING", "NOT_STARTED"]);

function titleFor(key: string) {
  return OUTPUTS.find((output) => output.key === key)?.title ?? key;
}

function labelFor(status: string) {
  return LABELS[status] ?? status;
}

export function VideoLearningPanel({ workspaceId, onJobsChanged, onOpenVersion, api = artifactsApi }: {
  workspaceId: string;
  onJobsChanged?: () => void | Promise<unknown>;
  onOpenVersion?: (artifactJobId: string, versionNo: number) => void | Promise<unknown>;
  api?: ArtifactsApi;
}) {
  const [overview, setOverview] = useState<VideoLearningOverview | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [videoUrl, setVideoUrl] = useState("");
  const [part, setPart] = useState("1");
  const [language, setLanguage] = useState<CreateVideoLearningInput["language"]>("zh-CN");
  const [density, setDensity] = useState<CreateVideoLearningInput["frame_density"]>("STANDARD");
  const [asr, setAsr] = useState<CreateVideoLearningInput["asr_fallback"]>("ALLOW");
  const [requirement, setRequirement] = useState("");
  const [selected, setSelected] = useState<string[]>(["knowledge_blog"]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const requestId = useRef(crypto.randomUUID());
  const available = overview?.available_skills ?? OUTPUTS.map((output) => output.key);
  const effectiveSelected = selected.filter((key) => available.includes(key));
  const visualOutputs = effectiveSelected.filter((key) =>
    key === "video_learning_deck" || key === "bilibili_course_note_pdf");
  const densityLabel = { LOW: "精简", STANDARD: "标准", HIGH: "丰富" }[density];

  useEffect(() => {
    if (!workspaceId) {
      setOverview(null);
      return;
    }
    let alive = true;
    const refresh = async () => {
      try {
        const current = await api.listVideoLearning(workspaceId);
        if (alive) {
          setOverview(current);
          setError("");
        }
      } catch (failure) {
        if (alive) setError(failure instanceof Error ? failure.message : "视频任务状态加载失败");
      }
    };
    void refresh();
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") void refresh();
    }, 5000);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, [workspaceId, api]);

  function changed(update: () => void) {
    update();
    requestId.current = crypto.randomUUID();
    setError("");
  }

  function toggleSkill(key: string) {
    changed(() => setSelected((current) => current.includes(key)
      ? current.filter((item) => item !== key)
      : [...current, key]));
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!workspaceId || effectiveSelected.length === 0 || busy) return;
    setBusy(true);
    setError("");
    try {
      const created = await api.createVideoLearning(workspaceId, {
        client_request_id: requestId.current,
        video_url: videoUrl.trim(),
        part: Number(part), language, frame_density: density,
        asr_fallback: asr, template_version: "original-v1",
        user_requirement: requirement.trim(), selected_skills: effectiveSelected
      });
      setOverview((current) => current
        ? { ...current, requests: [created,
            ...current.requests.filter((item) => item.request_id !== created.request_id)] }
        : { enabled: true, requests: [created] });
      requestId.current = crypto.randomUUID();
      setExpanded(false);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "视频学习任务创建失败");
    } finally {
      setBusy(false);
    }
  }

  async function cancel(item: VideoLearningRequest) {
    setBusy(true);
    setError("");
    try {
      const updated = await api.cancelVideoLearning(workspaceId, item.request_id);
      setOverview((current) => current
        ? { ...current, requests: current.requests.map((request) =>
            request.request_id === updated.request_id ? updated : request) }
        : current);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "取消请求失败");
    } finally {
      setBusy(false);
    }
  }

  async function retryChoice(item: VideoLearningRequest, skillKey: string) {
    setBusy(true);
    setError("");
    try {
      const updated = await api.retryVideoLearningChoice(workspaceId, item.request_id, skillKey);
      setOverview((current) => current
        ? { ...current, requests: current.requests.map((request) =>
            request.request_id === updated.request_id ? updated : request) }
        : current);
      await onJobsChanged?.();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "子产物重试失败");
    } finally {
      setBusy(false);
    }
  }

  if (!workspaceId || (!overview?.enabled && !overview?.requests.length)) return null;

  return (
    <section className="video-learning-panel" aria-labelledby="video-learning-heading">
      <div className="artifact-section-heading">
        <h3 className="section-label" id="video-learning-heading">视频资料包</h3>
        <Video size={16} aria-hidden="true" />
      </div>
      {overview?.enabled ? (
        <>
          <button className="video-learning-toggle secondary-button" type="button"
            aria-expanded={expanded} onClick={() => setExpanded((current) => !current)}>
            <span>一个视频，生成多种产物</span>
            {expanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
          </button>
          {expanded ? (
            <form className="video-learning-form" onSubmit={(event) => void submit(event)}>
              <label className="rail-field"><span>B站视频链接</span>
                <input type="url" required value={videoUrl}
                  placeholder="https://www.bilibili.com/video/BV..."
                  onChange={(event) => changed(() => setVideoUrl(event.target.value))} />
              </label>
              <div className="video-learning-pair">
                <label className="rail-field"><span>分集</span>
                  <input type="number" min="1" max="1000" required value={part}
                    onChange={(event) => changed(() => setPart(event.target.value))} />
                </label>
                <label className="rail-field"><span>输出语言</span>
                  <select value={language} onChange={(event) => changed(() =>
                    setLanguage(event.target.value as CreateVideoLearningInput["language"]))}>
                    <option value="zh-CN">中文</option><option value="en">English</option>
                    <option value="zh-EN">中英双语</option>
                  </select>
                </label>
              </div>
              <fieldset className="video-learning-choices">
                <legend>选择产物</legend>
                {OUTPUTS.map((output) => (
                  <label key={output.key}>
                    <input type="checkbox" checked={effectiveSelected.includes(output.key)}
                      disabled={!available.includes(output.key)}
                      onChange={() => toggleSkill(output.key)} />
                    <span>{output.title}</span><small>{available.includes(output.key) ? output.format : "暂停新建"}</small>
                  </label>
                ))}
              </fieldset>
              <div className="video-learning-pair">
                <label className="rail-field"><span>画面密度</span>
                  <select value={density} onChange={(event) => changed(() =>
                    setDensity(event.target.value as CreateVideoLearningInput["frame_density"]))}>
                    <option value="LOW">精简</option><option value="STANDARD">标准</option>
                    <option value="HIGH">丰富</option>
                  </select>
                </label>
                <label className="rail-field"><span>无字幕时</span>
                  <select value={asr} onChange={(event) => changed(() =>
                    setAsr(event.target.value as CreateVideoLearningInput["asr_fallback"]))}>
                    <option value="ALLOW">允许转录</option><option value="DENY">仅用字幕</option>
                  </select>
                </label>
              </div>
              <label className="rail-field"><span>学习重点</span>
                <textarea required maxLength={4000} rows={3} value={requirement}
                  placeholder="写下希望重点理解或练习的内容"
                  onChange={(event) => changed(() => setRequirement(event.target.value))} />
              </label>
              <p className="video-learning-cost" aria-live="polite">
                资源预估：1 次资料采集，加上 {effectiveSelected.length} 个独立产物任务。<br />
                画面按{densityLabel}密度采集；{visualOutputs.length > 0
                  ? `${visualOutputs.length} 种产物会使用原画面。`
                  : "当前选择以文字内容为主。"}<br />
                {asr === "ALLOW" ? "缺少字幕时可能增加语音转录。" : "缺少字幕时不转录，可能留下资料缺口。"}
                具体耗时与调用费用取决于视频长度及字幕状态。
              </p>
              <button type="submit" disabled={busy || effectiveSelected.length === 0 || !requirement.trim()}>
                {busy ? "正在创建" : `创建 ${effectiveSelected.length} 种产物`}
              </button>
            </form>
          ) : null}
        </>
      ) : null}
      {error ? <p className="artifact-composer-error" role="alert">{error}</p> : null}
      {overview?.requests.map((item) => (
        <div key={item.request_id} className="video-learning-request">
          <p className="video-learning-source">
            {item.video_url.match(/BV[0-9A-Za-z]{10}/)?.[0] ?? "B站视频"} · 第 {item.part} 集
          </p>
          <div className="video-learning-status">
            <strong>资料 {labelFor(item.material_state)}</strong>
            {!item.cancellation_requested && item.material_state !== "FAILED"
                && item.choices.some((choice) => ACTIVE.has(choice.status)) ? (
              <button type="button" className="secondary-button" disabled={busy}
                onClick={() => void cancel(item)}>取消未开始任务</button>
            ) : null}
          </div>
          <ul>{item.choices.map((choice) => (
            <li key={choice.skill_key}>
              <span>{titleFor(choice.skill_key)}</span>
              <small>{labelFor(choice.status)}</small>
              {choice.status === "FAILED" && choice.artifact_job_id
                  && item.material_state === "READY" && !item.cancellation_requested ? (
                <button type="button" className="secondary-button" disabled={busy}
                  onClick={() => void retryChoice(item, choice.skill_key)}>重试</button>
              ) : null}
              {choice.artifact_job_id && choice.latest_version_no > 0 ? (
                <button type="button" className="secondary-button" disabled={busy}
                  onClick={() => {
                    if (choice.artifact_job_id) void onOpenVersion?.(choice.artifact_job_id, choice.latest_version_no);
                  }}>
                  查看 v{choice.latest_version_no}
                </button>
              ) : null}
            </li>
          ))}</ul>
          {item.choices.some((choice) => choice.artifact_job_id) ? (
            <button type="button" className="video-learning-refresh secondary-button"
              onClick={() => void onJobsChanged?.()}>同步下方产物记录</button>
          ) : null}
        </div>
      ))}
    </section>
  );
}

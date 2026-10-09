import * as artifacts from "./fixtures/artifacts";
import * as memory from "./fixtures/memory";
import { evidenceByRun, messages, sources } from "./fixtures/chat";
import { researchTasks, runDetails, runs, runningRun } from "./fixtures/research";
import { reprocessMockSource, sourceTaskEvents, sourceTasks } from "./fixtures/sources";
import * as wiki from "./fixtures/wiki";

type Handler = (match: RegExpMatchArray, request: Request, url: URL) => unknown | Promise<unknown>;

// 每条路由返回与后端一致的响应信封；未匹配的请求交给真实接口处理。
const routes: Array<[method: string, pattern: RegExp, handler: Handler]> = [
  ["GET", /^\/api\/v2\/workspaces\/[^/]+\/sources$/, () => sources],
  // 历史消息只在第一页返回，后续分页为空，与后端分页行为一致。
  ["GET", /^\/api\/v2\/workspaces\/[^/]+\/conversations\/[^/]+\/messages$/, (_m, _r, url) => (
    url.searchParams.get("after_seq") === "0" ? messages : []
  )],
  ["GET", /^\/api\/v2\/workspaces\/[^/]+\/answer-runs\/([^/]+)\/evidence$/, (m) => (
    evidenceByRun[m[1]] ? { run_id: m[1], evidence: evidenceByRun[m[1]] } : undefined
  )],
  ["GET", /^\/api\/v2\/workspaces\/([^/]+)\/wiki-settings$/, (m) => ({ workspace_id: m[1], wiki_enabled: true })],
  // 知识库页面会用 wiki-home 返回的 workspace_id 继续请求，这里按请求路径回填。
  ["GET", /^\/api\/v2\/workspaces\/([^/]+)\/wiki-home$/, (m) => ({ ...wiki.home, workspace_id: m[1] })],
  ["GET", /^\/api\/v2\/workspaces\/([^/]+)\/wiki-index$/, (m) => ({ ...wiki.index, workspace_id: m[1] })],
  ["GET", /^\/api\/v2\/workspaces\/[^/]+\/wiki-stats$/, () => wiki.stats],
  ["GET", /^\/api\/v2\/workspaces\/[^/]+\/wiki-issues$/, () => wiki.wikiIssues],
  ["GET", /^\/api\/v2\/workspaces\/[^/]+\/wiki-log$/, () => []],
  ["GET", /^\/api\/v2\/workspaces\/[^/]+\/wiki\/rebuild-advice$/, () => wiki.rebuildAdvice],
  ["GET", /^\/api\/v2\/workspaces\/[^/]+\/wiki-graph$/, (_m, _r, url) => (
    wiki.graph(url.searchParams.get("mode") ?? "overview", url.searchParams.get("center"))
  )],
  ["GET", /^\/api\/v2\/workspaces\/[^/]+\/wiki-search$/, (_m, _r, url) => {
    const keyword = (url.searchParams.get("q") ?? "").toLowerCase();
    return wiki.pages.filter((page) => `${page.title} ${page.summary}`.toLowerCase().includes(keyword));
  }],
  ["GET", /^\/api\/v2\/knowledge-items\/([^/]+)$/, (m) => wiki.itemDetails[m[1]]],
  ["GET", /^\/api\/v2\/knowledge-items\/([^/]+)\/versions$/, (m) => wiki.versionsByItem[m[1]]],
  ["GET", /^\/api\/v2\/workspaces\/[^/]+\/research-runs$/, () => runs],
  ["POST", /^\/api\/v2\/workspaces\/[^/]+\/research-runs$/, () => ({
    research_run_id: runningRun.research_run_id, task_id: runningRun.task_id, status: "RUNNING"
  })],
  ["GET", /^\/api\/v2\/workspaces\/([^/]+)\/research-runs\/([^/]+)$/, (m) => {
    const run = runDetails[m[2]];
    return run ? { ...run, workspace_id: m[1] } : undefined;
  }],
  ["GET", /^\/api\/v2\/workspaces\/[^/]+\/research-runs\/[^/]+\/collection$/, () => null],
  ["GET", /^\/api\/v2\/workspaces\/[^/]+\/research-runs\/([^/]+)\/checkpoints\/(\d+)$/, (m) => {
    const checkpoint = runDetails[m[1]]?.closed_loop_state.checkpoints.find((item) => item.checkpoint_no === Number(m[2]));
    return checkpoint ? { ...checkpoint, counterfactual_summary: null, payload: {} } : undefined;
  }],
  ["POST", /^\/api\/v2\/workspaces\/[^/]+\/research-runs\/[^/]+\/resume-from-checkpoint\/\d+$/, () => ({
    research_run_id: runningRun.research_run_id, task_id: runningRun.task_id, status: "RUNNING"
  })],
  ["GET", /^\/api\/v2\/workspaces\/([^/]+)\/artifact-jobs$/, (m) => (
    artifacts.jobs.map((job) => ({ ...job, workspace_id: m[1] }))
  )],
  ["POST", /^\/api\/v2\/workspaces\/[^/]+\/artifact-jobs$/, () => ({
    artifact_job_id: "job-quiz", task_id: "task-job-quiz", skill_key: "quiz_pack", status: "RUNNING"
  })],
  ["GET", /^\/api\/v2\/workspaces\/[^/]+\/artifact-jobs\/([^/]+)\/versions$/, (m) => (
    artifacts.versionList(m[1]).length > 0 ? artifacts.versionList(m[1]) : undefined
  )],
  ["GET", /^\/api\/v2\/workspaces\/[^/]+\/artifact-jobs\/[^/]+\/versions\/compare$/, (_m, _r, url) => (
    artifacts.compareVersions(Number(url.searchParams.get("from")), Number(url.searchParams.get("to")))
  )],
  ["GET", /^\/api\/v2\/workspaces\/[^/]+\/artifact-jobs\/([^/]+)\/versions\/(\d+)$/, (m) => (
    artifacts.versionDetails[`${m[1]}:${m[2]}`]
  )],
  ["POST", /^\/api\/v2\/workspaces\/[^/]+\/artifact-jobs\/([^/]+)\/versions\/(\d+)\/save-as-source$/, (m) => ({
    source_id: `src-from-${m[1]}`, artifact_job_id: m[1], artifact_version_id: `${m[1]}-v${m[2]}`,
    status: "READY", parse_status: "PARSED", index_status: "INDEXED"
  })],
  ["POST", /^\/api\/v2\/workspaces\/[^/]+\/artifact-jobs\/[^/]+\/versions\/\d+\/writeback$/, () => ({ status: "ACCEPTED" })],
  ["GET", /^\/api\/v2\/workspaces\/[^/]+\/video-learning-bundles$/, () => artifacts.videoLearning],
  ["GET", /^\/api\/v2\/workspaces\/[^/]+\/memory\/items$/, () => memory.listItems()],
  ["POST", /^\/api\/v2\/workspaces\/[^/]+\/memory\/signals$/, async (_m, request) => memory.createSignal(await request.json())],
  ["POST", /^\/api\/v2\/workspaces\/[^/]+\/memory\/promotions$/, async (_m, request) => memory.promoteSignals((await request.json()).signal_ids)],
  ["POST", /^\/api\/v2\/workspaces\/[^/]+\/memory\/revisions\/([^/]+)\/review$/, async (m, request) => memory.decide(m[1], (await request.json()).decision)],
  ["GET", /^\/api\/v2\/tasks\/([^/]+)$/, (m) => researchTasks[m[1]] ?? sourceTasks[m[1]]],
  ["GET", /^\/api\/v2\/tasks\/([^/]+)\/event-history/, (m) => (
    researchTasks[m[1]] ? [] : sourceTaskEvents[m[1]]
  )],
  ["POST", /^\/api\/v2\/workspaces\/[^/]+\/sources\/([^/]+)\/reprocess$/, (m) => reprocessMockSource(m[1])]
];

export async function handleMockRequest(method: string, url: URL, request: Request): Promise<Response | null> {
  if (url.origin !== window.location.origin) return null;

  // mock 任务的事件流保持连接但不推送事件，表现与空闲的 SSE 连接一致。
  const stream = url.pathname.match(/^\/api\/v2\/tasks\/([^/]+)\/events$/);
  if (stream && researchTasks[stream[1]]) {
    return new Response(new ReadableStream({ start() { /* 不主动关闭 */ } }), {
      headers: { "Content-Type": "text/event-stream" }
    });
  }

  for (const [routeMethod, pattern, handler] of routes) {
    if (routeMethod !== method) continue;
    const match = url.pathname.match(pattern);
    if (!match) continue;
    const data = await handler(match, request, url);
    if (data === undefined) return null;
    await new Promise((resolve) => setTimeout(resolve, 120));
    return new Response(JSON.stringify({ success: true, code: "OK", message: "", data }), {
      headers: { "Content-Type": "application/json" }
    });
  }
  return null;
}

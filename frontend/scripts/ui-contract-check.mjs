import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const read = (relativePath) => readFileSync(resolve(relativePath), "utf8");

const appSource = read("src/App.tsx");
const chatWorkbenchSource = read("src/features/conversations/ChatWorkbench.tsx");
const chatSourcesPaneSource = read("src/features/conversations/ChatSourcesPane.tsx");
const artifactRailSource = read("src/features/artifacts/ArtifactRail.tsx");
const artifactUtilitySource = read("src/features/artifacts/ArtifactUtilityPanel.tsx");
const researchWorkbenchSource = read("src/features/research/ResearchWorkbenchView.tsx");
const shellSource = read("src/features/shell/WorkbenchShell.tsx");
const shellBusySource = read("src/features/shell/useShellBusy.ts");
const wikiControllerSource = read("src/features/knowledge/useWikiWorkbenchController.ts");
const wikiIssueActionsSource = read("src/features/knowledge/useWikiIssueActions.ts");
const researchControllerSource = read("src/features/research/useResearchWorkbenchController.ts");
const chatControllerSource = read("src/features/conversations/useChatSessionController.ts");
const routesSource = read("src/routes.tsx");
const stylesSource = read("src/styles/index.css") + read("src/styles/shell.css");
const mainSource = read("src/main.tsx");

const surfaceSource = [
  appSource,
  chatWorkbenchSource,
  chatSourcesPaneSource,
  artifactRailSource,
  artifactUtilitySource,
  researchWorkbenchSource,
  shellSource,
  wikiControllerSource,
  wikiIssueActionsSource,
  researchControllerSource,
  chatControllerSource
].join("\n");

const forbiddenSnippets = [
  { file: "App.tsx", source: appSource, snippet: "Deep Research（阶段5）" },
  { file: "App.tsx", source: appSource, snippet: "阶段4接入 Artifact Agent" },
  { file: "App.tsx", source: appSource, snippet: "阶段1/2/3联调会话" },
  { file: "App.tsx", source: appSource, snippet: "阶段1/2/3前端联调工作台" },
  { file: "App.tsx", source: appSource, snippet: "当前原型已接入三条聊天链路和工作台级 Wiki。" },
  { file: "App.tsx", source: appSource, snippet: "mode-explainer" },
  { file: "App.tsx", source: appSource, snippet: 'className="hero"' },
  { file: "App.tsx", source: appSource, snippet: 'className="workspace-card"' }
];

forbiddenSnippets.push(
  { file: "ChatWorkbench.tsx", source: chatWorkbenchSource, snippet: "createPortal" },
  { file: "ChatWorkbench.tsx", source: chatWorkbenchSource, snippet: "mode-context-line" },
  { file: "main.tsx", source: mainSource, snippet: "visual-refresh.css" }
);

for (const item of forbiddenSnippets) {
  if (item.source.includes(item.snippet)) {
    throw new Error(`${item.file} 仍残留旧宣传式、占位或已废弃壳层: ${item.snippet}`);
  }
}

const requiredSnippets = [
  { label: "品牌标题", source: shellSource, snippet: "<h1 className=\"brand-name\">NoteWeave</h1>" },
  { label: "WorkbenchShell", source: appSource, snippet: "WorkbenchShell" },
  { label: "顶栏导航", source: shellSource, snippet: "app-nav" },
  { label: "笔记本会话切换", source: shellSource, snippet: "ConversationSwitcher" },
  { label: "Chat 资料区", source: chatSourcesPaneSource, snippet: "sources-pane" },
  { label: "source-drawer 兼容 class", source: chatSourcesPaneSource, snippet: "source-drawer" },
  {
    label: "Chat 内 Artifact lazy 边界",
    source: chatWorkbenchSource,
    snippet: 'lazy(() => import("../artifacts/ArtifactRail")'
  },
  {
    label: "产物常驻右栏",
    source: chatWorkbenchSource,
    snippet: "studio-pane"
  },
  {
    label: "来源勾选接入 QA 范围",
    source: chatWorkbenchSource,
    snippet: "selectedSourceIds={selectedQaSourceIds}"
  },
  {
    label: "Research 工作台组合",
    source: researchWorkbenchSource,
    snippet: "LazyResearchSidebar"
  },
  {
    label: "Note 入库入口",
    source: artifactUtilitySource,
    snippet: "保存最新回答为 Note"
  },
  {
    label: "Wiki 构建开关文案",
    source: artifactUtilitySource,
    snippet: "开启 Wiki 构建"
  },
  {
    label: "进入 Wiki 工作台动作",
    source: wikiIssueActionsSource,
    snippet: "进入 Wiki 工作台"
  },
  {
    label: "Wiki 控制器接线",
    source: appSource,
    snippet: "useWikiWorkbenchController"
  },
  {
    label: "Research 控制器接线",
    source: appSource,
    snippet: "useResearchWorkbenchController"
  },
  {
    label: "Chat 控制器接线",
    source: appSource,
    snippet: "useChatSessionController"
  },
  {
    label: "popstate 路由",
    source: appSource,
    snippet: "popstate"
  },
  {
    label: "区域 busy",
    source: shellBusySource,
    snippet: "BusyScope"
  },
  {
    label: "useShellBusy 接线",
    source: appSource,
    snippet: "useShellBusy"
  },
  {
    label: "shell 样式",
    source: stylesSource,
    snippet: ".workbench-shell"
  },
  { label: "单一样式入口", source: mainSource, snippet: "./styles/index.css" }
];

for (const item of requiredSnippets) {
  if (!item.source.includes(item.snippet)) {
    throw new Error(`前端缺少约定入口或边界 (${item.label}): ${item.snippet}`);
  }
}

const requiredRouteSnippets = [
  'key: "qa"',
  'key: "note"',
  'key: "wiki"',
  'label: "问答 RAG"',
  'label: "Note"',
  'label: "Wiki"'
];

for (const snippet of requiredRouteSnippets) {
  if (!routesSource.includes(snippet)) {
    throw new Error(`三模式入口定义不完整: ${snippet}`);
  }
}

if (appSource.includes('lazy(() => import("./features/artifacts/ArtifactRail")')) {
  throw new Error("App.tsx 不应再直接 lazy ArtifactRail；应保持在 ChatWorkbench");
}

if (!surfaceSource.includes("ResearchWorkbenchView") && !appSource.includes("ResearchWorkbenchView")) {
  throw new Error("App 须组合 ResearchWorkbenchView");
}

const navViews = ["chat", "research", "wiki", "memory"];
for (const view of navViews) {
  if (!shellSource.includes(`view: "${view}"`)) {
    throw new Error(`WorkbenchShell 缺少导航项: ${view}`);
  }
}

console.log("UI contract check passed (top bar shell + notebook sources/chat/studio + scoped busy).");

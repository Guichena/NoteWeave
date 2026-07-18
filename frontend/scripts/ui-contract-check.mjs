import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const appSource = readFileSync(resolve("src/App.tsx"), "utf8");
const routesSource = readFileSync(resolve("src/routes.tsx"), "utf8");

const forbiddenAppSnippets = [
  "Deep Research（阶段5）",
  "阶段4接入 Artifact Agent",
  "阶段1/2/3联调会话",
  "阶段1/2/3前端联调工作台",
  "当前原型已接入三条聊天链路和工作台级 Wiki。",
  "mode-explainer"
];

for (const snippet of forbiddenAppSnippets) {
  if (appSource.includes(snippet)) {
    throw new Error(`前端仍残留旧宣传式或占位内容: ${snippet}`);
  }
}

const requiredAppSnippets = [
  "<h1>NoteWeave</h1>",
  'className="source-drawer"',
  "lazy(() => import(\"./features/artifacts/ArtifactRail\")",
  "artifactComposerOpen ? <Suspense",
  "保存最新回答为 Note",
  "打开 Wiki 工作台",
  "开启 Wiki 构建"
];

for (const snippet of requiredAppSnippets) {
  if (!appSource.includes(snippet)) {
    throw new Error(`前端缺少紧凑工作台所需入口或按需加载边界: ${snippet}`);
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

console.log("UI contract check passed.");

import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const appPath = resolve("src/App.tsx");
const routesPath = resolve("src/routes.tsx");

const appSource = readFileSync(appPath, "utf8");
const routesSource = readFileSync(routesPath, "utf8");

const forbiddenAppSnippets = [
  "Deep Research（阶段5）",
  "阶段4接入 Artifact Agent",
  "生成报告",
  "生成 FAQ",
  "生成测验",
  "生成学习指南",
  "导出 Markdown / PDF",
  "阶段1/2/3联调会话",
  "阶段1/2/3前端联调工作台"
];

for (const snippet of forbiddenAppSnippets) {
  if (appSource.includes(snippet)) {
    throw new Error(`前端仍残留不应展示的占位内容: ${snippet}`);
  }
}

const requiredAppSnippets = [
  "当前原型已接入三条聊天链路和工作台级 Wiki。",
  "保存最新回答为 Note",
  "打开 Wiki 工作台",
  "开启 Wiki 构建"
];

for (const snippet of requiredAppSnippets) {
  if (!appSource.includes(snippet)) {
    throw new Error(`前端缺少预期入口或说明: ${snippet}`);
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

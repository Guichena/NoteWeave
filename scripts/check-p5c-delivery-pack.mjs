import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptPath = fileURLToPath(import.meta.url);
const repoRoot = path.resolve(path.dirname(scriptPath), "..");
const narrativePath = path.join(repoRoot, "docs", "DeepResearch-P5C-项目讲解稿.md");
const evidencePath = path.join(repoRoot, "docs", "DeepResearch-P5C-亮点证据映射.json");

function fail(message) {
  console.error(`[P5-C] ${message}`);
  process.exit(1);
}

if (!fs.existsSync(narrativePath)) {
  fail(`project narrative not found: ${narrativePath}`);
}
if (!fs.existsSync(evidencePath)) {
  fail(`evidence mapping not found: ${evidencePath}`);
}

const narrative = fs.readFileSync(narrativePath, "utf8");
for (const keyword of [
  "Research Harness",
  "Closed-Loop Research",
  "Table-as-State",
  "Dual Verifier",
  "反证分支",
]) {
  if (!narrative.includes(keyword)) {
    fail(`project narrative must include keyword '${keyword}'`);
  }
}

for (const sectionHeading of [
  "## 1. 为什么不是普通问答",
  "## 2. 固定 Demo 顺序",
  "## 3. 五个核心关键词怎么讲",
  "## 4. 简历亮点短版",
]) {
  if (!narrative.includes(sectionHeading)) {
    fail(`project narrative must include section '${sectionHeading}'`);
  }
}

let evidenceMapping;
try {
  evidenceMapping = JSON.parse(fs.readFileSync(evidencePath, "utf8"));
} catch (error) {
  fail(`evidence mapping is not valid JSON: ${error instanceof Error ? error.message : String(error)}`);
}

if (evidenceMapping.pack_key !== "deep-research-p5c") {
  fail("evidence mapping pack_key must be 'deep-research-p5c'");
}

if (!Array.isArray(evidenceMapping.core_keywords) || evidenceMapping.core_keywords.length < 5) {
  fail("evidence mapping must contain at least 5 core_keywords");
}

for (const keyword of [
  "Research Harness",
  "Closed-Loop Research",
  "Table-as-State",
  "Dual Verifier",
  "反证分支",
]) {
  const item = evidenceMapping.core_keywords.find((entry) => entry && entry.keyword === keyword);
  if (!item) {
    fail(`evidence mapping must include keyword '${keyword}'`);
  }
  if (!Array.isArray(item.evidence_refs) || item.evidence_refs.length < 1) {
    fail(`keyword '${keyword}' must provide at least one evidence_ref`);
  }
}

if (!Array.isArray(evidenceMapping.demo_flow) || evidenceMapping.demo_flow.length < 4) {
  fail("demo_flow must contain the fixed presentation sequence");
}

console.log(
  `[P5-C] delivery pack OK: ${evidenceMapping.core_keywords.length} keywords, ${evidenceMapping.demo_flow.length} demo flow steps`
);

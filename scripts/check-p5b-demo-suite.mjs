import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptPath = fileURLToPath(import.meta.url);
const repoRoot = path.resolve(path.dirname(scriptPath), "..");
const manifestPath = path.join(
  repoRoot,
  "scripts",
  "fixtures",
  "deepresearch",
  "DeepResearch-P5B-demo-suite.json"
);

function fail(message) {
  console.error(`[P5-B] ${message}`);
  process.exit(1);
}

if (!fs.existsSync(manifestPath)) {
  fail(`demo suite manifest not found: ${manifestPath}`);
}

const raw = fs.readFileSync(manifestPath, "utf8");
let manifest;
try {
  manifest = JSON.parse(raw);
} catch (error) {
  fail(`demo suite manifest is not valid JSON: ${error instanceof Error ? error.message : String(error)}`);
}

if (manifest.suite_key !== "deep-research-p5b") {
  fail("suite_key must be 'deep-research-p5b'");
}

if (!Array.isArray(manifest.demo_cases) || manifest.demo_cases.length < 2 || manifest.demo_cases.length > 3) {
  fail("demo_cases must contain 2 to 3 fixed demo cases");
}

if (!Array.isArray(manifest.acceptance_commands) || manifest.acceptance_commands.length < 3) {
  fail("acceptance_commands must contain the fixed verification commands");
}

const requiredCaseFields = [
  "case_key",
  "title",
  "objective",
  "expected_highlights",
  "source_display_points",
  "process_display_points",
  "audit_display_points",
];

for (const demoCase of manifest.demo_cases) {
  for (const field of requiredCaseFields) {
    if (!(field in demoCase)) {
      fail(`demo case '${demoCase.case_key || "UNKNOWN"}' is missing field '${field}'`);
    }
  }
  for (const listField of [
    "expected_highlights",
    "source_display_points",
    "process_display_points",
    "audit_display_points",
  ]) {
    if (!Array.isArray(demoCase[listField]) || demoCase[listField].length < 1) {
      fail(`demo case '${demoCase.case_key}' must provide at least one '${listField}' item`);
    }
  }
}

const requiredCommands = [
  "gate1_smoke",
  "backend_contract",
  "frontend_build",
  "frontend_ui_contract",
];

for (const commandKey of requiredCommands) {
  if (!manifest.acceptance_commands.some((item) => item && item.command_key === commandKey && item.command)) {
    fail(`acceptance_commands must include '${commandKey}'`);
  }
}

console.log(`[P5-B] demo suite manifest OK: ${manifest.demo_cases.length} demo cases, ${manifest.acceptance_commands.length} commands`);

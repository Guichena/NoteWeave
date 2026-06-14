const fs = require("fs");
const path = require("path");
const { execFileSync } = require("child_process");
const { chromium } = require("playwright");

const BASE_URL = process.env.NOTEWEAVE_BASE_URL || "http://127.0.0.1:18082";
const ALICE_USERNAME = process.env.NOTEWEAVE_ALICE_USERNAME || "alice";
const ALICE_PASSWORD = process.env.NOTEWEAVE_ALICE_PASSWORD || "NoteWeave123!";
const ES_BASE_URL = process.env.NOTEWEAVE_ES_BASE_URL || "http://127.0.0.1:19200";
const CLAIM_INDEX = process.env.NOTEWEAVE_CLAIM_INDEX || "noteweave-dev-claim";
const OUTPUT_PATH = path.join(__dirname, "p4-claim-last-run.json");

const OUTPUT = {
  startedAt: new Date().toISOString(),
  baseUrl: BASE_URL,
  esBaseUrl: ES_BASE_URL,
  claimIndex: CLAIM_INDEX,
  steps: [],
  assertions: [],
  issues: [],
  ids: {}
};

function step(label, detail = null) {
  OUTPUT.steps.push({ at: new Date().toISOString(), label, detail });
}

function issue(type, detail) {
  OUTPUT.issues.push({ at: new Date().toISOString(), type, detail });
}

function assertOk(name, passed, detail = null) {
  OUTPUT.assertions.push({ name, passed: Boolean(passed), detail });
  if (!passed) {
    issue("assertion_failed", `${name}${detail ? `: ${detail}` : ""}`);
  }
}

function pageItems(response) {
  return response?.json?.data?.items || response?.json?.data?.content || response?.json?.data || [];
}

function mysqlQuery(sql) {
  return execFileSync("docker", [
    "exec",
    "noteweave-mysql",
    "mysql",
    "-unoteweave",
    "-pnoteweave",
    "-N",
    "-B",
    "noteweave",
    "-e",
    sql
  ], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
}

async function waitForAppIdle(page) {
  await page.waitForLoadState("domcontentloaded");
  await page.waitForTimeout(350);
  await page.waitForFunction(() => {
    const text = document.body ? document.body.innerText : "";
    return !text.includes("正在加载");
  }, null, { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(350);
}

async function api(page, method, apiPath, body = null) {
  const response = await page.evaluate(async ({ method, apiPath, body }) => {
    const auth = JSON.parse(localStorage.getItem("noteweave.workspace.auth") || "{}");
    const result = await fetch(`/api/v1${apiPath}`, {
      method,
      headers: {
        "Content-Type": "application/json",
        ...(auth.accessToken ? { Authorization: `${auth.tokenType || "Bearer"} ${auth.accessToken}` } : {})
      },
      body: body ? JSON.stringify(body) : undefined
    });
    const text = await result.text();
    let json = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      json = null;
    }
    return { status: result.status, text, json };
  }, { method, apiPath, body });
  if (response.status >= 400 || response.json?.success === false) {
    throw new Error(`${method} ${apiPath} failed: ${response.status} ${response.text}`);
  }
  return response;
}

async function esFetch(esPath, options = {}) {
  const response = await fetch(`${ES_BASE_URL}${esPath}`, options);
  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  return { status: response.status, text, json };
}

async function poll(fn, { timeoutMs = 20000, intervalMs = 800 } = {}) {
  const deadline = Date.now() + timeoutMs;
  let lastError = null;
  while (Date.now() < deadline) {
    try {
      const result = await fn();
      if (result) {
        return result;
      }
      lastError = null;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  if (lastError) {
    throw lastError;
  }
  return null;
}

async function login(page) {
  await page.goto(`${BASE_URL}/login`, { waitUntil: "domcontentloaded" });
  await waitForAppIdle(page);
  await page.locator('input[name="usernameOrEmail"]').fill(ALICE_USERNAME);
  await page.locator('input[name="password"]').fill(ALICE_PASSWORD);
  await page.locator('#login-form button[type="submit"]').click();
  await page.waitForURL((url) => !url.pathname.endsWith("/login"), { timeout: 15000 });
  await waitForAppIdle(page);
  step("login", ALICE_USERNAME);
}

async function currentWorkspace(page) {
  const spaces = await api(page, "GET", "/spaces?page=1&pageSize=100");
  const items = pageItems(spaces);
  const teamSpace = items.find((space) => space.type === "TEAM") || items[0];
  const personalSpace = items.find((space) => space.type === "PERSONAL") || items.find((space) => space.id !== teamSpace?.id) || teamSpace;
  if (!personalSpace) {
    throw new Error("No personal space available for alice.");
  }
  return { teamSpace, personalSpace };
}

async function queryTraceByMessageId(messageId) {
  const output = mysqlQuery(`
select id, trace_json
from retrieval_trace
where message_id = ${Number(messageId)}
order by id desc
limit 1;
`);
  if (!output) {
    return null;
  }
  const [idText, ...jsonParts] = output.split("\t");
  return {
    id: Number(idText),
    traceJson: jsonParts.join("\t").replace(/\\n/g, "").trim()
  };
}

async function countTraceClaimItems(traceId, claimId = null) {
  const sourceFilter = claimId == null ? "" : ` and source_id = ${Number(claimId)}`;
  const output = mysqlQuery(`
select count(*)
from retrieval_trace_item
where trace_id = ${Number(traceId)}
  and source_type = 'CLAIM'${sourceFilter};
`);
  return Number(output || 0);
}

function parseTraceJson(traceJson) {
  if (!traceJson) {
    return null;
  }
  return JSON.parse(String(traceJson).replace(/\\n/g, "").trim());
}

async function waitForClaimDoc(claimId, expectedStatement, { shouldExist = true, timeoutMs = 15000 } = {}) {
  const docId = encodeURIComponent(`claim-${claimId}`);
  return poll(async () => {
    const response = await esFetch(`/${CLAIM_INDEX}/_doc/${docId}`);
    if (!shouldExist) {
      if (response.status === 404 || response.json?.found === false) {
        return response;
      }
      return null;
    }
    if (response.status !== 200 || response.json?.found === false) {
      return null;
    }
    const statement = response.json?._source?.statement || "";
    if (expectedStatement && statement !== expectedStatement) {
      return null;
    }
    return response;
  }, { timeoutMs, intervalMs: 700 });
}

async function openProjectQuestionWorkspace(page, personalSpaceId, projectId, questionId) {
  await page.goto(`${BASE_URL}/spaces/${personalSpaceId}/personal/projects/${projectId}`, { waitUntil: "domcontentloaded" });
  await waitForAppIdle(page);
  await page.locator(`[data-action="select-question"][data-question-id="${questionId}"]`).click();
  await waitForAppIdle(page);
  step("open-question-workspace", String(questionId));
}

async function generateOverview(page, questionId) {
  await page.locator(`[data-action="generate-question-overview"][data-question-id="${questionId}"]`).click();
  await waitForAppIdle(page);
  step("generate-overview", String(questionId));
}

async function openChatSession(page, personalSpaceId, sessionId) {
  await page.goto(`${BASE_URL}/spaces/${personalSpaceId}/workbench/chat`, { waitUntil: "domcontentloaded" });
  await waitForAppIdle(page);
  await page.locator(`[data-action="select-session"][data-session-id="${sessionId}"]`).click();
  await waitForAppIdle(page);
  step("open-chat-session", String(sessionId));
}

async function main() {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1600, height: 1100 } });
  const page = await context.newPage();
  try {
    await login(page);
    const { personalSpace } = await currentWorkspace(page);
    const personalSpaceId = Number(personalSpace.id);
    OUTPUT.ids.personalSpaceId = personalSpaceId;

    const suffix = Date.now();
    const uniqueToken = `claim-token-${suffix}`;
    const project = (await api(page, "POST", "/personal/research-projects", {
      title: `P4 Claim Retrieval ${suffix}`,
      description: "Browser automation for claim index + hybrid retrieval",
      researchGoal: "Verify claim update/delete semantics and retrieval trace claim branch."
    })).json.data;
    OUTPUT.ids.projectId = project.id;
    step("create-project", String(project.id));

    const questionA = (await api(page, "POST", "/personal/research-questions", {
      researchProjectId: project.id,
      title: `Question A ${suffix}`,
      questionType: "evaluation",
      currentHypothesis: "Claim memory should survive across questions.",
      nextStep: "Create a claim and verify retrieval.",
      scopeNote: "Question A seeds the historical claim."
    })).json.data;
    const questionB = (await api(page, "POST", "/personal/research-questions", {
      researchProjectId: project.id,
      title: `Question B ${suffix}`,
      questionType: "follow-up",
      currentHypothesis: "A later question should recall Question A's claim.",
      nextStep: "Ask from the second question.",
      scopeNote: "Question B should not contain its own claim."
    })).json.data;
    OUTPUT.ids.questionAId = questionA.id;
    OUTPUT.ids.questionBId = questionB.id;
    step("create-questions", `${questionA.id},${questionB.id}`);

    const initialClaimStatement = `${uniqueToken}: GraphRAG-style claim memory helps a personal research wiki preserve claim relationships across sessions.`;
    const updatedClaimStatement = `${uniqueToken}: GraphRAG-style claim memory helps a personal research wiki preserve claim relationships across sessions and improves later question continuity.`;
    const recallQuestion = uniqueToken;
    const createdClaim = (await api(page, "POST", "/personal/claims", {
      researchQuestionId: questionA.id,
      statement: initialClaimStatement,
      claimType: "CONCLUSION",
      stance: "SUPPORTED",
      confidence: 0.82,
      rationale: "The wiki can later cite this judgment when a different question asks about continuity."
    })).json.data;
    OUTPUT.ids.claimId = createdClaim.id;
    step("create-claim", String(createdClaim.id));

    const claimDocV1 = await waitForClaimDoc(createdClaim.id, initialClaimStatement);
    assertOk("claim indexed after create", Boolean(claimDocV1?.json?._source?.statement === initialClaimStatement), claimDocV1?.text || null);

    const updatedClaim = (await api(page, "PUT", `/personal/claims/${createdClaim.id}`, {
      statement: updatedClaimStatement,
      claimType: "CONCLUSION",
      stance: "SUPPORTED",
      confidence: 0.91,
      rationale: "This conclusion should be recalled from Question B and appear in retrieval trace claim counts."
    })).json.data;
    step("update-claim", String(updatedClaim.id));

    const claimDocV2 = await waitForClaimDoc(updatedClaim.id, updatedClaimStatement);
    assertOk("claim index refreshed after update", Boolean(claimDocV2?.json?._source?.statement === updatedClaimStatement), claimDocV2?.text || null);

    await openProjectQuestionWorkspace(page, personalSpaceId, project.id, questionA.id);
    await page.waitForSelector(`text=${updatedClaimStatement}`, { timeout: 15000 });
    assertOk("question workspace shows updated claim", true, updatedClaimStatement);

    await generateOverview(page, questionA.id);
    await page.waitForSelector(`text=${updatedClaimStatement}`, { timeout: 15000 });
    assertOk("overview renders updated claim", true, updatedClaimStatement);

    const formalSession = (await api(page, "POST", "/chat/sessions", {
      spaceId: personalSpaceId,
      sessionType: "TEAM_CHAT",
      sessionKind: "FORMAL",
      scopeType: "SPACE",
      researchProjectId: project.id,
      researchQuestionId: questionB.id,
      scopeIds: [personalSpaceId],
      title: `P4 Cross Question Chat ${suffix}`
    })).json.data;
    OUTPUT.ids.chatSessionId = formalSession.id;
    step("create-chat-session", String(formalSession.id));

    const firstAsk = (await api(page, "POST", `/chat/sessions/${formalSession.id}/messages`, {
      content: recallQuestion
    })).json.data;
    OUTPUT.ids.firstUserMessageId = firstAsk.userMessageId;
    OUTPUT.ids.firstAssistantMessageId = firstAsk.assistantMessageId;
    step("ask-question-before-delete", String(firstAsk.assistantMessageId));

    const firstCitations = firstAsk.citations || [];
    assertOk("first ask returns citations", firstCitations.length > 0, JSON.stringify(firstCitations));

    const firstTrace = await queryTraceByMessageId(firstAsk.userMessageId);
    const firstTraceJson = parseTraceJson(firstTrace?.traceJson);
    OUTPUT.ids.firstTraceId = firstTrace?.id || null;
    assertOk("retrieval trace exists for first ask", Boolean(firstTrace?.id), JSON.stringify(firstTrace));
    assertOk("trace json reports claim branch hits", Number(firstTraceJson?.claim || 0) > 0, firstTrace?.traceJson || null);
    if (firstTrace?.id) {
      const targetClaimTraceItemCount = await countTraceClaimItems(firstTrace.id, createdClaim.id);
      assertOk("retrieval trace includes the newly created historical claim", targetClaimTraceItemCount > 0, String(targetClaimTraceItemCount));
    }

    await openChatSession(page, personalSpaceId, formalSession.id);
    await page.waitForSelector(".message.assistant", { timeout: 15000 });
    const citationButtons = await page.locator('.message.assistant [data-action="open-citation"]').count();
    assertOk("chat UI exposes citation buttons for recalled claim", citationButtons > 0, String(citationButtons));

    await api(page, "DELETE", `/personal/claims/${createdClaim.id}`);
    step("delete-claim", String(createdClaim.id));

    const missingClaimDoc = await waitForClaimDoc(createdClaim.id, null, { shouldExist: false });
    assertOk("claim removed from index after delete", Boolean(missingClaimDoc), missingClaimDoc?.text || null);

    const secondAsk = (await api(page, "POST", `/chat/sessions/${formalSession.id}/messages`, {
      content: recallQuestion
    })).json.data;
    OUTPUT.ids.secondUserMessageId = secondAsk.userMessageId;
    OUTPUT.ids.secondAssistantMessageId = secondAsk.assistantMessageId;
    step("ask-question-after-delete", String(secondAsk.assistantMessageId));

    const secondTrace = await queryTraceByMessageId(secondAsk.userMessageId);
    const secondTraceJson = parseTraceJson(secondTrace?.traceJson);
    OUTPUT.ids.secondTraceId = secondTrace?.id || null;
    assertOk("trace exists after delete", Boolean(secondTrace?.id), JSON.stringify(secondTrace));
    assertOk(
      "deleted claim no longer appears in follow-up citations",
      !(secondAsk.citations || []).some((item) => item.sourceType === "CLAIM" && Number(item.sourceId) === Number(createdClaim.id)),
      JSON.stringify(secondAsk.citations || [])
    );
    if (secondTrace?.id) {
      const deletedClaimTraceItemCount = await countTraceClaimItems(secondTrace.id, createdClaim.id);
      assertOk("deleted claim no longer appears in retrieval trace items", deletedClaimTraceItemCount === 0, String(deletedClaimTraceItemCount));
    }
  } finally {
    OUTPUT.finishedAt = new Date().toISOString();
    fs.writeFileSync(OUTPUT_PATH, JSON.stringify(OUTPUT, null, 2));
    await context.close().catch(() => {});
    await browser.close().catch(() => {});
  }

  process.stdout.write(`${JSON.stringify(OUTPUT, null, 2)}\n`);
  if (OUTPUT.issues.length) {
    process.exitCode = 1;
  }
}

main().catch((error) => {
  issue("fatal", error.stack || error.message);
  OUTPUT.finishedAt = new Date().toISOString();
  fs.writeFileSync(OUTPUT_PATH, JSON.stringify(OUTPUT, null, 2));
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exit(1);
});

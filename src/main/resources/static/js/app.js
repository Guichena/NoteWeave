import { api, ApiError, clearAuthState, loadAuthState, saveAuthState } from "./api.js";
import { md5File } from "./md5.js";
import { renderMarkdown } from "./markdown.js";

const root = document.getElementById("app");
const CHUNK_SIZE = 1024 * 1024;
const FINAL_TASK_STATUSES = new Set(["SUCCESS", "FAILED", "CANCELLED", "TIMEOUT"]);

const state = {
    route: null,
    user: null,
    spaces: [],
    currentSpaceId: null,
    spaceMembers: {},
    registry: {
        citations: {}
    },
    ui: {
        isPageLoading: false,
        pageError: null,
        drawer: null,
        toasts: [],
        selectedSpacePreviewId: null
    },
    knowledge: {
        list: [],
        detail: null,
        documentsByKb: {},
        searchResults: {},
        uploadJobsByKb: {}
    },
    chat: {
        sessions: [],
        activeSessionId: null,
        messagesBySession: {},
        artifactsBySession: {},
        citationsByMessage: {},
        draftsBySession: {},
        feedbackByMessage: {},
        localsBySession: {}
    },
    personal: {
        projects: [],
        projectCounts: {},
        detail: null,
        sourcesByProject: {},
        articleCardsByProject: {},
        conceptCardsByProject: {},
        synthesisCardsByProject: {},
        methodologyByProject: {},
        selectedSynthesisProjectId: null
    },
    studio: {
        skills: [],
        lastTask: null
    },
    artifacts: {
        list: [],
        detail: null,
        relations: [],
        editorDraft: null,
        distillPreview: null
    },
    wiki: {
        pages: [],
        selectedPageId: null,
        pageDetail: null,
        versions: [],
        searchResult: null
    },
    memory: {
        spaceMemory: null,
        userMemory: null,
        sessionSummaries: [],
        selectedSessionId: null
    },
    admin: {
        dashboard: null,
        tasks: null,
        taskFilters: {
            taskStatus: ""
        },
        selectedTaskId: null,
        taskDetail: null,
        taskEvents: null,
        health: null,
        evalCases: [],
        selectedEvalRunId: null,
        evalRun: null,
        evalResults: [],
        llmLogs: null,
        auditLogs: null,
        retrievalTrace: null
    },
    websocket: {
        status: "disconnected",
        socket: null,
        connectPromise: null,
        reconnectTimer: null,
        manualClose: false,
        lastAckBySession: {}
    }
};

let navigationVersion = 0;
let toastCounter = 0;
const taskPollers = new Map();

function escapeHtml(value) {
    return String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

function formatDate(value) {
    if (!value) {
        return "—";
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return escapeHtml(value);
    }
    return new Intl.DateTimeFormat("zh-CN", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit"
    }).format(date);
}

function formatNumber(value) {
    if (value === null || value === undefined || value === "") {
        return "—";
    }
    return new Intl.NumberFormat("zh-CN").format(value);
}

function humanizeStatus(value) {
    const raw = String(value ?? "").toUpperCase();
    const labels = {
        ACTIVE: "ACTIVE",
        ARCHIVED: "ARCHIVED",
        READY: "READY",
        GENERATING: "GENERATING",
        FAILED: "FAILED",
        SUCCESS: "SUCCESS",
        RUNNING: "RUNNING",
        PENDING: "PENDING",
        CANCELLED: "CANCELLED",
        TIMEOUT: "TIMEOUT",
        STOPPED: "STOPPED",
        IDLE: "IDLE",
        DRAFT_ACTIVE: "DRAFT",
        CONVERTED: "CONVERTED",
        DISCARDED: "DISCARDED",
        IMPORTED: "IMPORTED",
        COMPLETED: "COMPLETED"
    };
    return labels[raw] || raw || "UNKNOWN";
}

function statusTone(value) {
    const raw = String(value ?? "").toUpperCase();
    if (["SUCCESS", "READY", "ACTIVE", "COMPLETED", "IDLE", "PUBLISHED", "ENABLED"].includes(raw)) {
        return "success";
    }
    if (["FAILED", "CANCELLED", "TIMEOUT", "DISCARDED", "ERROR"].includes(raw)) {
        return "danger";
    }
    return "warning";
}

function badge(value, text = null) {
    const tone = statusTone(value);
    return `<span class="status-badge ${tone}">${escapeHtml(text || humanizeStatus(value))}</span>`;
}

function tag(value) {
    return `<span class="tag">${escapeHtml(value)}</span>`;
}

function panel(title, body, { subtitle = "", actions = "" } = {}) {
    return `
        <section class="panel">
            <div class="list-item-header">
                <div>
                    <h2>${escapeHtml(title)}</h2>
                    ${subtitle ? `<p class="panel-subtitle">${escapeHtml(subtitle)}</p>` : ""}
                </div>
                ${actions ? `<div class="stack-actions">${actions}</div>` : ""}
            </div>
            ${body}
        </section>
    `;
}

function emptyState(title, description) {
    return `<div class="empty-state"><strong>${escapeHtml(title)}</strong><div class="muted">${escapeHtml(description)}</div></div>`;
}

function renderErrorState(error) {
    const message = error instanceof ApiError ? error.message : "页面加载失败。";
    const code = error instanceof ApiError ? error.code : "UNKNOWN";
    return `<div class="error-state"><strong>${escapeHtml(message)}</strong><div class="muted">错误码：${escapeHtml(code)}</div></div>`;
}

function renderJson(value) {
    return `<pre class="mono">${escapeHtml(JSON.stringify(value ?? {}, null, 2))}</pre>`;
}

function flattenPageResponse(value) {
    return Array.isArray(value) ? value : value?.items || [];
}

function currentSpace() {
    return state.spaces.find((space) => Number(space.id) === Number(state.currentSpaceId)) || null;
}

function personalSpace() {
    return state.spaces.find((space) => String(space.type || "").toUpperCase() === "PERSONAL") || null;
}

function personalSpaceId() {
    const space = personalSpace();
    return space?.id ? Number(space.id) : null;
}

function currentRouteSpaceId() {
    return state.route?.spaceId ? Number(state.route.spaceId) : state.currentSpaceId;
}

function setCurrentSpace(spaceId) {
    state.currentSpaceId = Number(spaceId);
}

function findProjectById(projectId) {
    if (Number(state.personal.detail?.id) === Number(projectId)) {
        return state.personal.detail;
    }
    return state.personal.projects.find((project) => Number(project.id) === Number(projectId)) || null;
}

function resolveProjectSpaceId(project = state.personal.detail) {
    return project?.spaceId ? Number(project.spaceId) : (personalSpaceId() || currentRouteSpaceId());
}

function resolveProjectSpaceIdById(projectId) {
    return resolveProjectSpaceId(findProjectById(projectId));
}

function findArtifactById(artifactId) {
    if (Number(state.artifacts.detail?.id) === Number(artifactId)) {
        return state.artifacts.detail;
    }
    return state.artifacts.list.find((artifact) => Number(artifact.id) === Number(artifactId)) || null;
}

function resolveArtifactSpaceId(artifact = state.artifacts.detail) {
    return artifact?.spaceId ? Number(artifact.spaceId) : currentRouteSpaceId();
}

function resolveArtifactSpaceIdById(artifactId) {
    return resolveArtifactSpaceId(findArtifactById(artifactId));
}

function isPublicRoute(route) {
    return route?.name === "login" || route?.name === "register";
}

function parseRoute(pathname) {
    const path = pathname || "/";
    const patterns = [
        { name: "login", regex: /^\/login$/ },
        { name: "register", regex: /^\/register$/ },
        { name: "spaces", regex: /^\/spaces$/ },
        { name: "admin-home", regex: /^\/admin$/ },
        { name: "knowledge-list", regex: /^\/spaces\/(\d+)\/team\/knowledge-bases$/ },
        { name: "knowledge-detail", regex: /^\/spaces\/(\d+)\/team\/knowledge-bases\/(\d+)$/ },
        { name: "team-chat", regex: /^\/spaces\/(\d+)\/team\/chat$/ },
        { name: "projects", regex: /^\/spaces\/(\d+)\/personal\/projects$/ },
        { name: "project-detail", regex: /^\/spaces\/(\d+)\/personal\/projects\/(\d+)$/ },
        { name: "project-sources", regex: /^\/spaces\/(\d+)\/personal\/projects\/(\d+)\/sources$/ },
        { name: "project-cards", regex: /^\/spaces\/(\d+)\/personal\/projects\/(\d+)\/cards$/ },
        { name: "project-generate", regex: /^\/spaces\/(\d+)\/personal\/projects\/(\d+)\/generate$/ },
        { name: "workbench-chat", regex: /^\/spaces\/(\d+)\/workbench\/chat$/ },
        { name: "workbench-studio", regex: /^\/spaces\/(\d+)\/workbench\/studio$/ },
        { name: "artifacts", regex: /^\/spaces\/(\d+)\/artifacts$/ },
        { name: "artifact-detail", regex: /^\/spaces\/(\d+)\/artifacts\/(\d+)$/ },
        { name: "wiki", regex: /^\/spaces\/(\d+)\/wiki$/ },
        { name: "memory", regex: /^\/spaces\/(\d+)\/memory$/ },
        { name: "admin-tasks", regex: /^\/admin\/tasks$/ },
        { name: "admin-health", regex: /^\/admin\/health$/ },
        { name: "admin-evaluation", regex: /^\/admin\/evaluation$/ },
        { name: "admin-logs", regex: /^\/admin\/logs$/ }
    ];

    for (const pattern of patterns) {
        const match = path.match(pattern.regex);
        if (!match) {
            continue;
        }
        const route = { name: pattern.name };
        if (match[1]) {
            route.spaceId = Number(match[1]);
        }
        if (match[2]) {
            if (pattern.name.includes("artifact")) {
                route.artifactId = Number(match[2]);
            } else if (pattern.name.includes("knowledge")) {
                route.knowledgeBaseId = Number(match[2]);
            } else {
                route.projectId = Number(match[2]);
            }
        }
        return route;
    }

    return { name: "home" };
}

function navigate(path, replace = false) {
    if (replace) {
        window.history.replaceState({}, "", path);
    } else {
        window.history.pushState({}, "", path);
    }
    renderRoute();
}

function routeLink(name, spaceId, extra = null) {
    switch (name) {
        case "spaces":
            return "/spaces";
        case "knowledge":
            return `/spaces/${spaceId}/team/knowledge-bases`;
        case "chat":
            return `/spaces/${spaceId}/workbench/chat`;
        case "studio":
            return `/spaces/${spaceId}/workbench/studio`;
        case "artifacts":
            return `/spaces/${spaceId}/artifacts`;
        case "projects":
            return `/spaces/${spaceId}/personal/projects`;
        case "wiki":
            return `/spaces/${spaceId}/wiki`;
        case "memory":
            return `/spaces/${spaceId}/memory`;
        case "artifact-detail":
            return `/spaces/${spaceId}/artifacts/${extra}`;
        case "project":
            return `/spaces/${spaceId}/personal/projects/${extra}`;
        default:
            return "/";
    }
}

function queueToast(message, type = "info") {
    const id = ++toastCounter;
    state.ui.toasts = [...state.ui.toasts, { id, message, type }];
    paint();
    window.setTimeout(() => {
        state.ui.toasts = state.ui.toasts.filter((toast) => toast.id !== id);
        paint();
    }, 4200);
}

function clearPrivateState() {
    state.user = null;
    state.spaces = [];
    state.currentSpaceId = null;
    state.spaceMembers = {};
    state.knowledge = {
        list: [],
        detail: null,
        documentsByKb: {},
        searchResults: {},
        uploadJobsByKb: {}
    };
    state.chat = {
        sessions: [],
        activeSessionId: null,
        messagesBySession: {},
        artifactsBySession: {},
        citationsByMessage: {},
        draftsBySession: {},
        feedbackByMessage: {},
        localsBySession: {}
    };
    state.personal = {
        projects: [],
        projectCounts: {},
        detail: null,
        sourcesByProject: {},
        articleCardsByProject: {},
        conceptCardsByProject: {},
        synthesisCardsByProject: {},
        methodologyByProject: {},
        selectedSynthesisProjectId: null
    };
    state.studio = { skills: [], lastTask: null };
    state.artifacts = { list: [], detail: null, relations: [], editorDraft: null, distillPreview: null };
    state.wiki = { pages: [], selectedPageId: null, pageDetail: null, versions: [], searchResult: null };
    state.memory = { spaceMemory: null, userMemory: null, sessionSummaries: [], selectedSessionId: null };
    state.admin = {
        dashboard: null,
        tasks: null,
        health: null,
        evalCases: [],
        selectedEvalRunId: null,
        evalRun: null,
        evalResults: [],
        llmLogs: null,
        auditLogs: null,
        retrievalTrace: null
    };
    closeSocket();
}

function registerCitation(citation) {
    const key = `${citation.id || "local"}-${Object.keys(state.registry.citations).length + 1}`;
    state.registry.citations[key] = citation;
    return key;
}

function downloadTextFile(fileName, content) {
    const blob = new Blob([content], { type: "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = fileName;
    link.click();
    URL.revokeObjectURL(url);
}

function activeSession() {
    return state.chat.sessions.find((session) => Number(session.id) === Number(state.chat.activeSessionId)) || null;
}

function setDrawer(title, html) {
    state.ui.drawer = { title, html };
    paint();
}

function closeDrawer() {
    if (state.route?.name === "admin-tasks") {
        state.admin.selectedTaskId = null;
        state.admin.taskDetail = null;
        state.admin.taskEvents = null;
    }
    state.ui.drawer = null;
    paint();
}

function ensureLocalDraft(sessionId) {
    if (!state.chat.draftsBySession[sessionId]) {
        state.chat.draftsBySession[sessionId] = "";
    }
}

async function ensureAuthBootstrap() {
    const auth = loadAuthState();
    if (!auth?.accessToken) {
        clearPrivateState();
        navigate("/login", true);
        throw new Error("redirected");
    }

    if (!state.user) {
        state.user = await api.auth.me();
    }

    if (!state.spaces.length) {
        const response = await api.spaces.list();
        state.spaces = flattenPageResponse(response);
    }

    if (!state.currentSpaceId && state.spaces[0]) {
        state.currentSpaceId = Number(state.spaces[0].id);
    }
}

async function loadSpaceSelectionPage() {
    if (!state.ui.selectedSpacePreviewId && state.spaces[0]) {
        state.ui.selectedSpacePreviewId = Number(state.spaces[0].id);
    }
    if (state.ui.selectedSpacePreviewId) {
        const response = await api.spaces.members(state.ui.selectedSpacePreviewId);
        state.spaceMembers = {
            ...(state.spaceMembers || {}),
            [state.ui.selectedSpacePreviewId]: flattenPageResponse(response)
        };
    }
}

async function loadKnowledgePage(spaceId) {
    setCurrentSpace(spaceId);
    state.knowledge.list = await api.knowledge.list(spaceId);
    const documentEntries = await Promise.allSettled(
        state.knowledge.list.map(async (kb) => [kb.id, await api.knowledge.documents(kb.id)])
    );
    documentEntries.forEach((result) => {
        if (result.status === "fulfilled") {
            const [id, documents] = result.value;
            state.knowledge.documentsByKb[id] = documents;
        }
    });
}

async function loadKnowledgeDetail(spaceId, knowledgeBaseId) {
    await loadKnowledgePage(spaceId);
    state.knowledge.detail = await api.knowledge.get(knowledgeBaseId);
    state.knowledge.documentsByKb[knowledgeBaseId] = await api.knowledge.documents(knowledgeBaseId);
}

async function loadChatPage(spaceId) {
    setCurrentSpace(spaceId);
    state.chat.sessions = await api.chat.listSessions(spaceId);
    state.knowledge.list = await api.knowledge.list(spaceId);

    if (state.chat.activeSessionId && !state.chat.sessions.some((session) => Number(session.id) === Number(state.chat.activeSessionId))) {
        state.chat.activeSessionId = null;
    }
    if (!state.chat.activeSessionId && state.chat.sessions[0]) {
        state.chat.activeSessionId = Number(state.chat.sessions[0].id);
    }
    if (state.chat.activeSessionId) {
        ensureLocalDraft(state.chat.activeSessionId);
        await loadChatSessionData(state.chat.activeSessionId);
    }
}

async function loadChatSessionData(sessionId) {
    const messages = await api.chat.getMessages(sessionId);
    state.chat.messagesBySession[sessionId] = messages;
    await Promise.allSettled(
        messages
            .filter((message) => String(message.role || "").toUpperCase().includes("ASSISTANT"))
            .map(async (message) => {
                state.chat.citationsByMessage[message.id] = await api.chat.citations(message.id);
            })
    );
    state.chat.artifactsBySession[sessionId] = await api.artifacts.listBySession(sessionId);
}

async function loadProjectsPage(spaceId) {
    setCurrentSpace(spaceId);
    state.personal.projects = await api.personal.listProjects();
    state.artifacts.list = await api.artifacts.listBySpace(spaceId);
    const artifactCountByProject = {};
    state.artifacts.list.forEach((artifact) => {
        if (artifact.researchProjectId) {
            artifactCountByProject[artifact.researchProjectId] = (artifactCountByProject[artifact.researchProjectId] || 0) + 1;
        }
    });
    await Promise.allSettled(state.personal.projects.map(async (project) => {
        const [sources, articles, concepts, synthesis] = await Promise.all([
            api.personal.sources(project.id),
            api.personal.articleCards(project.id),
            api.personal.conceptCards(project.id),
            api.personal.synthesisCards(project.id)
        ]);
        state.personal.projectCounts[project.id] = {
            sourceCount: sources.length,
            cardCount: articles.length + concepts.length + synthesis.length,
            artifactCount: artifactCountByProject[project.id] || 0
        };
    }));
}

async function loadProjectDetail(spaceId, projectId) {
    await loadProjectsPage(spaceId);
    state.studio.skills = await api.studio.listSkills();
    state.personal.detail = await api.personal.getProject(projectId);
    state.personal.sourcesByProject[projectId] = await api.personal.sources(projectId);
    state.personal.articleCardsByProject[projectId] = await api.personal.articleCards(projectId);
    state.personal.conceptCardsByProject[projectId] = await api.personal.conceptCards(projectId);
    state.personal.synthesisCardsByProject[projectId] = await api.personal.synthesisCards(projectId);
    state.personal.methodologyByProject[projectId] = await api.personal.methodologyCards(projectId);
}

async function loadStudioPage(spaceId) {
    setCurrentSpace(spaceId);
    state.studio.skills = await api.studio.listSkills();
    state.personal.projects = await api.personal.listProjects();
    state.artifacts.list = await api.artifacts.listBySpace(spaceId);
}

async function loadArtifactsPage(spaceId) {
    setCurrentSpace(spaceId);
    state.artifacts.list = await api.artifacts.listBySpace(spaceId);
}

async function loadArtifactDetail(spaceId, artifactId) {
    await loadArtifactsPage(spaceId);
    state.artifacts.detail = await api.artifacts.get(artifactId);
    state.artifacts.relations = await api.artifacts.relations(artifactId);
    state.artifacts.editorDraft = {
        title: state.artifacts.detail.title || "",
        content: state.artifacts.detail.content || "",
        changeNote: ""
    };
}

async function loadWikiPage(spaceId) {
    setCurrentSpace(spaceId);
    state.wiki.pages = await api.wiki.list(spaceId);
    if (state.wiki.selectedPageId && !state.wiki.pages.some((page) => Number(page.id) === Number(state.wiki.selectedPageId))) {
        state.wiki.selectedPageId = null;
    }
    if (!state.wiki.selectedPageId && state.wiki.pages[0]) {
        state.wiki.selectedPageId = Number(state.wiki.pages[0].id);
    }
    if (state.wiki.selectedPageId) {
        state.wiki.pageDetail = await api.wiki.get(state.wiki.selectedPageId);
        state.wiki.versions = await api.wiki.versions(state.wiki.selectedPageId);
    } else {
        state.wiki.pageDetail = null;
        state.wiki.versions = [];
    }
}

async function loadMemoryPage(spaceId) {
    setCurrentSpace(spaceId);
    state.memory.spaceMemory = await api.memory.space(spaceId);
    state.memory.userMemory = await api.memory.user();
    state.chat.sessions = await api.chat.listSessions(spaceId);
    state.personal.projects = await api.personal.listProjects();
    if (!state.memory.selectedSessionId && state.chat.sessions[0]) {
        state.memory.selectedSessionId = Number(state.chat.sessions[0].id);
    }
    if (state.memory.selectedSessionId) {
        state.memory.sessionSummaries = await api.memory.sessionSummaries(state.memory.selectedSessionId);
    }
    if (!state.personal.selectedSynthesisProjectId && state.personal.projects[0]) {
        state.personal.selectedSynthesisProjectId = Number(state.personal.projects[0].id);
    }
    if (state.personal.selectedSynthesisProjectId) {
        state.personal.synthesisCardsByProject[state.personal.selectedSynthesisProjectId] = await api.personal.synthesisCards(state.personal.selectedSynthesisProjectId);
    }
}

async function loadAdminTasksPage() {
    const query = {
        page: 1,
        pageSize: 20,
        sort: "createdAt,desc"
    };
    if (state.admin.taskFilters.taskStatus) {
        query.taskStatus = state.admin.taskFilters.taskStatus;
    }
    state.admin.dashboard = await api.admin.dashboard();
    state.admin.tasks = await api.admin.tasks(query);
    if (state.admin.selectedTaskId) {
        await loadAdminTaskDetail(state.admin.selectedTaskId);
    }
}

async function loadAdminHealthPage() {
    state.admin.dashboard = await api.admin.dashboard();
    state.admin.health = await api.admin.health();
}

async function loadAdminEvaluationPage() {
    const spaceId = currentRouteSpaceId();
    if (!spaceId) {
        throw new ApiError("请先进入一个 Space。", { code: "SPACE_REQUIRED" });
    }
    state.admin.evalCases = await api.admin.evalCases(spaceId);
    if (state.admin.selectedEvalRunId) {
        state.admin.evalRun = await api.admin.getEvalRun(state.admin.selectedEvalRunId);
        state.admin.evalResults = await api.admin.getEvalResults(state.admin.selectedEvalRunId);
    }
}

async function loadAdminLogsPage() {
    const spaceId = currentRouteSpaceId() || undefined;
    state.admin.llmLogs = await api.admin.llmLogs({ page: 1, pageSize: 20, sort: "createdAt,desc", spaceId });
    state.admin.auditLogs = await api.admin.auditLogs({ page: 1, pageSize: 20, sort: "createdAt,desc" });
}

async function bootstrapRoute(route) {
    if (route.name === "home") {
        const auth = loadAuthState();
        if (auth?.accessToken) {
            await ensureAuthBootstrap();
            const spaceId = state.currentSpaceId || state.spaces[0]?.id;
            navigate(spaceId ? routeLink("chat", spaceId) : "/spaces", true);
        } else {
            navigate("/login", true);
        }
        return;
    }

    if (isPublicRoute(route)) {
        return;
    }

    await ensureAuthBootstrap();

    switch (route.name) {
        case "admin-home":
            navigate("/admin/tasks", true);
            return;
        case "spaces":
            await loadSpaceSelectionPage();
            break;
        case "knowledge-list":
            await loadKnowledgePage(route.spaceId);
            break;
        case "knowledge-detail":
            await loadKnowledgeDetail(route.spaceId, route.knowledgeBaseId);
            break;
        case "team-chat":
        case "workbench-chat":
            await loadChatPage(route.spaceId);
            break;
        case "projects":
            await loadProjectsPage(route.spaceId);
            break;
        case "project-detail":
        case "project-sources":
        case "project-cards":
        case "project-generate":
            await loadProjectDetail(route.spaceId, route.projectId);
            break;
        case "workbench-studio":
            await loadStudioPage(route.spaceId);
            break;
        case "artifacts":
            await loadArtifactsPage(route.spaceId);
            break;
        case "artifact-detail":
            await loadArtifactDetail(route.spaceId, route.artifactId);
            break;
        case "wiki":
            await loadWikiPage(route.spaceId);
            break;
        case "memory":
            await loadMemoryPage(route.spaceId);
            break;
        case "admin-tasks":
            await loadAdminTasksPage();
            break;
        case "admin-health":
            await loadAdminHealthPage();
            break;
        case "admin-evaluation":
            await loadAdminEvaluationPage();
            break;
        case "admin-logs":
            await loadAdminLogsPage();
            break;
        default:
            break;
    }
}

function paint() {
    if (!state.route) {
        return;
    }
    state.registry.citations = {};
    root.innerHTML = buildApp();
}

async function renderRoute() {
    const version = ++navigationVersion;
    state.route = parseRoute(window.location.pathname);
    state.ui.pageError = null;
    state.ui.isPageLoading = true;
    paint();

    try {
        await bootstrapRoute(state.route);
        if (version !== navigationVersion) {
            return;
        }
        state.ui.isPageLoading = false;
        state.ui.pageError = null;
        paint();
        if (state.route.name === "team-chat" || state.route.name === "workbench-chat") {
            await ensureChatSocket();
            resumeActiveSession();
        }
    } catch (error) {
        if (error?.message === "redirected" || version !== navigationVersion) {
            return;
        }
        state.ui.isPageLoading = false;
        state.ui.pageError = error;
        paint();
    }
}

function buildApp() {
    const route = state.route;
    if (isPublicRoute(route)) {
        return `${renderPublicRoute(route)}${renderToasts()}`;
    }

    return `
        <div class="app-shell">
            ${renderSidebar(route)}
            <main class="main-panel">
                ${renderTopbar()}
                <div class="page-body">
                    ${renderPageContent(route)}
                </div>
            </main>
            ${renderDrawer()}
        </div>
        ${renderToasts()}
    `;
}

function renderPublicRoute(route) {
    const isLogin = route.name === "login";
    return `
        <div class="public-layout">
            <section class="auth-card">
                <h1>${isLogin ? "进入 Workspace" : "创建账号"}</h1>
                <p class="subtitle">${isLogin ? "首屏直接进入工作台，不绕营销页。登录后即可切换 Space、上传知识库和启动 Chat。" : "注册后会直接拿到访问令牌，并可以马上进入你的工作台。"} </p>
                <form id="${isLogin ? "login-form" : "register-form"}" class="inline-form" style="margin-top:18px;">
                    ${isLogin ? `
                        <div class="field">
                            <label>用户名或邮箱</label>
                            <input name="usernameOrEmail" placeholder="you@example.com" required>
                        </div>
                        <div class="field">
                            <label>密码</label>
                            <input type="password" name="password" placeholder="至少 8 位" required>
                        </div>
                    ` : `
                        <div class="field-grid cols-2">
                            <div class="field">
                                <label>用户名</label>
                                <input name="username" required>
                            </div>
                            <div class="field">
                                <label>显示名</label>
                                <input name="displayName">
                            </div>
                        </div>
                        <div class="field">
                            <label>邮箱</label>
                            <input type="email" name="email" required>
                        </div>
                        <div class="field">
                            <label>密码</label>
                            <input type="password" name="password" placeholder="至少 8 位" required>
                        </div>
                    `}
                    <div class="page-actions">
                        <button class="button" type="submit">${isLogin ? "登录" : "注册并进入"}</button>
                        <button class="ghost-button" type="button" data-nav="${isLogin ? "/register" : "/login"}">${isLogin ? "去注册" : "去登录"}</button>
                    </div>
                </form>
            </section>
        </div>
    `;
}

function renderSidebar(route) {
    const spaceId = currentRouteSpaceId();
    const personalNavSpaceId = personalSpaceId();
    const adminVisible = state.user?.systemRole === "ADMIN";
    const navItem = (label, target, active) => `<a class="nav-link ${active ? "active" : ""}" href="${target}" data-nav="${target}"><span>${label}</span></a>`;

    return `
        <aside class="sidebar">
            <div class="brand">
                <strong>NoteWeave</strong>
                <span>Workspace UI / Team Knowledge / Personal Research</span>
            </div>
            <div class="nav-group">
                <div class="nav-label">Workspace</div>
                ${navItem("Spaces", "/spaces", route.name === "spaces")}
                ${spaceId ? navItem("Team Knowledge", routeLink("knowledge", spaceId), route.name.startsWith("knowledge")) : ""}
                ${personalNavSpaceId ? navItem("Personal Research", routeLink("projects", personalNavSpaceId), route.name.startsWith("project") || route.name === "projects") : ""}
                ${spaceId ? navItem("Chat", routeLink("chat", spaceId), route.name === "team-chat" || route.name === "workbench-chat") : ""}
                ${spaceId ? navItem("Studio", routeLink("studio", spaceId), route.name === "workbench-studio") : ""}
                ${spaceId ? navItem("Artifacts", routeLink("artifacts", spaceId), route.name === "artifacts" || route.name === "artifact-detail") : ""}
                ${spaceId ? navItem("Wiki", routeLink("wiki", spaceId), route.name === "wiki") : ""}
                ${spaceId ? navItem("Memory", routeLink("memory", spaceId), route.name === "memory") : ""}
            </div>
            ${adminVisible ? `
                <div class="nav-group">
                    <div class="nav-label">Admin</div>
                    ${navItem("Tasks", "/admin/tasks", route.name === "admin-tasks")}
                    ${navItem("Health", "/admin/health", route.name === "admin-health")}
                    ${navItem("Evaluation", "/admin/evaluation", route.name === "admin-evaluation")}
                    ${navItem("Logs", "/admin/logs", route.name === "admin-logs")}
                </div>
            ` : ""}
        </aside>
    `;
}

function renderTopbar() {
    const space = currentSpace();
    return `
        <div class="topbar">
            <div class="topbar-left">
                <div class="workspace-status">
                    <span class="status-dot ${escapeHtml(state.websocket.status)} ${state.websocket.status === "connected" ? "connected" : ""}"></span>
                    <span>WebSocket ${escapeHtml(state.websocket.status)}</span>
                </div>
                ${space ? `<div class="workspace-status"><strong>${escapeHtml(space.name)}</strong></div>` : ""}
            </div>
            <div class="topbar-right">
                <label class="field" style="min-width:220px;">
                    <span class="muted">Current Space</span>
                    <select id="space-switcher">
                        <option value="">选择 Space</option>
                        ${state.spaces.map((spaceItem) => `
                            <option value="${spaceItem.id}" ${Number(spaceItem.id) === Number(currentRouteSpaceId()) ? "selected" : ""}>${escapeHtml(spaceItem.name)}</option>
                        `).join("")}
                    </select>
                </label>
                <div class="workspace-status">
                    <span>${escapeHtml(state.user?.displayName || state.user?.username || "User")}</span>
                    <button class="ghost-button" type="button" data-action="logout">退出</button>
                </div>
            </div>
        </div>
    `;
}

function renderDrawer() {
    if (!state.ui.drawer) {
        return `<aside class="drawer"><div class="drawer-header"><div><h2>Details</h2><p class="panel-subtitle">选择 Citation、Trace 或 Artifact 关联后会显示在这里。</p></div></div><div class="drawer-body">${emptyState("右侧抽屉待命中", "点击消息引用、卡片证据或日志追踪即可查看详情。")}</div></aside>`;
    }
    return `
        <aside class="drawer">
            <div class="drawer-header">
                <div>
                    <h2>${escapeHtml(state.ui.drawer.title)}</h2>
                </div>
                <button class="ghost-button" type="button" data-action="close-drawer">关闭</button>
            </div>
            <div class="drawer-body">${state.ui.drawer.html}</div>
        </aside>
    `;
}

function renderToasts() {
    return `
        <div class="toast-stack">
            ${state.ui.toasts.map((toast) => `<div class="toast ${toast.type}">${escapeHtml(toast.message)}</div>`).join("")}
        </div>
    `;
}

function renderPageContent(route) {
    if (state.ui.isPageLoading) {
        return `<div class="loading-state">正在加载 ${escapeHtml(route.name)} …</div>`;
    }
    if (state.ui.pageError) {
        return renderErrorState(state.ui.pageError);
    }
    switch (route.name) {
        case "spaces":
            return renderSpacesPage();
        case "knowledge-list":
            return renderKnowledgeListPage();
        case "knowledge-detail":
            return renderKnowledgeDetailPage(route.knowledgeBaseId);
        case "team-chat":
        case "workbench-chat":
            return renderChatPage();
        case "projects":
            return renderProjectsPage();
        case "project-detail":
        case "project-sources":
        case "project-cards":
        case "project-generate":
            return renderProjectDetailPage(route);
        case "workbench-studio":
            return renderStudioPage();
        case "artifacts":
            return renderArtifactsPage();
        case "artifact-detail":
            return renderArtifactDetailPage();
        case "wiki":
            return renderWikiPage();
        case "memory":
            return renderMemoryPage();
        case "admin-home":
            return emptyState("Admin", "正在跳转到任务页。");
        case "admin-tasks":
            return renderAdminTasksPage();
        case "admin-health":
            return renderAdminHealthPage();
        case "admin-evaluation":
            return renderAdminEvaluationPage();
        case "admin-logs":
            return renderAdminLogsPage();
        default:
            return emptyState("空页面", "这个路由还没有匹配到工作台页面。");
    }
}

function renderSpacesPage() {
    const members = (state.spaceMembers || {})[state.ui.selectedSpacePreviewId] || [];
    return `
        <div class="page-header">
            <div>
                <h1>Space 选择与切换</h1>
                <p class="subtitle">查看你已加入的 Space，创建新 Space，并快速进入实际工作区。</p>
            </div>
        </div>
        <div class="content-grid cols-2">
            ${panel("已加入的 Space", `
                <div class="table-wrap">
                    <table>
                        <thead>
                        <tr><th>Name</th><th>Status</th><th>Owner</th><th>Actions</th></tr>
                        </thead>
                        <tbody>
                        ${state.spaces.map((space) => `
                            <tr>
                                <td><strong>${escapeHtml(space.name)}</strong><div class="muted">${escapeHtml(space.description || "无描述")}</div></td>
                                <td>${badge(space.status)}</td>
                                <td>${formatNumber(space.ownerId)}</td>
                                <td>
                                    <div class="page-actions">
                                        <button class="ghost-button" type="button" data-action="preview-space" data-space-id="${space.id}">成员</button>
                                        <button class="button" type="button" data-action="enter-space" data-space-id="${space.id}">进入</button>
                                    </div>
                                </td>
                            </tr>
                        `).join("") || `<tr><td colspan="4">${emptyState("暂无 Space", "先创建一个团队空间。")}</td></tr>`}
                        </tbody>
                    </table>
                </div>
            `)}
            ${panel("创建 Space / 成员预览", `
                <form id="create-space-form" class="inline-form">
                    <div class="field">
                        <label>Space 名称</label>
                        <input name="name" required maxlength="128" placeholder="例如：Platform Research">
                    </div>
                    <div class="field">
                        <label>描述</label>
                        <textarea name="description" placeholder="记录这个 Space 的协作范围。"></textarea>
                    </div>
                    <button class="button" type="submit">创建 Space</button>
                </form>
                <hr style="border:none;border-top:1px solid var(--border);margin:18px 0;">
                ${state.ui.selectedSpacePreviewId ? `
                    <h3>成员角色</h3>
                    <div class="list-stack">
                        ${members.map((member) => `
                            <div class="list-item">
                                <div class="list-item-header">
                                    <strong>${escapeHtml(member.displayName || member.username)}</strong>
                                    ${badge(member.role, member.role)}
                                </div>
                                <div class="muted">${escapeHtml(member.email || "无邮箱")}</div>
                            </div>
                        `).join("") || emptyState("还没有成员列表", "这个 Space 暂时没有可显示成员。")}
                    </div>
                ` : emptyState("先选一个 Space", "点击左侧表格里的“成员”来查看角色和成员状态。")}
            `)}
        </div>
    `;
}

function renderKnowledgeListPage() {
    return `
        <div class="page-header">
            <div>
                <h1>Team Knowledge</h1>
                <p class="subtitle">知识库列表直接连接真实 API，能查看文档数、处理状态，并进入详情页上传与检索。</p>
            </div>
        </div>
        <div class="content-grid cols-2">
            ${panel("Knowledge Base 列表", `
                <div class="table-wrap">
                    <table>
                        <thead>
                        <tr><th>Name</th><th>Status</th><th>Documents</th><th>Latest Processing</th><th></th></tr>
                        </thead>
                        <tbody>
                        ${state.knowledge.list.map((kb) => {
                            const docs = state.knowledge.documentsByKb[kb.id] || [];
                            const latest = docs[0];
                            const latestStatus = latest ? `${latest.parseStatus || "—"} / ${latest.indexStatus || "—"}` : "暂无文档";
                            return `
                                <tr>
                                    <td><strong>${escapeHtml(kb.name)}</strong><div class="muted">${escapeHtml(kb.description || "无描述")}</div></td>
                                    <td>${badge(kb.status)}</td>
                                    <td>${formatNumber(docs.length)}</td>
                                    <td>${escapeHtml(latestStatus)}</td>
                                    <td><button class="button" type="button" data-action="open-kb" data-kb-id="${kb.id}">详情</button></td>
                                </tr>
                            `;
                        }).join("") || `<tr><td colspan="5">${emptyState("暂无知识库", "创建后就可以开始上传团队文档。")}</td></tr>`}
                        </tbody>
                    </table>
                </div>
            `)}
            ${panel("创建 Knowledge Base", `
                <form id="create-kb-form" class="inline-form">
                    <div class="field">
                        <label>名称</label>
                        <input name="name" required maxlength="128" placeholder="例如：Engineering Handbook">
                    </div>
                    <div class="field">
                        <label>描述</label>
                        <textarea name="description" maxlength="512" placeholder="说明这个知识库主要覆盖的资料类型。"></textarea>
                    </div>
                    <button class="button" type="submit">创建知识库</button>
                </form>
            `, { subtitle: "不会走 mock，创建后会立刻刷新真实列表。" })}
        </div>
    `;
}

function renderKnowledgeDetailPage(knowledgeBaseId) {
    const kb = state.knowledge.detail;
    const documents = state.knowledge.documentsByKb[knowledgeBaseId] || [];
    const searchResult = state.knowledge.searchResults[knowledgeBaseId];
    const uploadJob = state.knowledge.uploadJobsByKb[knowledgeBaseId];
    return `
        <div class="page-header">
            <div>
                <h1>${escapeHtml(kb?.name || "Knowledge Base")}</h1>
                <p class="subtitle">上传支持 MD5 计算、分片、暂停/继续、异步任务状态展示和检索测试。</p>
            </div>
            <div class="page-actions">
                <button class="ghost-button" type="button" data-nav="${routeLink("knowledge", currentRouteSpaceId())}">返回列表</button>
            </div>
        </div>
        <div class="content-grid cols-2">
            ${panel("文档列表", `
                <div class="table-wrap">
                    <table>
                        <thead>
                        <tr><th>Title</th><th>Status</th><th>Parse / Index</th><th>Chunks</th><th>Error</th></tr>
                        </thead>
                        <tbody>
                        ${documents.map((document) => `
                            <tr>
                                <td><strong>${escapeHtml(document.title || document.originalFilename || `Document ${document.id}`)}</strong><div class="muted">${escapeHtml(document.originalFilename || "")}</div></td>
                                <td>${badge(document.status)}</td>
                                <td>${escapeHtml(`${document.parseStatus || "—"} / ${document.indexStatus || "—"}`)}</td>
                                <td>${formatNumber(document.chunkCount)}</td>
                                <td>${escapeHtml(document.errorMessage || "—")}</td>
                            </tr>
                        `).join("") || `<tr><td colspan="5">${emptyState("暂无文档", "先上传一个文件，处理状态会显示在这里。")}</td></tr>`}
                        </tbody>
                    </table>
                </div>
            `)}
            ${panel("上传与检索", `
                <form id="upload-document-form" class="inline-form">
                    <div class="field">
                        <label>选择文件</label>
                        <input type="file" name="file" required>
                    </div>
                    <button class="button" type="submit">开始上传</button>
                </form>
                ${uploadJob ? `
                    <div class="list-item" style="margin-top:14px;">
                        <div class="list-item-header">
                            <strong>${escapeHtml(uploadJob.fileName)}</strong>
                            ${badge(uploadJob.stage || uploadJob.status || "RUNNING", uploadJob.stage || uploadJob.status || "RUNNING")}
                        </div>
                        <div class="muted">MD5: <span class="mono">${escapeHtml(uploadJob.fileMd5 || "计算中")}</span></div>
                        <div class="muted">Progress: ${uploadJob.progress ? uploadJob.progress.toFixed(1) : "0.0"}%</div>
                        ${uploadJob.task ? `<div class="muted">Task ${uploadJob.task.id}: ${escapeHtml(uploadJob.task.taskStatus)}</div>` : ""}
                        ${uploadJob.error ? `<div class="error-state" style="margin-top:10px;">${escapeHtml(uploadJob.error)}</div>` : ""}
                        <div class="page-actions" style="margin-top:12px;">
                            ${uploadJob.paused ? `<button class="ghost-button" type="button" data-action="resume-upload" data-kb-id="${knowledgeBaseId}">继续</button>` : `<button class="ghost-button" type="button" data-action="pause-upload" data-kb-id="${knowledgeBaseId}">暂停</button>`}
                            <button class="danger-button" type="button" data-action="cancel-upload" data-kb-id="${knowledgeBaseId}">取消</button>
                        </div>
                    </div>
                ` : ""}
                <hr style="border:none;border-top:1px solid var(--border);margin:18px 0;">
                <form id="kb-search-form" class="inline-form" data-kb-id="${knowledgeBaseId}">
                    <div class="field">
                        <label>检索测试关键词</label>
                        <input name="keyword" placeholder="例如：token refresh flow" required>
                    </div>
                    <button class="button" type="submit">检索测试</button>
                </form>
                ${searchResult ? `
                    <div class="list-stack" style="margin-top:14px;">
                        ${(searchResult.items || []).map((item) => `
                            <div class="list-item">
                                <div class="list-item-header">
                                    <strong>${escapeHtml(item.documentTitle || `Chunk ${item.chunkId}`)}</strong>
                                    ${tag(`score ${item.score?.toFixed?.(3) ?? item.score ?? "—"}`)}
                                </div>
                                <div class="muted">${escapeHtml(item.content || "")}</div>
                            </div>
                        `).join("") || emptyState("没有命中", "换一个关键词试试。")}
                    </div>
                ` : ""}
            `)}
        </div>
    `;
}

function renderChatPage() {
    const session = activeSession();
    const sessionId = session?.id;
    const serverMessages = state.chat.messagesBySession[sessionId] || [];
    const localState = state.chat.localsBySession[sessionId];
    const messages = [...serverMessages];
    if (localState?.userMessage) {
        messages.push(localState.userMessage);
    }
    if (localState?.assistantMessage) {
        messages.push(localState.assistantMessage);
    }
    if (sessionId) {
        ensureLocalDraft(sessionId);
    }
    return `
        <div class="page-header">
            <div>
                <h1>Workbench Chat</h1>
                <p class="subtitle">左侧会话、中间消息流、右侧 Citation / Context / Artifact 抽屉。支持流式、中断、草稿会话恢复。</p>
            </div>
        </div>
        <div class="chat-layout">
            <section class="session-list">
                <div class="drawer-header">
                    <div>
                        <h2>会话</h2>
                        <p class="panel-subtitle">正式会话和草稿都会保留。</p>
                    </div>
                </div>
                <div class="session-items">
                    <form id="create-session-form" class="inline-form">
                        <div class="field">
                            <label>会话标题</label>
                            <input name="title" placeholder="例如：RAG grounding check" required>
                        </div>
                        <div class="field-grid cols-2">
                            <div class="field">
                                <label>类型</label>
                                <select name="sessionKind">
                                    <option value="FORMAL">FORMAL</option>
                                    <option value="DRAFT">DRAFT</option>
                                </select>
                            </div>
                            <div class="field">
                                <label>范围</label>
                                <select name="scopeType">
                                    <option value="SPACE">SPACE</option>
                                    <option value="KNOWLEDGE_BASE">KNOWLEDGE_BASE</option>
                                </select>
                            </div>
                        </div>
                        <div class="field">
                            <label>知识库 IDs（逗号分隔，可空）</label>
                            <input name="scopeIds" placeholder="留空则使用当前 Space">
                        </div>
                        <button class="button" type="submit">创建会话</button>
                    </form>
                    <div style="margin-top:16px;">
                        ${state.chat.sessions.map((item) => `
                            <button class="session-item ${Number(item.id) === Number(sessionId) ? "active" : ""}" type="button" data-action="select-session" data-session-id="${item.id}">
                                <div class="list-item-header">
                                    <strong>${escapeHtml(item.title)}</strong>
                                    ${badge(item.runtimeStatus || item.status, item.runtimeStatus || item.status)}
                                </div>
                                <div class="muted">${escapeHtml(item.sessionKind)} / ${escapeHtml(item.scopeType)}</div>
                                <div class="muted">${formatDate(item.updatedAt || item.lastActiveAt)}</div>
                            </button>
                        `).join("") || emptyState("还没有会话", "创建一个正式会话或草稿会话开始提问。")}
                    </div>
                </div>
            </section>
            <section class="chat-stream">
                <div class="message-list">
                    ${session ? messages.map((message) => renderChatMessage(message)).join("") || emptyState("暂无消息", "发送第一条问题来触发 WebSocket 流式响应。") : emptyState("请选择会话", "左侧选一个会话，或者先创建新会话。")}
                </div>
                <div class="chat-input">
                    ${session ? `
                        <div class="page-actions" style="margin-bottom:10px;">
                            ${session.sessionKind === "DRAFT" ? `<button class="ghost-button" type="button" data-action="convert-draft" data-session-id="${session.id}">转正式会话</button><button class="danger-button" type="button" data-action="discard-draft" data-session-id="${session.id}">丢弃草稿</button>` : ""}
                            ${localState?.assistantMessage?.status === "RUNNING" ? `<button class="danger-button" type="button" data-action="stop-chat" data-session-id="${session.id}">停止生成</button>` : ""}
                            <button class="ghost-button" type="button" data-action="reconnect-chat">重连 WebSocket</button>
                        </div>
                        <form id="chat-message-form">
                            <div class="field">
                                <label>提问内容</label>
                                <textarea name="content" data-chat-draft="${session.id}" placeholder="可以直接针对团队知识库提问。">${escapeHtml(state.chat.draftsBySession[session.id] || "")}</textarea>
                            </div>
                            <button class="button" type="submit">发送</button>
                        </form>
                    ` : emptyState("没有活动会话", "创建会话后输入区会显示在这里。")}
                </div>
            </section>
            <section class="context-panel">
                <div class="drawer-header">
                    <div>
                        <h2>Citation / Artifacts</h2>
                        <p class="panel-subtitle">点击消息里的 Citation 可在右侧全局抽屉看证据定位。</p>
                    </div>
                </div>
                <div class="context-body">
                    ${sessionId ? `
                        <div class="list-stack">
                            ${(state.chat.artifactsBySession[sessionId] || []).map((artifact) => `
                                <div class="list-item">
                                    <div class="list-item-header">
                                        <strong>${escapeHtml(artifact.title)}</strong>
                                        ${badge(artifact.status)}
                                    </div>
                                    <div class="muted">${escapeHtml(artifact.artifactType)}</div>
                                    <div class="page-actions" style="margin-top:10px;">
                                        <button class="ghost-button" type="button" data-action="open-artifact" data-artifact-id="${artifact.id}" data-space-id="${artifact.spaceId || currentRouteSpaceId()}">打开 Artifact</button>
                                    </div>
                                </div>
                            `).join("") || emptyState("暂无 Artifact", "聊天生成的 Artifact 会显示在这里。")}
                        </div>
                    ` : emptyState("暂无上下文", "先选择一个会话。")}
                </div>
            </section>
        </div>
    `;
}

function renderChatMessage(message) {
    const roleClass = String(message.role || "ASSISTANT").toLowerCase().includes("user") ? "user" : "assistant";
    const citations = message.id ? state.chat.citationsByMessage[message.id] : message.citations || [];
    const actions = [];
    if (citations?.length) {
        citations.forEach((citation, index) => {
            const key = registerCitation(citation);
            actions.push(`<button class="citation-chip" type="button" data-action="open-citation" data-citation-key="${key}">Citation ${index + 1}</button>`);
        });
    }
    if (message.id && roleClass === "assistant") {
        if (!citations?.length) {
            actions.push(`<button class="ghost-button" type="button" data-action="load-message-citations" data-message-id="${message.id}">查看引用</button>`);
        }
        actions.push(`<button class="ghost-button" type="button" data-action="feedback-up" data-message-id="${message.id}">有帮助</button>`);
        actions.push(`<button class="ghost-button" type="button" data-action="feedback-down" data-message-id="${message.id}">需改进</button>`);
    }

    return `
        <article class="message ${roleClass}">
            <div class="message-header">
                <span>${escapeHtml(message.role || roleClass.toUpperCase())}</span>
                ${badge(message.status || message.runtimeStatus || "ACTIVE", message.status || message.runtimeStatus || "ACTIVE")}
            </div>
            <div>${renderMarkdown(message.content || "")}</div>
            ${actions.length ? `<div class="message-actions">${actions.join("")}</div>` : ""}
        </article>
    `;
}

function renderProjectsPage() {
    return `
        <div class="page-header">
            <div>
                <h1>Personal Research Projects</h1>
                <p class="subtitle">项目列表、创建入口，以及 Source / Card / Artifact 数量概览。</p>
            </div>
        </div>
        <div class="content-grid cols-2">
            ${panel("项目列表", `
                <div class="table-wrap">
                    <table>
                        <thead>
                        <tr><th>Title</th><th>Status</th><th>Compile</th><th>Counts</th><th></th></tr>
                        </thead>
                        <tbody>
                        ${state.personal.projects.map((project) => {
                            const counts = state.personal.projectCounts[project.id] || {};
                            return `
                                <tr>
                                    <td><strong>${escapeHtml(project.title)}</strong><div class="muted">${escapeHtml(project.researchGoal || project.description || "无目标说明")}</div></td>
                                    <td>${badge(project.status)}</td>
                                    <td>${badge(project.compileStatus || "PENDING", project.compileStatus || "PENDING")}</td>
                                    <td>${formatNumber(counts.sourceCount)} Sources / ${formatNumber(counts.cardCount)} Cards / ${formatNumber(counts.artifactCount)} Artifacts</td>
                                    <td><button class="button" type="button" data-action="open-project" data-project-id="${project.id}" data-space-id="${project.spaceId || personalSpaceId() || ""}">进入</button></td>
                                </tr>
                            `;
                        }).join("") || `<tr><td colspan="5">${emptyState("暂无项目", "创建一个研究项目开始导入 Source。")}</td></tr>`}
                        </tbody>
                    </table>
                </div>
            `)}
            ${panel("创建 ResearchProject", `
                <form id="create-project-form" class="inline-form">
                    <div class="field">
                        <label>标题</label>
                        <input name="title" required maxlength="255" placeholder="例如：OpenTelemetry rollout">
                    </div>
                    <div class="field">
                        <label>描述</label>
                        <textarea name="description"></textarea>
                    </div>
                    <div class="field">
                        <label>研究目标</label>
                        <textarea name="researchGoal" placeholder="明确这次研究要回答的问题。"></textarea>
                    </div>
                    <button class="button" type="submit">创建项目</button>
                </form>
            `)}
        </div>
    `;
}

function renderProjectDetailPage(route) {
    const projectId = route.projectId;
    const project = state.personal.detail;
    const projectSpaceId = resolveProjectSpaceId(project);
    const sources = state.personal.sourcesByProject[projectId] || [];
    const articles = state.personal.articleCardsByProject[projectId] || [];
    const concepts = state.personal.conceptCardsByProject[projectId] || [];
    const synthesisCards = state.personal.synthesisCardsByProject[projectId] || [];
    const methodologyCards = state.personal.methodologyByProject[projectId] || [];
    const artifacts = state.artifacts.list.filter((artifact) => Number(artifact.researchProjectId) === Number(projectId));
    const tab = route.name.replace("project-", "") || "detail";
    const tabLink = (label, suffix, active) => `<a class="tab-link ${active ? "active" : ""}" href="/spaces/${projectSpaceId}/personal/projects/${projectId}${suffix}" data-nav="/spaces/${projectSpaceId}/personal/projects/${projectId}${suffix}">${label}</a>`;

    let body = "";
    if (route.name === "project-sources") {
        body = renderProjectSources(projectId, sources);
    } else if (route.name === "project-cards") {
        body = renderProjectCards(articles, concepts, synthesisCards);
    } else if (route.name === "project-generate") {
        body = renderProjectGenerate(projectSpaceId, projectId, methodologyCards, artifacts);
    } else {
        body = `
            <div class="metric-row">
                <div class="metric"><strong>${formatNumber(sources.length)}</strong><span>Sources</span></div>
                <div class="metric"><strong>${formatNumber(articles.length + concepts.length + synthesisCards.length)}</strong><span>Cards</span></div>
                <div class="metric"><strong>${formatNumber(artifacts.length)}</strong><span>Artifacts</span></div>
                <div class="metric"><strong>${formatNumber(methodologyCards.length)}</strong><span>Methodology</span></div>
            </div>
            <div class="content-grid cols-2" style="margin-top:16px;">
                ${panel("最近 Sources", sources.slice(0, 4).map((source) => `<div class="list-item"><strong>${escapeHtml(source.title)}</strong><div class="muted">${badge(source.importStatus, source.importStatus)} ${badge(source.compileStatus, source.compileStatus)}</div></div>`).join("") || emptyState("暂无 Source", "到 Sources 标签导入文件、URL 或文本。"))}
                ${panel("最近 Artifacts", artifacts.slice(0, 4).map((artifact) => `<div class="list-item"><strong>${escapeHtml(artifact.title)}</strong><div class="muted">${escapeHtml(artifact.artifactType)} / ${badge(artifact.status)}</div></div>`).join("") || emptyState("暂无 Artifact", "到 Generate 标签发起生成。"))}
            </div>
        `;
    }

    return `
        <div class="page-header">
            <div>
                <h1>${escapeHtml(project?.title || "Research Project")}</h1>
                <p class="subtitle">${escapeHtml(project?.researchGoal || project?.description || "项目概览")}</p>
            </div>
            <div class="page-actions">
                <button class="ghost-button" type="button" data-nav="${routeLink("projects", projectSpaceId)}">返回项目列表</button>
            </div>
        </div>
        <div class="tab-bar">
            ${tabLink("Overview", "", route.name === "project-detail")}
            ${tabLink("Sources", "/sources", route.name === "project-sources")}
            ${tabLink("Cards", "/cards", route.name === "project-cards")}
            ${tabLink("Generate", "/generate", route.name === "project-generate")}
        </div>
        ${body}
    `;
}

function renderProjectSources(projectId, sources) {
    return `
        <div class="content-grid cols-2">
            ${panel("导入 Sources", `
                <form id="source-file-form" data-project-id="${projectId}" class="inline-form">
                    <div class="field"><label>上传文件</label><input type="file" name="file" required></div>
                    <div class="field"><label>自定义标题</label><input name="title"></div>
                    <button class="button" type="submit">上传文件 Source</button>
                </form>
                <hr style="border:none;border-top:1px solid var(--border);margin:18px 0;">
                <form id="source-url-form" data-project-id="${projectId}" class="inline-form">
                    <div class="field"><label>URL</label><input name="url" required></div>
                    <div class="field"><label>标题</label><input name="title"></div>
                    <button class="button" type="submit">添加 URL Source</button>
                </form>
                <hr style="border:none;border-top:1px solid var(--border);margin:18px 0;">
                <form id="source-text-form" data-project-id="${projectId}" class="inline-form">
                    <div class="field"><label>标题</label><input name="title" required></div>
                    <div class="field"><label>内容</label><textarea name="content" required></textarea></div>
                    <button class="button" type="submit">添加文本 Source</button>
                </form>
            `)}
            ${panel("Source 列表", `
                <div class="list-stack">
                    ${sources.map((source) => `
                        <div class="list-item">
                            <div class="list-item-header">
                                <strong>${escapeHtml(source.title)}</strong>
                                <div class="page-actions">${badge(source.importStatus, source.importStatus)}${badge(source.compileStatus, source.compileStatus)}</div>
                            </div>
                            <div class="muted">${escapeHtml(source.sourceType)} / task ${formatNumber(source.taskId)}</div>
                            <div class="page-actions" style="margin-top:10px;">
                                <button class="ghost-button" type="button" data-action="source-import" data-source-id="${source.id}">重新导入</button>
                                <button class="ghost-button" type="button" data-action="source-compile" data-source-id="${source.id}">触发 Wiki Compiler</button>
                            </div>
                        </div>
                    `).join("") || emptyState("暂无 Source", "先导入一份文件、URL 或粘贴文本。")}
                </div>
            `)}
        </div>
    `;
}

function renderProjectCards(articles, concepts, synthesisCards) {
    return `
        <div class="content-grid cols-3">
            ${panel("ArticleCard", articles.map((card) => `
                <div class="list-item">
                    <div class="list-item-header"><strong>${escapeHtml(card.title)}</strong></div>
                    <div class="muted">${escapeHtml(card.summary || "无摘要")}</div>
                    ${(card.citations || []).slice(0, 2).map((citation, index) => {
                        const key = registerCitation(citation);
                        return `<button class="citation-chip" type="button" data-action="open-citation" data-citation-key="${key}">证据 ${index + 1}</button>`;
                    }).join("")}
                </div>
            `).join("") || emptyState("暂无 ArticleCard", "Source 完成导入后，这里会显示文章卡片。"))}
            ${panel("ConceptCard", concepts.map((card) => `
                <div class="list-item">
                    <div class="list-item-header"><strong>${escapeHtml(card.name)}</strong>${tag(`conf ${card.confidence}`)}</div>
                    <div class="muted">${escapeHtml(card.definition || card.explanation || "无定义")}</div>
                    ${(card.citations || []).slice(0, 2).map((citation, index) => {
                        const key = registerCitation(citation);
                        return `<button class="citation-chip" type="button" data-action="open-citation" data-citation-key="${key}">证据 ${index + 1}</button>`;
                    }).join("")}
                </div>
            `).join("") || emptyState("暂无 ConceptCard", "编译完成后会展示概念卡片。"))}
            ${panel("SynthesisCard", synthesisCards.map((card) => `
                <div class="list-item">
                    <div class="list-item-header"><strong>${escapeHtml(card.title)}</strong>${badge(card.cardStatus, card.cardStatus)}</div>
                    <div class="muted">${escapeHtml(card.summary || "无摘要")}</div>
                    ${(card.citations || []).slice(0, 2).map((citation, index) => {
                        const key = registerCitation(citation);
                        return `<button class="citation-chip" type="button" data-action="open-citation" data-citation-key="${key}">证据 ${index + 1}</button>`;
                    }).join("")}
                </div>
            `).join("") || emptyState("暂无 SynthesisCard", "Artifact 蒸馏或生成完成后会沉淀在这里。"))}
        </div>
    `;
}

function renderProjectGenerate(spaceId, projectId, methodologyCards, artifacts) {
    const skills = state.studio.skills;
    return `
        <div class="content-grid cols-2">
            ${panel("生成 Artifact", `
                <form id="project-generate-form" data-space-id="${spaceId}" data-project-id="${projectId}" class="inline-form">
                    <div class="field-grid cols-2">
                        <div class="field">
                            <label>Studio Skill</label>
                            <select name="skillId" required>
                                ${skills.map((skill) => `<option value="${escapeHtml(skill.id)}">${escapeHtml(skill.name)}</option>`).join("")}
                            </select>
                        </div>
                        <div class="field">
                            <label>MethodologyCard</label>
                            <select name="methodologyCardId">
                                <option value="">不指定</option>
                                ${methodologyCards.map((card) => `<option value="${card.id}">${escapeHtml(card.name)}</option>`).join("")}
                            </select>
                        </div>
                    </div>
                    <div class="field">
                        <label>Topic</label>
                        <input name="topic" required placeholder="例如：RAG citation grounding report">
                    </div>
                    <button class="button" type="submit">创建 Artifact</button>
                </form>
                ${state.studio.lastTask ? `
                    <div class="list-item" style="margin-top:14px;">
                        <div class="list-item-header">
                            <strong>最近生成任务</strong>
                            ${badge(state.studio.lastTask.taskStatus)}
                        </div>
                        <div class="muted">Artifact ${formatNumber(state.studio.lastTask.artifactId)} / Task ${formatNumber(state.studio.lastTask.taskId)}</div>
                    </div>
                ` : ""}
            `)}
            ${panel("Artifacts 与 Methodology", `
                <h3>Methodology Cards</h3>
                <div class="list-stack">
                    ${methodologyCards.map((card) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(card.name)}</strong>${badge(card.status)}</div>
                            <div class="muted">${escapeHtml(card.problemType || card.scene || "未设置场景")}</div>
                        </div>
                    `).join("") || emptyState("暂无 MethodologyCard", "当前项目还没有方法论卡片。")}
                </div>
                <hr style="border:none;border-top:1px solid var(--border);margin:18px 0;">
                <h3>Artifacts</h3>
                <div class="list-stack">
                    ${artifacts.map((artifact) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(artifact.title)}</strong>${badge(artifact.status)}</div>
                            <div class="page-actions" style="margin-top:10px;"><button class="ghost-button" type="button" data-action="open-artifact" data-artifact-id="${artifact.id}" data-space-id="${artifact.spaceId || spaceId}">打开</button></div>
                        </div>
                    `).join("") || emptyState("暂无 Artifact", "生成任务完成后会出现在这里。")}
                </div>
            `)}
        </div>
    `;
}

function renderStudioPage() {
    return `
        <div class="page-header">
            <div>
                <h1>Studio</h1>
                <p class="subtitle">选择 Skill、填写参数、选择上下文范围，并查看最近 Artifact 生成结果。</p>
            </div>
        </div>
        <div class="content-grid cols-2">
            ${panel("启动生成任务", `
                <form id="studio-task-form" class="inline-form" data-space-id="${currentRouteSpaceId()}">
                    <div class="field-grid cols-2">
                        <div class="field">
                            <label>Skill</label>
                            <select name="skillId" required>
                                ${state.studio.skills.map((skill) => `<option value="${skill.id}">${escapeHtml(skill.name)}</option>`).join("")}
                            </select>
                        </div>
                        <div class="field">
                            <label>ResearchProject</label>
                            <select name="projectId" required>
                                ${state.personal.projects.map((project) => `<option value="${project.id}">${escapeHtml(project.title)}</option>`).join("")}
                            </select>
                        </div>
                    </div>
                    <div class="field">
                        <label>Topic</label>
                        <input name="topic" required placeholder="例如：Study Guide for retrieval debugging">
                    </div>
                    <button class="button" type="submit">启动 Studio Task</button>
                </form>
                ${state.studio.lastTask ? `<div class="list-item" style="margin-top:14px;"><div class="list-item-header"><strong>最近任务</strong>${badge(state.studio.lastTask.taskStatus)}</div><div class="muted">Artifact ${formatNumber(state.studio.lastTask.artifactId)}</div></div>` : ""}
            `)}
            ${panel("技能与 Artifact", `
                <div class="list-stack">
                    ${state.studio.skills.map((skill) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(skill.name)}</strong>${tag(skill.artifactType)}</div>
                            <div class="muted">${escapeHtml(skill.description)}</div>
                            <div class="muted">Hint: ${escapeHtml(skill.topicHint)}</div>
                        </div>
                    `).join("")}
                </div>
                <hr style="border:none;border-top:1px solid var(--border);margin:18px 0;">
                <div class="list-stack">
                    ${state.artifacts.list.slice(0, 6).map((artifact) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(artifact.title)}</strong>${badge(artifact.status)}</div>
                            <div class="page-actions" style="margin-top:10px;"><button class="ghost-button" type="button" data-action="open-artifact" data-artifact-id="${artifact.id}" data-space-id="${artifact.spaceId || currentRouteSpaceId()}">打开 Artifact</button></div>
                        </div>
                    `).join("") || emptyState("暂无 Artifact", "生成后的内容会显示在这里。")}
                </div>
            `)}
        </div>
    `;
}

function renderArtifactsPage() {
    return `
        <div class="page-header">
            <div>
                <h1>Artifacts</h1>
                <p class="subtitle">预览、筛选和进入 Artifact 详情页。详情页支持编辑、导出、蒸馏与发布 Wiki。</p>
            </div>
        </div>
        ${panel("Artifact 列表", `
            <div class="table-wrap">
                <table>
                    <thead>
                    <tr><th>Title</th><th>Type</th><th>Status</th><th>Project</th><th></th></tr>
                    </thead>
                    <tbody>
                    ${state.artifacts.list.map((artifact) => `
                        <tr>
                            <td><strong>${escapeHtml(artifact.title)}</strong></td>
                            <td>${escapeHtml(artifact.artifactType)}</td>
                            <td>${badge(artifact.status)}</td>
                            <td>${formatNumber(artifact.researchProjectId)}</td>
                            <td><button class="button" type="button" data-action="open-artifact" data-artifact-id="${artifact.id}" data-space-id="${artifact.spaceId || currentRouteSpaceId()}">打开</button></td>
                        </tr>
                    `).join("") || `<tr><td colspan="5">${emptyState("暂无 Artifact", "从 Studio 或个人 Generate 入口创建一个。")}</td></tr>`}
                    </tbody>
                </table>
            </div>
        `)}
    `;
}

function renderArtifactDetailPage() {
    const artifact = state.artifacts.detail;
    const artifactSpaceId = resolveArtifactSpaceId(artifact);
    const draft = state.artifacts.editorDraft || { title: "", content: "", changeNote: "" };
    return `
        <div class="page-header">
            <div>
                <h1>${escapeHtml(artifact?.title || "Artifact Viewer")}</h1>
                <p class="subtitle">Markdown 预览、基础编辑、导出、Citation 查看、沉淀到个人 Wiki、发布到团队 Wiki。</p>
            </div>
            <div class="page-actions">
                <button class="ghost-button" type="button" data-nav="${routeLink("artifacts", artifactSpaceId)}">返回列表</button>
                <button class="ghost-button" type="button" data-action="export-artifact" data-artifact-id="${artifact.id}">导出 Markdown</button>
                <button class="ghost-button" type="button" data-action="regenerate-artifact" data-artifact-id="${artifact.id}">重新生成</button>
            </div>
        </div>
        <div class="content-grid cols-2">
            ${panel("编辑器", `
                <form id="artifact-edit-form" data-artifact-id="${artifact.id}" class="inline-form">
                    <div class="field">
                        <label>标题</label>
                        <input name="title" value="${escapeHtml(draft.title)}" required>
                    </div>
                    <div class="field">
                        <label>内容</label>
                        <textarea class="editor-textarea" name="content" required>${escapeHtml(draft.content)}</textarea>
                    </div>
                    <div class="field">
                        <label>变更说明</label>
                        <input name="changeNote" value="${escapeHtml(draft.changeNote || "")}">
                    </div>
                    <button class="button" type="submit">保存 Artifact</button>
                </form>
            `)}
            ${panel("预览与沉淀", `
                ${renderMarkdown(draft.content)}
                <hr style="border:none;border-top:1px solid var(--border);margin:18px 0;">
                <form id="distill-artifact-form" data-artifact-id="${artifact.id}" class="inline-form">
                    <div class="field">
                        <label>沉淀方式</label>
                        <select name="cardType">
                            <option value="SYNTHESIS">Synthesis</option>
                        </select>
                    </div>
                    <button class="button" type="submit">预览提炼结果</button>
                </form>
                <form id="publish-artifact-wiki-form" data-artifact-id="${artifact.id}" class="inline-form" style="margin-top:14px;">
                    <input type="hidden" name="spaceId" value="${artifactSpaceId}">
                    <div class="field">
                        <label>Wiki 页面标题</label>
                        <input name="title" value="${escapeHtml(artifact.title)}" required>
                    </div>
                    <button class="ghost-button" type="submit">发布到 Team Wiki</button>
                </form>
            `)}
        </div>
        <div class="content-grid cols-2" style="margin-top:16px;">
            ${panel("Citation", `
                <div class="list-stack">
                    ${(artifact.citations || []).map((citation, index) => {
                        const key = registerCitation(citation);
                        return `<button class="list-item" type="button" data-action="open-citation" data-citation-key="${key}"><strong>Citation ${index + 1}</strong><div class="muted">${escapeHtml(citation.title || citation.locationInfo || "")}</div></button>`;
                    }).join("") || emptyState("暂无 Citation", "这个 Artifact 目前没有引用记录。")}
                </div>
            `)}
            ${panel("Card Relations", `
                <div class="list-stack">
                    ${state.artifacts.relations.map((relation) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(relation.cardTitle || relation.cardType)}</strong>${tag(relation.relationType)}</div>
                            <div class="muted">${escapeHtml(relation.cardType)} / ID ${formatNumber(relation.cardId)}</div>
                        </div>
                    `).join("") || emptyState("暂无关联卡片", "蒸馏后这里会展示关联的 Wiki Card。")}
                </div>
            `)}
        </div>
    `;
}

function renderWikiPage() {
    const page = state.wiki.pageDetail;
    return `
        <div class="page-header">
            <div>
                <h1>Team Wiki</h1>
                <p class="subtitle">查看 Wiki 列表、创建草稿、编辑、发布、查看版本，并支持搜索。</p>
            </div>
        </div>
        <div class="content-grid cols-2">
            ${panel("Wiki 页面列表", `
                <form id="wiki-search-form" class="inline-form">
                    <div class="field"><label>搜索</label><input name="keyword" placeholder="输入关键词搜索 Wiki"></div>
                    <button class="ghost-button" type="submit">搜索</button>
                </form>
                ${state.wiki.searchResult ? `<div class="list-stack" style="margin-top:14px;">${(state.wiki.searchResult.items || []).map((item) => `<div class="list-item"><strong>${escapeHtml(item.title)}</strong><div class="muted">${escapeHtml(item.contentSnippet || "")}</div></div>`).join("") || emptyState("无搜索结果", "换个词试试。")}</div><hr style="border:none;border-top:1px solid var(--border);margin:18px 0;">` : ""}
                <div class="list-stack">
                    ${state.wiki.pages.map((wikiPage) => `
                        <button class="list-item" type="button" data-action="select-wiki-page" data-page-id="${wikiPage.id}">
                            <div class="list-item-header"><strong>${escapeHtml(wikiPage.title)}</strong>${badge(wikiPage.status)}</div>
                            <div class="muted">Version ${formatNumber(wikiPage.publishedVersionNo)}</div>
                        </button>
                    `).join("") || emptyState("暂无 Wiki 页面", "先创建一个草稿页。")}
                </div>
            `)}
            ${panel(page ? "编辑 Wiki Draft" : "创建 Wiki Draft", page ? `
                <form id="wiki-edit-form" data-page-id="${page.id}" class="inline-form">
                    <div class="field"><label>标题</label><input name="title" value="${escapeHtml(page.title)}" required></div>
                    <div class="field"><label>内容</label><textarea class="editor-textarea" name="content" required>${escapeHtml(page.content || "")}</textarea></div>
                    <div class="page-actions">
                        <button class="button" type="submit">保存草稿</button>
                        <button class="ghost-button" type="button" data-action="publish-wiki-page" data-page-id="${page.id}">发布 Wiki</button>
                    </div>
                </form>
            ` : `
                <form id="wiki-create-form" data-space-id="${currentRouteSpaceId()}" class="inline-form">
                    <div class="field"><label>标题</label><input name="title" required></div>
                    <div class="field"><label>内容</label><textarea class="editor-textarea" name="content" required></textarea></div>
                    <button class="button" type="submit">创建草稿</button>
                </form>
            `)}
        </div>
        <div style="margin-top:16px;">
            ${panel("版本历史", `
                <div class="list-stack">
                    ${state.wiki.versions.map((version) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>v${formatNumber(version.versionNo)}</strong><span class="muted">${formatDate(version.createdAt)}</span></div>
                            <div class="muted">${escapeHtml(version.title)}</div>
                        </div>
                    `).join("") || emptyState("暂无版本", "发布后会开始生成版本记录。")}
                </div>
            `)}
        </div>
    `;
}

function renderMemoryPage() {
    const synthesisProjectId = state.personal.selectedSynthesisProjectId;
    const synthesisCards = synthesisProjectId ? (state.personal.synthesisCardsByProject[synthesisProjectId] || []) : [];
    return `
        <div class="page-header">
            <div>
                <h1>Memory</h1>
                <p class="subtitle">明确哪些 Space / User / Session 内容会影响后续回答，保存动作明确，不对 DRAFT 会话做长期写入暗示。</p>
            </div>
        </div>
        <div class="content-grid cols-2">
            ${panel("Space Memory", `
                <div class="list-item">
                    <div class="list-item-header"><strong>Summary</strong>${state.memory.spaceMemory ? badge(state.memory.spaceMemory.writeEnabled ? "ENABLED" : "DISABLED", state.memory.spaceMemory.writeEnabled ? "WRITE ON" : "WRITE OFF") : ""}</div>
                    <div class="muted">${escapeHtml(state.memory.spaceMemory?.summary || "暂无 Summary")}</div>
                </div>
                <form id="space-memory-form" class="inline-form" data-space-id="${currentRouteSpaceId()}">
                    <div class="field-grid cols-2">
                        <div class="field"><label>Topic</label><input name="topic" required></div>
                        <div class="field"><label>MemoryType</label><select name="memoryType"><option value="SPACE_CONTEXT">SPACE_CONTEXT</option><option value="SESSION_INSIGHT">SESSION_INSIGHT</option></select></div>
                    </div>
                    <div class="field"><label>Summary</label><textarea name="summary" required></textarea></div>
                    <button class="button" type="submit">保存 Space Memory</button>
                </form>
                <div class="list-stack" style="margin-top:14px;">
                    ${(state.memory.spaceMemory?.items || []).map((item) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(item.topic)}</strong>${badge(item.memoryType, item.memoryType)}</div>
                            <div class="muted">${escapeHtml(item.summary)}</div>
                            <button class="danger-button" type="button" data-action="delete-space-memory-item" data-item-id="${item.id}">删除</button>
                        </div>
                    `).join("") || emptyState("暂无 Space Memory", "保存后这里会显示影响团队回答的长期记忆。")}
                </div>
            `)}
            ${panel("User Memory", `
                <div class="page-actions">
                    <button class="ghost-button" type="button" data-action="${state.memory.userMemory?.writeEnabled ? "disable-user-memory" : "enable-user-memory"}">${state.memory.userMemory?.writeEnabled ? "关闭写入" : "开启写入"}</button>
                </div>
                <div class="list-item" style="margin-top:10px;">
                    <div class="list-item-header"><strong>Summary</strong>${state.memory.userMemory ? badge(state.memory.userMemory.writeEnabled ? "ENABLED" : "DISABLED", state.memory.userMemory.writeEnabled ? "WRITE ON" : "WRITE OFF") : ""}</div>
                    <div class="muted">${escapeHtml(state.memory.userMemory?.summary || "暂无 Summary")}</div>
                </div>
                <form id="user-memory-form" class="inline-form" style="margin-top:14px;">
                    <div class="field-grid cols-2">
                        <div class="field"><label>Topic</label><input name="topic" required></div>
                        <div class="field"><label>MemoryType</label><select name="memoryType"><option value="USER_PREFERENCE">USER_PREFERENCE</option><option value="SESSION_INSIGHT">SESSION_INSIGHT</option></select></div>
                    </div>
                    <div class="field"><label>Summary</label><textarea name="summary" required></textarea></div>
                    <button class="button" type="submit">保存 User Memory</button>
                </form>
                <div class="list-stack" style="margin-top:14px;">
                    ${(state.memory.userMemory?.items || []).map((item) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(item.topic)}</strong>${badge(item.memoryType, item.memoryType)}</div>
                            <div class="muted">${escapeHtml(item.summary)}</div>
                            <button class="danger-button" type="button" data-action="delete-user-memory-item" data-item-id="${item.id}">删除</button>
                        </div>
                    `).join("") || emptyState("暂无 User Memory", "个人偏好与长期记忆会显示在这里。")}
                </div>
            `)}
        </div>
        <div class="content-grid cols-2" style="margin-top:16px;">
            ${panel("Session Summary", `
                <div class="field">
                    <label>选择会话</label>
                    <select id="memory-session-selector">
                        ${state.chat.sessions.map((session) => `<option value="${session.id}" ${Number(session.id) === Number(state.memory.selectedSessionId) ? "selected" : ""}>${escapeHtml(session.title)}</option>`).join("")}
                    </select>
                </div>
                <div class="list-stack" style="margin-top:14px;">
                    ${state.memory.sessionSummaries.map((summary) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(summary.topic)}</strong>${tag(`importance ${summary.importanceScore}`)}</div>
                            <div class="muted">${escapeHtml(summary.summary)}</div>
                        </div>
                    `).join("") || emptyState("暂无 Session Summary", "当前会话还没有生成会话摘要。")}
                </div>
            `)}
            ${panel("SynthesisCard", `
                <div class="field">
                    <label>选择项目</label>
                    <select id="synthesis-project-selector">
                        ${state.personal.projects.map((project) => `<option value="${project.id}" ${Number(project.id) === Number(synthesisProjectId) ? "selected" : ""}>${escapeHtml(project.title)}</option>`).join("")}
                    </select>
                </div>
                <div class="list-stack" style="margin-top:14px;">
                    ${synthesisCards.map((card) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(card.title)}</strong>${badge(card.cardStatus, card.cardStatus)}</div>
                            <div class="muted">${escapeHtml(card.summary || "无摘要")}</div>
                        </div>
                    `).join("") || emptyState("暂无 SynthesisCard", "Artifact 蒸馏后这里会同步显示。")}
                </div>
            `)}
        </div>
    `;
}

function renderAdminTasksPage() {
    const tasks = flattenPageResponse(state.admin.tasks);
    return `
        <div class="page-header">
            <div>
                <h1>Admin Tasks</h1>
                <p class="subtitle">任务列表、状态筛选、失败原因、重试与取消入口。</p>
            </div>
        </div>
        ${renderDashboardMetrics()}
        <div style="margin-top:16px;">
            ${panel("任务列表", `
                <form id="admin-task-filter-form" class="inline-form" style="margin-bottom:14px;">
                    <div class="field">
                        <label>状态筛选</label>
                        <select name="taskStatus">
                            <option value="">全部状态</option>
                            ${["PENDING", "RUNNING", "SUCCESS", "FAILED", "CANCELLED", "TIMEOUT"].map((status) => `
                                <option value="${status}" ${state.admin.taskFilters.taskStatus === status ? "selected" : ""}>${status}</option>
                            `).join("")}
                        </select>
                    </div>
                    <button class="ghost-button" type="submit">应用筛选</button>
                </form>
                <div class="table-wrap">
                    <table>
                        <thead>
                        <tr><th>ID</th><th>Type</th><th>Status</th><th>Target</th><th>Error</th><th>Actions</th></tr>
                        </thead>
                        <tbody>
                        ${tasks.map((task) => `
                            <tr>
                                <td class="mono">${formatNumber(task.id)}</td>
                                <td>${escapeHtml(task.taskType)}</td>
                                <td>${badge(task.taskStatus)}</td>
                                <td>${escapeHtml(task.targetType || "—")} / ${formatNumber(task.targetId)}</td>
                                <td>${escapeHtml(task.errorMessage || "—")}</td>
                                <td>
                                    <div class="page-actions">
                                        <button class="ghost-button" type="button" data-action="open-admin-task" data-task-id="${task.id}">详情</button>
                                        <button class="ghost-button" type="button" data-action="admin-retry-task" data-task-id="${task.id}">重试</button>
                                        <button class="danger-button" type="button" data-action="admin-cancel-task" data-task-id="${task.id}">取消</button>
                                    </div>
                                </td>
                            </tr>
                        `).join("") || `<tr><td colspan="6">${emptyState("暂无任务", "系统暂时没有可显示任务。")}</td></tr>`}
                        </tbody>
                    </table>
                </div>
            `)}
        </div>
    `;
}

function renderAdminTaskDetailDrawer() {
    const task = state.admin.taskDetail;
    const events = flattenPageResponse(state.admin.taskEvents);
    if (!task) {
        return emptyState("暂无任务详情", "点击任务列表中的详情按钮后，这里会展示完整执行信息。");
    }
    return `
        <div class="list-stack">
            <div class="list-item">
                <div class="list-item-header"><strong>Task ${formatNumber(task.id)}</strong>${badge(task.taskStatus)}</div>
                <div class="muted">Type: ${escapeHtml(task.taskType)}</div>
                <div class="muted">Target: ${escapeHtml(task.targetType || "—")} / ${formatNumber(task.targetId)}</div>
                <div class="muted">Retry: ${formatNumber(task.retryCount)} / ${formatNumber(task.maxRetryCount)}</div>
                <div class="muted">Started: ${formatDate(task.startedAt)}</div>
                <div class="muted">Finished: ${formatDate(task.finishedAt)}</div>
                <div class="muted">Error: ${escapeHtml(task.errorMessage || "—")}</div>
            </div>
            ${panel("输入", renderJson(task.input))}
            ${panel("输出", renderJson(task.output))}
            ${panel("事件历史", `
                <div class="list-stack">
                    ${events.map((item) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(item.eventType)}</strong><span class="muted">${formatDate(item.createdAt)}</span></div>
                            <div class="muted">${escapeHtml(item.message || "—")}</div>
                            <div class="muted">Status: ${escapeHtml(item.fromStatus || "—")} -> ${escapeHtml(item.toStatus || "—")}</div>
                            ${item.payload ? renderJson(item.payload) : ""}
                        </div>
                    `).join("") || emptyState("暂无事件", "这个任务还没有可展示的事件记录。")}
                </div>
            `)}
        </div>
    `;
}

function renderDashboardMetrics() {
    const dashboard = state.admin.dashboard;
    if (!dashboard) {
        return "";
    }
    return `
        <div class="metric-row">
            <div class="metric"><strong>${formatNumber(dashboard.userCount)}</strong><span>Users</span></div>
            <div class="metric"><strong>${formatNumber(dashboard.spaceCount)}</strong><span>Spaces</span></div>
            <div class="metric"><strong>${formatNumber(dashboard.taskCount)}</strong><span>Tasks</span></div>
            <div class="metric"><strong>${formatNumber(dashboard.failedTaskCount)}</strong><span>Failed Tasks</span></div>
        </div>
    `;
}

function renderAdminHealthPage() {
    const components = state.admin.health?.components || [];
    return `
        <div class="page-header">
            <div>
                <h1>Admin Health</h1>
                <p class="subtitle">组件健康状态、最近检查时间、延迟和错误详情。</p>
            </div>
            <div class="page-actions">
                <button class="ghost-button" type="button" data-action="refresh-page">手动刷新</button>
            </div>
        </div>
        ${renderDashboardMetrics()}
        <div style="margin-top:16px;">
            ${panel("系统健康", `
                <div class="table-wrap">
                    <table>
                        <thead><tr><th>Component</th><th>Status</th><th>Latency</th><th>Checked At</th><th>Detail</th></tr></thead>
                        <tbody>
                        ${components.map((component) => `
                            <tr>
                                <td>${escapeHtml(component.component)}</td>
                                <td>${badge(component.status)}</td>
                                <td>${formatNumber(component.latencyMs)} ms</td>
                                <td>${formatDate(component.checkedAt)}</td>
                                <td><button class="ghost-button" type="button" data-action="open-health-detail" data-component="${escapeHtml(component.component)}">查看</button></td>
                            </tr>
                        `).join("") || `<tr><td colspan="5">${emptyState("暂无健康数据", "点击刷新后会重新执行组件检查。")}</td></tr>`}
                        </tbody>
                    </table>
                </div>
            `)}
        </div>
    `;
}

function renderAdminEvaluationPage() {
    return `
        <div class="page-header">
            <div>
                <h1>Admin Evaluation</h1>
                <p class="subtitle">RagEvalCase 列表、创建入口、启动 Eval Run，并查看 recall@k / MRR / citationCoverage。</p>
            </div>
        </div>
        <div class="content-grid cols-2">
            ${panel("Eval Cases", `
                <form id="eval-case-form" data-space-id="${currentRouteSpaceId()}" class="inline-form">
                    <div class="field"><label>Name</label><input name="name" required></div>
                    <div class="field"><label>Query</label><textarea name="queryText" required></textarea></div>
                    <div class="field"><label>Expected Answer</label><textarea name="expectedAnswer"></textarea></div>
                    <button class="button" type="submit">创建 Eval Case</button>
                </form>
                <div class="list-stack" style="margin-top:14px;">
                    ${state.admin.evalCases.map((item) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(item.name)}</strong>${badge(item.enabled ? "ENABLED" : "DISABLED", item.enabled ? "ENABLED" : "DISABLED")}</div>
                            <div class="muted">${escapeHtml(item.queryText)}</div>
                        </div>
                    `).join("") || emptyState("暂无 Eval Case", "创建一个案例后再启动 Eval Run。")}
                </div>
            `)}
            ${panel("Eval Run", `
                <form id="eval-run-form" data-space-id="${currentRouteSpaceId()}" class="inline-form">
                    <div class="field"><label>Run Name</label><input name="name" required></div>
                    <button class="button" type="submit">启动 Eval Run</button>
                </form>
                ${state.admin.evalRun ? `
                    <div class="list-item" style="margin-top:14px;">
                        <div class="list-item-header"><strong>${escapeHtml(state.admin.evalRun.name)}</strong>${badge(state.admin.evalRun.status)}</div>
                        <div class="muted">${escapeHtml(state.admin.evalRun.summaryJson || "暂无 summaryJson")}</div>
                    </div>
                ` : ""}
                <div class="list-stack" style="margin-top:14px;">
                    ${state.admin.evalResults.map((result) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>Case ${formatNumber(result.caseId)}</strong>${tag(`latency ${formatNumber(result.latencyMs)}ms`)}</div>
                            <div class="muted">recall@k ${result.recallAtK} / MRR ${result.mrr} / citationCoverage ${result.citationCoverage}</div>
                        </div>
                    `).join("") || emptyState("暂无 Eval Result", "启动一次 Eval Run 后，这里会展示结果。")}
                </div>
            `)}
        </div>
    `;
}

function renderAdminLogsPage() {
    const llmLogs = flattenPageResponse(state.admin.llmLogs);
    const auditLogs = flattenPageResponse(state.admin.auditLogs);
    return `
        <div class="page-header">
            <div>
                <h1>Admin Logs</h1>
                <p class="subtitle">LLMCallLog 查询、AuditLog 查询，以及 RetrievalTrace 按 traceId 查看。</p>
            </div>
        </div>
        <div class="content-grid cols-2">
            ${panel("LLM Call Logs", `
                <div class="table-wrap">
                    <table>
                        <thead><tr><th>ID</th><th>Scene</th><th>Model</th><th>Tokens</th><th>Status</th><th>Time</th></tr></thead>
                        <tbody>
                        ${llmLogs.map((log) => `
                            <tr>
                                <td class="mono">${formatNumber(log.id)}</td>
                                <td>${escapeHtml(log.scene)}</td>
                                <td>${escapeHtml(log.model)}</td>
                                <td>${formatNumber(log.totalTokens)}</td>
                                <td>${badge(log.success ? "SUCCESS" : "FAILED", log.success ? "SUCCESS" : "FAILED")}</td>
                                <td>${formatDate(log.createdAt)}</td>
                            </tr>
                        `).join("") || `<tr><td colspan="6">${emptyState("暂无 LLM 日志", "当前条件没有日志记录。")}</td></tr>`}
                        </tbody>
                    </table>
                </div>
                <form id="retrieval-trace-form" class="inline-form" style="margin-top:14px;">
                    <div class="field"><label>Retrieval Trace ID</label><input name="traceId" required></div>
                    <button class="ghost-button" type="submit">查看 Trace</button>
                </form>
            `)}
            ${panel("Audit Logs", `
                <div class="table-wrap">
                    <table>
                        <thead><tr><th>ID</th><th>Action</th><th>Target</th><th>Operator</th><th>Created</th></tr></thead>
                        <tbody>
                        ${auditLogs.map((log) => `
                            <tr>
                                <td class="mono">${formatNumber(log.id)}</td>
                                <td>${escapeHtml(log.action)}</td>
                                <td>${escapeHtml(log.targetType)} / ${formatNumber(log.targetId)}</td>
                                <td>${formatNumber(log.operatorId)}</td>
                                <td>${formatDate(log.createdAt)}</td>
                            </tr>
                        `).join("") || `<tr><td colspan="5">${emptyState("暂无 AuditLog", "当前条件没有审计记录。")}</td></tr>`}
                        </tbody>
                    </table>
                </div>
                ${state.admin.retrievalTrace ? `<div class="list-item" style="margin-top:14px;"><div class="list-item-header"><strong>Retrieval Trace</strong></div>${renderJson(state.admin.retrievalTrace)}</div>` : ""}
            `)}
        </div>
    `;
}

async function handleLogin(form) {
    const payload = Object.fromEntries(new FormData(form).entries());
    const auth = await api.auth.login(payload);
    saveAuthState(auth);
    state.user = auth.user || null;
    queueToast("登录成功。", "success");
    navigate("/", true);
}

async function handleRegister(form) {
    const payload = Object.fromEntries(new FormData(form).entries());
    const auth = await api.auth.register(payload);
    saveAuthState(auth);
    state.user = auth.user || null;
    queueToast("注册成功，已进入工作台。", "success");
    navigate("/", true);
}

async function handleLogout() {
    const auth = loadAuthState();
    try {
        if (auth?.refreshToken) {
            await api.auth.logout(auth.refreshToken);
        }
    } catch (error) {
        // logout best effort
    }
    clearAuthState();
    clearPrivateState();
    navigate("/login", true);
}

async function handleCreateSpace(form) {
    const payload = Object.fromEntries(new FormData(form).entries());
    await api.spaces.create(payload);
    queueToast("Space 已创建。", "success");
    await renderRoute();
}

async function handleCreateKnowledgeBase(form) {
    const payload = Object.fromEntries(new FormData(form).entries());
    await api.knowledge.create(currentRouteSpaceId(), payload);
    queueToast("Knowledge Base 已创建。", "success");
    await renderRoute();
}

async function handleDocumentUpload(form) {
    const file = form.elements.file.files[0];
    const kbId = state.route.knowledgeBaseId;
    if (!file || !kbId) {
        return;
    }
    const job = {
        knowledgeBaseId: kbId,
        file,
        fileName: file.name,
        stage: "HASHING",
        paused: false,
        cancelled: false,
        progress: 0
    };
    state.knowledge.uploadJobsByKb[kbId] = job;
    paint();

    try {
        job.fileMd5 = await md5File(file);
        job.stage = "INIT";
        paint();
        const init = await api.knowledge.initUpload(kbId, {
            fileMd5: job.fileMd5,
            fileName: file.name,
            contentType: file.type || "application/octet-stream",
            totalSize: file.size,
            chunkSize: CHUNK_SIZE,
            totalChunks: Math.ceil(file.size / CHUNK_SIZE)
        });
        Object.assign(job, init, { stage: init.instantUpload ? "MERGING" : "UPLOADING" });
        paint();
        if (!init.instantUpload) {
            await continueUploadJob(job);
        }
        if (job.paused || job.cancelled) {
            paint();
            return;
        }
        const merge = await api.knowledge.mergeUpload(job.uploadId);
        job.stage = "PROCESSING";
        job.status = merge.status;
        job.documentId = merge.documentId;
        if (merge.taskId) {
            startTaskPolling(merge.taskId, (task) => {
                job.task = task;
                if (FINAL_TASK_STATUSES.has(task.taskStatus)) {
                    job.stage = task.taskStatus;
                }
                paint();
            });
        }
        queueToast("文件上传完成，后端正在异步处理。", "success");
        await renderRoute();
    } catch (error) {
        job.stage = "FAILED";
        job.error = error.message;
        paint();
        throw error;
    }
}

async function continueUploadJob(job) {
    const uploaded = new Set(job.uploadedChunks || []);
    for (let chunkIndex = 0; chunkIndex < job.totalChunks; chunkIndex += 1) {
        if (job.cancelled) {
            return;
        }
        if (job.paused) {
            job.stage = "PAUSED";
            paint();
            return;
        }
        if (uploaded.has(chunkIndex)) {
            continue;
        }
        const start = chunkIndex * CHUNK_SIZE;
        const end = Math.min(job.file.size, start + CHUNK_SIZE);
        const chunk = job.file.slice(start, end);
        const response = await api.knowledge.uploadChunk(job.uploadId, chunkIndex, chunk, job.file.name);
        job.uploadedChunks = response.uploadedChunks || [];
        job.progress = response.progress || 0;
        job.stage = "UPLOADING";
        paint();
    }
}

async function pauseUpload(kbId) {
    const job = state.knowledge.uploadJobsByKb[kbId];
    if (!job) {
        return;
    }
    job.paused = true;
    job.stage = "PAUSED";
    paint();
}

async function resumeUpload(kbId) {
    const job = state.knowledge.uploadJobsByKb[kbId];
    if (!job) {
        return;
    }
    job.paused = false;
    job.stage = "UPLOADING";
    paint();
    await continueUploadJob(job);
    if (job.paused || job.cancelled) {
        paint();
        return;
    }
    const merge = await api.knowledge.mergeUpload(job.uploadId);
    if (merge.taskId) {
        startTaskPolling(merge.taskId, (task) => {
            job.task = task;
            paint();
        });
    }
    queueToast("上传已继续。", "success");
    await renderRoute();
}

async function cancelUpload(kbId) {
    const job = state.knowledge.uploadJobsByKb[kbId];
    if (!job) {
        return;
    }
    job.cancelled = true;
    await api.knowledge.cancelUpload(job.uploadId);
    queueToast("上传已取消。", "success");
    delete state.knowledge.uploadJobsByKb[kbId];
    paint();
}

async function handleKnowledgeSearch(form) {
    const payload = Object.fromEntries(new FormData(form).entries());
    const kbId = Number(form.dataset.kbId);
    state.knowledge.searchResults[kbId] = await api.knowledge.search(kbId, payload.keyword);
    paint();
}

async function handleCreateSession(form) {
    const values = Object.fromEntries(new FormData(form).entries());
    const scopeIds = String(values.scopeIds || "")
        .split(",")
        .map((value) => value.trim())
        .filter(Boolean)
        .map(Number);
    if (values.scopeType === "KNOWLEDGE_BASE" && !scopeIds.length) {
        throw new ApiError("Knowledge Base 会话必须填写至少一个知识库 ID。", { code: "KNOWLEDGE_BASE_SCOPE_REQUIRED" });
    }
    const payload = {
        spaceId: currentRouteSpaceId(),
        sessionType: "TEAM_CHAT",
        sessionKind: values.sessionKind,
        scopeType: values.scopeType,
        scopeIds: values.scopeType === "SPACE" ? [currentRouteSpaceId()] : scopeIds,
        title: values.title
    };
    const session = await api.chat.createSession(payload);
    state.chat.activeSessionId = session.id;
    ensureLocalDraft(session.id);
    queueToast("会话已创建。", "success");
    await renderRoute();
}

async function ensureChatSocket() {
    if (state.websocket.socket && state.websocket.socket.readyState === WebSocket.OPEN) {
        return;
    }
    if (state.websocket.connectPromise) {
        return state.websocket.connectPromise;
    }

    state.websocket.status = "connecting";
    state.websocket.manualClose = false;
    paint();
    state.websocket.connectPromise = api.chat.wsTicket()
        .then((ticket) => new Promise((resolve, reject) => {
            const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
            const url = `${protocol}//${window.location.host}${ticket.webSocketUrl}`;
            const socket = new WebSocket(url);
            socket.addEventListener("open", () => {
                state.websocket.socket = socket;
                state.websocket.status = "connected";
                state.websocket.connectPromise = null;
                paint();
                resolve();
            });
            socket.addEventListener("message", async (event) => {
                try {
                    const envelope = JSON.parse(event.data);
                    await handleSocketEvent(envelope);
                } catch (error) {
                    console.error(error);
                }
            });
            socket.addEventListener("close", () => {
                state.websocket.socket = null;
                state.websocket.status = "disconnected";
                state.websocket.connectPromise = null;
                paint();
                if (!state.websocket.manualClose && (state.route?.name === "team-chat" || state.route?.name === "workbench-chat")) {
                    scheduleSocketReconnect();
                }
            });
            socket.addEventListener("error", () => {
                state.websocket.status = "failed";
                paint();
                reject(new ApiError("WebSocket 连接失败。", { code: "WS_CONNECT_FAILED" }));
            });
        }));
    return state.websocket.connectPromise;
}

function closeSocket() {
    if (state.websocket.reconnectTimer) {
        window.clearTimeout(state.websocket.reconnectTimer);
        state.websocket.reconnectTimer = null;
    }
    if (state.websocket.socket) {
        state.websocket.manualClose = true;
        state.websocket.socket.close();
        state.websocket.socket = null;
    }
    state.websocket.status = "disconnected";
    state.websocket.connectPromise = null;
}

function scheduleSocketReconnect() {
    if (state.websocket.reconnectTimer) {
        return;
    }
    state.websocket.reconnectTimer = window.setTimeout(async () => {
        state.websocket.reconnectTimer = null;
        try {
            await ensureChatSocket();
            resumeActiveSession();
        } catch (error) {
            scheduleSocketReconnect();
        }
    }, 2000);
}

function resumeActiveSession() {
    const session = activeSession();
    if (!session || !state.websocket.socket || state.websocket.socket.readyState !== WebSocket.OPEN) {
        return;
    }
    state.websocket.socket.send(JSON.stringify({
        event: "chat.resume",
        sessionId: session.id,
        ack: state.websocket.lastAckBySession[session.id] || 0
    }));
}

async function handleSocketEvent(envelope) {
    const sessionId = Number(envelope.sessionId);
    if (envelope.seq) {
        state.websocket.lastAckBySession[sessionId] = envelope.seq;
    }

    if (envelope.event === "session.state.updated") {
        const payload = envelope.payload || {};
        state.chat.sessions = state.chat.sessions.map((session) => Number(session.id) === sessionId
            ? { ...session, runtimeStatus: payload.runtimeStatus, draftStatus: payload.draftStatus || session.draftStatus }
            : session);
        paint();
        return;
    }

    const local = state.chat.localsBySession[sessionId] || {};
    if (envelope.event === "chat.delta") {
        const payload = envelope.payload || {};
        state.chat.localsBySession[sessionId] = {
            ...local,
            streamId: envelope.streamId,
            assistantMessage: {
                ...(local.assistantMessage || { role: "ASSISTANT", status: "RUNNING" }),
                role: "ASSISTANT",
                content: payload.partialContent || payload.delta || "",
                status: "RUNNING"
            }
        };
        paint();
        return;
    }

    if (envelope.event === "chat.completed") {
        const payload = envelope.payload || {};
        if (envelope.messageId && payload.citations) {
            state.chat.citationsByMessage[envelope.messageId] = payload.citations;
        }
        if (state.chat.localsBySession[sessionId]?.assistantMessage) {
            state.chat.localsBySession[sessionId].assistantMessage.status = "SUCCESS";
            state.chat.localsBySession[sessionId].assistantMessage.content = payload.answer || state.chat.localsBySession[sessionId].assistantMessage.content;
        }
        await loadChatSessionData(sessionId);
        delete state.chat.localsBySession[sessionId];
        paint();
        return;
    }

    if (envelope.event === "chat.failed") {
        state.chat.localsBySession[sessionId] = {
            ...local,
            assistantMessage: {
                ...(local.assistantMessage || { role: "ASSISTANT" }),
                role: "ASSISTANT",
                content: local.assistantMessage?.content || envelope.error?.message || "",
                status: "FAILED"
            }
        };
        queueToast(envelope.error?.message || "流式生成失败。", "error");
        paint();
        return;
    }

    if (envelope.event === "chat.stopped") {
        state.chat.localsBySession[sessionId] = {
            ...local,
            assistantMessage: {
                ...(local.assistantMessage || { role: "ASSISTANT" }),
                role: "ASSISTANT",
                status: "STOPPED"
            }
        };
        paint();
        return;
    }

    if (envelope.event === "chat.restored") {
        const payload = envelope.payload || {};
        if (payload.partialContent) {
            state.chat.localsBySession[sessionId] = {
                ...local,
                streamId: envelope.streamId,
                assistantMessage: {
                    ...(local.assistantMessage || { role: "ASSISTANT" }),
                    role: "ASSISTANT",
                    content: payload.partialContent,
                    status: payload.runtimeStatus || "RUNNING"
                }
            };
        }
        paint();
    }
}

async function handleSendChatMessage(form) {
    const session = activeSession();
    if (!session) {
        throw new ApiError("请先选择一个会话。", { code: "CHAT_SESSION_REQUIRED" });
    }
    const content = String(new FormData(form).get("content") || "").trim();
    if (!content) {
        return;
    }
    await ensureChatSocket();
    const requestId = `req-${Date.now()}`;
    const streamId = `stream-${Date.now()}`;
    state.chat.localsBySession[session.id] = {
        requestId,
        streamId,
        userMessage: { role: "USER", content, status: "SUCCESS" },
        assistantMessage: { role: "ASSISTANT", content: "", status: "RUNNING" }
    };
    state.chat.draftsBySession[session.id] = "";
    paint();
    state.websocket.socket.send(JSON.stringify({
        event: "chat.message",
        requestId,
        streamId,
        sessionId: session.id,
        payload: { content }
    }));
}

function stopChat(sessionId) {
    const local = state.chat.localsBySession[sessionId];
    if (!state.websocket.socket || !local?.streamId) {
        return;
    }
    state.websocket.socket.send(JSON.stringify({
        event: "chat.stop",
        requestId: local.requestId || `stop-${Date.now()}`,
        streamId: local.streamId,
        sessionId
    }));
}

async function startTaskPolling(taskId, onUpdate) {
    if (taskPollers.has(taskId)) {
        return;
    }
    const poll = async () => {
        try {
            const task = await api.tasks.get(taskId);
            onUpdate(task);
            if (!FINAL_TASK_STATUSES.has(task.taskStatus)) {
                taskPollers.set(taskId, window.setTimeout(poll, 2500));
            } else {
                taskPollers.delete(taskId);
            }
        } catch (error) {
            taskPollers.delete(taskId);
        }
    };
    taskPollers.set(taskId, window.setTimeout(poll, 100));
}

async function loadAdminTaskDetail(taskId) {
    state.admin.selectedTaskId = Number(taskId);
    const [task, events] = await Promise.all([
        api.admin.task(taskId),
        api.admin.taskEvents(taskId, { page: 1, pageSize: 50, sort: "createdAt,asc" })
    ]);
    state.admin.taskDetail = task;
    state.admin.taskEvents = events;
    state.ui.drawer = {
        title: `Task ${formatNumber(task.id)}`,
        html: renderAdminTaskDetailDrawer()
    };
}

async function handleAdminTaskFilter(form) {
    const values = Object.fromEntries(new FormData(form).entries());
    state.admin.taskFilters.taskStatus = String(values.taskStatus || "").trim();
    await renderRoute();
}

async function handleCreateProject(form) {
    const payload = Object.fromEntries(new FormData(form).entries());
    await api.personal.createProject(payload);
    queueToast("ResearchProject 已创建。", "success");
    await renderRoute();
}

async function handleSourceFile(form) {
    const file = form.elements.file.files[0];
    const projectId = Number(form.dataset.projectId);
    if (!file) {
        return;
    }
    await api.personal.uploadSource(projectId, file, form.elements.title.value);
    queueToast("文件 Source 已提交导入。", "success");
    await renderRoute();
}

async function handleSourceUrl(form) {
    const projectId = Number(form.dataset.projectId);
    await api.personal.addUrlSource(projectId, Object.fromEntries(new FormData(form).entries()));
    queueToast("URL Source 已创建。", "success");
    await renderRoute();
}

async function handleSourceText(form) {
    const projectId = Number(form.dataset.projectId);
    await api.personal.addTextSource(projectId, Object.fromEntries(new FormData(form).entries()));
    queueToast("文本 Source 已创建。", "success");
    await renderRoute();
}

function findSkill(skillId) {
    return state.studio.skills.find((skill) => skill.id === skillId) || null;
}

async function createArtifactTask(spaceId, projectId, skillId, topic, methodologyCardId = null) {
    const skill = findSkill(skillId);
    if (!skill) {
        throw new ApiError("未找到对应的 Studio Skill。", { code: "STUDIO_SKILL_NOT_FOUND" });
    }
    const task = await api.studio.createTask({
        spaceId,
        researchProjectId: projectId,
        taskType: "ARTIFACT_GENERATE",
        sourceScopeType: skill.sourceScopeType,
        sourceIds: [projectId],
        params: {
            artifactType: skill.artifactType,
            topic,
            methodologyCardId: methodologyCardId || null
        }
    });
    state.studio.lastTask = task;
    if (task.taskId) {
        startTaskPolling(task.taskId, (polledTask) => {
            state.studio.lastTask = { ...task, taskStatus: polledTask.taskStatus, task: polledTask };
            paint();
        });
    }
    queueToast("Artifact 生成任务已启动。", "success");
}

async function handleProjectGenerate(form) {
    const values = Object.fromEntries(new FormData(form).entries());
    const projectId = Number(form.dataset.projectId);
    const spaceId = Number(form.dataset.spaceId) || resolveProjectSpaceIdById(projectId);
    await createArtifactTask(spaceId, projectId, values.skillId, values.topic, values.methodologyCardId || null);
    await renderRoute();
}

async function handleStudioTask(form) {
    const values = Object.fromEntries(new FormData(form).entries());
    const projectId = Number(values.projectId);
    const spaceId = resolveProjectSpaceIdById(projectId) || Number(form.dataset.spaceId);
    await createArtifactTask(spaceId, projectId, values.skillId, values.topic);
    await renderRoute();
}

async function handleArtifactSave(form) {
    const artifactId = Number(form.dataset.artifactId);
    const payload = Object.fromEntries(new FormData(form).entries());
    await api.artifacts.update(artifactId, payload);
    queueToast("Artifact 已保存。", "success");
    await renderRoute();
}

async function handleArtifactExport(artifactId) {
    const exported = await api.artifacts.export(artifactId, "markdown");
    downloadTextFile(exported.fileName || `artifact-${artifactId}.md`, exported.content || "");
    queueToast("Markdown 已导出。", "success");
}

async function handleArtifactRegenerate(artifactId) {
    const result = await api.artifacts.regenerate(artifactId, {});
    state.studio.lastTask = result;
    if (result.taskId) {
        startTaskPolling(result.taskId, (task) => {
            state.studio.lastTask = { ...result, taskStatus: task.taskStatus, task };
            paint();
        });
    }
    queueToast("Artifact 重新生成任务已启动。", "success");
    await renderRoute();
}

async function handleDistillArtifact(form) {
    const artifactId = Number(form.dataset.artifactId);
    const payload = Object.fromEntries(new FormData(form).entries());
    const preview = await api.artifacts.distill(artifactId, {
        cardType: payload.cardType,
        confirm: false
    });
    state.artifacts.distillPreview = preview;
    setDrawer("Distill Preview", `
        <div class="list-stack">
            <div class="list-item"><strong>${escapeHtml(preview.title || "Synthesis Preview")}</strong><div class="muted">${escapeHtml(preview.summary || "")}</div></div>
            ${preview.insights?.length ? `<div class="list-item"><strong>Insights</strong><ul>${preview.insights.map((item) => `<li>${escapeHtml(item)}</li>`).join("")}</ul></div>` : ""}
            <button class="button" type="button" data-action="confirm-distill" data-artifact-id="${artifactId}" data-card-type="${escapeHtml(payload.cardType)}" data-proposal-id="${preview.proposalId || ""}">确认写入</button>
        </div>
    `);
}

async function confirmDistillArtifact(artifactId, cardType, proposalId) {
    const result = await api.artifacts.distill(artifactId, {
        cardType,
        proposalId,
        confirm: true
    });
    queueToast("Artifact 已沉淀到个人 Wiki。", "success");
    setDrawer("Distill Result", `
        <div class="list-item">
            <div class="list-item-header"><strong>${escapeHtml(result.title || "Synthesis Card")}</strong>${badge(result.confirmed ? "SUCCESS" : "PENDING", result.confirmed ? "CONFIRMED" : "PREVIEW")}</div>
            <div class="muted">${escapeHtml(result.summary || "")}</div>
        </div>
    `);
    await renderRoute();
}

async function handlePublishArtifactToWiki(form) {
    const artifactId = Number(form.dataset.artifactId);
    const values = Object.fromEntries(new FormData(form).entries());
    const artifactSpaceId = Number(values.spaceId) || resolveArtifactSpaceIdById(artifactId);
    await api.wiki.publishArtifact(artifactId, {
        spaceId: artifactSpaceId,
        title: values.title
    });
    queueToast("Artifact 已发布为 Wiki 草稿。", "success");
    navigate(routeLink("wiki", artifactSpaceId));
}

async function handleWikiCreate(form) {
    const payload = Object.fromEntries(new FormData(form).entries());
    await api.wiki.create(Number(form.dataset.spaceId), payload);
    queueToast("Wiki 草稿已创建。", "success");
    await renderRoute();
}

async function handleWikiUpdate(form) {
    const pageId = Number(form.dataset.pageId);
    await api.wiki.update(pageId, Object.fromEntries(new FormData(form).entries()));
    queueToast("Wiki 草稿已保存。", "success");
    await renderRoute();
}

async function publishWikiPage(pageId) {
    await api.wiki.publish(pageId, {});
    queueToast("Wiki 已发布。", "success");
    await renderRoute();
}

async function handleWikiSearch(form) {
    const keyword = String(new FormData(form).get("keyword") || "");
    state.wiki.searchResult = await api.wiki.search(currentRouteSpaceId(), keyword);
    paint();
}

async function saveSpaceMemory(form) {
    const payload = Object.fromEntries(new FormData(form).entries());
    payload.pin = false;
    await api.memory.upsertSpace(Number(form.dataset.spaceId), payload);
    queueToast("Space Memory 已保存。", "success");
    await renderRoute();
}

async function saveUserMemory(form) {
    const payload = Object.fromEntries(new FormData(form).entries());
    payload.pin = false;
    await api.memory.upsertUser(payload);
    queueToast("User Memory 已保存。", "success");
    await renderRoute();
}

async function handleEvalCase(form) {
    const payload = Object.fromEntries(new FormData(form).entries());
    payload.enabled = true;
    await api.admin.createEvalCase(Number(form.dataset.spaceId), payload);
    queueToast("Eval Case 已创建。", "success");
    await renderRoute();
}

async function handleEvalRun(form) {
    const payload = Object.fromEntries(new FormData(form).entries());
    const run = await api.admin.startEvalRun(Number(form.dataset.spaceId), payload);
    state.admin.selectedEvalRunId = run.id;
    queueToast("Eval Run 已启动。", "success");
    await renderRoute();
}

async function handleRetrievalTrace(form) {
    const traceId = Number(new FormData(form).get("traceId"));
    state.admin.retrievalTrace = await api.admin.retrievalTrace(traceId);
    paint();
}

async function showHealthDetail(component) {
    const detail = await api.admin.healthComponent(component);
    setDrawer(`Health: ${component}`, renderJson(detail));
}

function openCitation(citationKey) {
    const citation = state.registry.citations[citationKey];
    if (!citation) {
        return;
    }
    setDrawer("Citation Evidence", `
        <div class="list-stack">
            <div class="list-item">
                <div class="list-item-header"><strong>${escapeHtml(citation.title || citation.sourceType || "Citation")}</strong>${citation.pageNo ? tag(`p.${citation.pageNo}`) : ""}</div>
                <div class="muted">${escapeHtml(citation.locationInfo || "无位置描述")}</div>
            </div>
            <div class="list-item">
                <strong>Quote</strong>
                <div class="muted">${escapeHtml(citation.quoteText || "")}</div>
            </div>
            ${citation.startOffset !== undefined ? `<div class="list-item"><strong>Offset</strong><div class="muted">${formatNumber(citation.startOffset)} - ${formatNumber(citation.endOffset)}</div></div>` : ""}
        </div>
    `);
}

async function submitFeedback(messageId, rating) {
    await api.chat.feedback(messageId, { rating, reason: rating === "UP" ? "HELPFUL" : "MISSING_CONTEXT" });
    queueToast("反馈已提交。", "success");
}

document.addEventListener("click", async (event) => {
    const target = event.target.closest("[data-nav],[data-action]");
    if (!target) {
        return;
    }
    event.preventDefault();
    const action = target.dataset.action;
    try {
        if (target.dataset.nav) {
            navigate(target.dataset.nav);
            return;
        }
        switch (action) {
            case "logout":
                await handleLogout();
                break;
            case "close-drawer":
                closeDrawer();
                break;
            case "preview-space":
                state.ui.selectedSpacePreviewId = Number(target.dataset.spaceId);
                await loadSpaceSelectionPage();
                paint();
                break;
            case "enter-space":
                navigate(routeLink("chat", Number(target.dataset.spaceId)));
                break;
            case "open-kb":
                navigate(`/spaces/${currentRouteSpaceId()}/team/knowledge-bases/${target.dataset.kbId}`);
                break;
            case "select-session":
                state.chat.activeSessionId = Number(target.dataset.sessionId);
                await loadChatSessionData(state.chat.activeSessionId);
                paint();
                resumeActiveSession();
                break;
            case "reconnect-chat":
                closeSocket();
                await ensureChatSocket();
                resumeActiveSession();
                break;
            case "stop-chat":
                stopChat(Number(target.dataset.sessionId));
                break;
            case "open-citation":
                openCitation(target.dataset.citationKey);
                break;
            case "feedback-up":
                await submitFeedback(Number(target.dataset.messageId), "UP");
                break;
            case "feedback-down":
                await submitFeedback(Number(target.dataset.messageId), "DOWN");
                break;
            case "load-message-citations":
                state.chat.citationsByMessage[Number(target.dataset.messageId)] = await api.chat.citations(Number(target.dataset.messageId));
                paint();
                break;
            case "convert-draft":
                await api.chat.convertDraft(Number(target.dataset.sessionId));
                queueToast("草稿已转为正式会话。", "success");
                await renderRoute();
                break;
            case "discard-draft":
                await api.chat.discardDraft(Number(target.dataset.sessionId));
                queueToast("草稿已丢弃。", "success");
                await renderRoute();
                break;
            case "pause-upload":
                await pauseUpload(Number(target.dataset.kbId));
                break;
            case "resume-upload":
                await resumeUpload(Number(target.dataset.kbId));
                break;
            case "cancel-upload":
                await cancelUpload(Number(target.dataset.kbId));
                break;
            case "open-project":
                navigate(routeLink("project", Number(target.dataset.spaceId) || resolveProjectSpaceIdById(Number(target.dataset.projectId)), Number(target.dataset.projectId)));
                break;
            case "source-import":
                await api.personal.triggerImport(Number(target.dataset.sourceId));
                queueToast("Source 重新导入任务已触发。", "success");
                await renderRoute();
                break;
            case "source-compile":
                await api.personal.compileSource(Number(target.dataset.sourceId));
                queueToast("Wiki Compiler 已触发。", "success");
                await renderRoute();
                break;
            case "open-artifact":
                navigate(routeLink("artifact-detail", Number(target.dataset.spaceId) || resolveArtifactSpaceIdById(Number(target.dataset.artifactId)), Number(target.dataset.artifactId)));
                break;
            case "export-artifact":
                await handleArtifactExport(Number(target.dataset.artifactId));
                break;
            case "regenerate-artifact":
                await handleArtifactRegenerate(Number(target.dataset.artifactId));
                break;
            case "confirm-distill":
                await confirmDistillArtifact(
                    Number(target.dataset.artifactId),
                    target.dataset.cardType,
                    Number(target.dataset.proposalId)
                );
                break;
            case "select-wiki-page":
                state.wiki.selectedPageId = Number(target.dataset.pageId);
                await loadWikiPage(currentRouteSpaceId());
                paint();
                break;
            case "publish-wiki-page":
                await publishWikiPage(Number(target.dataset.pageId));
                break;
            case "delete-space-memory-item":
                await api.memory.deleteSpaceItem(currentRouteSpaceId(), Number(target.dataset.itemId));
                queueToast("Space Memory 已删除。", "success");
                await renderRoute();
                break;
            case "delete-user-memory-item":
                await api.memory.deleteUserItem(Number(target.dataset.itemId));
                queueToast("User Memory 已删除。", "success");
                await renderRoute();
                break;
            case "disable-user-memory":
                await api.memory.disableWriteback();
                queueToast("User Memory 写入已关闭。", "success");
                await renderRoute();
                break;
            case "enable-user-memory":
                await api.memory.enableWriteback();
                queueToast("User Memory 写入已开启。", "success");
                await renderRoute();
                break;
            case "admin-retry-task":
                await api.admin.retryTask(Number(target.dataset.taskId));
                queueToast("任务已提交重试。", "success");
                await renderRoute();
                break;
            case "open-admin-task":
                await loadAdminTaskDetail(Number(target.dataset.taskId));
                paint();
                break;
            case "admin-cancel-task":
                await api.admin.cancelTask(Number(target.dataset.taskId));
                queueToast("任务已取消。", "success");
                await renderRoute();
                break;
            case "refresh-page":
                await renderRoute();
                break;
            case "open-health-detail":
                await showHealthDetail(target.dataset.component);
                break;
            default:
                break;
        }
    } catch (error) {
        queueToast(error.message || "操作失败。", "error");
    }
});

document.addEventListener("submit", async (event) => {
    const form = event.target;
    if (!(form instanceof HTMLFormElement)) {
        return;
    }
    event.preventDefault();
    try {
        switch (form.id) {
            case "login-form":
                await handleLogin(form);
                break;
            case "register-form":
                await handleRegister(form);
                break;
            case "create-space-form":
                await handleCreateSpace(form);
                break;
            case "create-kb-form":
                await handleCreateKnowledgeBase(form);
                break;
            case "upload-document-form":
                await handleDocumentUpload(form);
                break;
            case "kb-search-form":
                await handleKnowledgeSearch(form);
                break;
            case "create-session-form":
                await handleCreateSession(form);
                break;
            case "chat-message-form":
                await handleSendChatMessage(form);
                break;
            case "create-project-form":
                await handleCreateProject(form);
                break;
            case "source-file-form":
                await handleSourceFile(form);
                break;
            case "source-url-form":
                await handleSourceUrl(form);
                break;
            case "source-text-form":
                await handleSourceText(form);
                break;
            case "project-generate-form":
                await handleProjectGenerate(form);
                break;
            case "studio-task-form":
                await handleStudioTask(form);
                break;
            case "artifact-edit-form":
                await handleArtifactSave(form);
                break;
            case "distill-artifact-form":
                await handleDistillArtifact(form);
                break;
            case "publish-artifact-wiki-form":
                await handlePublishArtifactToWiki(form);
                break;
            case "wiki-create-form":
                await handleWikiCreate(form);
                break;
            case "wiki-edit-form":
                await handleWikiUpdate(form);
                break;
            case "wiki-search-form":
                await handleWikiSearch(form);
                break;
            case "space-memory-form":
                await saveSpaceMemory(form);
                break;
            case "user-memory-form":
                await saveUserMemory(form);
                break;
            case "eval-case-form":
                await handleEvalCase(form);
                break;
            case "eval-run-form":
                await handleEvalRun(form);
                break;
            case "admin-task-filter-form":
                await handleAdminTaskFilter(form);
                break;
            case "retrieval-trace-form":
                await handleRetrievalTrace(form);
                break;
            default:
                break;
        }
    } catch (error) {
        queueToast(error.message || "提交失败。", "error");
    }
});

document.addEventListener("input", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLTextAreaElement || target instanceof HTMLInputElement)) {
        return;
    }
    const draftSessionId = target.dataset.chatDraft;
    if (draftSessionId) {
        state.chat.draftsBySession[draftSessionId] = target.value;
    }
});

document.addEventListener("change", async (event) => {
    const target = event.target;
    if (!(target instanceof HTMLSelectElement)) {
        return;
    }
    try {
        if (target.id === "space-switcher") {
            const spaceId = Number(target.value);
            if (spaceId) {
                const route = state.route;
                if (route?.name.startsWith("admin")) {
                    setCurrentSpace(spaceId);
                    await renderRoute();
                    return;
                }
                navigate(routeLink("chat", spaceId));
            }
        }
        if (target.id === "memory-session-selector") {
            state.memory.selectedSessionId = Number(target.value);
            await loadMemoryPage(currentRouteSpaceId());
            paint();
        }
        if (target.id === "synthesis-project-selector") {
            state.personal.selectedSynthesisProjectId = Number(target.value);
            await loadMemoryPage(currentRouteSpaceId());
            paint();
        }
    } catch (error) {
        queueToast(error.message || "切换失败。", "error");
    }
});

window.addEventListener("popstate", () => {
    renderRoute();
});

window.addEventListener("noteweave:auth-cleared", () => {
    clearPrivateState();
});

renderRoute();

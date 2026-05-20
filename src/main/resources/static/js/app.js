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
        ACTIVE: "启用",
        ARCHIVED: "已归档",
        READY: "就绪",
        GENERATING: "生成中",
        FAILED: "失败",
        SUCCESS: "成功",
        RUNNING: "运行中",
        PENDING: "等待中",
        CANCELLED: "已取消",
        TIMEOUT: "已超时",
        STOPPED: "已停止",
        IDLE: "空闲",
        DRAFT_ACTIVE: "草稿",
        CONVERTED: "已转换",
        DISCARDED: "已丢弃",
        IMPORTED: "已导入",
        COMPLETED: "已完成",
        PUBLISHED: "已发布",
        ENABLED: "已启用",
        DISABLED: "已禁用",
        HASHING: "计算校验中",
        INIT: "初始化中",
        UPLOADING: "上传中",
        MERGING: "合并中",
        PROCESSING: "处理中",
        INDEXED: "已索引",
        CONNECTED: "已连接",
        CONNECTING: "连接中",
        DISCONNECTED: "已断开"
    };
    return labels[raw] || raw || "未知";
}

function humanizeSystemRole(value) {
    const raw = String(value ?? "").toUpperCase();
    return {
        ADMIN: "管理员",
        USER: "普通用户"
    }[raw] || raw || "未知角色";
}

function humanizeSpaceRole(value) {
    const raw = String(value ?? "").toUpperCase();
    return {
        OWNER: "所有者",
        EDITOR: "编辑者",
        VIEWER: "查看者"
    }[raw] || raw || "未知角色";
}

function humanizeSessionKind(value) {
    const raw = String(value ?? "").toUpperCase();
    return {
        FORMAL: "正式会话",
        DRAFT: "草稿会话"
    }[raw] || raw || "未分类会话";
}

function humanizeScopeType(value) {
    const raw = String(value ?? "").toUpperCase();
    return {
        SPACE: "当前空间",
        KNOWLEDGE_BASE: "指定知识库"
    }[raw] || raw || "未分类范围";
}

function humanizeMemoryType(value) {
    const raw = String(value ?? "").toUpperCase();
    return {
        SPACE_CONTEXT: "空间上下文",
        SESSION_INSIGHT: "会话洞察",
        USER_PREFERENCE: "用户偏好"
    }[raw] || raw || "未分类记忆";
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

function renderGuideCards(items) {
    if (!items?.length) {
        return "";
    }
    return `
        <section class="info-strip">
            ${items.map((item) => `
                <article class="info-card">
                    ${item.eyebrow ? `<span>${escapeHtml(item.eyebrow)}</span>` : ""}
                    <strong>${escapeHtml(item.title)}</strong>
                    <p>${escapeHtml(item.description)}</p>
                </article>
            `).join("")}
        </section>
    `;
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

function canDistillArtifactToPersonalWiki(artifact = state.artifacts.detail) {
    return Boolean(
        artifact?.researchProjectId &&
        personalSpaceId() &&
        Number(artifact.spaceId) === Number(personalSpaceId())
    );
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

function routeDescriptor(route = state.route) {
    const descriptors = {
        spaces: {
            eyebrow: "工作区",
            title: "空间总览",
            detail: "在同一个工作台里切换团队空间和个人空间。"
        },
        "knowledge-list": {
            eyebrow: "团队知识",
            title: "知识库管理",
            detail: "集中查看导入、索引和检索准备状态。"
        },
        "knowledge-detail": {
            eyebrow: "团队知识",
            title: "知识库详情",
            detail: "查看文档状态、上传进度和检索调试信息。"
        },
        "team-chat": {
            eyebrow: "聊天",
            title: "团队检索问答",
            detail: "基于共享空间上下文提问，并获得带依据的回答。"
        },
        "workbench-chat": {
            eyebrow: "工作台",
            title: "证据问答",
            detail: "边看流式回答边检查引用，并沉淀有价值的对话。"
        },
        "workbench-studio": {
            eyebrow: "工作室",
            title: "成果生成",
            detail: "结合研究上下文和空间记忆发起结构化生成任务。"
        },
        projects: {
            eyebrow: "个人研究",
            title: "项目列表",
            detail: "查看研究项目的资料导入、编译和沉淀情况。"
        },
        "project-detail": {
            eyebrow: "个人研究",
            title: "研究概览",
            detail: "从单个项目视角查看资料覆盖、卡片深度和成果产出。"
        },
        "project-sources": {
            eyebrow: "个人研究",
            title: "资料导入",
            detail: "添加文件、链接和文本资料，并跟踪导入与编译状态。"
        },
        "project-cards": {
            eyebrow: "个人研究",
            title: "卡片库",
            detail: "查看文章卡、概念卡和综合卡，并保留证据追溯能力。"
        },
        "project-generate": {
            eyebrow: "个人研究",
            title: "基于研究生成",
            detail: "选择方法卡和上下文，生成可继续打磨的成果。"
        },
        artifacts: {
            eyebrow: "成果",
            title: "成果库",
            detail: "浏览已生成内容，并继续编辑值得发布或沉淀的成果。"
        },
        "artifact-detail": {
            eyebrow: "成果",
            title: "阅读与编辑",
            detail: "在舒适阅读中继续修改，并保留回到源证据的关联。"
        },
        wiki: {
            eyebrow: "Wiki",
            title: "团队知识页",
            detail: "沉淀稳定团队知识，同时保留回到原始资料的路径。"
        },
        memory: {
            eyebrow: "记忆",
            title: "长期上下文",
            detail: "管理会影响后续回答的用户、会话和空间层记忆。"
        },
        "admin-tasks": {
            eyebrow: "管理后台",
            title: "任务控制",
            detail: "查看队列、失败原因和重试路径。"
        },
        "admin-health": {
            eyebrow: "管理后台",
            title: "系统健康",
            detail: "查看组件状态、延迟和最近检查结果。"
        },
        "admin-evaluation": {
            eyebrow: "管理后台",
            title: "评测运行",
            detail: "查看检索质量和引用覆盖率等评测记录。"
        },
        "admin-logs": {
            eyebrow: "管理后台",
            title: "运行日志",
            detail: "集中查看 LLM 调用、审计记录和检索链路。"
        }
    };

    return descriptors[route?.name] || {
        eyebrow: "工作区",
        title: "NoteWeave",
        detail: "把团队知识、个人研究和成果沉淀放进同一个工作台。"
    };
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

    if (!state.user?.systemRole || !state.user?.email) {
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
    if (canDistillArtifactToPersonalWiki(state.artifacts.detail)) {
        try {
            state.artifacts.relations = await api.artifacts.relations(artifactId);
        } catch (error) {
            if (error instanceof ApiError && [400, 403, 404].includes(error.status)) {
                state.artifacts.relations = [];
            } else {
                throw error;
            }
        }
    } else {
        state.artifacts.relations = [];
    }
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
        throw new ApiError("请先进入一个空间。", { code: "SPACE_REQUIRED" });
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
    const capabilityItems = isLogin ? [
        { title: "知识整理", description: "团队知识库与个人资料统一管理。" },
        { title: "研究协作", description: "围绕空间、对话和项目持续推进。" },
        { title: "成果沉淀", description: "把结果整理成成果与 Wiki 页面。" }
    ] : [
        { title: "建立空间", description: "先创建空间，再组织研究。" },
        { title: "导入资料", description: "上传知识、建立项目、开始提问。" },
        { title: "继续发布", description: "结果保留下来，便于后续复用。" }
    ];
    return `
        <div class="public-layout">
            <section class="auth-showcase">
                <div class="auth-brand-lockup">
                    <div class="auth-brand-mark">NW</div>
                <div class="auth-brand-copy">
                    <strong>NoteWeave</strong>
                    <span>面向知识整理与研究协作的 AI 工作台</span>
                </div>
            </div>
            <div class="auth-showcase-copy">
                <span class="context-kicker">${isLogin ? "知识整理、研究协作、成果沉淀" : "创建你的 NoteWeave 账号"}</span>
                    <h2>${isLogin ? "NoteWeave 是一个用于知识整理、研究协作和成果沉淀的工作台。" : "注册后即可开始使用 NoteWeave 进行知识整理、研究协作和成果沉淀。"}</h2>
                    <p>${isLogin ? "把知识库、研究项目、聊天和成果放在同一个界面里，方便整理和复用。" : "创建账号后即可建立空间、上传资料并整理结果。"}</p>
                </div>
                <div class="auth-capability-list">
                    ${capabilityItems.map((item) => `
                        <article class="auth-capability-item">
                            <span></span>
                            <div class="auth-capability-copy">
                                <strong>${escapeHtml(item.title)}</strong>
                                <p>${escapeHtml(item.description)}</p>
                            </div>
                        </article>
                    `).join("")}
                </div>
                <div class="auth-visual">
                    <div class="auth-visual-window">
                        <div class="auth-visual-topbar">
                            <strong>NoteWeave 工作台</strong>
                            <div class="auth-visual-dots"><span></span><span></span><span></span></div>
                        </div>
                        <div class="auth-visual-layout">
                            <aside class="auth-visual-sidebar">
                                <span class="active">空间</span>
                                <span>知识库</span>
                                <span>研究</span>
                                <span>成果</span>
                            </aside>
                            <div class="auth-visual-content">
                                <div class="auth-visual-hero">
                                    <strong>${isLogin ? "Q3 入门研究" : "创建你的第一个工作空间"}</strong>
                                    <p>${isLogin ? "知识、聊天和成果保持在同一条工作链路里。" : "创建空间、导入资料，并持续整理可编辑成果。"}</p>
                                </div>
                                <div class="auth-visual-grid">
                                    <div class="auth-visual-card primary">
                                        <strong>团队知识库</strong>
                                        <span>12 份文档已建立索引</span>
                                    </div>
                                    <div class="auth-visual-card">
                                        <strong>个人研究</strong>
                                        <span>3 个进行中的项目</span>
                                    </div>
                                    <div class="auth-visual-card">
                                        <strong>工作台聊天</strong>
                                        <span>回答带引用依据</span>
                                    </div>
                                    <div class="auth-visual-card accent">
                                        <strong>成果输出</strong>
                                        <span>可继续编辑与发布</span>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                    <div class="auth-float-card top">
                        <strong>知识库</strong>
                        <span>政策手册已同步</span>
                    </div>
                    <div class="auth-float-card bottom">
                        <strong>成果草稿</strong>
                        <span>已可进入审阅</span>
                    </div>
                </div>
            </section>
            <section class="auth-card">
                <div class="auth-card-brand">
                    <div class="auth-brand-mark small">NW</div>
                    <div class="auth-card-brand-copy">
                        <strong>NoteWeave</strong>
                        <span>${isLogin ? "登录后直接进入研究工作台" : "创建账号后直接进入你的 NoteWeave 工作台"}</span>
                    </div>
                </div>
                <span class="context-kicker">${isLogin ? "欢迎回到 NoteWeave" : "创建新的 NoteWeave 账号"}</span>
                <h1>${isLogin ? "登录 NoteWeave" : "注册并进入 NoteWeave"}</h1>
                <p class="subtitle">${isLogin ? "登录后直接进入工作台。" : "注册后直接开始创建空间、导入资料和沉淀成果。"} </p>
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
                <div class="auth-form-note">
                    <strong>${isLogin ? "第一次进入 NoteWeave 先看哪里？" : "注册完成后推荐怎么开始？"}</strong>
                    <p>${isLogin ? "先从空间页进入对应空间，再切到知识库、聊天或个人研究。" : "建议先创建一个空间，再上传资料和建立项目。"}</p>
                </div>
            </section>
        </div>
    `;
}

function renderSidebar(route) {
    const spaceId = currentRouteSpaceId();
    const personalNavSpaceId = personalSpaceId();
    const adminVisible = state.user?.systemRole === "ADMIN";
    const navItem = (label, target, active) => `<a class="nav-link ${active ? "active" : ""}" href="${target}" data-nav="${target}"><span class="nav-link-text">${label}</span></a>`;

    return `
        <aside class="sidebar">
            <div class="brand">
                <span class="brand-badge">知识、研究与成稿一体化工作台</span>
                <strong>NoteWeave</strong>
                <span>把团队知识、个人研究和可持续沉淀的成果放进同一个清晰工作区。</span>
            </div>
            <div class="nav-group">
                <div class="nav-label">工作区</div>
                ${navItem("空间", "/spaces", route.name === "spaces")}
                ${spaceId ? navItem("团队知识", routeLink("knowledge", spaceId), route.name.startsWith("knowledge")) : ""}
                ${personalNavSpaceId ? navItem("个人研究", routeLink("projects", personalNavSpaceId), route.name.startsWith("project") || route.name === "projects") : ""}
                ${spaceId ? navItem("聊天", routeLink("chat", spaceId), route.name === "team-chat" || route.name === "workbench-chat") : ""}
                ${spaceId ? navItem("工作室", routeLink("studio", spaceId), route.name === "workbench-studio") : ""}
                ${spaceId ? navItem("成果", routeLink("artifacts", spaceId), route.name === "artifacts" || route.name === "artifact-detail") : ""}
                ${spaceId ? navItem("知识沉淀", routeLink("wiki", spaceId), route.name === "wiki") : ""}
                ${spaceId ? navItem("记忆", routeLink("memory", spaceId), route.name === "memory") : ""}
            </div>
            ${adminVisible ? `
                <div class="nav-group">
                    <div class="nav-label">管理后台</div>
                    ${navItem("任务", "/admin/tasks", route.name === "admin-tasks")}
                    ${navItem("健康", "/admin/health", route.name === "admin-health")}
                    ${navItem("评测", "/admin/evaluation", route.name === "admin-evaluation")}
                    ${navItem("日志", "/admin/logs", route.name === "admin-logs")}
                </div>
            ` : ""}
            <div class="sidebar-footer">
                <div class="sidebar-footer-row">
                    <strong>${escapeHtml(state.user?.displayName || state.user?.username || "工作台用户")}</strong>
                    ${state.user?.systemRole ? tag(humanizeSystemRole(state.user.systemRole)) : ""}
                </div>
                <p class="sidebar-note">让回答可追溯、状态可见、下一步操作始终靠近你。</p>
            </div>
        </aside>
    `;
}

function renderTopbar() {
    const space = currentSpace();
    const descriptor = routeDescriptor(state.route);
    return `
        <div class="topbar">
            <div class="topbar-left">
                <div class="topbar-context">
                    <span class="context-kicker">${escapeHtml(descriptor.eyebrow)}</span>
                    <strong>${escapeHtml(descriptor.title)}</strong>
                    <span>${escapeHtml(descriptor.detail)}</span>
                </div>
                <div class="topbar-meta">
                    <div class="workspace-status">
                        <span class="status-dot ${escapeHtml(state.websocket.status)} ${state.websocket.status === "connected" ? "connected" : ""}"></span>
                        <span>实时连接 ${escapeHtml(humanizeStatus(state.websocket.status))}</span>
                    </div>
                    ${space ? `<div class="workspace-status workspace-status-strong"><span class="status-caption">当前空间</span><strong>${escapeHtml(space.name)}</strong></div>` : ""}
                </div>
            </div>
            <div class="topbar-right">
                <label class="field topbar-field">
                    <span class="muted">切换空间</span>
                    <select id="space-switcher">
                        <option value="">选择空间</option>
                        ${state.spaces.map((spaceItem) => `
                            <option value="${spaceItem.id}" ${Number(spaceItem.id) === Number(currentRouteSpaceId()) ? "selected" : ""}>${escapeHtml(spaceItem.name)}</option>
                        `).join("")}
                    </select>
                </label>
                <div class="user-chip">
                    <div class="user-chip-copy">
                        <strong>${escapeHtml(state.user?.displayName || state.user?.username || "用户")}</strong>
                        <span>${escapeHtml(state.user?.email || humanizeSystemRole(state.user?.systemRole) || "空间成员")}</span>
                    </div>
                    <button class="ghost-button" type="button" data-action="logout">退出</button>
                </div>
            </div>
        </div>
    `;
}

function renderDrawer() {
    if (!state.ui.drawer) {
        return `<aside class="drawer drawer-empty" aria-hidden="true"><div class="drawer-header"><div><span class="context-kicker">上下文抽屉</span><h2>详情面板</h2><p class="panel-subtitle">选择引用、追踪或成果关联后，会在这里显示细节。</p></div></div><div class="drawer-body">${emptyState("右侧抽屉待命中", "点击消息引用、卡片证据或日志追踪即可查看详情。")}</div></aside>`;
    }
    return `
        <aside class="drawer">
            <div class="drawer-header">
                <div>
                    <span class="context-kicker">上下文</span>
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
    const spaces = state.spaces || [];
    const teamSpaces = spaces.filter((space) => String(space.type || "").toUpperCase() === "TEAM");
    const personalSpaces = spaces.filter((space) => String(space.type || "").toUpperCase() === "PERSONAL");
    return `
        <div class="page-header">
            <div>
                <div class="context-kicker">工作区</div>
                <h1>空间选择与切换</h1>
                <p class="subtitle">查看你已加入的空间，创建新空间，并快速进入实际工作区。</p>
            </div>
        </div>
        ${renderGuideCards([
            { eyebrow: "Boundary", title: "团队与个人空间分层使用", description: "团队空间适合共享知识和协作入口，个人空间更适合私有研究和持续沉淀。" },
            { eyebrow: "Preview", title: "先看成员再进入", description: "进入前先确认所有者和角色，能减少跨空间误操作和权限疑惑。" },
            { eyebrow: "Naming", title: "名称和描述直接定义协作边界", description: "一句话说清这个空间存什么资料、谁维护、用来解决什么问题。" }
        ])}
        <div class="metric-row">
            <div class="metric"><strong>${formatNumber(spaces.length)}</strong><span>已加入空间</span></div>
            <div class="metric"><strong>${formatNumber(teamSpaces.length)}</strong><span>团队空间</span></div>
            <div class="metric"><strong>${formatNumber(personalSpaces.length)}</strong><span>个人空间</span></div>
            <div class="metric"><strong>${formatNumber(members.length)}</strong><span>预览成员</span></div>
        </div>
        <div class="content-grid cols-2">
            ${panel("已加入的空间", `
                <div class="table-wrap">
                    <table>
                        <thead>
                        <tr><th>名称</th><th>状态</th><th>所有者</th><th>操作</th></tr>
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
                        `).join("") || `<tr><td colspan="4">${emptyState("暂无空间", "先创建一个团队空间。")}</td></tr>`}
                        </tbody>
                    </table>
                </div>
            `)}
            ${panel("创建空间 / 成员预览", `
                <form id="create-space-form" class="inline-form">
                    <div class="field">
                        <label>空间名称</label>
                        <input name="name" required maxlength="128" placeholder="例如：Platform Research">
                    </div>
                    <div class="field">
                        <label>描述</label>
                        <textarea name="description" placeholder="记录这个空间的协作范围。"></textarea>
                    </div>
                    <button class="button" type="submit">创建空间</button>
                </form>
                <hr style="border:none;border-top:1px solid var(--border);margin:18px 0;">
                ${state.ui.selectedSpacePreviewId ? `
                    <h3>成员角色</h3>
                    <div class="list-stack">
                        ${members.map((member) => `
                            <div class="list-item">
                                <div class="list-item-header">
                                    <strong>${escapeHtml(member.displayName || member.username)}</strong>
                                    ${badge(member.role, humanizeSpaceRole(member.role))}
                                </div>
                                <div class="muted">${escapeHtml(member.email || "无邮箱")}</div>
                            </div>
                        `).join("") || emptyState("还没有成员列表", "这个空间暂时没有可显示成员。")}
                    </div>
                ` : emptyState("先选一个空间", "点击左侧表格里的“成员”来查看角色和成员状态。")}
            `)}
        </div>
    `;
}

function renderKnowledgeListPage() {
    const knowledgeBases = state.knowledge.list || [];
    const allDocuments = knowledgeBases.flatMap((kb) => state.knowledge.documentsByKb[kb.id] || []);
    const indexedCount = allDocuments.filter((document) => String(document.indexStatus || "").toUpperCase().includes("INDEX")).length;
    const failedCount = allDocuments.filter((document) => String(document.status || document.indexStatus || document.parseStatus || "").toUpperCase().includes("FAIL")).length;
    return `
        <div class="page-header">
            <div>
                <div class="context-kicker">团队知识</div>
                <h1>团队知识</h1>
                <p class="subtitle">知识库列表直接连接真实 API，能查看文档数、处理状态，并进入详情页上传与检索。</p>
            </div>
        </div>
        ${renderGuideCards([
            { eyebrow: "Scope", title: "按主题拆知识库", description: "把文档按团队主题或业务域拆开，比把所有资料塞进一个库里更容易维护。" },
            { eyebrow: "Status", title: "先看处理状态再检索", description: "Indexed、Processing 和 Failed 一眼分清，能更快定位问题出在导入还是检索。" },
            { eyebrow: "Naming", title: "把知识库名写成内容域", description: "比起临时文件夹名称，明确的主题名更适合长期被团队反复引用。" }
        ])}
        <div class="metric-row knowledge-metrics">
            <div class="metric"><strong>${formatNumber(knowledgeBases.length)}</strong><span>知识库数量</span></div>
            <div class="metric"><strong>${formatNumber(allDocuments.length)}</strong><span>文档总数</span></div>
            <div class="metric"><strong>${formatNumber(indexedCount)}</strong><span>已索引</span></div>
            <div class="metric"><strong>${formatNumber(failedCount)}</strong><span>待处理</span></div>
        </div>
        <div class="content-grid cols-2">
            ${panel("知识库列表", `
                <div class="table-wrap">
                    <table>
                        <thead>
                        <tr><th>名称</th><th>状态</th><th>文档数</th><th>最新处理状态</th><th></th></tr>
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
            ${panel("创建知识库", `
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
    const indexedDocuments = documents.filter((document) => String(document.indexStatus || "").toUpperCase().includes("INDEX"));
    const failedDocuments = documents.filter((document) => String(document.status || document.indexStatus || document.parseStatus || "").toUpperCase().includes("FAIL"));
    const processingDocuments = documents.filter((document) => {
        const raw = String(document.status || document.indexStatus || document.parseStatus || "").toUpperCase();
        return raw && !raw.includes("FAIL") && !raw.includes("INDEX") && raw !== "READY" && raw !== "SUCCESS";
    });
    return `
        <div class="page-header">
            <div>
                <div class="context-kicker">知识库详情</div>
                <h1>${escapeHtml(kb?.name || "知识库")}</h1>
                <p class="subtitle">上传支持 MD5 计算、分片、暂停/继续、异步任务状态展示和检索测试。</p>
            </div>
            <div class="page-actions">
                <button class="ghost-button" type="button" data-nav="${routeLink("knowledge", currentRouteSpaceId())}">返回列表</button>
            </div>
        </div>
        ${renderGuideCards([
            { eyebrow: "Upload", title: "上传不中断浏览", description: "文档处理与搜索测试可以并行进行，不必等所有任务完成再继续工作。" },
            { eyebrow: "Search", title: "用真实关键词压测召回", description: "先拿团队常问的问题测试检索，比只看上传成功更能判断知识库是否可用。" },
            { eyebrow: "Repair", title: "失败项优先看 parse 和 index", description: "先判断错误发生在解析还是索引阶段，再决定重传、暂停或继续。" }
        ])}
        <div class="metric-row knowledge-metrics">
            <div class="metric"><strong>${formatNumber(documents.length)}</strong><span>文档总数</span></div>
            <div class="metric"><strong>${formatNumber(indexedDocuments.length)}</strong><span>已索引</span></div>
            <div class="metric"><strong>${formatNumber(processingDocuments.length)}</strong><span>处理中</span></div>
            <div class="metric"><strong>${formatNumber(failedDocuments.length)}</strong><span>失败</span></div>
        </div>
        <div class="content-grid cols-2">
            ${panel("文档列表", `
                <div class="table-wrap">
                    <table>
                        <thead>
                        <tr><th>标题</th><th>状态</th><th>解析 / 索引</th><th>分片数</th><th>错误</th></tr>
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
                    <div class="list-item upload-status-card" style="margin-top:14px;">
                        <div class="list-item-header">
                            <strong>${escapeHtml(uploadJob.fileName)}</strong>
                            ${badge(uploadJob.stage || uploadJob.status || "RUNNING", uploadJob.stage || uploadJob.status || "RUNNING")}
                        </div>
                        <div class="muted">MD5: <span class="mono">${escapeHtml(uploadJob.fileMd5 || "计算中")}</span></div>
                        <div class="muted">Progress: ${uploadJob.progress ? uploadJob.progress.toFixed(1) : "0.0"}%</div>
                        <div class="progress-bar" aria-hidden="true"><span class="progress-bar-fill" style="width:${Math.max(0, Math.min(100, uploadJob.progress || 0))}%"></span></div>
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
                    <div class="list-stack search-result-stack" style="margin-top:14px;">
                        ${(searchResult.items || []).map((item) => `
                            <div class="list-item search-result-item">
                                <div class="list-item-header">
                                    <strong>${escapeHtml(item.documentTitle || `Chunk ${item.chunkId}`)}</strong>
                                    ${tag(`score ${item.score?.toFixed?.(3) ?? item.score ?? "—"}`)}
                                </div>
                                <div class="muted">${escapeHtml(item.content || "")}</div>
                            </div>
                        `).join("") || emptyState("没有命中", "换一个关键词试试。")}
                    </div>
                ` : ""}
            `, { subtitle: "上传任务不会阻塞文档浏览，检索结果直接展示命中的 chunk 片段。" })}
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
                <div class="context-kicker">工作台</div>
                <h1>工作台聊天</h1>
                <p class="subtitle">左侧会话、中间消息流、右侧引用 / 上下文 / 成果抽屉。支持流式回答、中断和草稿会话恢复。</p>
            </div>
        </div>
        ${renderGuideCards([
            { eyebrow: "Prompting", title: "把问题写成明确任务", description: "说明你是要判断、比较、总结还是生成结果，响应通常会更稳。" },
            { eyebrow: "Session", title: "正式会话与草稿会话分开", description: "草稿适合试探和摸索，正式会话更适合留下后续要复用的结论链。" },
            { eyebrow: "Evidence", title: "答案最好回到引用", description: "抽屉和引用按钮让你能快速验证回答到底基于哪些上下文。" }
        ])}
        <div class="chat-layout">
            <section class="session-list">
                <div class="drawer-header">
                    <div>
                        <span class="context-kicker">会话</span>
                        <h2>会话</h2>
                        <p class="panel-subtitle">正式会话和草稿都会保留。</p>
                    </div>
                </div>
                <div class="session-items">
                    <form id="create-session-form" class="inline-form session-create-form">
                        <div class="field">
                            <label>会话标题</label>
                                <input name="title" placeholder="例如：新用户激活证据核对" required>
                        </div>
                        <div class="field-grid cols-2">
                            <div class="field">
                                <label>类型</label>
                                <select name="sessionKind">
                                    <option value="FORMAL">正式会话</option>
                                    <option value="DRAFT">草稿会话</option>
                                </select>
                            </div>
                            <div class="field">
                                <label>范围</label>
                                <select name="scopeType">
                                    <option value="SPACE">当前空间</option>
                                    <option value="KNOWLEDGE_BASE">指定知识库</option>
                                </select>
                            </div>
                        </div>
                        <div class="field">
                            <label>知识库 IDs（逗号分隔，可空）</label>
                            <input name="scopeIds" placeholder="留空则使用当前空间">
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
                                <div class="muted">${escapeHtml(humanizeSessionKind(item.sessionKind))} / ${escapeHtml(humanizeScopeType(item.scopeType))}</div>
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
                        <div class="page-actions chat-toolbar">
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
                        <span class="context-kicker">上下文</span>
                        <h2>引用 / 成果</h2>
                        <p class="panel-subtitle">点击消息里的引用，可在右侧全局抽屉查看证据定位。</p>
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
                                        <button class="ghost-button" type="button" data-action="open-artifact" data-artifact-id="${artifact.id}" data-space-id="${artifact.spaceId || currentRouteSpaceId()}">打开成果</button>
                                    </div>
                                </div>
                            `).join("") || emptyState("暂无成果", "聊天生成的成果会显示在这里。")}
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
            actions.push(`<button class="citation-chip" type="button" data-action="open-citation" data-citation-key="${key}">引用 ${index + 1}</button>`);
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
    const projects = state.personal.projects || [];
    const counts = Object.values(state.personal.projectCounts || {});
    const totalSources = counts.reduce((sum, item) => sum + Number(item.sourceCount || 0), 0);
    const totalCards = counts.reduce((sum, item) => sum + Number(item.cardCount || 0), 0);
    const totalArtifacts = counts.reduce((sum, item) => sum + Number(item.artifactCount || 0), 0);
    return `
        <div class="page-header">
            <div>
                <div class="context-kicker">个人研究</div>
                <h1>个人研究项目</h1>
                <p class="subtitle">项目列表、创建入口，以及资料、卡片和成果数量概览。</p>
            </div>
        </div>
        ${renderGuideCards([
            { eyebrow: "Question", title: "一个项目对应一个研究问题", description: "先把研究目标写清楚，后续的资料、卡片和成果才会更聚焦。" },
            { eyebrow: "Flow", title: "先导资料，再编译卡片", description: "资料是原料层，卡片是结构化理解层，成果是输出层。" },
            { eyebrow: "Reuse", title: "把成果当成可复用资产", description: "项目结束后真正有价值的是可以被继续改写、发布和引用的成果。" }
        ])}
        <div class="metric-row">
            <div class="metric"><strong>${formatNumber(projects.length)}</strong><span>项目数</span></div>
            <div class="metric"><strong>${formatNumber(totalSources)}</strong><span>资料数</span></div>
            <div class="metric"><strong>${formatNumber(totalCards)}</strong><span>卡片数</span></div>
            <div class="metric"><strong>${formatNumber(totalArtifacts)}</strong><span>成果数</span></div>
        </div>
        <div class="content-grid cols-2">
            ${panel("项目列表", `
                <div class="table-wrap">
                    <table>
                        <thead>
                        <tr><th>标题</th><th>状态</th><th>编译</th><th>统计</th><th></th></tr>
                        </thead>
                        <tbody>
                        ${state.personal.projects.map((project) => {
                            const counts = state.personal.projectCounts[project.id] || {};
                            return `
                                <tr>
                                    <td><strong>${escapeHtml(project.title)}</strong><div class="muted">${escapeHtml(project.researchGoal || project.description || "无目标说明")}</div></td>
                                    <td>${badge(project.status)}</td>
                                    <td>${badge(project.compileStatus || "PENDING", project.compileStatus || "PENDING")}</td>
                                    <td>${formatNumber(counts.sourceCount)} 份资料 / ${formatNumber(counts.cardCount)} 张卡片 / ${formatNumber(counts.artifactCount)} 个成果</td>
                                    <td><button class="button" type="button" data-action="open-project" data-project-id="${project.id}" data-space-id="${project.spaceId || personalSpaceId() || ""}">进入</button></td>
                                </tr>
                            `;
                        }).join("") || `<tr><td colspan="5">${emptyState("暂无项目", "创建一个研究项目开始导入资料。")}</td></tr>`}
                        </tbody>
                    </table>
                </div>
            `)}
            ${panel("创建研究项目", `
                <form id="create-project-form" class="inline-form">
                    <div class="field">
                        <label>标题</label>
                        <input name="title" required maxlength="255" placeholder="例如：新用户激活流程复盘">
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
    const projectGuideItems = route.name === "project-sources" ? [
        { eyebrow: "Import", title: "文件、URL 与文本共用同一研究入口", description: "入口统一后，后续的编译、卡片生成和失败重试都会更好追踪。" },
        { eyebrow: "Compile", title: "先看导入状态，再决定是否编译", description: "资料状态会告诉你应该重试、继续还是触发知识编译。" },
        { eyebrow: "Repair", title: "失败项要立刻回收处理", description: "比起堆积异常资料，及时修复更能保持项目链路干净。" }
    ] : route.name === "project-cards" ? [
        { eyebrow: "Article", title: "ArticleCard 负责摘要原始材料", description: "它是资料进入结构化理解层的第一步，帮助你缩短回看成本。" },
        { eyebrow: "Concept", title: "ConceptCard 负责抽象稳定概念", description: "概念层越清晰，后续跨来源综合和检索复用就越稳定。" },
        { eyebrow: "Synthesis", title: "SynthesisCard 负责形成可复用结论", description: "它更接近长期沉淀，而不是一次性的聊天输出。" }
    ] : route.name === "project-generate" ? [
        { eyebrow: "Method", title: "生成前先确认方法卡", description: "方法卡决定了你是做总结、综述、提案还是其他更具体的成果形式。" },
        { eyebrow: "Output", title: "成果是可继续编辑的输出面", description: "生成不是结束，后面还可以导出、发布 Wiki 或继续蒸馏。" },
        { eyebrow: "Scope", title: "保持研究目标与生成范围一致", description: "题目越窄，生成出的结构和引用越容易保持可靠。" }
    ] : [
        { eyebrow: "Goal", title: "先确认研究问题是否清晰", description: "项目概览最适合回看你到底要解决什么问题，以及现在进展到哪一步。" },
        { eyebrow: "Recent", title: "最近资料与成果放在一起回看", description: "这样能更快判断研究是在扩展证据，还是已经该开始收束输出。" },
        { eyebrow: "Methodology", title: "方法卡决定后续产出质量", description: "保持方法和研究目标一致，会让成果更像成品而不是草稿。" }
    ];

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
                <div class="metric"><strong>${formatNumber(sources.length)}</strong><span>资料数</span></div>
                <div class="metric"><strong>${formatNumber(articles.length + concepts.length + synthesisCards.length)}</strong><span>卡片数</span></div>
                <div class="metric"><strong>${formatNumber(artifacts.length)}</strong><span>成果数</span></div>
                <div class="metric"><strong>${formatNumber(methodologyCards.length)}</strong><span>方法卡</span></div>
            </div>
            <div class="content-grid cols-2" style="margin-top:16px;">
                ${panel("最近资料", sources.slice(0, 4).map((source) => `<div class="list-item"><strong>${escapeHtml(source.title)}</strong><div class="muted">${badge(source.importStatus, source.importStatus)} ${badge(source.compileStatus, source.compileStatus)}</div></div>`).join("") || emptyState("暂无资料", "到资料标签导入文件、URL 或文本。"))}
                ${panel("最近成果", artifacts.slice(0, 4).map((artifact) => `<div class="list-item"><strong>${escapeHtml(artifact.title)}</strong><div class="muted">${escapeHtml(artifact.artifactType)} / ${badge(artifact.status)}</div></div>`).join("") || emptyState("暂无成果", "到生成标签发起生成。"))}
            </div>
        `;
    }

    return `
        <div class="page-header">
            <div>
                <div class="context-kicker">研究项目</div>
                <h1>${escapeHtml(project?.title || "研究项目")}</h1>
                <p class="subtitle">${escapeHtml(project?.researchGoal || project?.description || "项目概览")}</p>
            </div>
            <div class="page-actions">
                <button class="ghost-button" type="button" data-nav="${routeLink("projects", projectSpaceId)}">返回项目列表</button>
            </div>
        </div>
        <div class="tab-bar">
            ${tabLink("概览", "", route.name === "project-detail")}
            ${tabLink("资料", "/sources", route.name === "project-sources")}
            ${tabLink("卡片", "/cards", route.name === "project-cards")}
            ${tabLink("生成", "/generate", route.name === "project-generate")}
        </div>
        ${renderGuideCards(projectGuideItems)}
        ${body}
    `;
}

function renderProjectSources(projectId, sources) {
    return `
        <div class="content-grid cols-2">
            ${panel("导入资料", `
                <form id="source-file-form" data-project-id="${projectId}" class="inline-form">
                    <div class="field"><label>上传文件</label><input type="file" name="file" required></div>
                    <div class="field"><label>自定义标题</label><input name="title"></div>
                    <button class="button" type="submit">上传文件资料</button>
                </form>
                <hr style="border:none;border-top:1px solid var(--border);margin:18px 0;">
                <form id="source-url-form" data-project-id="${projectId}" class="inline-form">
                    <div class="field"><label>URL</label><input name="url" required></div>
                    <div class="field"><label>标题</label><input name="title"></div>
                    <button class="button" type="submit">添加网址资料</button>
                </form>
                <hr style="border:none;border-top:1px solid var(--border);margin:18px 0;">
                <form id="source-text-form" data-project-id="${projectId}" class="inline-form">
                    <div class="field"><label>标题</label><input name="title" required></div>
                    <div class="field"><label>内容</label><textarea name="content" required></textarea></div>
                    <button class="button" type="submit">添加文本资料</button>
                </form>
            `, { subtitle: "同一个项目支持文件、URL 和纯文本三种入口，导入后再进入编译链路。" })}
            ${panel("资料列表", `
                <div class="list-stack">
                    ${sources.map((source) => `
                        <div class="list-item">
                            <div class="list-item-header">
                                <strong>${escapeHtml(source.title)}</strong>
                                <div class="page-actions">${badge(source.importStatus, source.importStatus)}${badge(source.compileStatus, source.compileStatus)}</div>
                            </div>
                            <div class="muted">${escapeHtml(source.sourceType)} / 任务 ${formatNumber(source.taskId)}</div>
                            <div class="page-actions" style="margin-top:10px;">
                                <button class="ghost-button" type="button" data-action="source-import" data-source-id="${source.id}">重新导入</button>
                                <button class="ghost-button" type="button" data-action="source-compile" data-source-id="${source.id}">触发知识编译</button>
                            </div>
                        </div>
                    `).join("") || emptyState("暂无资料", "先导入一份文件、URL 或粘贴文本。")}
                </div>
            `, { subtitle: "优先看导入状态和编译状态，失败项要能立刻重试。" })}
        </div>
    `;
}

function renderProjectCards(articles, concepts, synthesisCards) {
    return `
        <div class="content-grid cols-3">
            ${panel("文章卡", articles.map((card) => `
                <div class="list-item">
                    <div class="list-item-header"><strong>${escapeHtml(card.title)}</strong></div>
                    <div class="muted">${escapeHtml(card.summary || "无摘要")}</div>
                    ${(card.citations || []).slice(0, 2).map((citation, index) => {
                        const key = registerCitation(citation);
                        return `<button class="citation-chip" type="button" data-action="open-citation" data-citation-key="${key}">证据 ${index + 1}</button>`;
                    }).join("")}
                </div>
            `).join("") || emptyState("暂无文章卡", "资料完成导入后，这里会显示文章卡片。"), { subtitle: "文章卡承担对原始资料的结构化摘要，是后续概念融合的入口。" })}
            ${panel("概念卡", concepts.map((card) => `
                <div class="list-item">
                    <div class="list-item-header"><strong>${escapeHtml(card.name)}</strong>${tag(`置信度 ${card.confidence}`)}</div>
                    <div class="muted">${escapeHtml(card.definition || card.explanation || "无定义")}</div>
                    ${(card.citations || []).slice(0, 2).map((citation, index) => {
                        const key = registerCitation(citation);
                        return `<button class="citation-chip" type="button" data-action="open-citation" data-citation-key="${key}">证据 ${index + 1}</button>`;
                    }).join("")}
                </div>
            `).join("") || emptyState("暂无概念卡", "编译完成后会展示概念卡片。"), { subtitle: "概念卡更强调跨资料融合与长期复用。" })}
            ${panel("综合卡", synthesisCards.map((card) => `
                <div class="list-item">
                    <div class="list-item-header"><strong>${escapeHtml(card.title)}</strong>${badge(card.cardStatus, card.cardStatus)}</div>
                    <div class="muted">${escapeHtml(card.summary || "无摘要")}</div>
                    ${(card.citations || []).slice(0, 2).map((citation, index) => {
                        const key = registerCitation(citation);
                        return `<button class="citation-chip" type="button" data-action="open-citation" data-citation-key="${key}">证据 ${index + 1}</button>`;
                    }).join("")}
                </div>
            `).join("") || emptyState("暂无综合卡", "成果蒸馏或生成完成后会沉淀在这里。"), { subtitle: "综合卡是已经确认过价值的沉淀结果，应该比普通卡更稳定。" })}
        </div>
    `;
}

function renderProjectGenerate(spaceId, projectId, methodologyCards, artifacts) {
    const skills = state.studio.skills;
    return `
        <div class="content-grid cols-2">
            ${panel("生成成果", `
                <form id="project-generate-form" data-space-id="${spaceId}" data-project-id="${projectId}" class="inline-form">
                    <div class="field-grid cols-2">
                        <div class="field">
                            <label>工作室技能</label>
                            <select name="skillId" required>
                                ${skills.map((skill) => `<option value="${escapeHtml(skill.id)}">${escapeHtml(skill.name)}</option>`).join("")}
                            </select>
                        </div>
                        <div class="field">
                            <label>方法卡</label>
                            <select name="methodologyCardId">
                                <option value="">不指定</option>
                                ${methodologyCards.map((card) => `<option value="${card.id}">${escapeHtml(card.name)}</option>`).join("")}
                            </select>
                        </div>
                    </div>
                    <div class="field">
                        <label>主题</label>
                        <input name="topic" required placeholder="例如：新用户激活方案建议稿">
                    </div>
                    <button class="button" type="submit">创建成果</button>
                </form>
                ${state.studio.lastTask ? `
                    <div class="list-item" style="margin-top:14px;">
                        <div class="list-item-header">
                            <strong>最近生成任务</strong>
                            ${badge(state.studio.lastTask.taskStatus)}
                        </div>
                        <div class="muted">成果 ${formatNumber(state.studio.lastTask.artifactId)} / 任务 ${formatNumber(state.studio.lastTask.taskId)}</div>
                    </div>
                ` : ""}
            `, { subtitle: "先确定输出目标，再决定是否套用方法卡，不要把生成表单做成复杂配置台。" })}
            ${panel("成果与方法卡", `
                <h3>方法卡</h3>
                <div class="list-stack">
                    ${methodologyCards.map((card) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(card.name)}</strong>${badge(card.status)}</div>
                            <div class="muted">${escapeHtml(card.problemType || card.scene || "未设置场景")}</div>
                        </div>
                    `).join("") || emptyState("暂无方法卡", "当前项目还没有方法论卡片。")}
                </div>
                <hr style="border:none;border-top:1px solid var(--border);margin:18px 0;">
                <h3>成果</h3>
                <div class="list-stack">
                    ${artifacts.map((artifact) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(artifact.title)}</strong>${badge(artifact.status)}</div>
                            <div class="page-actions" style="margin-top:10px;"><button class="ghost-button" type="button" data-action="open-artifact" data-artifact-id="${artifact.id}" data-space-id="${artifact.spaceId || spaceId}">打开</button></div>
                        </div>
                    `).join("") || emptyState("暂无成果", "生成任务完成后会出现在这里。")}
                </div>
            `, { subtitle: "左边负责发起，右边负责判断当前项目是否已经有足够的方法和产物积累。" })}
        </div>
    `;
}

function renderStudioPage() {
    const lastTaskStatus = state.studio.lastTask?.taskStatus || "IDLE";
    return `
        <div class="page-header">
            <div>
                <div class="context-kicker">工作室</div>
                <h1>工作室</h1>
                <p class="subtitle">选择技能、填写参数、选择上下文范围，并查看最近成果生成结果。</p>
            </div>
        </div>
        <div class="metric-row">
            <div class="metric"><strong>${formatNumber(state.studio.skills.length)}</strong><span>技能数</span></div>
            <div class="metric"><strong>${formatNumber(state.personal.projects.length)}</strong><span>项目数</span></div>
            <div class="metric"><strong>${formatNumber(state.artifacts.list.length)}</strong><span>可见成果</span></div>
            <div class="metric"><strong>${escapeHtml(humanizeStatus(lastTaskStatus))}</strong><span>最近任务</span></div>
        </div>
        <div class="content-grid cols-2">
            ${panel("启动生成任务", `
                <form id="studio-task-form" class="inline-form" data-space-id="${currentRouteSpaceId()}">
                    <div class="field-grid cols-2">
                        <div class="field">
                            <label>技能</label>
                            <select name="skillId" required>
                                ${state.studio.skills.map((skill) => `<option value="${skill.id}">${escapeHtml(skill.name)}</option>`).join("")}
                            </select>
                        </div>
                        <div class="field">
                            <label>研究项目</label>
                            <select name="projectId" required>
                                ${state.personal.projects.map((project) => `<option value="${project.id}">${escapeHtml(project.title)}</option>`).join("")}
                            </select>
                        </div>
                    </div>
                    <div class="field">
                        <label>主题</label>
                        <input name="topic" required placeholder="例如：检索调试说明稿">
                    </div>
                    <button class="button" type="submit">启动工作室任务</button>
                </form>
                ${state.studio.lastTask ? `<div class="list-item" style="margin-top:14px;"><div class="list-item-header"><strong>最近任务</strong>${badge(state.studio.lastTask.taskStatus)}</div><div class="muted">成果 ${formatNumber(state.studio.lastTask.artifactId)}</div></div>` : ""}
            `, { subtitle: "工作室更像任务编排入口，不是一次性提示词表单。" })}
            ${panel("技能与成果", `
                <div class="list-stack">
                    ${state.studio.skills.map((skill) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(skill.name)}</strong>${tag(skill.artifactType)}</div>
                            <div class="muted">${escapeHtml(skill.description)}</div>
                            <div class="muted">主题提示：${escapeHtml(skill.topicHint)}</div>
                        </div>
                    `).join("")}
                </div>
                <hr style="border:none;border-top:1px solid var(--border);margin:18px 0;">
                <div class="list-stack">
                    ${state.artifacts.list.slice(0, 6).map((artifact) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(artifact.title)}</strong>${badge(artifact.status)}</div>
                            <div class="page-actions" style="margin-top:10px;"><button class="ghost-button" type="button" data-action="open-artifact" data-artifact-id="${artifact.id}" data-space-id="${artifact.spaceId || currentRouteSpaceId()}">打开成果</button></div>
                        </div>
                    `).join("") || emptyState("暂无成果", "生成后的内容会显示在这里。")}
                </div>
            `, { subtitle: "右侧更像当前工作区的技能目录和最近成果架。" })}
        </div>
    `;
}

function renderArtifactsPage() {
    const artifacts = state.artifacts.list || [];
    const publishedCount = artifacts.filter((artifact) => ["READY", "SUCCESS", "PUBLISHED", "ACTIVE"].includes(String(artifact.status || "").toUpperCase())).length;
    const projectCount = new Set(artifacts.map((artifact) => artifact.researchProjectId).filter(Boolean)).size;
    const typeCount = new Set(artifacts.map((artifact) => artifact.artifactType).filter(Boolean)).size;
    return `
        <div class="page-header">
            <div>
                <div class="context-kicker">成果</div>
                <h1>成果</h1>
                <p class="subtitle">预览、筛选和进入成果详情页。详情页支持编辑、导出、蒸馏与发布 Wiki。</p>
            </div>
        </div>
        ${renderGuideCards([
            { eyebrow: "书架", title: "把成果当成可回访书架", description: "成果更适合被继续阅读、改写和引用，而不是看完即走的任务输出。" },
            { eyebrow: "类型", title: "不同成果类型可以并行存在", description: "同一个研究项目可以沉淀出综述、摘要、提案等多种不同的可交付物。" },
            { eyebrow: "复用", title: "优先打开可复用内容", description: "除了关注最新生成，也要保留那些已经适合继续发布或二次加工的结果。" }
        ])}
        <div class="metric-row">
            <div class="metric"><strong>${formatNumber(artifacts.length)}</strong><span>成果总数</span></div>
            <div class="metric"><strong>${formatNumber(publishedCount)}</strong><span>可发布成果</span></div>
            <div class="metric"><strong>${formatNumber(projectCount)}</strong><span>关联项目数</span></div>
            <div class="metric"><strong>${formatNumber(typeCount)}</strong><span>成果类型数</span></div>
        </div>
        <div class="content-grid cols-2" style="margin-top:16px;">
            ${panel("成果列表", `
                <div class="table-wrap">
                    <table>
                        <thead>
                        <tr><th>标题</th><th>类型</th><th>状态</th><th>项目</th><th></th></tr>
                        </thead>
                        <tbody>
                        ${artifacts.map((artifact) => `
                            <tr>
                                <td><strong>${escapeHtml(artifact.title)}</strong><div class="muted">${escapeHtml(artifact.summary || artifact.description || "可继续编辑、导出或沉淀的工作成果。")}</div></td>
                                <td>${escapeHtml(artifact.artifactType)}</td>
                                <td>${badge(artifact.status)}</td>
                                <td>${formatNumber(artifact.researchProjectId)}</td>
                                <td><button class="button" type="button" data-action="open-artifact" data-artifact-id="${artifact.id}" data-space-id="${artifact.spaceId || currentRouteSpaceId()}">打开</button></td>
                            </tr>
                        `).join("") || `<tr><td colspan="5">${emptyState("暂无成果", "从工作室或个人生成入口创建一个。")}</td></tr>`}
                        </tbody>
                    </table>
                </div>
            `)}
            ${panel("最近阅读区", `
                <div class="list-stack">
                    ${artifacts.slice(0, 5).map((artifact) => `
                        <div class="list-item artifact-library-item">
                            <div class="list-item-header">
                                <strong>${escapeHtml(artifact.title)}</strong>
                                ${badge(artifact.status)}
                            </div>
                            <div class="muted">${escapeHtml(artifact.artifactType)} / 项目 ${formatNumber(artifact.researchProjectId)}</div>
                            <div class="page-actions" style="margin-top:10px;">
                                <button class="ghost-button" type="button" data-action="open-artifact" data-artifact-id="${artifact.id}" data-space-id="${artifact.spaceId || currentRouteSpaceId()}">继续阅读</button>
                            </div>
                        </div>
                    `).join("") || emptyState("阅读面待填充", "当成果开始积累后，这里会更像你的成果书架。")}
                </div>
            `, { subtitle: "让成果更像可回访的阅读对象，而不是一次性的任务输出。" })}
        </div>
    `;
}

function renderArtifactDetailPage() {
    const artifact = state.artifacts.detail;
    const artifactSpaceId = resolveArtifactSpaceId(artifact);
    const canDistill = canDistillArtifactToPersonalWiki(artifact);
    const draft = state.artifacts.editorDraft || { title: "", content: "", changeNote: "" };
    return `
        <div class="page-header">
            <div>
                <div class="context-kicker">成果</div>
                <h1>${escapeHtml(artifact?.title || "成果查看器")}</h1>
                <p class="subtitle">支持 Markdown 预览、基础编辑、导出、引用查看、沉淀到个人 Wiki，以及发布到团队 Wiki。</p>
            </div>
            <div class="page-actions">
                <button class="ghost-button" type="button" data-nav="${routeLink("artifacts", artifactSpaceId)}">返回列表</button>
                <button class="ghost-button" type="button" data-action="export-artifact" data-artifact-id="${artifact.id}">导出 Markdown</button>
                <button class="ghost-button" type="button" data-action="regenerate-artifact" data-artifact-id="${artifact.id}">重新生成</button>
            </div>
        </div>
        ${renderGuideCards([
            { eyebrow: "Edit", title: "左侧负责改稿，右侧负责读稿", description: "先稳定标题和结构，再处理正文内容，整体会更接近可发布成品。" },
            { eyebrow: "Trace", title: "引用与关系保证可追溯性", description: "引用和卡片关联让这份成果不只是好看，也更容易被复核。" },
            { eyebrow: "Distill", title: "个人成果可以继续蒸馏", description: "如果当前成果属于个人研究空间，还可以继续沉淀进更长期的 Wiki 卡片。" }
        ])}
        <div class="metric-row">
            <div class="metric"><strong>${escapeHtml(artifact?.artifactType || "—")}</strong><span>成果类型</span></div>
            <div class="metric"><strong>${escapeHtml(humanizeStatus(artifact?.status || "—"))}</strong><span>状态</span></div>
            <div class="metric"><strong>${formatNumber(artifact?.researchProjectId)}</strong><span>研究项目</span></div>
            <div class="metric"><strong>${formatDate(artifact?.updatedAt || artifact?.createdAt)}</strong><span>最近更新</span></div>
        </div>
        <div class="content-grid cols-2 artifact-detail-grid" style="margin-top:16px;">
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
                    <button class="button" type="submit">保存成果</button>
                </form>
            `)}
            ${panel("预览与沉淀", `
                <div class="artifact-meta-row">
                    ${tag(artifact?.artifactType || "成果")}
                    ${badge(artifact?.status)}
                    ${artifact?.scopeType ? tag(artifact.scopeType) : ""}
                </div>
                <div class="artifact-reading-surface">${renderMarkdown(draft.content)}</div>
                ${canDistill ? `
                    <hr style="border:none;border-top:1px solid var(--border);margin:18px 0;">
                    <form id="distill-artifact-form" data-artifact-id="${artifact.id}" class="inline-form">
                        <div class="field">
                            <label>沉淀方式</label>
                            <select name="cardType">
                                <option value="SYNTHESIS">综合卡</option>
                            </select>
                        </div>
                        <button class="button" type="submit">预览提炼结果</button>
                    </form>
                ` : `
                    <hr style="border:none;border-top:1px solid var(--border);margin:18px 0;">
                    <div class="empty-state">
                        <strong>该成果不支持个人蒸馏</strong>
                        <div class="muted">只有个人研究空间中的成果才会显示综合卡蒸馏入口。</div>
                    </div>
                `}
                <form id="publish-artifact-wiki-form" data-artifact-id="${artifact.id}" class="inline-form" style="margin-top:14px;">
                    <input type="hidden" name="spaceId" value="${artifactSpaceId}">
                    <div class="field">
                        <label>Wiki 页面标题</label>
                        <input name="title" value="${escapeHtml(artifact.title)}" required>
                    </div>
                    <button class="ghost-button" type="submit">发布到团队 Wiki</button>
                </form>
            `)}
        </div>
        <div class="content-grid cols-2 artifact-secondary-grid" style="margin-top:16px;">
            ${panel("引用", `
                <div class="list-stack">
                    ${(artifact.citations || []).map((citation, index) => {
                        const key = registerCitation(citation);
                        return `<button class="list-item" type="button" data-action="open-citation" data-citation-key="${key}"><strong>引用 ${index + 1}</strong><div class="muted">${escapeHtml(citation.title || citation.locationInfo || "")}</div></button>`;
                    }).join("") || emptyState("暂无引用", "这个成果目前没有引用记录。")}
                </div>
            `)}
            ${panel("卡片关联", `
                <div class="list-stack">
                    ${canDistill ? state.artifacts.relations.map((relation) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(relation.cardTitle || relation.cardType)}</strong>${tag(relation.relationType)}</div>
                            <div class="muted">${escapeHtml(relation.cardType)} / ID ${formatNumber(relation.cardId)}</div>
                        </div>
                    `).join("") || emptyState("暂无关联卡片", "蒸馏后这里会展示关联的 Wiki 卡片。") : emptyState("当前无需展示关联卡片", "团队空间成果不会关联个人综合卡。")}
                </div>
            `)}
        </div>
    `;
}

function renderWikiPage() {
    const page = state.wiki.pageDetail;
    const pages = state.wiki.pages || [];
    return `
        <div class="page-header">
            <div>
                <div class="context-kicker">Wiki</div>
                <h1>团队 Wiki</h1>
                <p class="subtitle">查看 Wiki 列表、创建草稿、编辑、发布、查看版本，并支持搜索。</p>
            </div>
        </div>
        ${renderGuideCards([
            { eyebrow: "Draft", title: "先把草稿写顺，再决定是否发布", description: "Wiki 更适合沉淀稳定知识，而不是直接复制临时讨论结果。" },
            { eyebrow: "Version", title: "版本历史保留演进轨迹", description: "这让团队可以看见结论是如何收敛出来的，而不只是最终页面。" },
            { eyebrow: "Search", title: "搜索更适合找沉淀，不是找聊天", description: "命名和段落结构越清楚，后续检索命中和复用就越自然。" }
        ])}
        <div class="metric-row">
            <div class="metric"><strong>${formatNumber(pages.length)}</strong><span>页面数</span></div>
            <div class="metric"><strong>${formatNumber(state.wiki.versions.length)}</strong><span>版本数</span></div>
            <div class="metric"><strong>${formatNumber(state.wiki.searchResult?.items?.length || 0)}</strong><span>搜索命中</span></div>
            <div class="metric"><strong>${page ? "编辑中" : "空闲"}</strong><span>编辑器状态</span></div>
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
                            <div class="muted">版本 ${formatNumber(wikiPage.publishedVersionNo)}</div>
                        </button>
                    `).join("") || emptyState("暂无 Wiki 页面", "先创建一个草稿页。")}
                </div>
            `, { subtitle: "把这里当作团队沉淀区，而不是普通文档堆。" })}
            ${panel(page ? "编辑 Wiki 草稿" : "创建 Wiki 草稿", page ? `
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
            `, { subtitle: page ? "先把草稿写清楚，再决定是否发布进团队知识层。" : "新建时保持轻量，内容稳定后再发布为正式 Wiki。" })}
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
    const spaceMemoryItems = state.memory.spaceMemory?.items || [];
    const userMemoryItems = state.memory.userMemory?.items || [];
    return `
        <div class="page-header">
            <div>
                <div class="context-kicker">记忆</div>
                <h1>记忆</h1>
                <p class="subtitle">明确哪些空间 / 用户 / 会话内容会影响后续回答，保存动作明确，不对草稿会话做长期写入暗示。</p>
            </div>
        </div>
        ${renderGuideCards([
            { eyebrow: "空间", title: "空间记忆影响团队级回答", description: "适合记录稳定上下文、术语和长期有效的协作事实，不适合临时结论。" },
            { eyebrow: "用户", title: "用户记忆记录个人偏好", description: "例如表达方式、长期关注点和工作习惯，而不是一次性的执行噪音。" },
            { eyebrow: "边界", title: "草稿会话不应直接变成长记忆", description: "把探索和沉淀分开，能减少后续回答被短期波动误导的风险。" }
        ])}
        <div class="metric-row">
            <div class="metric"><strong>${formatNumber(spaceMemoryItems.length)}</strong><span>空间记忆</span></div>
            <div class="metric"><strong>${formatNumber(userMemoryItems.length)}</strong><span>用户记忆</span></div>
            <div class="metric"><strong>${formatNumber(state.memory.sessionSummaries.length)}</strong><span>会话摘要</span></div>
            <div class="metric"><strong>${formatNumber(synthesisCards.length)}</strong><span>综合卡</span></div>
        </div>
        <div class="content-grid cols-2">
            ${panel("空间记忆", `
                <div class="list-item">
                    <div class="list-item-header"><strong>摘要</strong>${state.memory.spaceMemory ? badge(state.memory.spaceMemory.writeEnabled ? "ENABLED" : "DISABLED", state.memory.spaceMemory.writeEnabled ? "写入开启" : "写入关闭") : ""}</div>
                    <div class="muted">${escapeHtml(state.memory.spaceMemory?.summary || "暂无摘要")}</div>
                </div>
                <form id="space-memory-form" class="inline-form" data-space-id="${currentRouteSpaceId()}">
                    <div class="field-grid cols-2">
                        <div class="field"><label>主题</label><input name="topic" required></div>
                        <div class="field"><label>记忆类型</label><select name="memoryType"><option value="SPACE_CONTEXT">空间上下文</option><option value="SESSION_INSIGHT">会话洞察</option></select></div>
                    </div>
                    <div class="field"><label>摘要</label><textarea name="summary" required></textarea></div>
                    <button class="button" type="submit">保存空间记忆</button>
                </form>
                <div class="list-stack" style="margin-top:14px;">
                    ${(state.memory.spaceMemory?.items || []).map((item) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(item.topic)}</strong>${badge(item.memoryType, humanizeMemoryType(item.memoryType))}</div>
                            <div class="muted">${escapeHtml(item.summary)}</div>
                            <button class="danger-button" type="button" data-action="delete-space-memory-item" data-item-id="${item.id}">删除</button>
                        </div>
                    `).join("") || emptyState("暂无空间记忆", "保存后这里会显示影响团队回答的长期记忆。")}
                </div>
            `, { subtitle: "团队层记忆应该尽量稳定、清楚，并且明确影响哪些后续回答。" })}
            ${panel("用户记忆", `
                <div class="page-actions">
                    <button class="ghost-button" type="button" data-action="${state.memory.userMemory?.writeEnabled ? "disable-user-memory" : "enable-user-memory"}">${state.memory.userMemory?.writeEnabled ? "关闭写入" : "开启写入"}</button>
                </div>
                <div class="list-item" style="margin-top:10px;">
                    <div class="list-item-header"><strong>摘要</strong>${state.memory.userMemory ? badge(state.memory.userMemory.writeEnabled ? "ENABLED" : "DISABLED", state.memory.userMemory.writeEnabled ? "写入开启" : "写入关闭") : ""}</div>
                    <div class="muted">${escapeHtml(state.memory.userMemory?.summary || "暂无摘要")}</div>
                </div>
                <form id="user-memory-form" class="inline-form" style="margin-top:14px;">
                    <div class="field-grid cols-2">
                        <div class="field"><label>主题</label><input name="topic" required></div>
                        <div class="field"><label>记忆类型</label><select name="memoryType"><option value="USER_PREFERENCE">用户偏好</option><option value="SESSION_INSIGHT">会话洞察</option></select></div>
                    </div>
                    <div class="field"><label>摘要</label><textarea name="summary" required></textarea></div>
                    <button class="button" type="submit">保存用户记忆</button>
                </form>
                <div class="list-stack" style="margin-top:14px;">
                    ${(state.memory.userMemory?.items || []).map((item) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(item.topic)}</strong>${badge(item.memoryType, humanizeMemoryType(item.memoryType))}</div>
                            <div class="muted">${escapeHtml(item.summary)}</div>
                            <button class="danger-button" type="button" data-action="delete-user-memory-item" data-item-id="${item.id}">删除</button>
                        </div>
                    `).join("") || emptyState("暂无用户记忆", "个人偏好与长期记忆会显示在这里。")}
                </div>
            `, { subtitle: "用户层记忆更适合偏好、习惯和长期关注点，不适合灌入临时噪音。" })}
        </div>
        <div class="content-grid cols-2" style="margin-top:16px;">
            ${panel("会话摘要", `
                <div class="field">
                    <label>选择会话</label>
                    <select id="memory-session-selector">
                        ${state.chat.sessions.map((session) => `<option value="${session.id}" ${Number(session.id) === Number(state.memory.selectedSessionId) ? "selected" : ""}>${escapeHtml(session.title)}</option>`).join("")}
                    </select>
                </div>
                <div class="list-stack" style="margin-top:14px;">
                    ${state.memory.sessionSummaries.map((summary) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(summary.topic)}</strong>${tag(`重要度 ${summary.importanceScore}`)}</div>
                            <div class="muted">${escapeHtml(summary.summary)}</div>
                        </div>
                    `).join("") || emptyState("暂无会话摘要", "当前会话还没有生成会话摘要。")}
                </div>
            `, { subtitle: "这里帮助用户看见哪些会话记忆可能会被写回长期上下文。" })}
            ${panel("综合卡", `
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
                    `).join("") || emptyState("暂无综合卡", "成果蒸馏后这里会同步显示。")}
                </div>
            `, { subtitle: "综合卡是长期知识沉淀，不应该和临时聊天结果混淆。" })}
        </div>
    `;
}

function renderAdminTasksPage() {
    const tasks = flattenPageResponse(state.admin.tasks);
    return `
        <div class="page-header">
            <div>
                <div class="context-kicker">管理后台</div>
                <h1>任务中心</h1>
                <p class="subtitle">任务列表、状态筛选、失败原因、重试与取消入口。</p>
            </div>
        </div>
        ${renderGuideCards([
            { eyebrow: "Filter", title: "先筛状态，再追失败原因", description: "把正在运行、失败和已完成的任务分开看，排查效率会更高。" },
            { eyebrow: "Inspect", title: "重试前先检查输入输出", description: "很多任务问题不是执行器本身，而是进入任务时的数据已经不对。" },
            { eyebrow: "Trace", title: "抽屉里的事件历史最有价值", description: "它能把一次失败究竟发生在哪个阶段串得更完整。" }
        ])}
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
                        <tr><th>ID</th><th>类型</th><th>状态</th><th>目标</th><th>错误</th><th>操作</th></tr>
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
                <div class="list-item-header"><strong>任务 ${formatNumber(task.id)}</strong>${badge(task.taskStatus)}</div>
                <div class="muted">类型：${escapeHtml(task.taskType)}</div>
                <div class="muted">目标：${escapeHtml(task.targetType || "—")} / ${formatNumber(task.targetId)}</div>
                <div class="muted">重试次数：${formatNumber(task.retryCount)} / ${formatNumber(task.maxRetryCount)}</div>
                <div class="muted">开始时间：${formatDate(task.startedAt)}</div>
                <div class="muted">结束时间：${formatDate(task.finishedAt)}</div>
                <div class="muted">错误信息：${escapeHtml(task.errorMessage || "—")}</div>
            </div>
            ${panel("输入", renderJson(task.input))}
            ${panel("输出", renderJson(task.output))}
            ${panel("事件历史", `
                <div class="list-stack">
                    ${events.map((item) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(item.eventType)}</strong><span class="muted">${formatDate(item.createdAt)}</span></div>
                            <div class="muted">${escapeHtml(item.message || "—")}</div>
                            <div class="muted">状态：${escapeHtml(item.fromStatus || "—")} -> ${escapeHtml(item.toStatus || "—")}</div>
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
            <div class="metric"><strong>${formatNumber(dashboard.userCount)}</strong><span>用户数</span></div>
            <div class="metric"><strong>${formatNumber(dashboard.spaceCount)}</strong><span>空间数</span></div>
            <div class="metric"><strong>${formatNumber(dashboard.taskCount)}</strong><span>任务数</span></div>
            <div class="metric"><strong>${formatNumber(dashboard.failedTaskCount)}</strong><span>失败任务</span></div>
        </div>
    `;
}

function renderAdminHealthPage() {
    const components = state.admin.health?.components || [];
    return `
        <div class="page-header">
            <div>
                <div class="context-kicker">管理后台</div>
                <h1>系统健康</h1>
                <p class="subtitle">组件健康状态、最近检查时间、延迟和错误详情。</p>
            </div>
            <div class="page-actions">
                <button class="ghost-button" type="button" data-action="refresh-page">手动刷新</button>
            </div>
        </div>
        ${renderGuideCards([
            { eyebrow: "概览", title: "健康页更适合看系统面", description: "它回答的是当前哪些组件不稳，而不是某一次具体业务为什么失败。" },
            { eyebrow: "延迟", title: "延迟和时间戳要一起判断", description: "一次慢响应未必说明组件异常，连续慢和最近检查时间更值得关注。" },
            { eyebrow: "刷新", title: "刷新后再判断是否持续异常", description: "这样可以避免把短暂抖动误判成持续性故障。" }
        ])}
        ${renderDashboardMetrics()}
        <div style="margin-top:16px;">
            ${panel("系统健康", `
                <div class="table-wrap">
                    <table>
                        <thead><tr><th>组件</th><th>状态</th><th>延迟</th><th>检查时间</th><th>详情</th></tr></thead>
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
                <div class="context-kicker">管理后台</div>
                <h1>评测中心</h1>
                <p class="subtitle">查看评测案例、发起评测运行，并查看召回率、MRR 和引用覆盖率。</p>
            </div>
        </div>
        ${renderGuideCards([
            { eyebrow: "案例", title: "案例必须带着明确预期", description: "一个好案例至少要说清问题、期望回答和它是否仍然有效。" },
            { eyebrow: "指标", title: "不要只看单一分数", description: "召回率、MRR 和引用覆盖率一起看，才能判断问题出在检索还是回答。" },
            { eyebrow: "实验", title: "运行名称最好对应实验假设", description: "这样回看评测结果时，才知道这一轮到底验证了什么改变。" }
        ])}
        <div class="metric-row">
            <div class="metric"><strong>${formatNumber(state.admin.evalCases.length)}</strong><span>评测案例</span></div>
            <div class="metric"><strong>${formatNumber(state.admin.evalResults.length)}</strong><span>评测结果</span></div>
            <div class="metric"><strong>${state.admin.evalRun ? escapeHtml(humanizeStatus(state.admin.evalRun.status || "RUNNING")) : "空闲"}</strong><span>运行状态</span></div>
            <div class="metric"><strong>${formatNumber(currentRouteSpaceId())}</strong><span>空间范围</span></div>
        </div>
        <div class="content-grid cols-2">
            ${panel("评测案例", `
                <form id="eval-case-form" data-space-id="${currentRouteSpaceId()}" class="inline-form">
                    <div class="field"><label>名称</label><input name="name" required></div>
                    <div class="field"><label>问题</label><textarea name="queryText" required></textarea></div>
                    <div class="field"><label>期望答案</label><textarea name="expectedAnswer"></textarea></div>
                    <button class="button" type="submit">创建评测案例</button>
                </form>
                <div class="list-stack" style="margin-top:14px;">
                    ${state.admin.evalCases.map((item) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(item.name)}</strong>${badge(item.enabled ? "ENABLED" : "DISABLED", item.enabled ? "已启用" : "已停用")}</div>
                            <div class="muted">${escapeHtml(item.queryText)}</div>
                        </div>
                    `).join("") || emptyState("暂无评测案例", "创建一个案例后再启动评测运行。")}
                </div>
            `, { subtitle: "每个案例都应该能清楚表达问题、期望回答和是否仍然有效。" })}
            ${panel("评测运行", `
                <form id="eval-run-form" data-space-id="${currentRouteSpaceId()}" class="inline-form">
                    <div class="field"><label>运行名称</label><input name="name" required></div>
                    <button class="button" type="submit">启动评测运行</button>
                </form>
                ${state.admin.evalRun ? `
                    <div class="list-item" style="margin-top:14px;">
                        <div class="list-item-header"><strong>${escapeHtml(state.admin.evalRun.name)}</strong>${badge(state.admin.evalRun.status)}</div>
                        <div class="muted">${escapeHtml(state.admin.evalRun.summaryJson || "暂无摘要")}</div>
                    </div>
                ` : ""}
                <div class="list-stack" style="margin-top:14px;">
                    ${state.admin.evalResults.map((result) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>案例 ${formatNumber(result.caseId)}</strong>${tag(`延迟 ${formatNumber(result.latencyMs)}ms`)}</div>
                            <div class="muted">召回率 ${result.recallAtK} / MRR ${result.mrr} / 引用覆盖率 ${result.citationCoverage}</div>
                        </div>
                    `).join("") || emptyState("暂无评测结果", "启动一次评测运行后，这里会展示结果。")}
                </div>
            `, { subtitle: "结果页要让人一眼分清质量、延迟和引用覆盖率，而不是只看一个总分。" })}
        </div>
    `;
}

function renderAdminLogsPage() {
    const llmLogs = flattenPageResponse(state.admin.llmLogs);
    const auditLogs = flattenPageResponse(state.admin.auditLogs);
    return `
        <div class="page-header">
            <div>
                <div class="context-kicker">管理后台</div>
                <h1>运行日志</h1>
                <p class="subtitle">集中查看模型调用日志、审计日志，以及按链路 ID 查询检索过程。</p>
            </div>
        </div>
        ${renderGuideCards([
            { eyebrow: "模型", title: "模型日志看调用成本和结果", description: "这里更适合回答模型调了多少、是否成功、消耗了多少 tokens。" },
            { eyebrow: "审计", title: "审计日志看操作路径", description: "它帮助你理解是谁在什么时候对哪个目标做了什么动作。" },
            { eyebrow: "链路", title: "检索链路用来串起全过程", description: "当回答质量异常时，链路信息能帮助你定位问题在检索、重排还是生成。" }
        ])}
        <div class="metric-row">
            <div class="metric"><strong>${formatNumber(llmLogs.length)}</strong><span>模型日志</span></div>
            <div class="metric"><strong>${formatNumber(auditLogs.length)}</strong><span>审计日志</span></div>
            <div class="metric"><strong>${state.admin.retrievalTrace ? "已加载" : "空闲"}</strong><span>链路查看器</span></div>
            <div class="metric"><strong>${formatNumber(state.user?.id)}</strong><span>操作人</span></div>
        </div>
        <div class="content-grid cols-2">
            ${panel("模型调用日志", `
                <div class="table-wrap">
                    <table>
                        <thead><tr><th>ID</th><th>场景</th><th>模型</th><th>Tokens</th><th>状态</th><th>时间</th></tr></thead>
                        <tbody>
                        ${llmLogs.map((log) => `
                            <tr>
                                <td class="mono">${formatNumber(log.id)}</td>
                                <td>${escapeHtml(log.scene)}</td>
                                <td>${escapeHtml(log.model)}</td>
                                <td>${formatNumber(log.totalTokens)}</td>
                                <td>${badge(log.success ? "SUCCESS" : "FAILED", log.success ? "成功" : "失败")}</td>
                                <td>${formatDate(log.createdAt)}</td>
                            </tr>
                        `).join("") || `<tr><td colspan="6">${emptyState("暂无模型日志", "当前条件没有日志记录。")}</td></tr>`}
                        </tbody>
                    </table>
                </div>
                <form id="retrieval-trace-form" class="inline-form" style="margin-top:14px;">
                    <div class="field"><label>检索链路 ID</label><input name="traceId" required></div>
                    <button class="ghost-button" type="submit">查看链路</button>
                </form>
            `, { subtitle: "把这块当作模型调用和检索诊断入口，而不是纯日志堆。" })}
            ${panel("审计日志", `
                <div class="table-wrap">
                    <table>
                        <thead><tr><th>ID</th><th>动作</th><th>目标</th><th>操作人</th><th>创建时间</th></tr></thead>
                        <tbody>
                        ${auditLogs.map((log) => `
                            <tr>
                                <td class="mono">${formatNumber(log.id)}</td>
                                <td>${escapeHtml(log.action)}</td>
                                <td>${escapeHtml(log.targetType)} / ${formatNumber(log.targetId)}</td>
                                <td>${formatNumber(log.operatorId)}</td>
                                <td>${formatDate(log.createdAt)}</td>
                            </tr>
                        `).join("") || `<tr><td colspan="5">${emptyState("暂无审计日志", "当前条件没有审计记录。")}</td></tr>`}
                        </tbody>
                    </table>
                </div>
                ${state.admin.retrievalTrace ? `<div class="list-item" style="margin-top:14px;"><div class="list-item-header"><strong>检索链路</strong></div>${renderJson(state.admin.retrievalTrace)}</div>` : ""}
            `, { subtitle: "审计信息和 Trace 放在一起，更容易串起一次完整的系统行为路径。" })}
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
    queueToast("空间已创建。", "success");
    await renderRoute();
}

async function handleCreateKnowledgeBase(form) {
    const payload = Object.fromEntries(new FormData(form).entries());
    await api.knowledge.create(currentRouteSpaceId(), payload);
    queueToast("知识库已创建。", "success");
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
        throw new ApiError("指定知识库会话必须填写至少一个知识库 ID。", { code: "KNOWLEDGE_BASE_SCOPE_REQUIRED" });
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
    queueToast("研究项目已创建。", "success");
    await renderRoute();
}

async function handleSourceFile(form) {
    const file = form.elements.file.files[0];
    const projectId = Number(form.dataset.projectId);
    if (!file) {
        return;
    }
    await api.personal.uploadSource(projectId, file, form.elements.title.value);
    queueToast("文件资料已提交导入。", "success");
    await renderRoute();
}

async function handleSourceUrl(form) {
    const projectId = Number(form.dataset.projectId);
    await api.personal.addUrlSource(projectId, Object.fromEntries(new FormData(form).entries()));
    queueToast("网址资料已创建。", "success");
    await renderRoute();
}

async function handleSourceText(form) {
    const projectId = Number(form.dataset.projectId);
    await api.personal.addTextSource(projectId, Object.fromEntries(new FormData(form).entries()));
    queueToast("文本资料已创建。", "success");
    await renderRoute();
}

function findSkill(skillId) {
    return state.studio.skills.find((skill) => skill.id === skillId) || null;
}

async function createArtifactTask(spaceId, projectId, skillId, topic, methodologyCardId = null) {
    const skill = findSkill(skillId);
    if (!skill) {
        throw new ApiError("未找到对应的工作室技能。", { code: "STUDIO_SKILL_NOT_FOUND" });
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
    queueToast("成果生成任务已启动。", "success");
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
    queueToast("成果已保存。", "success");
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
    queueToast("成果重新生成任务已启动。", "success");
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
    setDrawer("沉淀预览", `
        <div class="list-stack">
            <div class="list-item"><strong>${escapeHtml(preview.title || "综合卡预览")}</strong><div class="muted">${escapeHtml(preview.summary || "")}</div></div>
            ${preview.insights?.length ? `<div class="list-item"><strong>要点</strong><ul>${preview.insights.map((item) => `<li>${escapeHtml(item)}</li>`).join("")}</ul></div>` : ""}
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
    queueToast("成果已沉淀到个人 Wiki。", "success");
    setDrawer("沉淀结果", `
        <div class="list-item">
            <div class="list-item-header"><strong>${escapeHtml(result.title || "综合卡")}</strong>${badge(result.confirmed ? "SUCCESS" : "PENDING", result.confirmed ? "已确认" : "预览中")}</div>
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
    queueToast("成果已发布为 Wiki 草稿。", "success");
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
    queueToast("空间记忆已保存。", "success");
    await renderRoute();
}

async function saveUserMemory(form) {
    const payload = Object.fromEntries(new FormData(form).entries());
    payload.pin = false;
    await api.memory.upsertUser(payload);
    queueToast("用户记忆已保存。", "success");
    await renderRoute();
}

async function handleEvalCase(form) {
    const payload = Object.fromEntries(new FormData(form).entries());
    payload.enabled = true;
    await api.admin.createEvalCase(Number(form.dataset.spaceId), payload);
    queueToast("评测案例已创建。", "success");
    await renderRoute();
}

async function handleEvalRun(form) {
    const payload = Object.fromEntries(new FormData(form).entries());
    const run = await api.admin.startEvalRun(Number(form.dataset.spaceId), payload);
    state.admin.selectedEvalRunId = run.id;
    queueToast("评测运行已启动。", "success");
    await renderRoute();
}

async function handleRetrievalTrace(form) {
    const traceId = Number(new FormData(form).get("traceId"));
    state.admin.retrievalTrace = await api.admin.retrievalTrace(traceId);
    paint();
}

async function showHealthDetail(component) {
    const detail = await api.admin.healthComponent(component);
    setDrawer(`健康详情：${component}`, renderJson(detail));
}

function openCitation(citationKey) {
    const citation = state.registry.citations[citationKey];
    if (!citation) {
        return;
    }
    setDrawer("引用证据", `
        <div class="list-stack">
            <div class="list-item">
                <div class="list-item-header"><strong>${escapeHtml(citation.title || citation.sourceType || "引用")}</strong>${citation.pageNo ? tag(`第 ${citation.pageNo} 页`) : ""}</div>
                <div class="muted">${escapeHtml(citation.locationInfo || "无位置描述")}</div>
            </div>
            <div class="list-item">
                <strong>引文</strong>
                <div class="muted">${escapeHtml(citation.quoteText || "")}</div>
            </div>
            ${citation.startOffset !== undefined ? `<div class="list-item"><strong>偏移量</strong><div class="muted">${formatNumber(citation.startOffset)} - ${formatNumber(citation.endOffset)}</div></div>` : ""}
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
                queueToast("资料重新导入任务已触发。", "success");
                await renderRoute();
                break;
            case "source-compile":
                await api.personal.compileSource(Number(target.dataset.sourceId));
                queueToast("知识编译任务已触发。", "success");
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
                queueToast("空间记忆已删除。", "success");
                await renderRoute();
                break;
            case "delete-user-memory-item":
                await api.memory.deleteUserItem(Number(target.dataset.itemId));
                queueToast("用户记忆已删除。", "success");
                await renderRoute();
                break;
            case "disable-user-memory":
                await api.memory.disableWriteback();
                queueToast("用户记忆写入已关闭。", "success");
                await renderRoute();
                break;
            case "enable-user-memory":
                await api.memory.enableWriteback();
                queueToast("用户记忆写入已开启。", "success");
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

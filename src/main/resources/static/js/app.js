import { api, ApiError, clearAuthState, loadAuthState, saveAuthState } from "./api.js";
import { md5File } from "./md5.js";
import { renderMarkdown } from "./markdown.js";

const root = document.getElementById("app");
const CHUNK_SIZE = 1024 * 1024;
const FINAL_TASK_STATUSES = new Set(["SUCCESS", "FAILED", "CANCELLED", "TIMEOUT"]);
const FORM_MODAL_TITLES = {
    "create-space-form": "创建空间",
    "create-session-form": "新建会话",
    "create-kb-form": "创建知识库",
    "upload-document-form": "上传文档",
    "kb-search-form": "检索测试",
    "create-project-form": "创建研究项目",
    "source-file-form": "上传资料",
    "source-url-form": "添加链接资料",
    "source-text-form": "添加文本资料",
    "project-generate-form": "生成成果",
    "studio-task-form": "启动生成任务",
    "chat-artifact-form": "通过对话生成成果",
    "graph-filter-form": "筛选图谱",
    "artifact-edit-form": "编辑成果",
    "distill-artifact-form": "沉淀到个人 Wiki",
    "publish-artifact-wiki-form": "发布到团队 Wiki",
    "wiki-edit-form": "编辑 Wiki",
    "wiki-create-form": "创建 Wiki 草稿",
    "space-memory-form": "写入空间记忆",
    "user-memory-form": "写入用户记忆",
    "admin-task-filter-form": "筛选任务",
    "eval-case-form": "创建评测案例",
    "eval-run-form": "启动评测运行",
    "retrieval-trace-form": "查看检索追踪"
};
const INLINE_FORM_MODAL_EXCLUDES = new Set([
    "login-form",
    "register-form",
    "chat-message-form",
    "kb-search-form",
    "wiki-search-form",
    "graph-filter-form",
    "admin-task-filter-form",
    "retrieval-trace-form"
]);

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
        selectedSpacePreviewId: null,
        contextRailCollapsed: false
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
        questionsByProject: {},
        selectedQuestionIdByProject: {},
        questionWorkspaceById: {},
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
        relations: null,
        pageGraph: null,
        spaceGraph: null,
        searchResult: null
    },
    graph: {
        spaceGraph: null,
        selectedNodeId: null,
        nodeDetail: null,
        neighborhood: null,
        path: null,
        filters: {
            nodeTypes: "",
            edgeTypes: "",
            onlyPublished: false,
            onlyIndexed: false
        }
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
let lastRenderedLocation = `${window.location.pathname}${window.location.search}`;
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
        DRAFT: "草稿",
        DRAFT_ACTIVE: "草稿",
        CONVERTED: "已转换",
        DISCARDED: "已丢弃",
        IMPORTING: "导入中",
        IMPORTED: "已导入",
        COMPILING: "编译中",
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
        DISCONNECTED: "已断开",
        UNKNOWN: "未知"
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

function humanizeArtifactType(value) {
    const raw = String(value ?? "").toUpperCase();
    return {
        REPORT: "研究报告",
        STUDY_GUIDE: "学习指南",
        READING_NOTES: "阅读笔记",
        BRIEFING: "简报",
        FAQ: "问答",
        COMPARISON: "对比分析",
        WORK_PREP: "工作准备",
        WIKI_DRAFT: "Wiki 草稿"
    }[raw] || raw || "成果";
}

function humanizeGraphNodeType(value) {
    const raw = String(value ?? "").trim();
    const key = raw.toUpperCase().replace(/[-\s]+/g, "_");
    const labels = {
        WIKI_PAGE: "Wiki 页面",
        DOCUMENT: "文档",
        SOURCE: "资料源",
        ARTIFACT: "成果",
        CONCEPT_CARD: "概念卡",
        SYNTHESIS_CARD: "综合卡",
        METHODOLOGY_CARD: "方法卡",
        NOTE: "笔记",
        MEMORY: "记忆",
        NODE: "节点",
        ROOT: "入口节点"
    };
    return labels[key] || raw || "节点";
}

function humanizeGraphEdgeType(value) {
    const raw = String(value ?? "").trim();
    const key = raw.toUpperCase().replace(/[-\s]+/g, "_");
    const labels = {
        LINKS_TO: "关联",
        LINK: "关联",
        CITES: "引用",
        CITED_BY: "被引用",
        GENERATES: "生成",
        GENERATED_FROM: "由资料生成",
        SOURCE: "来源",
        SOURCE_TO_WIKI: "资料生成 Wiki",
        WIKI_LINK: "Wiki 关联",
        WIKI_CITES_DOCUMENT: "Wiki 引用文档",
        AUTO_MAINTAINED_FROM_DOCUMENT: "文档自动维护",
        AUTO_MAINTAINED_FROM_SOURCE: "资料自动维护",
        AUTO_MAINTAINED: "自动维护",
        PUBLISHED_FROM_ARTIFACT: "由成果发布",
        PUBLISHED_FROM_WIKI: "由 Wiki 发布",
        RELATED_TO: "相关",
        DERIVES_FROM: "派生自",
        CONTAINS: "包含",
        BELONGS_TO: "属于",
        RELATION: "关系"
    };
    return labels[key] || raw.replace(/[_-]+/g, " ") || "关系";
}

function humanizeSourceType(value) {
    const raw = String(value ?? "").trim();
    const key = raw.toUpperCase().replace(/[-\s]+/g, "_");
    const labels = {
        FILE: "文件",
        URL: "链接",
        TEXT: "文本",
        DOCUMENT: "文档",
        WIKI_PAGE: "Wiki 页面",
        CHAT_MESSAGE: "对话消息",
        ARTIFACT: "成果",
        MANUAL: "手动创建",
        SOURCE: "资料源"
    };
    return labels[key] || raw || "资料";
}

function humanizeTaskType(value) {
    const raw = String(value ?? "").trim();
    const key = raw.toUpperCase().replace(/[-\s]+/g, "_");
    const labels = {
        DOCUMENT_PARSE: "文档解析",
        DOCUMENT_INDEX: "文档索引",
        KNOWLEDGE_IMPORT: "知识导入",
        SOURCE_IMPORT: "资料导入",
        SOURCE_COMPILE: "资料编译",
        ARTIFACT_GENERATE: "成果生成",
        WIKI_GENERATE: "Wiki 生成",
        WIKI_MAINTAIN: "Wiki 维护",
        EMBEDDING: "向量化",
        EVALUATION: "评测",
        CHAT: "对话"
    };
    return labels[key] || raw.replace(/[_-]+/g, " ") || "任务";
}

function humanizeTargetType(value) {
    const raw = String(value ?? "").trim();
    const key = raw.toUpperCase().replace(/[-\s]+/g, "_");
    const labels = {
        SPACE: "空间",
        KNOWLEDGE_BASE: "知识库",
        DOCUMENT: "文档",
        SOURCE: "资料源",
        PROJECT: "项目",
        ARTIFACT: "成果",
        WIKI_PAGE: "Wiki 页面",
        SESSION: "会话"
    };
    return labels[key] || raw.replace(/[_-]+/g, " ") || "目标";
}

function normalizeGraphFilterTypes(value, kind) {
    const nodeLabels = {
        "wiki": "WIKI_PAGE",
        "wiki页面": "WIKI_PAGE",
        "页面": "WIKI_PAGE",
        "文档": "DOCUMENT",
        "资料": "SOURCE",
        "资料源": "SOURCE",
        "成果": "ARTIFACT",
        "概念卡": "CONCEPT_CARD",
        "综合卡": "SYNTHESIS_CARD",
        "方法卡": "METHODOLOGY_CARD"
    };
    const edgeLabels = {
        "关联": "LINKS_TO",
        "引用": "CITES",
        "生成": "GENERATES",
        "来源": "SOURCE",
        "资料生成wiki": "SOURCE_TO_WIKI",
        "自动维护": "AUTO_MAINTAINED_FROM_DOCUMENT"
    };
    const labels = kind === "edge" ? edgeLabels : nodeLabels;
    return String(value || "")
        .split(/[,，、\s]+/)
        .map((token) => {
            const trimmed = token.trim();
            const normalized = trimmed.toLowerCase().replace(/\s+/g, "");
            return labels[normalized] || trimmed;
        })
        .filter(Boolean)
        .join(",");
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
    return `<div class="error-state" role="alert" aria-live="assertive"><strong>${escapeHtml(message)}</strong><div class="muted">错误码：${escapeHtml(code)}</div></div>`;
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

async function openArtifactById(artifactId, preferredSpaceId = null) {
    const normalizedArtifactId = Number(artifactId);
    let spaceId = Number(preferredSpaceId) || resolveArtifactSpaceIdById(normalizedArtifactId);
    if (!spaceId) {
        const artifact = await api.artifacts.get(normalizedArtifactId);
        if (artifact) {
            state.artifacts.detail = artifact;
            spaceId = resolveArtifactSpaceId(artifact);
        }
    }
    navigate(routeLink("artifact-detail", spaceId || currentRouteSpaceId(), normalizedArtifactId));
}

function isPersonalResearchArtifact(artifact = state.artifacts.detail) {
    return Boolean(
        artifact?.researchProjectId &&
        personalSpaceId() &&
        Number(artifact.spaceId) === Number(personalSpaceId())
    );
}

function hasTraceableArtifactCitations(artifact = state.artifacts.detail) {
    return Array.isArray(artifact?.citations) && artifact.citations.length > 0;
}

function canDistillArtifactToPersonalWiki(artifact = state.artifacts.detail) {
    return isPersonalResearchArtifact(artifact) && hasTraceableArtifactCitations(artifact);
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
        { name: "graph", regex: /^\/spaces\/(\d+)\/graph$/ },
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
    state.ui.drawer = null;
    if (replace) {
        window.history.replaceState({}, "", path);
    } else {
        window.history.pushState({}, "", path);
    }
    renderRoute();
}

function resetRouteScroll() {
    window.scrollTo({ top: 0, left: 0 });
    document.documentElement.scrollTop = 0;
    document.body.scrollTop = 0;
    root.querySelectorAll(".main-canvas, .main-canvas-body, .page-body").forEach((element) => {
        element.scrollTop = 0;
        element.scrollLeft = 0;
    });
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
        case "graph":
            return `/spaces/${spaceId}/graph`;
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
        graph: {
            eyebrow: "图谱",
            title: "知识图谱",
            detail: "从统一知识图谱查看 Wiki、文档、成果与引用之间的关系。"
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
        questionsByProject: {},
        selectedQuestionIdByProject: {},
        questionWorkspaceById: {},
        sourcesByProject: {},
        articleCardsByProject: {},
        conceptCardsByProject: {},
        synthesisCardsByProject: {},
        methodologyByProject: {},
        selectedSynthesisProjectId: null
    };
    state.studio = { skills: [], lastTask: null };
    state.artifacts = { list: [], detail: null, relations: [], editorDraft: null, distillPreview: null };
    state.wiki = { pages: [], selectedPageId: null, pageDetail: null, versions: [], relations: null, pageGraph: null, spaceGraph: null, searchResult: null };
    state.graph = {
        spaceGraph: null,
        selectedNodeId: null,
        nodeDetail: null,
        neighborhood: null,
        path: null,
        filters: { nodeTypes: "", edgeTypes: "", onlyPublished: false, onlyIndexed: false }
    };
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

function isChatRoute(route = state.route) {
    return route?.name === "team-chat" || route?.name === "workbench-chat";
}

function isTransientFetchError(error) {
    return error?.name === "TypeError" && /Failed to fetch|NetworkError|Load failed/i.test(error.message || "");
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

function closeInlineFormDrawer() {
    if (state.ui.drawer?.source === "inline-form") {
        state.ui.drawer = null;
    }
}

function ensureLocalDraft(sessionId) {
    if (!state.chat.draftsBySession[sessionId]) {
        state.chat.draftsBySession[sessionId] = "";
    }
}

function artifactCreationSkills() {
    return (state.studio.skills || []).filter((skill) => skill.entryType !== "mcp_tool");
}

async function ensureArtifactActionCatalog() {
    const [skillsResult, projectsResult] = await Promise.allSettled([
        state.studio.skills?.length ? Promise.resolve(state.studio.skills) : api.studio.listSkills(),
        state.personal.projects?.length ? Promise.resolve(state.personal.projects) : api.personal.listProjects()
    ]);
    if (skillsResult.status === "fulfilled") {
        state.studio.skills = skillsResult.value || [];
    }
    if (projectsResult.status === "fulfilled") {
        state.personal.projects = projectsResult.value || [];
    }
}

function renderChatArtifactDialogHtml(session) {
    const skills = artifactCreationSkills();
    const projects = state.personal.projects || [];
    if (!skills.length || !projects.length) {
        return `
            <div class="list-stack">
                ${emptyState(
                    !projects.length ? "还没有个人研究项目" : "还没有可用成果技能",
                    !projects.length ? "先到个人研究创建项目，再从聊天里发起成果生成。" : "当前工作室没有可用于个人成果的技能。"
                )}
            </div>
        `;
    }
    return `
        <div class="chat-artifact-dialog">
            <div class="chat-artifact-note">
                <span>对话执行</span>
                <strong>提交后会向当前会话发送一条 /成果 指令，由聊天工作流创建成果任务并返回可打开的成果。</strong>
                <em>${escapeHtml(session?.title || "当前会话")}</em>
            </div>
            <form id="chat-artifact-form" class="inline-form modal-inline-form">
                <div class="field-grid cols-2">
                    <div class="field">
                        <label>成果技能</label>
                        <select name="skillId" required>
                            ${skills.map((skill) => `<option value="${escapeHtml(skill.id)}">${escapeHtml(skill.name)} · ${escapeHtml(humanizeArtifactType(skill.artifactType))}</option>`).join("")}
                        </select>
                    </div>
                    <div class="field">
                        <label>研究项目</label>
                        <select name="projectId" required>
                            ${projects.map((project) => `<option value="${project.id}">${escapeHtml(project.title)}</option>`).join("")}
                        </select>
                    </div>
                </div>
                <div class="field">
                    <label>生成主题</label>
                    <input name="topic" required placeholder="例如：基于检索链路生成一份问题定位报告">
                </div>
                <button class="button" type="submit">交给当前会话生成</button>
            </form>
        </div>
    `;
}

async function openChatArtifactDialog() {
    const session = activeSession();
    if (!session) {
        queueToast("请先选择一个会话。", "error");
        return;
    }
    await ensureArtifactActionCatalog();
    state.ui.drawer = {
        title: "通过对话生成成果",
        source: "inline-form",
        html: renderChatArtifactDialogHtml(session)
    };
    paint();
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
    try {
        state.chat.artifactsBySession[sessionId] = await api.artifacts.listBySession(sessionId);
    } catch (error) {
        state.chat.artifactsBySession[sessionId] = state.chat.artifactsBySession[sessionId] || [];
    }
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
    state.personal.questionsByProject[projectId] = await api.personal.listQuestions(projectId);
    state.personal.sourcesByProject[projectId] = await api.personal.sources(projectId);
    state.personal.articleCardsByProject[projectId] = await api.personal.articleCards(projectId);
    state.personal.conceptCardsByProject[projectId] = await api.personal.conceptCards(projectId);
    state.personal.synthesisCardsByProject[projectId] = await api.personal.synthesisCards(projectId);
    state.personal.methodologyByProject[projectId] = await api.personal.methodologyCards(projectId);
    const questions = state.personal.questionsByProject[projectId] || [];
    const selectedQuestionId = Number(state.personal.selectedQuestionIdByProject[projectId]);
    if (!questions.some((question) => Number(question.id) === selectedQuestionId)) {
        state.personal.selectedQuestionIdByProject[projectId] = questions[0] ? Number(questions[0].id) : null;
    }
    if (state.personal.selectedQuestionIdByProject[projectId]) {
        await loadProjectQuestionWorkspace(projectId, state.personal.selectedQuestionIdByProject[projectId], true);
    }
}

async function loadProjectQuestionWorkspace(projectId, questionId, force = false) {
    const normalizedQuestionId = Number(questionId);
    if (!normalizedQuestionId) {
        return null;
    }
    state.personal.selectedQuestionIdByProject[projectId] = normalizedQuestionId;
    if (!force && state.personal.questionWorkspaceById[normalizedQuestionId]) {
        return state.personal.questionWorkspaceById[normalizedQuestionId];
    }
    const workspace = await api.personal.questionWorkspace(normalizedQuestionId);
    state.personal.questionWorkspaceById[normalizedQuestionId] = workspace;
    return workspace;
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
    const [pages, spaceGraph] = await Promise.all([
        api.wiki.list(spaceId),
        api.wiki.spaceGraph(spaceId)
    ]);
    state.wiki.pages = pages;
    state.wiki.spaceGraph = spaceGraph;
    if (state.wiki.selectedPageId && !state.wiki.pages.some((page) => Number(page.id) === Number(state.wiki.selectedPageId))) {
        state.wiki.selectedPageId = null;
    }
    if (!state.wiki.selectedPageId && state.wiki.pages[0]) {
        state.wiki.selectedPageId = Number(state.wiki.pages[0].id);
    }
    if (state.wiki.selectedPageId) {
        const [detail, versions, relations, pageGraph] = await Promise.all([
            api.wiki.get(state.wiki.selectedPageId),
            api.wiki.versions(state.wiki.selectedPageId),
            api.wiki.relations(state.wiki.selectedPageId),
            api.wiki.pageGraph(state.wiki.selectedPageId, { depth: 1 })
        ]);
        state.wiki.pageDetail = detail;
        state.wiki.versions = versions;
        state.wiki.relations = relations;
        state.wiki.pageGraph = pageGraph;
    } else {
        state.wiki.pageDetail = null;
        state.wiki.versions = [];
        state.wiki.relations = null;
        state.wiki.pageGraph = null;
    }
}

function graphQueryParams() {
    const filters = state.graph.filters || {};
    return {
        nodeTypes: filters.nodeTypes || undefined,
        edgeTypes: filters.edgeTypes || undefined,
        onlyPublished: filters.onlyPublished || undefined,
        onlyIndexed: filters.onlyIndexed || undefined
    };
}

function normalizeGraphNode(node = {}) {
    const id = node.id ?? node.nodeId ?? node.refId ?? "";
    return {
        ...node,
        id: String(id),
        type: String(node.type || node.nodeType || "NODE"),
        title: node.title || node.name || String(id || "Graph node"),
        status: node.status || node.indexStatus || ""
    };
}

function normalizeGraphEdge(edge = {}) {
    return {
        ...edge,
        sourceId: String(edge.sourceId ?? edge.sourceNodeId ?? edge.fromNodeId ?? ""),
        targetId: String(edge.targetId ?? edge.targetNodeId ?? edge.toNodeId ?? ""),
        type: String(edge.type || edge.edgeType || "RELATION")
    };
}

function normalizeGraphResponse(graph) {
    if (!graph) {
        return { nodes: [], edges: [], nodeCount: 0, edgeCount: 0, rootNodeId: "" };
    }
    const nodes = (graph.nodes || []).map(normalizeGraphNode);
    const edges = (graph.edges || []).map(normalizeGraphEdge);
    return {
        ...graph,
        nodes,
        edges,
        nodeCount: graph.nodeCount ?? nodes.length,
        edgeCount: graph.edgeCount ?? edges.length,
        rootNodeId: graph.rootNodeId ? String(graph.rootNodeId) : ""
    };
}

async function loadGraphPage(spaceId) {
    setCurrentSpace(spaceId);
    state.graph.spaceGraph = normalizeGraphResponse(await api.graph.space(spaceId, graphQueryParams()));
    if (state.graph.selectedNodeId && !((state.graph.spaceGraph?.nodes || []).some((node) => node.id === state.graph.selectedNodeId))) {
        state.graph.selectedNodeId = null;
        state.graph.nodeDetail = null;
        state.graph.neighborhood = null;
    }
}

async function loadMemoryPage(spaceId) {
    setCurrentSpace(spaceId);
    state.memory.spaceMemory = await api.memory.space(spaceId);
    state.memory.userMemory = await api.memory.user();
    state.chat.sessions = await api.chat.listSessions(spaceId);
    if (!state.memory.selectedSessionId && state.chat.sessions[0]) {
        state.memory.selectedSessionId = Number(state.chat.sessions[0].id);
    }
    if (state.memory.selectedSessionId) {
        state.memory.sessionSummaries = await api.memory.sessionSummaries(state.memory.selectedSessionId);
    }
    await loadMemoryPersonalContext();
}

async function loadMemoryPersonalContext() {
    try {
        state.personal.projects = await api.personal.listProjects();
    } catch (error) {
        if (!(error instanceof ApiError) || (error.status !== 403 && error.status !== 404)) {
            throw error;
        }
        state.personal.projects = [];
    }

    const availableProjectIds = new Set(state.personal.projects.map((project) => Number(project.id)));
    if (!availableProjectIds.has(Number(state.personal.selectedSynthesisProjectId))) {
        state.personal.selectedSynthesisProjectId = state.personal.projects[0]
            ? Number(state.personal.projects[0].id)
            : null;
    }

    if (!state.personal.selectedSynthesisProjectId) {
        return;
    }

    state.personal.synthesisCardsByProject[state.personal.selectedSynthesisProjectId] =
        await api.personal.synthesisCards(state.personal.selectedSynthesisProjectId);
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
        case "graph":
            await loadGraphPage(route.spaceId);
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
    enhanceInlineForms();
    enhanceResponsiveTables();
}

function enhanceInlineForms() {
    root.querySelectorAll("form.inline-form").forEach((form) => {
        if (!(form instanceof HTMLFormElement) || !form.id) {
            return;
        }
        if (INLINE_FORM_MODAL_EXCLUDES.has(form.id) || form.closest(".modal-dialog")) {
            return;
        }
        const formId = form.id;
        const title = FORM_MODAL_TITLES[formId] || "打开操作";
        const entry = document.createElement("div");
        entry.className = "form-modal-entry";
        entry.innerHTML = `
            <div class="form-modal-entry-copy">
                <strong>${escapeHtml(title)}</strong>
                <span>${escapeHtml(form.dataset.modalHint || "弹窗表单")}</span>
            </div>
            <button class="button" type="button" data-action="open-inline-form" data-form-id="${escapeHtml(formId)}" aria-label="${escapeHtml(title)}">打开</button>
        `;
        form.hidden = true;
        form.setAttribute("aria-hidden", "true");
        form.dataset.originalId = formId;
        form.removeAttribute("id");
        form.before(entry);
    });
}

function enhanceResponsiveTables() {
    root.querySelectorAll(".table-wrap table").forEach((table) => {
        const headers = Array.from(table.querySelectorAll("thead th"))
            .map((header) => header.textContent.trim());
        table.querySelectorAll("tbody tr").forEach((row) => {
            Array.from(row.children).forEach((cell, index) => {
                if (cell instanceof HTMLTableCellElement && headers[index]) {
                    cell.dataset.label = headers[index];
                }
            });
        });
    });
}

function cloneFormForModal(form) {
    const clone = form.cloneNode(true);
    const formId = form.dataset.originalId || form.id;
    if (formId) {
        clone.id = formId;
        delete clone.dataset.originalId;
    }
    clone.hidden = false;
    clone.removeAttribute("hidden");
    clone.removeAttribute("aria-hidden");
    clone.classList.add("modal-inline-form");
    return clone.outerHTML;
}

function openInlineFormModal(trigger) {
    const formId = trigger?.dataset?.formId;
    if (!formId) {
        return;
    }
    const entry = trigger.closest(".form-modal-entry");
    const form = entry?.nextElementSibling instanceof HTMLFormElement
        ? entry.nextElementSibling
        : Array.from(root.querySelectorAll("form.inline-form")).find((item) =>
            (item.id === formId || item.dataset.originalId === formId) && !item.closest(".modal-dialog"));
    if (!(form instanceof HTMLFormElement)) {
        return;
    }
    const title = FORM_MODAL_TITLES[form.dataset.originalId || form.id] || "打开操作";
    state.ui.drawer = {
        title,
        html: `<div class="modal-form-shell">${cloneFormForModal(form)}</div>`,
        source: "inline-form"
    };
    paint();
}

async function renderRoute() {
    const version = ++navigationVersion;
    const nextLocation = `${window.location.pathname}${window.location.search}`;
    const shouldResetScroll = nextLocation !== lastRenderedLocation;
    lastRenderedLocation = nextLocation;
    state.route = parseRoute(window.location.pathname);
    state.ui.pageError = null;
    state.ui.isPageLoading = true;
    if (!isChatRoute(state.route)) {
        closeSocket();
    }
    paint();
    if (shouldResetScroll) {
        resetRouteScroll();
    }

    try {
        await bootstrapRoute(state.route);
        if (version !== navigationVersion) {
            return;
        }
        state.ui.isPageLoading = false;
        state.ui.pageError = null;
        paint();
        if (shouldResetScroll) {
            resetRouteScroll();
        }
        if (isChatRoute(state.route)) {
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
        if (shouldResetScroll) {
            resetRouteScroll();
        }
    }
}

function buildApp() {
    const route = state.route;
    if (isPublicRoute(route)) {
        return `${renderPublicRoute(route)}${renderToasts()}`;
    }
    const mode = routeMode(route);
    const inspectorOpen = Boolean(state.ui.drawer);
    const contextCollapsed = state.ui.contextRailCollapsed;

    return `
        <div class="workbench-shell workbench-mode-${mode} ${inspectorOpen ? "inspector-open" : "inspector-collapsed"} ${contextCollapsed ? "context-rail-collapsed" : ""}">
            ${renderGlobalRail(route)}
            ${renderContextRail(route)}
            <main class="main-canvas">
                ${renderTopbar()}
                <div class="main-canvas-body ${mode === "chat" ? "main-canvas-body-chat" : ""}">
                    ${renderPageContent(route)}
                </div>
            </main>
        </div>
        ${renderModalInspector()}
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

function routeMode(route = state.route) {
    if (route?.name === "team-chat" || route?.name === "workbench-chat") {
        return "chat";
    }
    if (route?.name === "workbench-studio") {
        return "studio";
    }
    if (route?.name === "wiki") {
        return "wiki";
    }
    if (route?.name === "graph") {
        return "graph";
    }
    if (route?.name === "artifact-detail" || route?.name === "artifacts") {
        return "artifacts";
    }
    if (route?.name === "memory") {
        return "memory";
    }
    if (route?.name?.startsWith("admin")) {
        return "admin";
    }
    if (route?.name?.startsWith("project")) {
        return "personal";
    }
    if (route?.name?.startsWith("knowledge")) {
        return "knowledge";
    }
    return "workspace";
}

const NAV_ICONS = {
    sidebar: `<rect x="3" y="4" width="18" height="16" rx="2"></rect><path d="M9 4v16"></path>`,
    collapse: `<path d="m15 18-6-6 6-6"></path>`,
    expand: `<path d="m9 18 6-6-6-6"></path>`,
    spaces: `<path d="M3 10.5 12 4l9 6.5V21a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1V10.5Z"></path>`,
    knowledge: `<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path><path d="M4 4.5A2.5 2.5 0 0 1 6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15Z"></path>`,
    research: `<circle cx="11" cy="8" r="4"></circle><path d="M4 22a7 7 0 0 1 14 0"></path><path d="m18.5 14.5 3 3"></path><circle cx="17" cy="13" r="2.5"></circle>`,
    chat: `<path d="M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4v8Z"></path>`,
    wiki: `<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z"></path><path d="M14 2v6h6"></path><path d="M8 13h8"></path><path d="M8 17h6"></path>`,
    graph: `<circle cx="6" cy="6" r="3"></circle><circle cx="18" cy="6" r="3"></circle><circle cx="12" cy="18" r="3"></circle><path d="m8.5 8.5 2.2 6"></path><path d="m15.5 8.5-2.2 6"></path><path d="M9 6h6"></path>`,
    artifacts: `<path d="m12 2 9 5-9 5-9-5 9-5Z"></path><path d="m3 12 9 5 9-5"></path><path d="m3 17 9 5 9-5"></path>`,
    memory: `<path d="M12 5a3 3 0 0 0-5.2-2 3 3 0 0 0-1.7 5A3 3 0 0 0 5 14a3 3 0 0 0 3 5h4"></path><path d="M12 5a3 3 0 0 1 5.2-2 3 3 0 0 1 1.7 5A3 3 0 0 1 19 14a3 3 0 0 1-3 5h-4"></path><path d="M12 5v14"></path>`,
    tasks: `<path d="M9 11l3 3L22 4"></path><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"></path>`,
    health: `<path d="M22 12h-4l-3 9L9 3l-3 9H2"></path>`,
    evaluation: `<circle cx="12" cy="12" r="9"></circle><circle cx="12" cy="12" r="3"></circle>`,
    logs: `<path d="M8 6h13"></path><path d="M8 12h13"></path><path d="M8 18h13"></path><path d="M3 6h.01"></path><path d="M3 12h.01"></path><path d="M3 18h.01"></path>`
};

function navIcon(name, fallback) {
    const icon = NAV_ICONS[name];
    if (!icon) {
        return `<span class="global-rail-letter">${escapeHtml(fallback || "")}</span>`;
    }
    return `<svg class="global-rail-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">${icon}</svg>`;
}

function renderGlobalRail(route) {
    const spaceId = currentRouteSpaceId();
    const personalNavSpaceId = personalSpaceId();
    const adminVisible = state.user?.systemRole === "ADMIN";
    const item = ({ label, glyph, icon, target, active }) => target ? `
        <a class="global-rail-item ${active ? "active" : ""}" href="${target}" data-nav="${target}" title="${escapeHtml(label)}" aria-label="${escapeHtml(label)}">
            ${navIcon(icon, glyph)}
        </a>
    ` : "";

    return `
        <aside class="global-rail" aria-label="全局导航">
            <button class="global-rail-brand" type="button" data-nav="${spaceId ? routeLink("chat", spaceId) : "/spaces"}" title="NoteWeave">NW</button>
            <nav class="global-rail-nav">
                ${item({ label: "空间首页", icon: "spaces", glyph: "S", target: "/spaces", active: route.name === "spaces" })}
                ${item({ label: "团队知识", icon: "knowledge", glyph: "K", target: spaceId ? routeLink("knowledge", spaceId) : "", active: route.name.startsWith("knowledge") })}
                ${item({ label: "个人研究", icon: "research", glyph: "P", target: personalNavSpaceId ? routeLink("projects", personalNavSpaceId) : "", active: route.name.startsWith("project") || route.name === "projects" })}
                ${item({ label: "对话工作台", icon: "chat", glyph: "C", target: spaceId ? routeLink("chat", spaceId) : "", active: routeMode(route) === "chat" })}
                ${item({ label: "Wiki", icon: "wiki", glyph: "W", target: spaceId ? routeLink("wiki", spaceId) : "", active: route.name === "wiki" })}
                ${item({ label: "知识图谱", icon: "graph", glyph: "G", target: spaceId ? routeLink("graph", spaceId) : "", active: route.name === "graph" })}
                ${item({ label: "成果", icon: "artifacts", glyph: "A", target: spaceId ? routeLink("artifacts", spaceId) : "", active: route.name === "artifacts" || route.name === "artifact-detail" })}
                ${item({ label: "记忆", icon: "memory", glyph: "M", target: spaceId ? routeLink("memory", spaceId) : "", active: route.name === "memory" })}
            </nav>
            ${adminVisible ? `
                <nav class="global-rail-nav global-rail-admin">
                    ${item({ label: "任务管理", icon: "tasks", glyph: "T", target: "/admin/tasks", active: route.name === "admin-tasks" })}
                    ${item({ label: "系统健康", icon: "health", glyph: "H", target: "/admin/health", active: route.name === "admin-health" })}
                    ${item({ label: "评测中心", icon: "evaluation", glyph: "E", target: "/admin/evaluation", active: route.name === "admin-evaluation" })}
                    ${item({ label: "系统日志", icon: "logs", glyph: "L", target: "/admin/logs", active: route.name === "admin-logs" })}
                </nav>
            ` : ""}
        </aside>
    `;
}

function renderContextRail(route) {
    const descriptor = routeDescriptor(route);
    const collapsed = state.ui.contextRailCollapsed;
    return `
        <aside class="context-rail" aria-label="上下文侧边栏">
            <div class="context-rail-shellbar">
                <button class="context-rail-toggle" type="button" data-action="toggle-context-rail" aria-expanded="${collapsed ? "false" : "true"}" aria-label="${collapsed ? "展开侧边栏" : "收起侧边栏"}" title="${collapsed ? "展开侧边栏" : "收起侧边栏"}">
                    ${navIcon(collapsed ? "expand" : "collapse", collapsed ? ">" : "<")}
                </button>
                <span class="context-rail-mini-title">${escapeHtml(descriptor.eyebrow)}</span>
            </div>
            <div class="context-rail-content">
                ${renderContextRailContent(route)}
            </div>
        </aside>
    `;
}

function renderContextRailContent(route) {
    if (route.name === "team-chat" || route.name === "workbench-chat") {
        return renderChatContextRail();
    }
    if (route.name === "wiki") {
        return renderWikiContextRail();
    }
    if (route.name === "graph") {
        return renderGraphContextRail();
    }
    return renderDefaultContextRail(route);
}

function renderChatContextRail() {
    const session = activeSession();
    const sessionId = session?.id;
    const artifacts = sessionId ? (state.chat.artifactsBySession[sessionId] || []) : [];

    return `
        <div class="context-rail-header">
            <span class="context-kicker">侧栏</span>
            <h2>会话</h2>
            <p>聊天记录与关联成果。</p>
        </div>
        <div class="context-rail-section">
            <div class="context-rail-section-title">
                <span>会话</span>
                ${tag(`${formatNumber(state.chat.sessions.length)} 个`)}
            </div>
            <form id="create-session-form" class="inline-form context-create-form">
                <div class="field">
                    <label>会话标题</label>
                    <input name="title" placeholder="例如：新用户激活证据核对" required>
                </div>
                <div class="field-grid cols-2">
                    <div class="field">
                        <label>类型</label>
                        <select name="sessionKind">
                            <option value="FORMAL">正式</option>
                            <option value="DRAFT">草稿</option>
                        </select>
                    </div>
                    <div class="field">
                        <label>范围</label>
                        <select name="scopeType">
                            <option value="SPACE">空间</option>
                            <option value="KNOWLEDGE_BASE">知识库</option>
                        </select>
                    </div>
                </div>
                <div class="field">
                    <label>知识库 IDs</label>
                    <input name="scopeIds" placeholder="指定知识库时填写，逗号分隔">
                </div>
                <button class="button context-action-button" type="submit">新建会话</button>
            </form>
            <div class="context-list session-tree">
                ${state.chat.sessions.map((item) => `
                    <button class="context-list-item session-item ${Number(item.id) === Number(sessionId) ? "active" : ""}" type="button" data-action="select-session" data-session-id="${item.id}">
                        <span class="context-list-title">${escapeHtml(item.title)}</span>
                        <span class="context-list-meta">${escapeHtml(humanizeSessionKind(item.sessionKind))} / ${escapeHtml(humanizeScopeType(item.scopeType))}</span>
                        <span class="context-list-meta">${formatDate(item.updatedAt || item.lastActiveAt)}</span>
                        ${badge(item.runtimeStatus || item.status, item.runtimeStatus || item.status)}
                    </button>
                `).join("") || emptyState("还没有会话", "创建一个正式会话或草稿会话开始提问。")}
            </div>
        </div>
        <div class="context-rail-section">
            <div class="context-rail-section-title">
                <span>当前会话相关成果</span>
                ${tag(`${formatNumber(artifacts.length)} 个`)}
            </div>
            <div class="context-list">
                ${artifacts.map((artifact) => `
                    <article class="context-list-item">
                        <span class="context-list-title">${escapeHtml(artifact.title)}</span>
                        <span class="context-list-meta">${escapeHtml(humanizeArtifactType(artifact.artifactType))} / ${escapeHtml(humanizeStatus(artifact.status))}</span>
                        <button class="ghost-button" type="button" data-action="open-artifact" data-artifact-id="${artifact.id}" data-space-id="${artifact.spaceId || currentRouteSpaceId()}">打开成果</button>
                    </article>
                `).join("") || emptyState("暂无成果", "聊天生成或关联的成果会在这里出现。")}
            </div>
        </div>
    `;
}

function renderWikiContextRail() {
    const pageId = state.wiki.selectedPageId;
    return `
        <div class="context-rail-header">
            <span class="context-kicker">侧栏</span>
            <h2>Wiki</h2>
            <p>页面树与搜索。</p>
        </div>
        <div class="context-rail-section">
            <div class="context-rail-section-title">
                <span>页面搜索</span>
                ${tag(`${formatNumber(state.wiki.pages.length)} 页`)}
            </div>
            <form id="wiki-search-form" class="inline-form context-create-form">
                <div class="field"><label>搜索 Wiki</label><input name="keyword" placeholder="输入关键词搜索 Wiki"></div>
                <button class="ghost-button context-action-button" type="submit">搜索</button>
            </form>
            ${state.wiki.searchResult ? `
                <div class="context-list">
                    ${(state.wiki.searchResult.items || []).map((item) => `
                        <button class="context-list-item" type="button" data-action="select-wiki-page" data-page-id="${item.pageId || item.id}">
                            <span class="context-list-title">${escapeHtml(item.title)}</span>
                            <span class="context-list-meta">${escapeHtml(item.contentSnippet || "搜索命中")}</span>
                        </button>
                    `).join("") || emptyState("无搜索结果", "换个词试试。")}
                </div>
            ` : ""}
        </div>
        <div class="context-rail-section">
            <div class="context-rail-section-title"><span>页面树</span></div>
            <div class="context-list wiki-tree">
                ${state.wiki.pages.map((wikiPage) => `
                    <button class="context-list-item ${Number(wikiPage.id) === Number(pageId) ? "active" : ""}" type="button" data-action="select-wiki-page" data-page-id="${wikiPage.id}">
                        <span class="context-list-title">${escapeHtml(wikiPage.title)}</span>
                        <span class="context-list-meta">${escapeHtml(humanizeStatus(wikiPage.status))} / v${formatNumber(wikiPage.publishedVersionNo)}</span>
                    </button>
                `).join("") || emptyState("暂无 Wiki 页面", "先在主画布创建一个草稿页。")}
            </div>
        </div>
    `;
}

function renderGraphContextRail() {
    const graph = state.graph.spaceGraph;
    const nodes = graph?.nodes || [];
    const selectedNodeId = state.graph.selectedNodeId;
    return `
        <div class="context-rail-header">
            <span class="context-kicker">侧栏</span>
            <h2>图谱</h2>
            <p>筛选和定位节点。</p>
        </div>
        <div class="context-rail-section">
            <div class="context-rail-section-title"><span>筛选</span></div>
            <form id="graph-filter-form" class="inline-form context-create-form">
                <div class="field"><label>节点类型</label><input name="nodeTypes" value="${escapeHtml(state.graph.filters.nodeTypes)}" placeholder="Wiki 页面,文档"></div>
                <div class="field"><label>边类型</label><input name="edgeTypes" value="${escapeHtml(state.graph.filters.edgeTypes)}" placeholder="关联,引用"></div>
                <label class="checkbox-line"><input type="checkbox" name="onlyPublished" ${state.graph.filters.onlyPublished ? "checked" : ""}> 仅发布</label>
                <label class="checkbox-line"><input type="checkbox" name="onlyIndexed" ${state.graph.filters.onlyIndexed ? "checked" : ""}> 仅已索引</label>
                <button class="ghost-button context-action-button" type="submit">应用筛选</button>
            </form>
        </div>
        <div class="context-rail-section">
            <div class="context-rail-section-title">
                <span>节点集合</span>
                ${tag(`${formatNumber(nodes.length)} 个`)}
            </div>
            <div class="context-list graph-node-list">
                ${nodes.slice(0, 80).map((node) => `
                    <button class="context-list-item ${node.id === selectedNodeId ? "active" : ""}" type="button" data-action="select-graph-node" data-node-id="${escapeHtml(node.id)}" title="${escapeHtml(node.title || node.id)}">
                        <span class="context-list-title">${escapeHtml(node.title || node.id)}</span>
                        <span class="context-list-meta">${escapeHtml(humanizeGraphNodeType(node.type || "NODE"))} / ${escapeHtml(humanizeStatus(node.status || node.indexStatus || "ACTIVE"))}</span>
                    </button>
                `).join("") || emptyState("暂无节点", "当前筛选条件下没有图谱节点。")}
            </div>
        </div>
    `;
}

function renderDefaultContextRail(route) {
    const descriptor = routeDescriptor(route);
    const space = currentSpace();
    return `
        <div class="context-rail-header">
            <span class="context-kicker">侧栏</span>
            <h2>${escapeHtml(descriptor.eyebrow)}</h2>
            <p>${escapeHtml(space?.name || "选择空间后开始工作。")}</p>
        </div>
        <div class="context-rail-section">
            <div class="context-rail-section-title"><span>当前模式</span></div>
            <article class="context-summary-card">
                <strong>${escapeHtml(descriptor.title)}</strong>
                <p>${escapeHtml(descriptor.detail)}</p>
            </article>
        </div>
        <div class="context-rail-section">
            <div class="context-rail-section-title"><span>快速入口</span></div>
            <div class="context-list">
                ${renderContextQuickLink("空间", "/spaces", route.name === "spaces")}
                ${currentRouteSpaceId() ? renderContextQuickLink("团队知识", routeLink("knowledge", currentRouteSpaceId()), route.name.startsWith("knowledge")) : ""}
                ${currentRouteSpaceId() ? renderContextQuickLink("聊天", routeLink("chat", currentRouteSpaceId()), routeMode(route) === "chat") : ""}
                ${currentRouteSpaceId() ? renderContextQuickLink("Wiki", routeLink("wiki", currentRouteSpaceId()), route.name === "wiki") : ""}
                ${currentRouteSpaceId() ? renderContextQuickLink("图谱", routeLink("graph", currentRouteSpaceId()), route.name === "graph") : ""}
                ${currentRouteSpaceId() ? renderContextQuickLink("成果", routeLink("artifacts", currentRouteSpaceId()), route.name === "artifacts" || route.name === "artifact-detail") : ""}
                ${currentRouteSpaceId() ? renderContextQuickLink("记忆", routeLink("memory", currentRouteSpaceId()), route.name === "memory") : ""}
            </div>
        </div>
    `;
}

function renderContextQuickLink(label, target, active) {
    return `<a class="context-list-item ${active ? "active" : ""}" href="${target}" data-nav="${target}"><span class="context-list-title">${escapeHtml(label)}</span></a>`;
}

function renderTopbar() {
    const descriptor = routeDescriptor(state.route);
    const collapsed = state.ui.contextRailCollapsed;
    const isChatRoute = routeMode(state.route) === "chat";
    const session = isChatRoute ? activeSession() : null;
    const localState = session?.id ? state.chat.localsBySession[session.id] : null;
    const topbarTitle = isChatRoute && session ? session.title : descriptor.title;
    const topbarEyebrow = isChatRoute
        ? (session ? `${humanizeSessionKind(session.sessionKind)} / ${humanizeScopeType(session.scopeType)}` : descriptor.eyebrow)
        : descriptor.eyebrow;
    const chatActions = isChatRoute ? `
        <div class="topbar-chat-actions">
            ${session?.sessionKind === "DRAFT" ? `<button class="ghost-button" type="button" data-action="convert-draft" data-session-id="${session.id}">转正式</button><button class="danger-button" type="button" data-action="discard-draft" data-session-id="${session.id}">丢弃</button>` : ""}
            ${localState?.assistantMessage?.status === "RUNNING" ? `<button class="danger-button" type="button" data-action="stop-chat" data-session-id="${session.id}">停止</button>` : ""}
            <button class="ghost-button" type="button" data-action="reconnect-chat">重连</button>
        </div>
    ` : "";
    return `
        <div class="topbar">
            <div class="topbar-left">
                    <button class="topbar-icon-button" type="button" data-action="toggle-context-rail" aria-expanded="${collapsed ? "false" : "true"}" aria-label="${collapsed ? "展开侧边栏" : "收起侧边栏"}" title="${collapsed ? "展开侧边栏" : "收起侧边栏"}">
                    ${navIcon("sidebar", "S")}
                </button>
                <div class="topbar-context">
                    <span class="context-kicker">${escapeHtml(topbarEyebrow)}</span>
                    <strong>${escapeHtml(topbarTitle)}</strong>
                </div>
                <div class="topbar-meta">
                    <div class="workspace-status">
                        <span class="status-dot ${escapeHtml(state.websocket.status)} ${state.websocket.status === "connected" ? "connected" : ""}"></span>
                        <span>实时连接 ${escapeHtml(humanizeStatus(state.websocket.status))}</span>
                    </div>
                </div>
            </div>
            <div class="topbar-right">
                ${chatActions}
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

function renderInspector() {
    if (!state.ui.drawer) {
        return `<aside class="inspector inspector-empty" aria-label="详情面板"><div class="inspector-header"><div><span class="context-kicker">详情</span><h2>详情面板</h2><p class="panel-subtitle">选择引用、证据、成果或追踪后，会在这里显示解释型内容。</p></div></div><div class="inspector-body">${emptyState("详情面板待命中", "点击消息引用、卡片证据或日志追踪即可查看详情。")}</div></aside>`;
    }
    return `
        <aside class="inspector" aria-label="详情面板">
            <div class="inspector-header">
                <div>
                    <span class="context-kicker">详情</span>
                    <h2>${escapeHtml(state.ui.drawer.title)}</h2>
                </div>
                <button class="ghost-button" type="button" data-action="close-drawer">收起</button>
            </div>
            <div class="inspector-body">${state.ui.drawer.html}</div>
        </aside>
    `;
}

function renderDrawer() {
    return renderInspector();
}

function renderModalInspector() {
    if (!state.ui.drawer) {
        return "";
    }
    const modalKicker = state.ui.drawer.source === "inline-form" ? "操作面板" : "详情预览";
    return `
        <div class="modal-layer" role="presentation">
            <button class="modal-backdrop" type="button" data-action="close-drawer" aria-label="关闭弹窗"></button>
            <aside class="inspector modal-dialog" role="dialog" aria-modal="true" aria-labelledby="modal-title">
                <div class="inspector-header">
                    <div>
                        <span class="context-kicker">${modalKicker}</span>
                        <h2 id="modal-title">${escapeHtml(state.ui.drawer.title)}</h2>
                    </div>
                    <button class="ghost-button modal-close-button" type="button" data-action="close-drawer" aria-label="关闭弹窗">关闭</button>
                </div>
                <div class="inspector-body">${state.ui.drawer.html}</div>
            </aside>
        </div>
    `;
}

function renderToasts() {
    return `
        <div class="toast-stack" aria-live="polite" aria-atomic="true">
            ${state.ui.toasts.map((toast) => `<div class="toast ${toast.type}" role="${toast.type === "error" ? "alert" : "status"}">${escapeHtml(toast.message)}</div>`).join("")}
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
            return renderWikiPageV2();
        case "graph":
            return renderGraphPage();
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
                            const latestStatus = latest ? `${humanizeStatus(latest.parseStatus || "UNKNOWN")} / ${humanizeStatus(latest.indexStatus || "UNKNOWN")}` : "暂无文档";
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
    const indexedDocuments = documents.filter((document) => {
        const raw = String(document.indexStatus || document.status || document.parseStatus || "").toUpperCase();
        return raw.includes("INDEX") || raw === "READY" || raw === "SUCCESS";
    });
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
            { eyebrow: "上传", title: "上传不中断浏览", description: "文档处理与搜索测试可以并行进行，不必等所有任务完成再继续工作。" },
            { eyebrow: "检索", title: "用真实关键词压测召回", description: "先拿团队常问的问题测试检索，比只看上传成功更能判断知识库是否可用。" },
            { eyebrow: "修复", title: "失败项优先看解析和索引", description: "先判断错误发生在解析还是索引阶段，再决定重传、暂停或继续。" }
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
                                <td>${escapeHtml(`${humanizeStatus(document.parseStatus || "UNKNOWN")} / ${humanizeStatus(document.indexStatus || "UNKNOWN")}`)}</td>
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
                            ${badge(uploadJob.stage || uploadJob.status || "RUNNING")}
                        </div>
                        <div class="muted">MD5: <span class="mono">${escapeHtml(uploadJob.fileMd5 || "计算中")}</span></div>
                        <div class="muted">进度：${uploadJob.progress ? uploadJob.progress.toFixed(1) : "0.0"}%</div>
                        <div class="progress-bar" aria-hidden="true"><span class="progress-bar-fill" style="width:${Math.max(0, Math.min(100, uploadJob.progress || 0))}%"></span></div>
                        ${uploadJob.task ? `<div class="muted">任务 ${uploadJob.task.id}: ${escapeHtml(humanizeStatus(uploadJob.task.taskStatus))}</div>` : ""}
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
        <div class="chat-workbench">
            <section class="chat-main-canvas">
                <div class="chat-canvas-header">
                    <div>
                        <span class="context-kicker">主画布</span>
                        <h1>${escapeHtml(session?.title || "工作台聊天")}</h1>
                        <p class="subtitle">${session ? `${humanizeSessionKind(session.sessionKind)} / ${humanizeScopeType(session.scopeType)} / ${humanizeStatus(session.runtimeStatus || session.status)}` : "从左侧侧栏选择或创建会话。"}</p>
                    </div>
                    <div class="page-actions chat-toolbar">
                        ${session?.sessionKind === "DRAFT" ? `<button class="ghost-button" type="button" data-action="convert-draft" data-session-id="${session.id}">转正式会话</button><button class="danger-button" type="button" data-action="discard-draft" data-session-id="${session.id}">丢弃草稿</button>` : ""}
                        ${localState?.assistantMessage?.status === "RUNNING" ? `<button class="danger-button" type="button" data-action="stop-chat" data-session-id="${session.id}">停止生成</button>` : ""}
                        <button class="ghost-button" type="button" data-action="reconnect-chat">重连 WebSocket</button>
                    </div>
                </div>
                <div class="message-list">
                    ${session ? messages.map((message) => renderChatMessage(message)).join("") || emptyState("暂无消息", "发送第一条问题来触发 WebSocket 流式响应。") : emptyState("请选择会话", "左侧选一个会话，或者先创建新会话。")}
                </div>
                <div class="chat-composer">
                    ${session ? `
                        <div class="composer-command-strip">
                            <span class="composer-command-label">生成</span>
                            <button class="composer-command-chip primary" type="button" data-action="open-chat-artifact-dialog">成果对话框</button>
                            <button class="composer-command-chip" type="button" data-action="insert-chat-command" data-command="/成果 生成研究报告 type=REPORT ">研究报告</button>
                            <button class="composer-command-chip" type="button" data-action="insert-chat-command" data-command="/artifact 整理学习指南 type=STUDY_GUIDE ">学习指南</button>
                            <button class="composer-command-chip" type="button" data-action="insert-chat-command" data-command="/artifact 生成对比分析 type=COMPARISON ">对比分析</button>
                            <button class="composer-command-chip subtle" type="button" data-action="insert-chat-command" data-command=" project=">项目参数</button>
                        </div>
                        <form id="chat-message-form">
                            <div class="field">
                                <label>输入</label>
                                <textarea name="content" data-chat-draft="${session.id}" placeholder="可以提问，也可以输入 /成果 生成研究报告 type=REPORT project=项目ID">${escapeHtml(state.chat.draftsBySession[session.id] || "")}</textarea>
                            </div>
                            <button class="button" type="submit">发送</button>
                        </form>
                    ` : emptyState("没有活动会话", "创建会话后输入区会显示在这里。")}
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
        if (message.artifactId) {
            const artifactSpaceId = Number(message.artifactSpaceId) || "";
            actions.push(`<button class="button message-artifact-button" type="button" data-action="open-artifact" data-artifact-id="${message.artifactId}" data-space-id="${artifactSpaceId}">打开成果</button>`);
        }
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
            ${message.artifactId ? `<div class="message-artifact-meta">成果 #${formatNumber(message.artifactId)} 正在生成或已可查看</div>` : ""}
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
    const questions = state.personal.questionsByProject[projectId] || [];
    const selectedQuestionId = Number(state.personal.selectedQuestionIdByProject[projectId]);
    const selectedQuestionWorkspace = selectedQuestionId ? state.personal.questionWorkspaceById[selectedQuestionId] : null;
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
        { eyebrow: "修复", title: "失败项要立刻回收处理", description: "比起堆积异常资料，及时修复更能保持项目链路干净。" }
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
                ${panel("最近成果", artifacts.slice(0, 4).map((artifact) => `<div class="list-item"><strong>${escapeHtml(artifact.title)}</strong><div class="muted">${escapeHtml(humanizeArtifactType(artifact.artifactType))} / ${badge(artifact.status)}</div></div>`).join("") || emptyState("暂无成果", "到生成标签发起生成。"))}
            </div>
        `;
    }
    if (route.name === "project-detail") {
        body += renderProjectQuestionWorkbench(projectId, questions, selectedQuestionId, selectedQuestionWorkspace);
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

function renderProjectQuestionWorkbench(projectId, questions, selectedQuestionId, workspace) {
    const selectedQuestion = questions.find((item) => Number(item.id) === Number(selectedQuestionId)) || null;
    return `
        <div class="content-grid cols-2" style="margin-top:16px;">
            ${panel("研究问题", `
                <form id="create-question-form" data-project-id="${projectId}" class="inline-form">
                    <div class="field"><label>问题标题</label><input name="title" required placeholder="例如：GraphRAG 是否适合个人研究 Wiki MVP？"></div>
                    <div class="field-grid cols-2">
                        <div class="field"><label>问题类型</label><input name="questionType" placeholder="tradeoff / evaluation / roadmap"></div>
                        <div class="field"><label>下一步</label><input name="nextStep" placeholder="先补哪类证据或实验"></div>
                    </div>
                    <div class="field"><label>当前假设</label><textarea name="currentHypothesis" placeholder="先写下当前猜想，后续可以被 claim 修正。"></textarea></div>
                    <div class="field"><label>范围备注</label><textarea name="scopeNote" placeholder="边界、前提、暂不讨论什么。"></textarea></div>
                    <button class="button" type="submit">创建研究问题</button>
                </form>
                <hr style="border:none;border-top:1px solid var(--border);margin:18px 0;">
                <div class="list-stack">
                    ${questions.map((question) => `
                        <button class="list-item ${Number(question.id) === Number(selectedQuestionId) ? "active" : ""}" type="button" data-action="select-question" data-project-id="${projectId}" data-question-id="${question.id}">
                            <div class="list-item-header">
                                <strong>${escapeHtml(question.title)}</strong>
                                ${badge(question.status || "OPEN")}
                            </div>
                            <div class="muted">${escapeHtml(question.currentHypothesis || question.questionType || "点击查看工作台")}</div>
                        </button>
                    `).join("") || emptyState("还没有研究问题", "先创建一个问题，随后就可以在右侧看到它的工作台与综述。")}
                </div>
            `, { subtitle: "把 project 拆成可持续推进的问题单元，然后围绕每个问题沉淀结论、证据、争议与下一步。" })}
            ${panel(selectedQuestion ? "问题工作台" : "问题综述", selectedQuestion
                ? renderResearchQuestionWorkspace(projectId, selectedQuestion, workspace)
                : emptyState("请选择研究问题", "左侧选中一个问题后，这里会展示当前结论、开放问题、关联概念与问题综述。"),
                { subtitle: selectedQuestion ? "这里聚合当前 claim、open issue、概念冲突、最近 session summary 和 overview。" : "" })}
        </div>
    `;
}

function renderResearchQuestionWorkspace(projectId, question, workspace) {
    if (!workspace) {
        return emptyState("工作台加载中", "稍后会展示问题当前的结论、证据、争议与下一步。");
    }
    const overview = workspace.latestOverview || null;
    const currentAnswer = overview?.currentAnswer || question.currentAnswer || "尚未沉淀出明确结论。";
    const nextStep = workspace.nextStep || question.nextStep || "先补充当前问题下的关键判断与证据。";
    const currentClaims = workspace.currentClaims || [];
    const openIssues = workspace.openIssues || [];
    const relatedConcepts = workspace.relatedConcepts || [];
    const conflictingConcepts = workspace.conflictingConcepts || [];
    const recentSessions = workspace.recentSessions || [];
    return `
        <div class="list-stack">
            <div class="list-item">
                <div class="list-item-header">
                    <strong>${escapeHtml(question.title)}</strong>
                    ${badge(question.status || "OPEN")}
                </div>
                <div class="muted">${escapeHtml(question.scopeNote || question.currentHypothesis || "暂无范围备注。")}</div>
                <div class="page-actions" style="margin-top:12px;">
                    <button class="button" type="button" data-action="generate-question-overview" data-project-id="${projectId}" data-question-id="${question.id}">
                        ${overview?.generated ? "刷新综述" : "生成综述"}
                    </button>
                </div>
            </div>
            <div class="metric-row">
                <div class="metric"><strong>${formatNumber(currentClaims.length)}</strong><span>当前判断</span></div>
                <div class="metric"><strong>${formatNumber(openIssues.length)}</strong><span>开放问题</span></div>
                <div class="metric"><strong>${formatNumber(relatedConcepts.length)}</strong><span>关联概念</span></div>
                <div class="metric"><strong>${formatNumber(conflictingConcepts.length)}</strong><span>冲突概念</span></div>
            </div>
            ${panel("当前结论", `<div class="muted">${escapeHtml(currentAnswer)}</div>`)}
            ${panel("下一步", `<div class="muted">${escapeHtml(nextStep)}</div>`)}
            ${panel("关键判断", currentClaims.map((claim) => `
                <div class="list-item">
                    <div class="list-item-header">
                        <strong>${escapeHtml(claim.statement)}</strong>
                        ${badge(claim.claimType || "CLAIM")} ${badge(claim.stance || "UNCERTAIN")} ${badge(claim.cardStatus || "READY")}
                    </div>
                    <div class="muted">${escapeHtml(claim.rationale || "无额外说明")}</div>
                </div>
            `).join("") || emptyState("暂无当前判断", "这个问题还没有沉淀出 claim。"))}
            ${panel("未解决问题", openIssues.map((claim) => `
                <div class="list-item">
                    <strong>${escapeHtml(claim.statement)}</strong>
                    <div class="muted">${escapeHtml(claim.rationale || "等待后续验证或补证。")}</div>
                </div>
            `).join("") || emptyState("暂无开放问题", "当前没有标记为 OPEN_ISSUE 的判断。"))}
            ${panel("关联概念", relatedConcepts.map((concept) => `
                <div class="list-item">
                    <div class="list-item-header">
                        <strong>${escapeHtml(concept.conceptName)}</strong>
                        ${concept.conflicting ? badge("CONFLICT") : ""}
                    </div>
                    <div class="muted">${escapeHtml((concept.relationTypes || []).join(" / ") || "RELATED")} · ${formatNumber(concept.claimCount)} 条 claim</div>
                </div>
            `).join("") || emptyState("暂无关联概念", "当 claim 与 concept 绑定后，这里会显示概念侧视图。"))}
            ${panel("最近会话摘要", recentSessions.map((item) => `
                <div class="list-item">
                    <div class="list-item-header">
                        <strong>${escapeHtml(item.topic || `Session #${item.sessionId}`)}</strong>
                        <span class="muted">${escapeHtml(formatDate(item.updatedAt))}</span>
                    </div>
                    <div class="muted">${escapeHtml(item.summary || "")}</div>
                </div>
            `).join("") || emptyState("暂无会话摘要", "正式会话写回后，这里会出现和该问题相关的最近摘要。"))}
            ${panel("问题综述 Markdown", overview?.markdown
                ? `<div class="artifact-reading-surface">${renderMarkdown(overview.markdown)}</div>`
                : emptyState("尚未生成综述", "点击上方“生成综述”后，这里会出现稳定的 markdown 综述。"))}
        </div>
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
                    ${sources.map((source) => {
                        const importStatus = String(source.importStatus || "").toUpperCase();
                        const compileStatus = String(source.compileStatus || "").toUpperCase();
                        const importBusy = ["PENDING", "IMPORTING", "RUNNING"].includes(importStatus);
                        const compileBusy = ["COMPILING", "RUNNING"].includes(compileStatus);
                        const compileBlocked = importStatus !== "READY" || compileBusy;
                        const compileLabel = importStatus !== "READY" ? "等待导入完成" : compileBusy ? "编译中" : compileStatus === "READY" ? "重新编译" : "触发知识编译";
                        return `
                        <div class="list-item source-list-item ${compileStatus === "FAILED" || importStatus === "FAILED" ? "source-list-item-failed" : ""}">
                            <div class="list-item-header">
                                <strong title="${escapeHtml(source.title)}">${escapeHtml(source.title)}</strong>
                                <div class="page-actions source-status-strip">${badge(source.importStatus)}${badge(source.compileStatus)}</div>
                            </div>
                            <div class="muted">${escapeHtml(humanizeSourceType(source.sourceType))} / 任务 ${formatNumber(source.taskId)}</div>
                            ${source.errorMessage ? `<div class="source-error-note"><strong>错误原因</strong><span>${escapeHtml(source.errorMessage)}</span></div>` : ""}
                            <div class="page-actions" style="margin-top:10px;">
                                <button class="ghost-button" type="button" data-action="source-import" data-source-id="${source.id}" ${importBusy ? "disabled" : ""}>重新导入</button>
                                <button class="ghost-button" type="button" data-action="source-compile" data-source-id="${source.id}" ${compileBlocked ? "disabled" : ""}>${compileLabel}</button>
                            </div>
                        </div>
                    `;
                    }).join("") || emptyState("暂无资料", "先导入一份文件、URL 或粘贴文本。")}
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
                <h1>成果工作室</h1>
                <p class="subtitle">把个人研究项目交给固定技能，生成报告、指南、对比分析和可继续沉淀的成果。</p>
            </div>
        </div>
        <div class="metric-row">
            <div class="metric"><strong>${formatNumber(state.studio.skills.length)}</strong><span>技能</span></div>
            <div class="metric"><strong>${formatNumber(state.personal.projects.length)}</strong><span>研究项目</span></div>
            <div class="metric"><strong>${formatNumber(state.artifacts.list.length)}</strong><span>成果</span></div>
            <div class="metric"><strong>${escapeHtml(humanizeStatus(lastTaskStatus))}</strong><span>最近任务</span></div>
        </div>
        <div class="content-grid cols-2">
            ${panel("创建成果任务", `
                <form id="studio-task-form" class="inline-form" data-space-id="${currentRouteSpaceId()}" data-modal-hint="选择技能、项目和主题后执行">
                    <div class="field-grid cols-2">
                        <div class="field">
                            <label>成果技能</label>
                            <select name="skillId" required>
                                ${state.studio.skills.map((skill) => `<option value="${skill.id}">${escapeHtml(skill.name)} · ${escapeHtml(humanizeArtifactType(skill.artifactType))}</option>`).join("")}
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
                    <button class="button" type="submit">启动生成</button>
                </form>
                ${state.studio.lastTask ? `<div class="list-item" style="margin-top:14px;"><div class="list-item-header"><strong>最近任务</strong>${badge(state.studio.lastTask.taskStatus)}</div><div class="muted">成果 ${formatNumber(state.studio.lastTask.artifactId)}</div></div>` : ""}
            `, { subtitle: "适合一次性编排；需要在对话中执行时，用聊天页的成果对话框。" })}
            ${panel("技能与最近成果", `
                <div class="studio-skill-grid">
                    ${state.studio.skills.map(renderSkillSummary).join("") || emptyState("暂无技能", "工作室技能加载后会显示在这里。")}
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
            `, { subtitle: "固定技能覆盖学习、报告、对比、工作准备和外部资料整理。" })}
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
                                <td>${escapeHtml(humanizeArtifactType(artifact.artifactType))}</td>
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
                            <div class="muted">${escapeHtml(humanizeArtifactType(artifact.artifactType))} / 项目 ${formatNumber(artifact.researchProjectId)}</div>
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
    const isPersonalArtifact = isPersonalResearchArtifact(artifact);
    const hasTraceableCitations = hasTraceableArtifactCitations(artifact);
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
            { eyebrow: "追踪", title: "引用与关系保证可追溯性", description: "引用和卡片关联让这份成果不只是好看，也更容易被复核。" },
            { eyebrow: "Distill", title: "个人成果可以继续蒸馏", description: "如果当前成果属于个人研究空间，还可以继续沉淀进更长期的 Wiki 卡片。" }
        ])}
        <div class="metric-row">
            <div class="metric"><strong>${escapeHtml(humanizeArtifactType(artifact?.artifactType || "—"))}</strong><span>成果类型</span></div>
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
                    ${tag(humanizeArtifactType(artifact?.artifactType || "成果"))}
                    ${badge(artifact?.status)}
                    ${artifact?.scopeType ? tag(humanizeScopeType(artifact.scopeType)) : ""}
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
                        <strong>${isPersonalArtifact ? "需要可追溯引用后才能沉淀" : "该成果不支持个人蒸馏"}</strong>
                        <div class="muted">${isPersonalArtifact && !hasTraceableCitations ? "沉淀到个人 Wiki 会保留证据链；当前成果没有引用记录，请先从带引用的资料或卡片生成成果。" : "只有个人研究空间中的成果才会显示综合卡蒸馏入口。"}</div>
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
    const relations = state.wiki.relations;
    const graph = state.wiki.pageGraph;
    const spaceGraph = state.wiki.spaceGraph;
    return `
        <div class="wiki-workbench">
            <section class="wiki-reader">
                <div class="wiki-reader-header">
                    <div>
                        <span class="context-kicker">主画布</span>
                        <h1>${escapeHtml(page?.title || "团队 Wiki")}</h1>
                        <p class="subtitle">${page ? `${humanizeStatus(page.status)} / ${formatNumber(pages.length)} 个页面 / ${formatNumber(state.wiki.versions.length)} 个版本` : "左侧选择页面，或在下方创建新的 Wiki 草稿。"}</p>
                    </div>
                    <div class="page-actions">
                        <button class="ghost-button" type="button" data-action="open-wiki-create-form">创建草稿</button>
                        ${page ? `<button class="ghost-button" type="button" data-action="open-wiki-inspector">关系与版本</button>` : ""}
                        ${page ? `<button class="ghost-button" type="button" data-action="open-wiki-graph-card">图谱概览</button>` : ""}
                        ${page ? `<button class="button" type="button" data-action="publish-wiki-page" data-page-id="${page.id}">发布 Wiki</button>` : ""}
                    </div>
                </div>
                ${page ? `
                    <div class="wiki-reading-surface">
                        <div class="artifact-meta-row">
                            ${badge(page.status)}
                            ${tag(`版本 ${formatNumber(page.publishedVersionNo || state.wiki.versions[0]?.versionNo)}`)}
                            ${relations?.summary ? tag(`邻居 ${formatNumber(relations.summary.neighborCount)}`) : ""}
                            ${graph ? tag(`节点 ${formatNumber(graph.nodeCount)} / 边 ${formatNumber(graph.edgeCount)}`) : ""}
                        </div>
                        ${renderMarkdown(page.content || "")}
                    </div>
                    <div class="wiki-editor-card">
                        <span class="context-kicker">Editor</span>
                        <form id="wiki-edit-form" data-page-id="${page.id}" class="inline-form">
                            <div class="field"><label>标题</label><input name="title" value="${escapeHtml(page.title)}" required></div>
                            <div class="field"><label>内容</label><textarea class="editor-textarea" name="content" required>${escapeHtml(page.content || "")}</textarea></div>
                            <button class="button" type="submit">保存草稿</button>
                        </form>
                    </div>
                ` : `
                    <div class="wiki-editor-card">
                        <span class="context-kicker">Create Draft</span>
                        <form id="wiki-create-form" data-space-id="${currentRouteSpaceId()}" class="inline-form">
                            <div class="field"><label>标题</label><input name="title" required></div>
                            <div class="field"><label>内容</label><textarea class="editor-textarea" name="content" required></textarea></div>
                            <button class="button" type="submit">创建草稿</button>
                        </form>
                    </div>
                `}
            </section>
            ${renderWikiInsightColumn(page, relations, graph, spaceGraph)}
        </div>
    `;
}

function renderWikiInspectorContent() {
    const relations = state.wiki.relations;
    const versions = state.wiki.versions || [];
    if (!state.wiki.pageDetail) {
        return emptyState("暂无 Wiki 页面", "选择 Wiki 页面后会展示关系与版本。");
    }
    return `
        <div class="list-stack">
            ${renderWikiRelationsCard(relations)}
            ${panel("版本历史", `
                <div class="list-stack">
                    ${versions.map((version) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>v${formatNumber(version.versionNo)}</strong><span class="muted">${formatDate(version.createdAt)}</span></div>
                            <div class="muted">${escapeHtml(version.title)}</div>
                            ${version.changeNote ? `<div class="muted">${escapeHtml(version.changeNote)}</div>` : ""}
                        </div>
                    `).join("") || emptyState("暂无版本", "发布后会开始生成版本记录。")}
                </div>
            `)}
        </div>
    `;
}

function renderWikiRelationsCard(relations) {
    const summary = relations?.summary || {};
    return panel("页面关系", `
        <div class="metric-row compact-metrics">
            <div class="metric"><strong>${formatNumber(summary.outgoingResolvedCount)}</strong><span>出链</span></div>
            <div class="metric"><strong>${formatNumber(summary.incomingResolvedCount)}</strong><span>入链</span></div>
            <div class="metric"><strong>${formatNumber(summary.unresolvedOutgoingCount)}</strong><span>未解析</span></div>
            <div class="metric"><strong>${formatNumber(summary.neighborCount)}</strong><span>邻居</span></div>
        </div>
        <div class="list-stack" style="margin-top:14px;">
            ${renderRelationList("出链", relations?.outgoingLinks)}
            ${renderRelationList("入链", relations?.incomingLinks)}
            ${renderRelationList("邻居页", relations?.neighborPages)}
            ${renderUnresolvedLinks(relations?.unresolvedLinks)}
        </div>
    `);
}

function renderRelationList(title, items = []) {
    return `
        <div class="list-item">
            <div class="list-item-header"><strong>${escapeHtml(title)}</strong>${tag(`${formatNumber(items.length)} 项`)}</div>
            ${(items || []).slice(0, 8).map((item) => `
                <button class="subtle-row-button" type="button" data-action="select-wiki-page" data-page-id="${item.pageId}">
                    ${escapeHtml(item.title || `Page ${item.pageId}`)}
                    <span>${formatNumber(item.mentionCount || item.outgoingMentionCount || item.incomingMentionCount)} 次</span>
                </button>
            `).join("") || `<div class="muted">暂无${escapeHtml(title)}。</div>`}
        </div>
    `;
}

function renderUnresolvedLinks(items = []) {
    return `
        <div class="list-item">
            <div class="list-item-header"><strong>未解析链接</strong>${tag(`${formatNumber(items.length)} 项`)}</div>
            ${(items || []).slice(0, 8).map((item) => `
                <div class="muted">${escapeHtml(item.targetTitle)} / ${escapeHtml(item.relationStatus || "UNRESOLVED")} / ${formatNumber(item.mentionCount)} 次</div>
            `).join("") || `<div class="muted">暂无未解析链接。</div>`}
        </div>
    `;
}

function renderWikiGraphCard() {
    const graph = state.wiki.pageGraph;
    if (!graph) {
        return emptyState("暂无图谱概览", "当前页面还没有可展示的图谱数据。");
    }
    return `
        <div class="list-stack">
            <div class="metric-row compact-metrics">
                <div class="metric"><strong>${formatNumber(graph.nodeCount)}</strong><span>节点</span></div>
                <div class="metric"><strong>${formatNumber(graph.edgeCount)}</strong><span>关系</span></div>
                <div class="metric"><strong>${escapeHtml(graph.rootNodeId || "—")}</strong><span>根节点</span></div>
            </div>
            <div class="list-stack">
                ${(graph.nodes || []).slice(0, 8).map((node) => `
                    <button class="list-item" type="button" data-action="open-graph-from-node" data-node-id="${escapeHtml(node.id)}">
                        <div class="list-item-header"><strong>${escapeHtml(node.title || node.id)}</strong>${tag(humanizeGraphNodeType(node.type || "NODE"))}</div>
                        <div class="muted">${escapeHtml(node.subtitle || humanizeStatus(node.status || "UNKNOWN"))}</div>
                    </button>
                `).join("") || emptyState("暂无节点", "该页面局部图谱暂时没有节点。")}
            </div>
            <button class="button" type="button" data-nav="${routeLink("graph", currentRouteSpaceId())}">进入完整图谱</button>
        </div>
    `;
}

function renderWikiPageV2() {
    const page = state.wiki.pageDetail;
    const pages = state.wiki.pages || [];
    const relations = state.wiki.relations;
    const pageGraph = state.wiki.pageGraph;
    const spaceGraph = state.wiki.spaceGraph;
    const activeNode = findWikiGraphNode(spaceGraph, page?.id) || findWikiGraphNode(pageGraph, page?.id);
    const versionNo = page?.publishedVersionNo || state.wiki.versions[0]?.versionNo;
    const isDraft = String(page?.status || "").toUpperCase() === "DRAFT";
    const isAutoMaintained = Boolean(page?.autoMaintained);
    return `
        <div class="wiki-workbench wiki-workbench-v2">
            <section class="wiki-reader wiki-reader-v2">
                <div class="wiki-topbar">
                    <div class="wiki-title-block">
                        <span class="context-kicker">Team Wiki</span>
                        <h1>${escapeHtml(page?.title || "团队 Wiki")}</h1>
                        <div class="wiki-title-meta">
                            ${page ? badge(page.status) : tag("未选择页面")}
                            ${isAutoMaintained ? tag("自动维护") : ""}
                            ${page?.indexStatus ? tag(`索引 ${humanizeStatus(page.indexStatus)}`) : ""}
                            ${versionNo ? tag(`v${formatNumber(versionNo)}`) : ""}
                            ${tag(`${formatNumber(pages.length)} 页`)}
                        </div>
                    </div>
                    <div class="page-actions compact-actions">
                        <button class="ghost-button" type="button" data-action="open-wiki-create-form">新建</button>
                        ${page ? `<button class="ghost-button" type="button" data-action="open-wiki-inspector">关系</button>` : ""}
                        ${page ? `<button class="ghost-button" type="button" data-action="open-wiki-graph-card">局部图</button>` : ""}
                        ${page && isDraft ? `<button class="button" type="button" data-action="publish-wiki-page" data-page-id="${page.id}">发布</button>` : ""}
                    </div>
                </div>

                ${page ? `
                    <div class="wiki-page-grid">
                        <article class="wiki-reading-surface wiki-reading-surface-v2">
                            <div class="wiki-page-status-line">
                                ${tag(`出链 ${formatNumber(activeNode?.outgoingResolvedCount ?? relations?.summary?.outgoingResolvedCount ?? 0)}`)}
                                ${tag(`反链 ${formatNumber(activeNode?.incomingResolvedCount ?? relations?.summary?.incomingResolvedCount ?? 0)}`)}
                                ${tag(`证据 ${formatNumber(activeNode?.evidenceCitationCount ?? 0)}`)}
                                ${(activeNode?.unresolvedOutgoingCount || relations?.summary?.unresolvedOutgoingCount) ? tag(`待修复 ${formatNumber(activeNode?.unresolvedOutgoingCount ?? relations?.summary?.unresolvedOutgoingCount)}`) : ""}
                            </div>
                            ${renderMarkdown(page.content || "")}
                        </article>
                        ${renderWikiMaintenancePanel(page, isDraft, isAutoMaintained)}
                    </div>
                ` : `
                    <div class="wiki-editor-card wiki-editor-card-v2">
                        <span class="context-kicker">Create Draft</span>
                        <form id="wiki-create-form" data-space-id="${currentRouteSpaceId()}" class="inline-form">
                            <div class="field"><label>标题</label><input name="title" required></div>
                            <div class="field"><label>内容</label><textarea class="editor-textarea" name="content" required></textarea></div>
                            <button class="button" type="submit">创建草稿</button>
                        </form>
                    </div>
                `}
            </section>
            ${renderWikiInsightColumn(page, relations, pageGraph, spaceGraph)}
        </div>
    `;
}

function renderWikiMaintenancePanel(page, isDraft, isAutoMaintained) {
    if (isDraft) {
        return `
            <div class="wiki-editor-card wiki-editor-card-v2">
                <div class="wiki-editor-heading">
                    <span class="context-kicker">Editor</span>
                    <span class="muted">草稿内容直接参与页面链接解析</span>
                </div>
                <form id="wiki-edit-form" data-page-id="${page.id}" class="inline-form">
                    <div class="field"><label>标题</label><input name="title" value="${escapeHtml(page.title)}" required></div>
                    <div class="field"><label>内容</label><textarea class="editor-textarea" name="content" required>${escapeHtml(page.content || "")}</textarea></div>
                    <button class="button" type="submit">保存草稿</button>
                </form>
            </div>
        `;
    }
    const sourceRows = [
        ["维护方式", isAutoMaintained ? "自动维护" : "已发布手动页"],
        ["来源文档", page.sourceDocumentId ? `Document #${page.sourceDocumentId}` : "—"],
        ["个人资料", page.sourcePersonalSourceId ? `Source #${page.sourcePersonalSourceId}` : "—"],
        ["来源成果", page.sourceArtifactId ? `Artifact #${page.sourceArtifactId}` : "—"],
        ["来源消息", page.sourceMessageId ? `Message #${page.sourceMessageId}` : "—"]
    ];
    return `
        <div class="wiki-editor-card wiki-editor-card-v2 wiki-maintenance-card">
            <div class="wiki-editor-heading">
                <span class="context-kicker">${isAutoMaintained ? "Auto Wiki" : "Published"}</span>
                <span class="muted">${isAutoMaintained ? "来源更新后自动刷新页面、引用和图谱" : "发布页保持只读，避免直接破坏已索引版本"}</span>
            </div>
            <div class="wiki-chain-list">
                ${sourceRows.map(([label, value]) => `
                    <div class="wiki-chain-row">
                        <span>${escapeHtml(label)}</span>
                        <strong>${escapeHtml(value)}</strong>
                    </div>
                `).join("")}
            </div>
            <div class="source-error-note source-info-note">
                <strong>${isAutoMaintained ? "自动维护说明" : "修改方式"}</strong>
                <span>${isAutoMaintained ? "上传文档重建索引或重新编译个人资料时，系统会写入新版本并重建引用与图谱。" : "需要调整内容时，请从来源成果或消息创建新草稿后再发布。"}</span>
            </div>
        </div>
    `;
}

function renderWikiInsightColumn(page, relations, pageGraph, spaceGraph) {
    const summary = spaceGraph?.summary || {};
    const activeNode = findWikiGraphNode(spaceGraph, page?.id) || findWikiGraphNode(pageGraph, page?.id);
    return `
        <aside class="wiki-insight-column">
            <section class="wiki-insight-panel">
                <div class="wiki-panel-title">
                    <strong>图谱状态</strong>
                    <span>${formatNumber(summary.resolvedEdgeCount ?? spaceGraph?.edgeCount ?? 0)} 条链接</span>
                </div>
                <div class="wiki-metric-grid">
                    <div><strong>${formatNumber(summary.totalPageCount ?? spaceGraph?.nodeCount ?? 0)}</strong><span>页面</span></div>
                    <div><strong>${formatNumber(summary.evidenceBackedPageCount ?? 0)}</strong><span>有证据</span></div>
                    <div><strong>${formatNumber(summary.orphanPageCount ?? 0)}</strong><span>孤立</span></div>
                    <div><strong>${formatNumber(summary.unresolvedLinkCount ?? 0)}</strong><span>待修复</span></div>
                </div>
                ${renderWikiGraphCanvas(spaceGraph, page?.id)}
            </section>

            ${page ? `
                <section class="wiki-insight-panel">
                    <div class="wiki-panel-title">
                        <strong>引用状态</strong>
                        <span>${wikiEvidenceLabel(activeNode)}</span>
                    </div>
                    <div class="wiki-chain-list">
                        <div class="wiki-chain-row">
                            <span>来源</span>
                            <strong>${escapeHtml(wikiSourceLabel(activeNode, page))}</strong>
                        </div>
                        <div class="wiki-chain-row">
                            <span>证据引用</span>
                            <strong>${formatNumber(activeNode?.evidenceCitationCount ?? 0)}</strong>
                        </div>
                        <div class="wiki-chain-row">
                            <span>页面状态</span>
                            <strong>${escapeHtml(humanizeStatus(page.status))} / ${escapeHtml(humanizeStatus(page.indexStatus))}</strong>
                        </div>
                    </div>
                    ${renderWikiGraphHealthRows(activeNode)}
                </section>

                <section class="wiki-insight-panel">
                    <div class="wiki-panel-title">
                        <strong>反链与出链</strong>
                        <span>${formatNumber(relations?.summary?.neighborCount ?? activeNode?.neighborCount ?? 0)} 个邻居</span>
                    </div>
                    ${renderWikiCompactRelationGroup("出链", relations?.outgoingLinks, "out")}
                    ${renderWikiCompactRelationGroup("反链", relations?.incomingLinks, "in")}
                    ${renderWikiCompactRelationGroup("邻居", relations?.neighborPages, "neighbor")}
                    ${renderWikiUnresolvedCompact(relations?.unresolvedLinks)}
                </section>
            ` : `
                <section class="wiki-insight-panel">
                    ${emptyState("选择页面后查看链路", "这里会显示反链、出链、缺失链接和证据覆盖。")}
                </section>
            `}
        </aside>
    `;
}

function renderWikiGraphCanvas(graph, activePageId) {
    const nodes = [...(graph?.nodes || [])]
        .sort((left, right) => (right.linkCount || 0) - (left.linkCount || 0) || String(left.title || "").localeCompare(String(right.title || "")))
        .slice(0, 28);
    const edges = graph?.edges || [];
    if (!nodes.length) {
        return `<div class="wiki-mini-graph empty">${escapeHtml("暂无图谱数据")}</div>`;
    }
    return `
        <div class="wiki-mini-graph" style="--edge-count:${Math.min(edges.length, 18)};">
            ${nodes.map((node, index) => renderWikiGraphNodePill(node, index, Number(node.id) === Number(activePageId))).join("")}
        </div>
    `;
}

function renderWikiGraphNodePill(node, index, active) {
    const size = Math.min(1.28, 0.82 + ((node.linkCount || 0) * 0.08));
    return `
        <button class="wiki-graph-pill ${active ? "active" : ""} ${node.orphan ? "orphan" : ""}"
            type="button"
            data-action="select-wiki-page"
            data-page-id="${node.id}"
            style="--i:${index};--s:${size};">
            <span>${escapeHtml(node.title || `Page ${node.id}`)}</span>
        </button>
    `;
}

function renderWikiCompactRelationGroup(title, items = [], tone = "") {
    const rows = (items || []).slice(0, 6);
    return `
        <div class="wiki-relation-block ${tone}">
            <div class="wiki-relation-heading"><strong>${escapeHtml(title)}</strong><span>${formatNumber((items || []).length)}</span></div>
            ${rows.map((item) => `
                <button class="wiki-relation-row" type="button" data-action="select-wiki-page" data-page-id="${item.pageId}">
                    <span>${escapeHtml(item.title || `Page ${item.pageId}`)}</span>
                    <em>${formatNumber(item.mentionCount || item.outgoingMentionCount || item.incomingMentionCount || 0)}</em>
                </button>
            `).join("") || `<div class="wiki-relation-empty">暂无${escapeHtml(title)}</div>`}
        </div>
    `;
}

function renderWikiUnresolvedCompact(items = []) {
    const rows = (items || []).slice(0, 6);
    return `
        <div class="wiki-relation-block unresolved">
            <div class="wiki-relation-heading"><strong>未解析</strong><span>${formatNumber((items || []).length)}</span></div>
            ${rows.map((item) => `
                <div class="wiki-relation-row unresolved">
                    <span>${escapeHtml(item.targetTitle)}</span>
                    <em>${escapeHtml(item.relationStatus || "MISSING")} · ${formatNumber(item.mentionCount || 0)}</em>
                </div>
            `).join("") || `<div class="wiki-relation-empty">暂无未解析链接</div>`}
        </div>
    `;
}

function renderWikiGraphHealthRows(node) {
    const rows = [];
    if (node?.orphan) {
        rows.push(["孤立页", "没有反链，建议从目录页或相关页面建立入口"]);
    }
    if (node?.leaf) {
        rows.push(["叶子页", "没有出链，适合补充后续阅读或相关主题"]);
    }
    if ((node?.unresolvedOutgoingCount || 0) > 0) {
        rows.push(["缺失链接", `${formatNumber(node.unresolvedOutgoingCount)} 个 [[页面]] 还没有解析`]);
    }
    if ((node?.evidenceCitationCount || 0) === 0) {
        rows.push(["证据不足", "当前发布版本没有文档级引用"]);
    }
    if (!rows.length) {
        rows.push(["链路健康", "页面、反链和证据状态完整"]);
    }
    return `<div class="wiki-health-list">${rows.map(([title, body]) => `<div><strong>${escapeHtml(title)}</strong><span>${escapeHtml(body)}</span></div>`).join("")}</div>`;
}

function findWikiGraphNode(graph, pageId) {
    if (!pageId) {
        return null;
    }
    return (graph?.nodes || []).find((node) => Number(node.id) === Number(pageId)) || null;
}

function wikiSourceLabel(node, page) {
    const sourceType = node?.sourceType || (page?.sourceArtifactId ? "ARTIFACT" : page?.sourceMessageId ? "CHAT_MESSAGE" : "MANUAL");
    if (sourceType === "ARTIFACT") {
        return `Artifact #${node?.sourceId || page?.sourceArtifactId || ""}`;
    }
    if (sourceType === "CHAT_MESSAGE") {
        return `对话消息 #${node?.sourceId || page?.sourceMessageId || ""}`;
    }
    return "手动草稿";
}

function wikiEvidenceLabel(node) {
    const count = node?.evidenceCitationCount || 0;
    return count > 0 ? `${formatNumber(count)} 条证据` : "未绑定证据";
}

function graphNodeLayerX(node, edgeType = "") {
    const type = String(node?.type || "").toUpperCase();
    const relationType = String(edgeType || "").toUpperCase();
    if (type.includes("DOCUMENT") || type.includes("SOURCE") || type.includes("KNOWLEDGE")) {
        return 20;
    }
    if (relationType.includes("CITES") && (type.includes("WIKI") || type.includes("ARTIFACT") || type.includes("CHAT"))) {
        return 54;
    }
    if (type.includes("WIKI") || type.includes("CONCEPT")) {
        return 42;
    }
    if (type.includes("CHAT") || type.includes("MESSAGE") || type.includes("SESSION")) {
        return 58;
    }
    if (type.includes("ARTIFACT") || type.includes("PROJECT")) {
        return 78;
    }
    return 50;
}

function collectGraphConnectedNodeIds(edges = []) {
    const ids = new Set();
    (edges || []).forEach((edge) => {
        if (edge?.sourceId !== undefined && edge?.sourceId !== null) {
            ids.add(String(edge.sourceId));
        }
        if (edge?.targetId !== undefined && edge?.targetId !== null) {
            ids.add(String(edge.targetId));
        }
    });
    return ids;
}

function addGraphLayoutVote(votes, id, x, y) {
    if (!id) {
        return;
    }
    const key = String(id);
    const current = votes.get(key) || { x: 0, y: 0, count: 0 };
    current.x += x;
    current.y += y;
    current.count += 1;
    votes.set(key, current);
}

function spreadGraphLayoutRows(nodes, layout, preferredVotes, minY = 18, rowGap = 12) {
    const lanes = new Map();
    nodes.forEach((node, index) => {
        const vote = preferredVotes.get(String(node.id));
        const preferredX = vote ? vote.x / vote.count : graphNodeLayerX(node);
        const lane = String(Math.round(preferredX / 10) * 10);
        const entries = lanes.get(lane) || [];
        entries.push({ node, preferredX, vote, index });
        lanes.set(lane, entries);
    });

    let maxRows = 1;
    lanes.forEach((entries) => {
        maxRows = Math.max(maxRows, entries.length);
        const laneGap = entries.length > 1 ? Math.min(rowGap, (78 - minY) / (entries.length - 1)) : 0;
        entries.forEach(({ node, preferredX, vote, index }, row) => {
            const stagger = row % 2 === 0 ? 0 : 3.5;
            layout.set(String(node.id), {
                x: clampGraphPoint(preferredX + stagger, 12, 88),
                y: clampGraphPoint((vote ? Math.max(minY, vote.y / vote.count) : minY) + row * laneGap + (index % 3) * 0.8, 12, 82)
            });
        });
    });
    return maxRows;
}

const GRAPH_CANVAS_NODE_LIMIT = 64;
const GRAPH_CANVAS_EDGE_LIMIT = 120;

function buildGraphCanvasNodes(nodes = [], edges = [], selectedNodeId) {
    const allNodes = nodes || [];
    const byId = new Map(allNodes.map((node) => [String(node.id), node]));
    const selectedId = selectedNodeId === undefined || selectedNodeId === null ? "" : String(selectedNodeId);
    const selectedEdgeIds = new Set();
    const ids = [];
    const add = (id) => {
        const key = String(id);
        if (byId.has(key) && !ids.includes(key) && ids.length < GRAPH_CANVAS_NODE_LIMIT) {
            ids.push(key);
        }
    };

    allNodes.filter((node) => node.root).forEach((node) => add(node.id));
    if (selectedId) {
        add(selectedId);
        (edges || []).forEach((edge) => {
            if (String(edge.sourceId) === selectedId || String(edge.targetId) === selectedId) {
                selectedEdgeIds.add(String(edge.sourceId));
                selectedEdgeIds.add(String(edge.targetId));
            }
        });
        selectedEdgeIds.forEach(add);
    }

    (edges || []).forEach((edge) => {
        add(edge.sourceId);
        add(edge.targetId);
    });
    allNodes.forEach((node) => add(node.id));
    return ids.map((id) => byId.get(id)).filter(Boolean);
}

function buildGraphNodeLayout(nodes = [], edges = []) {
    const visibleNodes = nodes || [];
    const layout = new Map();
    const nodeById = new Map(visibleNodes.map((node) => [String(node.id), node]));
    const visibleEdges = (edges || []).filter((edge) => nodeById.has(String(edge.sourceId)) && nodeById.has(String(edge.targetId)));
    const connectedIds = collectGraphConnectedNodeIds(visibleEdges);
    const roots = visibleNodes.filter((node) => node.root);
    const connectedNodes = visibleNodes.filter((node) => connectedIds.has(String(node.id)) && !node.root);
    const orphanNodes = visibleNodes.filter((node) => !connectedIds.has(String(node.id)) && !node.root);
    const preferredVotes = new Map();

    if (visibleNodes.length > 28) {
        const columns = 3;
        const rows = Math.max(1, Math.ceil(visibleNodes.length / columns));
        const xStep = columns > 1 ? 64 / (columns - 1) : 0;
        const yStep = rows > 1 ? 80 / (rows - 1) : 0;
        visibleNodes.forEach((node, index) => {
            const col = index % columns;
            const row = Math.floor(index / columns);
            layout.set(String(node.id), {
                x: clampGraphPoint(columns === 1 ? 50 : 18 + col * xStep, 16, 84),
                y: clampGraphPoint(10 + row * yStep, 10, 90)
            });
        });
        layout.rowCount = Math.max(rows, 3);
        layout.connectedIds = connectedIds;
        return layout;
    }

    visibleEdges.forEach((edge, index) => {
        const source = nodeById.get(String(edge.sourceId));
        const target = nodeById.get(String(edge.targetId));
        if (!source || !target) {
            return;
        }
        const type = String(edge.type || edge.label || "").toUpperCase();
        const rowY = 20 + (index % 4) * 12;
        let sourceX = graphNodeLayerX(source, type);
        let targetX = graphNodeLayerX(target, type);
        if (type.includes("LINK") && Math.abs(sourceX - targetX) < 18) {
            sourceX = 36;
            targetX = 64;
        }
        if (type.includes("GENERATES") || type.includes("SOURCE")) {
            sourceX = Math.min(sourceX, 46);
            targetX = Math.max(targetX, 70);
        }
        if (type.includes("CITES")) {
            sourceX = Math.max(sourceX, 50);
            targetX = Math.min(targetX, 26);
        }
        if (Math.abs(sourceX - targetX) < 16) {
            sourceX = 36;
            targetX = 64;
        }
        addGraphLayoutVote(preferredVotes, source.id, sourceX, rowY);
        addGraphLayoutVote(preferredVotes, target.id, targetX, rowY + (index % 2 === 0 ? 0 : 6));
    });

    roots.forEach((node, index) => {
        layout.set(String(node.id), { x: 50, y: clampGraphPoint(16 + index * 11, 12, 36) });
    });

    const connectedRowCount = spreadGraphLayoutRows(connectedNodes, layout, preferredVotes, roots.length ? 26 : 18, 12);

    const relationRows = Math.max(0, ...Array.from(layout.values()).map((point) => point.y));
    const orphanStartY = connectedNodes.length || roots.length ? Math.min(58, Math.max(46, relationRows + 8)) : 22;
    const orphanColumns = orphanNodes.length > 16 ? 5 : orphanNodes.length > 9 ? 4 : orphanNodes.length > 4 ? 3 : Math.max(1, orphanNodes.length);
    const orphanRows = Math.max(1, Math.ceil(orphanNodes.length / Math.max(orphanColumns, 1)));
    const orphanXStep = orphanColumns > 1 ? 76 / (orphanColumns - 1) : 0;
    const orphanYStep = orphanRows > 1 ? Math.min(12, (88 - orphanStartY) / (orphanRows - 1)) : 0;
    orphanNodes.forEach((node, index) => {
        const col = index % orphanColumns;
        const row = Math.floor(index / orphanColumns);
        layout.set(String(node.id), {
            x: clampGraphPoint(orphanColumns === 1 ? 50 : 12 + col * orphanXStep, 10, 90),
            y: clampGraphPoint(orphanStartY + row * orphanYStep, 12, 90)
        });
    });

    layout.rowCount = Math.max(orphanRows + (connectedNodes.length || roots.length ? 2 : 0), connectedRowCount, Math.ceil(Math.max(connectedNodes.length, roots.length, 1) / 4), 3);
    layout.connectedIds = connectedIds;
    return layout;
}

function clampGraphPoint(value, min = 9, max = 91) {
    return Math.max(min, Math.min(max, Math.round(value * 10) / 10));
}

function formatSvgNumber(value) {
    return Number(value).toFixed(2).replace(/\.?0+$/, "");
}

function graphEdgeTone(edge) {
    const type = String(edge.type || edge.label || "").toUpperCase();
    if (type.includes("CITES")) {
        return "evidence";
    }
    if (type.includes("SOURCE") || type.includes("GENERATES")) {
        return "source";
    }
    if (type.includes("WIKI") || type.includes("LINK")) {
        return "wiki";
    }
    return "default";
}

function graphEdgeLabel(edge) {
    const raw = humanizeGraphEdgeType(edge.label || edge.type || "RELATION").trim();
    return raw.length > 24 ? `${raw.slice(0, 21)}...` : raw;
}

function buildGraphEdgeGeometry(source, target, index) {
    const dx = target.x - source.x;
    const dy = target.y - source.y;
    const distance = Math.hypot(dx, dy);
    if (distance < 0.5) {
        const x = clampGraphPoint(source.x + 8);
        const y = clampGraphPoint(source.y - 8, 12, 88);
        return {
            path: `M ${formatSvgNumber(source.x + 4)} ${formatSvgNumber(source.y)} C ${formatSvgNumber(x)} ${formatSvgNumber(y - 5)}, ${formatSvgNumber(x + 5)} ${formatSvgNumber(y + 5)}, ${formatSvgNumber(source.x)} ${formatSvgNumber(source.y + 4)}`,
            labelX: x,
            labelY: y
        };
    }
    const ux = dx / distance;
    const uy = dy / distance;
    const nx = -uy;
    const ny = ux;
    const horizontalWeight = Math.abs(ux);
    const endpointOffset = Math.min(10.5, Math.max(5.5, 5.6 + horizontalWeight * 4.4));
    const startX = source.x + ux * endpointOffset;
    const startY = source.y + uy * endpointOffset;
    const endX = target.x - ux * endpointOffset;
    const endY = target.y - uy * endpointOffset;
    const bendDirection = index % 2 === 0 ? 1 : -1;
    const bend = Math.min(7, Math.max(2, distance * 0.08)) * bendDirection * (1 + (index % 3) * 0.1);
    const controlX = clampGraphPoint((startX + endX) / 2 + nx * bend, 8, 92);
    const controlY = clampGraphPoint((startY + endY) / 2 + ny * bend, 11, 89);
    const innerDx = endX - startX;
    const innerDy = endY - startY;
    const pull = 0.42;
    const c1X = clampGraphPoint(startX + innerDx * pull + nx * bend * 0.38, 8, 92);
    const c1Y = clampGraphPoint(startY + innerDy * pull + ny * bend * 0.38, 10, 90);
    const c2X = clampGraphPoint(endX - innerDx * pull + nx * bend * 0.38, 8, 92);
    const c2Y = clampGraphPoint(endY - innerDy * pull + ny * bend * 0.38, 10, 90);
    return {
        path: `M ${formatSvgNumber(startX)} ${formatSvgNumber(startY)} C ${formatSvgNumber(c1X)} ${formatSvgNumber(c1Y)}, ${formatSvgNumber(c2X)} ${formatSvgNumber(c2Y)}, ${formatSvgNumber(endX)} ${formatSvgNumber(endY)}`,
        labelX: controlX,
        labelY: controlY
    };
}

function renderGraphEdgeSvg(edges = [], layout, selectedNodeId) {
    const visibleEdges = (edges || [])
        .filter((edge) => layout.has(String(edge.sourceId)) && layout.has(String(edge.targetId)))
        .slice(0, 80);
    if (!visibleEdges.length) {
        return "";
    }
    const edgeModels = visibleEdges
        .map((edge, index) => ({ edge, index, geometry: buildGraphEdgeGeometry(layout.get(String(edge.sourceId)), layout.get(String(edge.targetId)), index) }));
    const labeledEdges = edgeModels
        .filter(({ edge }) => selectedNodeId && (String(edge.sourceId) === String(selectedNodeId) || String(edge.targetId) === String(selectedNodeId)))
        .slice(0, 6);
    const fallbackLabels = visibleEdges.length <= 5 ? edgeModels.slice(0, Math.min(3, visibleEdges.length)) : [];
    const labels = labeledEdges.length ? labeledEdges : fallbackLabels;
    return `
        <svg class="graph-edge-svg" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
            <defs>
                <marker id="graph-arrow-default" markerWidth="7" markerHeight="7" refX="6.3" refY="3.5" orient="auto" markerUnits="strokeWidth">
                    <path d="M1,1 L6.5,3.5 L1,6 Z" fill="#64748b"></path>
                </marker>
                <marker id="graph-arrow-wiki" markerWidth="7" markerHeight="7" refX="6.3" refY="3.5" orient="auto" markerUnits="strokeWidth">
                    <path d="M1,1 L6.5,3.5 L1,6 Z" fill="#2563eb"></path>
                </marker>
                <marker id="graph-arrow-evidence" markerWidth="7" markerHeight="7" refX="6.3" refY="3.5" orient="auto" markerUnits="strokeWidth">
                    <path d="M1,1 L6.5,3.5 L1,6 Z" fill="#059669"></path>
                </marker>
                <marker id="graph-arrow-source" markerWidth="7" markerHeight="7" refX="6.3" refY="3.5" orient="auto" markerUnits="strokeWidth">
                    <path d="M1,1 L6.5,3.5 L1,6 Z" fill="#d97706"></path>
                </marker>
            </defs>
            ${edgeModels.map(({ edge, geometry }) => {
                const active = selectedNodeId && (String(edge.sourceId) === String(selectedNodeId) || String(edge.targetId) === String(selectedNodeId));
                const tone = graphEdgeTone(edge);
                return `
                    <path class="graph-edge-halo ${tone} ${active ? "active" : ""}" d="${geometry.path}"></path>
                    <path class="graph-edge-path ${tone} ${active ? "active" : ""}" d="${geometry.path}" marker-end="url(#graph-arrow-${tone})"><title>${escapeHtml(humanizeGraphEdgeType(edge.label || edge.type || "RELATION"))}</title></path>
                `;
            }).join("")}
        </svg>
        <div class="graph-edge-label-layer" aria-hidden="true">
            ${labels.map(({ edge, geometry }) => {
                const tone = graphEdgeTone(edge);
                return `<span class="graph-edge-label ${tone}" style="left:${formatSvgNumber(geometry.labelX)}%;top:${formatSvgNumber(geometry.labelY)}%;">${escapeHtml(graphEdgeLabel(edge))}</span>`;
            }).join("")}
        </div>
    `;
}

function renderGraphPage() {
    const graph = state.graph.spaceGraph;
    const nodes = graph?.nodes || [];
    const edges = graph?.edges || [];
    const selected = nodes.find((node) => String(node.id) === String(state.graph.selectedNodeId));
    const canvasNodes = buildGraphCanvasNodes(nodes, edges, state.graph.selectedNodeId);
    const canvasNodeIds = new Set(canvasNodes.map((node) => String(node.id)));
    const canvasEdges = (edges || [])
        .filter((edge) => canvasNodeIds.has(String(edge.sourceId)) && canvasNodeIds.has(String(edge.targetId)))
        .slice(0, GRAPH_CANVAS_EDGE_LIMIT);
    const graphLayout = buildGraphNodeLayout(canvasNodes, canvasEdges);
    const nodeTitles = new Map(nodes.map((node) => [String(node.id), node.title || node.id]));
    const nodeLabel = (nodeId) => nodeTitles.get(String(nodeId)) || nodeId;
    const graphCanvasHeight = Math.max(440, 180 + (graphLayout.rowCount || 3) * 72);
    const canvasSummary = nodes.length > canvasNodes.length
        ? `画布显示 ${formatNumber(canvasNodes.length)} / ${formatNumber(nodes.length)} 个重点节点`
        : "画布显示全部节点";
    return `
        <div class="graph-workbench">
            <section class="graph-canvas">
                <div class="graph-canvas-header">
                    <div>
                        <span class="context-kicker">主画布</span>
                        <h1>知识图谱</h1>
                        <p class="subtitle">把 Wiki、文档、引用与成果放在同一张关系图里，优先定位证据链、孤立页和缺失链接。</p>
                    </div>
                    <div class="page-actions">
                        <button class="ghost-button" type="button" data-action="refresh-page">刷新图谱</button>
                        ${selected ? `<button class="button" type="button" data-action="open-graph-node-inspector" data-node-id="${escapeHtml(selected.id)}">查看节点详情</button>` : ""}
                    </div>
                </div>
                <div class="graph-metrics">
                    <div class="metric"><strong>${formatNumber(graph?.nodeCount ?? nodes.length)}</strong><span>节点</span></div>
                    <div class="metric"><strong>${formatNumber(graph?.edgeCount ?? edges.length)}</strong><span>关系</span></div>
                    <div class="metric"><strong>${escapeHtml(graph?.rootNodeId ? nodeLabel(graph.rootNodeId) : "—")}</strong><span>入口节点</span></div>
                    <div class="metric"><strong>${selected ? escapeHtml(selected.title || selected.id) : "未选择"}</strong><span>当前节点</span></div>
                </div>
                <div class="graph-board">
                    <div class="graph-node-cloud" style="--graph-row-count:${graphLayout.rowCount || 3};--graph-canvas-height:${graphCanvasHeight}px;">
                        ${renderGraphEdgeSvg(canvasEdges, graphLayout, state.graph.selectedNodeId)}
                        ${canvasNodes.map((node, index) => `
                            <button class="graph-node graph-node-${String(node.type || "node").toLowerCase()} ${graphLayout.connectedIds?.has(String(node.id)) ? "graph-node-connected" : "graph-node-orphan"} ${node.root ? "root" : ""} ${String(node.id) === String(state.graph.selectedNodeId) ? "active" : ""}"
                                type="button"
                                data-action="select-graph-node"
                                data-node-id="${escapeHtml(node.id)}"
                                title="${escapeHtml(node.title || node.id)}"
                                style="--node-x:${(graphLayout.get(String(node.id)) || { x: (index * 37) % 92 }).x}%;--node-y:${(graphLayout.get(String(node.id)) || { y: (index * 53) % 86 }).y}%;">
                                <span>${escapeHtml(node.title || node.id)}</span>
                                <small>${escapeHtml(humanizeGraphNodeType(node.type || "NODE"))}</small>
                            </button>
                        `).join("") || emptyState("暂无图谱节点", "当前空间还没有可展示的知识图谱数据。")}
                    </div>
                    <div class="graph-edge-list" aria-label="${escapeHtml(canvasSummary)}">
                        <span class="context-kicker">关系边</span>
                        ${canvasEdges.slice(0, 24).map((edge) => `
                            <div class="graph-edge-row">
                                <strong>${escapeHtml(humanizeGraphEdgeType(edge.label || edge.type || "RELATION"))}</strong>
                                <span>${escapeHtml(nodeLabel(edge.sourceId))} → ${escapeHtml(nodeLabel(edge.targetId))}</span>
                            </div>
                        `).join("") || `<div class="muted">暂无关系边。</div>`}
                    </div>
                </div>
            </section>
        </div>
    `;
}

function renderGraphNodeInspector() {
    const detail = state.graph.nodeDetail;
    const neighborhood = state.graph.neighborhood;
    if (!detail) {
        return emptyState("暂无节点详情", "选择图谱节点后会加载详情。");
    }
    return `
        <div class="list-stack">
            <div class="list-item">
                <div class="list-item-header"><strong>${escapeHtml(detail.title || detail.id)}</strong>${tag(humanizeGraphNodeType(detail.type || "NODE"))}</div>
                <div class="muted">${escapeHtml(detail.subtitle || "")}</div>
                <div class="muted">状态：${escapeHtml(humanizeStatus(detail.status || "UNKNOWN"))} / 引用 ${formatNumber(detail.refId)}</div>
            </div>
            ${detail.attributes ? panel("属性", renderJson(detail.attributes)) : ""}
            ${panel("相邻关系", `
                <div class="list-stack">
                    ${(detail.adjacentEdges || []).map((edge) => `
                        <div class="list-item">
                            <div class="list-item-header"><strong>${escapeHtml(humanizeGraphEdgeType(edge.label || edge.type || "RELATION"))}</strong>${tag(`权重 ${formatNumber(edge.weight)}`)}</div>
                            <div class="muted">${escapeHtml(edge.sourceId)} → ${escapeHtml(edge.targetId)}</div>
                        </div>
                    `).join("") || emptyState("暂无相邻关系", "该节点暂时没有相邻边。")}
                </div>
            `)}
            ${neighborhood ? panel("邻域概览", `
                <div class="metric-row compact-metrics">
                    <div class="metric"><strong>${formatNumber(neighborhood.nodeCount)}</strong><span>节点</span></div>
                    <div class="metric"><strong>${formatNumber(neighborhood.edgeCount)}</strong><span>关系</span></div>
                </div>
            `) : ""}
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
            { eyebrow: "筛选", title: "先筛状态，再追失败原因", description: "把正在运行、失败和已完成的任务分开看，排查效率会更高。" },
            { eyebrow: "检查", title: "重试前先检查输入输出", description: "很多任务问题不是执行器本身，而是进入任务时的数据已经不对。" },
            { eyebrow: "追踪", title: "抽屉里的事件历史最有价值", description: "它能把一次失败究竟发生在哪个阶段串得更完整。" }
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
                                <option value="${status}" ${state.admin.taskFilters.taskStatus === status ? "selected" : ""}>${humanizeStatus(status)}</option>
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
                                <td>${escapeHtml(humanizeTaskType(task.taskType))}</td>
                                <td>${badge(task.taskStatus)}</td>
                                <td>${escapeHtml(humanizeTargetType(task.targetType || "UNKNOWN"))} / ${formatNumber(task.targetId)}</td>
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
                <div class="muted">类型：${escapeHtml(humanizeTaskType(task.taskType))}</div>
                <div class="muted">目标：${escapeHtml(humanizeTargetType(task.targetType || "UNKNOWN"))} / ${formatNumber(task.targetId)}</div>
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
                                <td>${escapeHtml(humanizeTargetType(log.targetType || "UNKNOWN"))} / ${formatNumber(log.targetId)}</td>
                                <td>${formatNumber(log.operatorId)}</td>
                                <td>${formatDate(log.createdAt)}</td>
                            </tr>
                        `).join("") || `<tr><td colspan="5">${emptyState("暂无审计日志", "当前条件没有审计记录。")}</td></tr>`}
                        </tbody>
                    </table>
                </div>
                ${state.admin.retrievalTrace ? `<div class="list-item" style="margin-top:14px;"><div class="list-item-header"><strong>检索链路</strong></div>${renderJson(state.admin.retrievalTrace)}</div>` : ""}
            `, { subtitle: "审计信息和追踪链路放在一起，更容易串起一次完整的系统行为路径。" })}
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
            startTaskPolling(merge.taskId, async (task) => {
                job.task = task;
                if (FINAL_TASK_STATUSES.has(task.taskStatus)) {
                    job.stage = task.taskStatus;
                    if (state.route?.name === "knowledge-detail" && Number(state.route.knowledgeBaseId) === Number(kbId)) {
                        state.knowledge.detail = await api.knowledge.get(kbId);
                        state.knowledge.documentsByKb[kbId] = await api.knowledge.documents(kbId);
                    }
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
                    if (!isTransientFetchError(error) || isChatRoute()) {
                        console.error(error);
                    }
                }
            });
            socket.addEventListener("close", () => {
                state.websocket.socket = null;
                state.websocket.status = "disconnected";
                state.websocket.connectPromise = null;
                paint();
                if (!state.websocket.manualClose && isChatRoute()) {
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
            state.chat.localsBySession[sessionId].assistantMessage.id = payload.assistantMessageId || envelope.messageId || state.chat.localsBySession[sessionId].assistantMessage.id;
            state.chat.localsBySession[sessionId].assistantMessage.artifactId = payload.artifactId || state.chat.localsBySession[sessionId].assistantMessage.artifactId;
            state.chat.localsBySession[sessionId].assistantMessage.artifactSpaceId = payload.artifactSpaceId || state.chat.localsBySession[sessionId].assistantMessage.artifactSpaceId;
        }
        if (isChatRoute() && Number(state.chat.activeSessionId) === sessionId) {
            await loadChatSessionData(sessionId);
        }
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

async function sendChatContent(content) {
    const session = activeSession();
    if (!session) {
        throw new ApiError("请先选择一个会话。", { code: "CHAT_SESSION_REQUIRED" });
    }
    const trimmedContent = String(content || "").trim();
    if (!trimmedContent) {
        return;
    }
    await ensureChatSocket();
    const requestId = `req-${Date.now()}`;
    const streamId = `stream-${Date.now()}`;
    state.chat.localsBySession[session.id] = {
        requestId,
        streamId,
        userMessage: { role: "USER", content: trimmedContent, status: "SUCCESS" },
        assistantMessage: { role: "ASSISTANT", content: "", status: "RUNNING" }
    };
    state.chat.draftsBySession[session.id] = "";
    paint();
    state.websocket.socket.send(JSON.stringify({
        event: "chat.message",
        requestId,
        streamId,
        sessionId: session.id,
        payload: { content: trimmedContent }
    }));
}

async function handleSendChatMessage(form) {
    const content = String(new FormData(form).get("content") || "").trim();
    if (!content) {
        return;
    }
    await sendChatContent(content);
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

function insertChatCommand(command) {
    const session = activeSession();
    if (!session || !command) {
        return;
    }
    ensureLocalDraft(session.id);
    const current = state.chat.draftsBySession[session.id] || "";
    state.chat.draftsBySession[session.id] = current.trim()
        ? `${current.trim()} ${command}`.trim()
        : command;
    paint();
    requestAnimationFrame(() => {
        const input = document.querySelector(`textarea[data-chat-draft="${session.id}"]`);
        if (input instanceof HTMLTextAreaElement) {
            input.focus();
            input.selectionStart = input.selectionEnd = input.value.length;
        }
    });
}

async function startTaskPolling(taskId, onUpdate) {
    if (taskPollers.has(taskId)) {
        return;
    }
    const poll = async () => {
        try {
            const task = await api.tasks.get(taskId);
            await onUpdate(task);
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
        title: `任务 ${formatNumber(task.id)}`,
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

async function handleCreateResearchQuestion(form) {
    const projectId = Number(form.dataset.projectId);
    const payload = Object.fromEntries(new FormData(form).entries());
    payload.researchProjectId = projectId;
    await api.personal.createQuestion(payload);
    queueToast("研究问题已创建。", "success");
    state.personal.selectedQuestionIdByProject[projectId] = null;
    await renderRoute();
}

async function handleGenerateResearchQuestionOverview(projectId, questionId) {
    await api.personal.generateQuestionOverview(questionId);
    await loadProjectQuestionWorkspace(projectId, questionId, true);
    queueToast("问题综述已生成。", "success");
    paint();
}

async function handleSourceFile(form) {
    const file = form.elements.file.files[0];
    const projectId = Number(form.dataset.projectId);
    if (!file) {
        return;
    }
    await api.personal.uploadSource(projectId, file, form.elements.title.value);
    queueToast("文件资料已提交导入。", "success");
    closeInlineFormDrawer();
    await renderRoute();
}

async function handleSourceUrl(form) {
    const projectId = Number(form.dataset.projectId);
    await api.personal.addUrlSource(projectId, Object.fromEntries(new FormData(form).entries()));
    queueToast("网址资料已创建。", "success");
    closeInlineFormDrawer();
    await renderRoute();
}

async function handleSourceText(form) {
    const projectId = Number(form.dataset.projectId);
    await api.personal.addTextSource(projectId, Object.fromEntries(new FormData(form).entries()));
    queueToast("文本资料已创建。", "success");
    closeInlineFormDrawer();
    await renderRoute();
}

function findSkill(skillId) {
    return state.studio.skills.find((skill) => skill.id === skillId) || null;
}

function isMcpSkill(skill) {
    return Boolean(skill?.mcpToolName);
}

function buildArtifactTaskParams(skill, topic, methodologyCardId = null, mcpUrl = "") {
    const params = {
        artifactType: skill.artifactType,
        topic,
        methodologyCardId: methodologyCardId || null
    };
    if (isMcpSkill(skill)) {
        params.mcpToolName = skill.mcpToolName;
        params.mcpArgs = { url: String(mcpUrl || "").trim() };
    }
    return params;
}

function renderSkillFormFields(skill) {
    if (!isMcpSkill(skill)) {
        return "";
    }
    return `
        <div class="field">
            <label>Bilibili 链接</label>
            <input name="mcpUrl" type="url" required placeholder="https://www.bilibili.com/video/BV...">
            <div class="muted">显式 MCP 工具：${escapeHtml(skill.mcpToolName)}，参数为 url</div>
        </div>
    `;
}

function renderSkillSummary(skill) {
    const entryTag = skill.entryType === "mcp_tool"
        ? tag("外部工具")
        : tag(humanizeArtifactType(skill.artifactType));
    const hint = skill.entryType === "mcp_tool"
        ? "需要链接输入"
        : skill.topicHint;
    return `
        <article class="skill-card">
            <div class="skill-card-header"><strong>${escapeHtml(skill.name)}</strong>${entryTag}</div>
            <p>${escapeHtml(skill.description)}</p>
            <span>${escapeHtml(hint || "输入主题后即可执行")}</span>
        </article>
    `;
}

function syncSkillFields(selectElement) {
    if (!(selectElement instanceof HTMLSelectElement)) {
        return;
    }
    const form = selectElement.form;
    if (!(form instanceof HTMLFormElement)) {
        return;
    }
    const skill = findSkill(selectElement.value);
    const mcpUrlField = form.querySelector('input[name="mcpUrl"]');
    if (mcpUrlField instanceof HTMLInputElement) {
        const visible = isMcpSkill(skill);
        const container = mcpUrlField.closest(".field");
        if (container) {
            container.hidden = !visible;
        }
        mcpUrlField.required = visible;
        if (!visible) {
            mcpUrlField.value = "";
        }
    }
}

async function createArtifactTask(spaceId, projectId, skillId, topic, methodologyCardId = null, mcpUrl = "") {
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
        params: buildArtifactTaskParams(skill, topic, methodologyCardId, mcpUrl)
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
    await createArtifactTask(spaceId, projectId, values.skillId, values.topic, values.methodologyCardId || null, values.mcpUrl || "");
    await renderRoute();
}

async function handleStudioTask(form) {
    const values = Object.fromEntries(new FormData(form).entries());
    const projectId = Number(values.projectId);
    const spaceId = resolveProjectSpaceIdById(projectId) || Number(form.dataset.spaceId);
    await createArtifactTask(spaceId, projectId, values.skillId, values.topic, null, values.mcpUrl || "");
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

function buildChatArtifactCommand(values) {
    const topic = String(values.topic || "").trim();
    const skill = findSkill(values.skillId);
    const artifactType = skill?.artifactType || String(values.artifactType || "READING_NOTES").trim();
    const projectId = Number(values.projectId);
    return `/成果 ${topic} type=${artifactType} project=${projectId}`;
}

async function handleChatArtifactForm(form) {
    const values = Object.fromEntries(new FormData(form).entries());
    const topic = String(values.topic || "").trim();
    const skill = findSkill(values.skillId);
    const projectId = Number(values.projectId);
    if (!skill) {
        throw new ApiError("请选择成果技能。", { code: "CHAT_ARTIFACT_SKILL_REQUIRED" });
    }
    if (!projectId) {
        throw new ApiError("请选择研究项目。", { code: "CHAT_ARTIFACT_PROJECT_REQUIRED" });
    }
    if (!topic) {
        throw new ApiError("请输入生成主题。", { code: "CHAT_ARTIFACT_TOPIC_REQUIRED" });
    }
    await sendChatContent(buildChatArtifactCommand(values));
    queueToast("已通过当前会话发起成果生成。", "success");
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

function openWikiInspector() {
    setDrawer("Wiki 关系与版本", renderWikiInspectorContent());
}

function openWikiGraphCard() {
    setDrawer("Wiki 图谱概览", renderWikiGraphCard());
}

function openWikiCreateForm() {
    state.ui.drawer = {
        title: "创建 Wiki 草稿",
        source: "inline-form",
        html: `
            <div class="modal-form-shell">
                <form id="wiki-create-form" data-space-id="${currentRouteSpaceId()}" class="inline-form modal-inline-form">
                    <div class="field"><label>标题</label><input name="title" required></div>
                    <div class="field"><label>内容</label><textarea class="editor-textarea" name="content" required></textarea></div>
                    <button class="button" type="submit">创建草稿</button>
                </form>
            </div>
        `
    };
    paint();
}

async function handleGraphFilter(form) {
    const values = Object.fromEntries(new FormData(form).entries());
    state.graph.filters = {
        nodeTypes: normalizeGraphFilterTypes(values.nodeTypes, "node"),
        edgeTypes: normalizeGraphFilterTypes(values.edgeTypes, "edge"),
        onlyPublished: Boolean(values.onlyPublished),
        onlyIndexed: Boolean(values.onlyIndexed)
    };
    state.graph.selectedNodeId = null;
    state.graph.nodeDetail = null;
    state.graph.neighborhood = null;
    await loadGraphPage(currentRouteSpaceId());
    paint();
}

async function selectGraphNode(nodeId) {
    state.graph.selectedNodeId = nodeId;
    const spaceId = currentRouteSpaceId();
    const [detail, neighborhood] = await Promise.all([
        api.graph.node(spaceId, nodeId, graphQueryParams()),
        api.graph.neighborhood(spaceId, nodeId, { ...graphQueryParams(), depth: 1 })
    ]);
    state.graph.nodeDetail = {
        ...normalizeGraphNode(detail),
        attributes: detail?.attributes,
        adjacentEdges: (detail?.adjacentEdges || []).map(normalizeGraphEdge)
    };
    state.graph.neighborhood = normalizeGraphResponse(neighborhood);
    setDrawer("图谱节点详情", renderGraphNodeInspector());
    paint();
}

async function openGraphFromNode(nodeId) {
    state.graph.selectedNodeId = nodeId;
    navigate(routeLink("graph", currentRouteSpaceId()));
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
                <div class="list-item-header"><strong>${escapeHtml(citation.title || humanizeSourceType(citation.sourceType) || "引用")}</strong>${citation.pageNo ? tag(`第 ${citation.pageNo} 页`) : ""}</div>
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
            case "toggle-context-rail":
                state.ui.contextRailCollapsed = !state.ui.contextRailCollapsed;
                paint();
                break;
            case "logout":
                await handleLogout();
                break;
            case "close-drawer":
                closeDrawer();
                break;
            case "open-inline-form":
                openInlineFormModal(target);
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
            case "insert-chat-command":
                insertChatCommand(target.dataset.command || "");
                break;
            case "open-chat-artifact-dialog":
                await openChatArtifactDialog();
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
            case "open-wiki-inspector":
                openWikiInspector();
                break;
            case "open-wiki-graph-card":
                openWikiGraphCard();
                break;
            case "open-wiki-create-form":
                openWikiCreateForm();
                break;
            case "select-graph-node":
                await selectGraphNode(target.dataset.nodeId);
                break;
            case "open-graph-node-inspector":
                await selectGraphNode(target.dataset.nodeId);
                break;
            case "open-graph-from-node":
                await openGraphFromNode(target.dataset.nodeId);
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
            case "select-question":
                await loadProjectQuestionWorkspace(Number(target.dataset.projectId), Number(target.dataset.questionId), true);
                paint();
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
                await openArtifactById(Number(target.dataset.artifactId), Number(target.dataset.spaceId));
                break;
            case "generate-question-overview":
                await handleGenerateResearchQuestionOverview(Number(target.dataset.projectId), Number(target.dataset.questionId));
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
    const submittedFromModal = Boolean(form.closest(".modal-dialog"));
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
            case "create-question-form":
                await handleCreateResearchQuestion(form);
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
            case "chat-artifact-form":
                await handleChatArtifactForm(form);
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
            case "graph-filter-form":
                await handleGraphFilter(form);
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
        if ((submittedFromModal || state.ui.drawer?.source === "inline-form") && state.ui.drawer?.source === "inline-form") {
            state.ui.drawer = null;
            paint();
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
    try {
        if (target instanceof HTMLSelectElement && target.name === "skillId") {
            syncSkillFields(target);
        }
        if (!(target instanceof HTMLSelectElement)) {
            return;
        }
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


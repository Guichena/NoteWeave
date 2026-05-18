const STORAGE_KEY = "noteweave.workspace.auth";
const API_BASE = "/api/v1";

let refreshPromise = null;

export class ApiError extends Error {
    constructor(message, { status = 0, code = "UNKNOWN", details = null } = {}) {
        super(message);
        this.name = "ApiError";
        this.status = status;
        this.code = code;
        this.details = details;
    }
}

export function loadAuthState() {
    try {
        return JSON.parse(localStorage.getItem(STORAGE_KEY) || "null");
    } catch (error) {
        localStorage.removeItem(STORAGE_KEY);
        return null;
    }
}

export function saveAuthState(auth) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(auth));
}

export function clearAuthState() {
    localStorage.removeItem(STORAGE_KEY);
    window.dispatchEvent(new CustomEvent("noteweave:auth-cleared"));
}

function buildQuery(query) {
    const params = new URLSearchParams();
    Object.entries(query || {}).forEach(([key, value]) => {
        if (value === undefined || value === null || value === "") {
            return;
        }
        params.append(key, value);
    });
    const serialized = params.toString();
    return serialized ? `?${serialized}` : "";
}

async function parseResponse(response) {
    if (response.status === 204) {
        return null;
    }
    const contentType = response.headers.get("content-type") || "";
    if (contentType.includes("application/json")) {
        return response.json();
    }
    const text = await response.text();
    return text ? { success: response.ok, data: text, message: text } : null;
}

async function refreshAccessToken() {
    const auth = loadAuthState();
    if (!auth?.refreshToken) {
        clearAuthState();
        throw new ApiError("登录已失效，请重新登录。", { status: 401, code: "UNAUTHORIZED" });
    }

    if (!refreshPromise) {
        refreshPromise = fetch(`${API_BASE}/auth/refresh`, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                Accept: "application/json"
            },
            body: JSON.stringify({ refreshToken: auth.refreshToken })
        })
            .then(async (response) => {
                const payload = await parseResponse(response);
                if (!response.ok || !payload?.success || !payload.data) {
                    throw new ApiError(payload?.message || "刷新令牌失败。", {
                        status: response.status,
                        code: payload?.code || "TOKEN_REFRESH_FAILED"
                    });
                }
                const nextAuth = {
                    accessToken: payload.data.accessToken,
                    refreshToken: payload.data.refreshToken || auth.refreshToken,
                    expiresIn: payload.data.expiresIn,
                    tokenType: payload.data.tokenType || "Bearer",
                    user: payload.data.user || auth.user || null
                };
                saveAuthState(nextAuth);
                return nextAuth;
            })
            .catch((error) => {
                clearAuthState();
                throw error;
            })
            .finally(() => {
                refreshPromise = null;
            });
    }

    return refreshPromise;
}

async function request(path, {
    method = "GET",
    query,
    body,
    formData,
    auth = true,
    retry = true
} = {}) {
    const authState = loadAuthState();
    const headers = {
        Accept: "application/json"
    };

    let payload = undefined;
    if (formData) {
        payload = formData;
    } else if (body !== undefined) {
        headers["Content-Type"] = "application/json";
        payload = JSON.stringify(body);
    }

    if (auth && authState?.accessToken) {
        headers.Authorization = `${authState.tokenType || "Bearer"} ${authState.accessToken}`;
    }

    const response = await fetch(`${API_BASE}${path}${buildQuery(query)}`, {
        method,
        headers,
        body: payload
    });

    if (response.status === 401 && auth && retry && authState?.refreshToken) {
        await refreshAccessToken();
        return request(path, { method, query, body, formData, auth, retry: false });
    }

    const envelope = await parseResponse(response);
    if (!response.ok || (envelope && envelope.success === false)) {
        throw new ApiError(envelope?.message || "请求失败。", {
            status: response.status,
            code: envelope?.code || "REQUEST_FAILED",
            details: envelope
        });
    }

    return envelope?.data ?? null;
}

function jsonBlob(value) {
    return new Blob([JSON.stringify(value)], { type: "application/json" });
}

export const api = {
    auth: {
        register: (payload) => request("/auth/register", { method: "POST", body: payload, auth: false }),
        login: (payload) => request("/auth/login", { method: "POST", body: payload, auth: false }),
        logout: (refreshToken) => request("/auth/logout", { method: "POST", body: { refreshToken } }),
        me: () => request("/users/me")
    },
    spaces: {
        list: () => request("/spaces", { query: { page: 1, pageSize: 100 } }),
        create: (payload) => request("/spaces", { method: "POST", body: payload }),
        members: (spaceId) => request(`/spaces/${spaceId}/members`, { query: { page: 1, pageSize: 100 } })
    },
    knowledge: {
        list: (spaceId) => request(`/team/spaces/${spaceId}/knowledge-bases`),
        get: (kbId) => request(`/team/knowledge-bases/${kbId}`),
        create: (spaceId, payload) => request(`/team/spaces/${spaceId}/knowledge-bases`, { method: "POST", body: payload }),
        documents: (kbId) => request(`/team/knowledge-bases/${kbId}/documents`),
        search: (kbId, keyword) => request(`/team/knowledge-bases/${kbId}/search`, { query: { keyword } }),
        initUpload: (kbId, payload) => request(`/team/knowledge-bases/${kbId}/documents/uploads/init`, { method: "POST", body: payload }),
        uploadChunk: (uploadId, chunkIndex, fileBlob, fileName) => {
            const formData = new FormData();
            formData.append("file", fileBlob, fileName);
            return request(`/team/document-uploads/${uploadId}/chunks`, {
                method: "POST",
                query: { chunkIndex },
                formData
            });
        },
        uploadStatus: (uploadId) => request(`/team/document-uploads/${uploadId}/status`),
        mergeUpload: (uploadId) => request(`/team/document-uploads/${uploadId}/merge`, { method: "POST" }),
        cancelUpload: (uploadId) => request(`/team/document-uploads/${uploadId}/cancel`, { method: "POST" })
    },
    chat: {
        listSessions: (spaceId) => request(`/spaces/${spaceId}/chat-sessions`),
        createSession: (payload) => request("/chat/sessions", { method: "POST", body: payload }),
        getMessages: (sessionId) => request(`/chat/sessions/${sessionId}/messages`),
        convertDraft: (sessionId) => request(`/chat/sessions/${sessionId}/convert-to-formal`, { method: "POST" }),
        discardDraft: (sessionId) => request(`/chat/sessions/${sessionId}/discard-draft`, { method: "POST" }),
        citations: (messageId) => request(`/chat/messages/${messageId}/citations`),
        feedback: (messageId, payload) => request(`/chat/messages/${messageId}/feedback`, { method: "POST", body: payload }),
        getFeedback: (messageId) => request(`/chat/messages/${messageId}/feedback`),
        wsTicket: () => request("/chat/ws-ticket", { method: "POST" })
    },
    personal: {
        listProjects: () => request("/personal/research-projects"),
        createProject: (payload) => request("/personal/research-projects", { method: "POST", body: payload }),
        getProject: (projectId) => request(`/personal/research-projects/${projectId}`),
        sources: (projectId) => request(`/personal/research-projects/${projectId}/sources`),
        uploadSource: (projectId, file, title) => {
            const formData = new FormData();
            formData.append("file", file, file.name);
            if (title) {
                formData.append("request", jsonBlob({ title }));
            }
            return request(`/personal/research-projects/${projectId}/sources/upload`, {
                method: "POST",
                formData
            });
        },
        addUrlSource: (projectId, payload) => request(`/personal/research-projects/${projectId}/sources/url`, { method: "POST", body: payload }),
        addTextSource: (projectId, payload) => request(`/personal/research-projects/${projectId}/sources/text`, { method: "POST", body: payload }),
        triggerImport: (sourceId) => request(`/personal/sources/${sourceId}/import`, { method: "POST" }),
        compileSource: (sourceId) => request(`/personal/sources/${sourceId}/compile`, { method: "POST" }),
        articleCards: (projectId) => request(`/personal/research-projects/${projectId}/article-cards`),
        conceptCards: (projectId) => request(`/personal/research-projects/${projectId}/concept-cards`),
        synthesisCards: (projectId) => request(`/personal/research-projects/${projectId}/synthesis-cards`),
        methodologyCards: (projectId) => request(`/personal/research-projects/${projectId}/methodology-cards`)
    },
    studio: {
        listSkills: () => request("/studio/skills"),
        createTask: (payload) => request("/studio/tasks", { method: "POST", body: payload }),
        getTask: (taskId) => request(`/studio/tasks/${taskId}`),
        cancelTask: (taskId) => request(`/studio/tasks/${taskId}/cancel`, { method: "POST" }),
        retryTask: (taskId) => request(`/studio/tasks/${taskId}/retry`, { method: "POST" })
    },
    artifacts: {
        listBySpace: (spaceId, query) => request(`/spaces/${spaceId}/artifacts`, { query }),
        listBySession: (sessionId) => request(`/chat/sessions/${sessionId}/artifacts`),
        get: (artifactId) => request(`/artifacts/${artifactId}`),
        update: (artifactId, payload) => request(`/artifacts/${artifactId}`, { method: "PUT", body: payload }),
        export: (artifactId, format = "markdown") => request(`/artifacts/${artifactId}/export`, { query: { format } }),
        relations: (artifactId) => request(`/artifacts/${artifactId}/card-relations`),
        regenerate: (artifactId, payload = {}) => request(`/artifacts/${artifactId}/generate`, { method: "POST", body: payload }),
        distill: (artifactId, payload) => request(`/artifacts/${artifactId}/distill-to-personal-wiki`, { method: "POST", body: payload })
    },
    wiki: {
        list: (spaceId) => request(`/team/spaces/${spaceId}/wiki-pages`),
        get: (pageId) => request(`/team/wiki-pages/${pageId}`),
        create: (spaceId, payload) => request(`/team/spaces/${spaceId}/wiki-pages`, { method: "POST", body: payload }),
        update: (pageId, payload) => request(`/team/wiki-pages/${pageId}`, { method: "PUT", body: payload }),
        publish: (pageId, payload = {}) => request(`/team/wiki-pages/${pageId}/publish`, { method: "POST", body: payload }),
        versions: (pageId) => request(`/team/wiki-pages/${pageId}/versions`),
        search: (spaceId, keyword) => request(`/team/spaces/${spaceId}/wiki-pages/search`, { query: { keyword } }),
        publishArtifact: (artifactId, payload) => request(`/artifacts/${artifactId}/publish-to-wiki`, { method: "POST", body: payload })
    },
    memory: {
        space: (spaceId) => request(`/spaces/${spaceId}/memory`),
        upsertSpace: (spaceId, payload) => request(`/spaces/${spaceId}/memory`, { method: "PUT", body: payload }),
        deleteSpaceItem: (spaceId, itemId) => request(`/spaces/${spaceId}/memory/${itemId}`, { method: "DELETE" }),
        user: () => request("/users/me/memory"),
        upsertUser: (payload) => request("/users/me/memory", { method: "PUT", body: payload }),
        deleteUserItem: (itemId) => request(`/users/me/memory/${itemId}`, { method: "DELETE" }),
        disableWriteback: () => request("/users/me/memory/disable", { method: "POST" }),
        enableWriteback: () => request("/users/me/memory/enable", { method: "POST" }),
        sessionSummaries: (sessionId) => request(`/chat/sessions/${sessionId}/summaries`)
    },
    admin: {
        tasks: (query) => request("/admin/tasks", { query }),
        task: (taskId) => request(`/admin/tasks/${taskId}`),
        taskEvents: (taskId, query) => request(`/admin/tasks/${taskId}/events`, { query }),
        retryTask: (taskId) => request(`/admin/tasks/${taskId}/retry`, { method: "POST" }),
        cancelTask: (taskId) => request(`/admin/tasks/${taskId}/cancel`, { method: "POST" }),
        dashboard: () => request("/admin/dashboard/summary"),
        health: () => request("/admin/health"),
        healthComponent: (component) => request(`/admin/health/${component}`),
        evalCases: (spaceId) => request(`/admin/spaces/${spaceId}/rag-eval-cases`),
        createEvalCase: (spaceId, payload) => request(`/admin/spaces/${spaceId}/rag-eval-cases`, { method: "POST", body: payload }),
        startEvalRun: (spaceId, payload) => request(`/admin/spaces/${spaceId}/rag-eval-runs`, { method: "POST", body: payload }),
        getEvalRun: (runId) => request(`/admin/rag-eval-runs/${runId}`),
        getEvalResults: (runId) => request(`/admin/rag-eval-runs/${runId}/results`),
        llmLogs: (query) => request("/admin/llm-call-logs", { query }),
        auditLogs: (query) => request("/admin/audit-logs", { query }),
        retrievalTrace: (traceId) => request(`/admin/retrieval-traces/${traceId}`)
    },
    tasks: {
        get: (taskId) => request(`/tasks/${taskId}`)
    }
};

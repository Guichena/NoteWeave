import { type AccessTokenProvider, refreshAuthSession } from "./auth";
import { ApiError } from "./error";

export type ApiEnvelope<T> = {
  success: boolean;
  code: string;
  message: string;
  data: T;
  request_id?: string;
};

export type ApiClientOptions = {
  baseUrl?: string;
  fetcher?: typeof fetch;
  accessTokenProvider?: AccessTokenProvider;
  correlationIdFactory?: () => string;
  onUnauthorized?: (error: ApiError) => void;
};

export class ApiClient {
  private readonly baseUrl: string;
  private readonly fetcher: typeof fetch;
  private readonly accessTokenProvider: AccessTokenProvider;
  private readonly correlationIdFactory: () => string;
  private readonly onUnauthorized: (error: ApiError) => void;

  constructor(options: ApiClientOptions = {}) {
    this.baseUrl = options.baseUrl ?? "";
    this.fetcher = options.fetcher ?? globalThis.fetch.bind(globalThis);
    this.accessTokenProvider = options.accessTokenProvider ?? (() => null);
    this.correlationIdFactory = options.correlationIdFactory ?? createCorrelationId;
    this.onUnauthorized = options.onUnauthorized ?? (() => undefined);
  }

  url(path: string) {
    return /^https?:\/\//i.test(path) ? path : `${this.baseUrl}${path}`;
  }

  async raw(path: string, init: RequestInit = {}, allowRefresh = true): Promise<Response> {
    const headers = new Headers(init.headers);
    if (!headers.has("Accept")) {
      headers.set("Accept", "application/json");
    }
    if (!headers.has("X-Correlation-ID")) {
      headers.set("X-Correlation-ID", this.correlationIdFactory());
    }
    const token = this.accessTokenProvider()?.trim();
    if (token && !headers.has("Authorization")) {
      headers.set("Authorization", `Bearer ${token}`);
    }
    const response = await this.fetcher(this.url(path), { ...init, headers });
    if (!response.ok) {
      const error = await toApiError(response);
      if (response.status === 401 && allowRefresh && !path.includes("/api/v2/auth/")) {
        try {
          const refreshed = await refreshAuthSession();
          if (refreshed?.access_token) {
            const retryHeaders = new Headers(init.headers);
            if (!retryHeaders.has("Accept")) {
              retryHeaders.set("Accept", "application/json");
            }
            if (!retryHeaders.has("X-Correlation-ID")) {
              retryHeaders.set("X-Correlation-ID", this.correlationIdFactory());
            }
            retryHeaders.set("Authorization", `Bearer ${refreshed.access_token}`);
            return this.raw(path, { ...init, headers: retryHeaders }, false);
          }
        } catch {
          // fall through to unauthorized handling
        }
        this.onUnauthorized(error);
      } else if (response.status === 401) {
        this.onUnauthorized(error);
      }
      throw error;
    }
    return response;
  }

  async request<T>(path: string, init: RequestInit = {}) {
    const response = await this.raw(path, init);
    if (response.status === 204) {
      return undefined as T;
    }
    const envelope = await readEnvelope<T>(response);
    if (!envelope.success) {
      throw envelopeError(response, envelope);
    }
    return envelope.data;
  }

  get<T>(path: string, init: RequestInit = {}) {
    return this.request<T>(path, init);
  }

  post<T>(path: string, body?: unknown, init: RequestInit = {}) {
    return this.json<T>(path, "POST", body, init);
  }

  put<T>(path: string, body?: unknown, init: RequestInit = {}) {
    return this.json<T>(path, "PUT", body, init);
  }

  patch<T>(path: string, body?: unknown, init: RequestInit = {}) {
    return this.json<T>(path, "PATCH", body, init);
  }

  delete(path: string, init: RequestInit = {}) {
    return this.raw(path, { ...init, method: "DELETE" }).then(() => undefined);
  }

  deleteJson<T>(path: string, init: RequestInit = {}) {
    return this.request<T>(path, { ...init, method: "DELETE" });
  }

  async text(path: string, init: RequestInit = {}) {
    return (await this.raw(path, init)).text();
  }

  async blob(path: string, init: RequestInit = {}) {
    return (await this.raw(path, init)).blob();
  }

  private json<T>(path: string, method: string, body: unknown, init: RequestInit) {
    const headers = new Headers(init.headers);
    headers.set("Content-Type", "application/json");
    return this.request<T>(path, {
      ...init,
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body)
    });
  }
}

async function toApiError(response: Response) {
  const requestId = response.headers.get("X-Request-ID") ?? "";
  const correlationId = response.headers.get("X-Correlation-ID") ?? "";
  const text = await response.text();
  let code = `HTTP_${response.status}`;
  let message = text || `请求失败：${response.status}`;
  let bodyRequestId = "";
  if (text) {
    try {
      const payload = JSON.parse(text) as Partial<ApiEnvelope<unknown>>;
      code = payload.code || code;
      message = payload.message || message;
      bodyRequestId = payload.request_id || "";
    } catch {}
  }
  return new ApiError(
    message,
    response.status,
    code,
    bodyRequestId || requestId,
    correlationId
  );
}

async function readEnvelope<T>(response: Response) {
  try {
    return await response.json() as ApiEnvelope<T>;
  } catch {
    throw new ApiError(
      "服务返回了无效的 JSON 响应",
      response.status,
      "INVALID_API_RESPONSE",
      response.headers.get("X-Request-ID") ?? "",
      response.headers.get("X-Correlation-ID") ?? ""
    );
  }
}

function envelopeError<T>(response: Response, envelope: ApiEnvelope<T>) {
  return new ApiError(
    envelope.message || "请求失败",
    response.status,
    envelope.code || "API_ERROR",
    envelope.request_id || response.headers.get("X-Request-ID") || "",
    response.headers.get("X-Correlation-ID") ?? ""
  );
}

function createCorrelationId() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `web-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

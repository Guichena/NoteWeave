import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiClient } from "./client";
import { ApiError } from "./error";

describe("ApiClient", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("binds the default fetch implementation to the global receiver", async () => {
    let receiver: unknown;
    vi.stubGlobal("fetch", vi.fn(function (this: unknown) {
      receiver = this;
      return Promise.resolve(jsonResponse({
        success: true,
        code: "OK",
        message: "ok",
        data: { ready: true }
      }));
    }));
    const client = new ApiClient();

    await expect(client.get("/health")).resolves.toEqual({ ready: true });
    expect(receiver).toBe(globalThis);
  });

  it("unwraps the API envelope and sends auth plus correlation headers", async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => jsonResponse({
      success: true,
      code: "OK",
      message: "ok",
      data: { workspace_id: "workspace" },
      request_id: "request-1"
    }));
    const client = new ApiClient({
      baseUrl: "http://backend",
      fetcher: fetcher as unknown as typeof fetch,
      accessTokenProvider: () => "token",
      correlationIdFactory: () => "correlation-1"
    });

    await expect(client.get<{ workspace_id: string }>("/api/v2/workspaces/1"))
      .resolves.toEqual({ workspace_id: "workspace" });

    const [url, init] = fetcher.mock.calls[0];
    const headers = new Headers(init?.headers);
    expect(url).toBe("http://backend/api/v2/workspaces/1");
    expect(headers.get("Authorization")).toBe("Bearer token");
    expect(headers.get("X-Correlation-ID")).toBe("correlation-1");
  });

  it("maps backend errors with request and correlation identity", async () => {
    const fetcher = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => jsonResponse({
      success: false,
      code: "WORKSPACE_FORBIDDEN",
      message: "无权访问",
      data: null,
      request_id: "request-body"
    }, 403, {
      "X-Request-ID": "request-header",
      "X-Correlation-ID": "correlation-2"
    }));
    const client = new ApiClient({ fetcher: fetcher as unknown as typeof fetch });

    const error = await client.get("/forbidden").catch((value) => value);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 403,
      code: "WORKSPACE_FORBIDDEN",
      requestId: "request-body",
      correlationId: "correlation-2",
      message: "无权访问"
    });
  });

  it("passes cancellation to fetch without converting AbortError", async () => {
    const abortController = new AbortController();
    const fetcher = vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
      expect(init?.signal).toBe(abortController.signal);
      throw new DOMException("aborted", "AbortError");
    });
    const client = new ApiClient({ fetcher: fetcher as unknown as typeof fetch });

    await expect(client.get("/slow", { signal: abortController.signal }))
      .rejects.toMatchObject({ name: "AbortError" });
  });

  it("notifies the authentication owner on 401", async () => {
    const onUnauthorized = vi.fn();
    const fetcher = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => jsonResponse({
      success: false,
      code: "AUTH_REQUIRED",
      message: "请登录",
      data: null,
      request_id: "request-auth"
    }, 401));
    const client = new ApiClient({
      fetcher: fetcher as unknown as typeof fetch,
      onUnauthorized
    });

    await expect(client.get("/protected")).rejects.toBeInstanceOf(ApiError);
    expect(onUnauthorized).toHaveBeenCalledWith(expect.objectContaining({
      status: 401,
      code: "AUTH_REQUIRED",
      requestId: "request-auth"
    }));
  });

  it("downloads blobs through the authenticated request path", async () => {
    const fetcher = vi.fn(async () => new Response("pdf-data", { status: 200 }));
    const client = new ApiClient({
      fetcher: fetcher as unknown as typeof fetch,
      accessTokenProvider: () => "download-token"
    });

    await expect(client.blob("/artifact.pdf")).resolves.toBeInstanceOf(Blob);
    const calls = fetcher.mock.calls as unknown as Array<[RequestInfo | URL, RequestInit?]>;
    const headers = new Headers(calls[0]?.[1]?.headers);
    expect(headers.get("Authorization")).toBe("Bearer download-token");
  });
});

function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers }
  });
}

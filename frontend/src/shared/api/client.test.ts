import { describe, expect, it, vi } from "vitest";
import { ApiClient } from "./client";
import { ApiError } from "./error";

describe("ApiClient", () => {
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
});

function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers }
  });
}

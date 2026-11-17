// @vitest-environment jsdom

import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiClient } from "./client";
import {
  type AuthSession, clearAuthSession, getAuthSession, inMemoryAccessTokenProvider,
  logoutAuthSession, refreshAuthSession, setAuthSession, validateAuthSession
} from "./auth";

afterEach(() => {
  clearAuthSession();
  vi.unstubAllGlobals();
});

describe("登录凭证并发", () => {
  it("多个刷新共享一次请求", async () => {
    setAuthSession(session("old"));
    const response = deferred<Response>();
    const fetcher = vi.fn(() => response.promise);
    vi.stubGlobal("fetch", fetcher);
    const first = refreshAuthSession();
    const second = refreshAuthSession();
    expect(first).toBe(second);
    expect(fetcher).toHaveBeenCalledTimes(1);
    response.resolve(ok(session("new")));
    await expect(Promise.all([first, second])).resolves.toEqual([session("new"), session("new")]);
    expect(getAuthSession()?.access_token).toBe("access-new");
  });

  it("旧刷新失败不会清除后来登录的账号", async () => {
    setAuthSession(session("old"));
    const response = deferred<Response>();
    vi.stubGlobal("fetch", vi.fn(() => response.promise));
    const refresh = refreshAuthSession();
    setAuthSession(session("other", "user-other"));
    response.resolve(unauthorized());
    await refresh;
    expect(getAuthSession()).toEqual(session("other", "user-other"));
  });

  it("退出后旧刷新成功也不能恢复登录", async () => {
    setAuthSession(session("old"));
    const response = deferred<Response>();
    vi.stubGlobal("fetch", vi.fn(() => response.promise));
    const refresh = refreshAuthSession();
    clearAuthSession();
    response.resolve(ok(session("new")));
    await expect(refresh).resolves.toBeNull();
    expect(getAuthSession()).toBeNull();
  });

  it("当前凭证刷新失败时清除对应会话", async () => {
    setAuthSession(session("old"));
    vi.stubGlobal("fetch", vi.fn(async () => unauthorized()));
    await expect(refreshAuthSession()).rejects.toThrow();
    expect(getAuthSession()).toBeNull();
  });

  it("旧退出请求完成不会清除新登录", async () => {
    setAuthSession(session("old"));
    const response = deferred<Response>();
    vi.stubGlobal("fetch", vi.fn(() => response.promise));
    const logout = logoutAuthSession();
    expect(getAuthSession()).toBeNull();
    setAuthSession(session("other", "user-other"));
    response.resolve(ok(null));
    await logout;
    expect(getAuthSession()).toEqual(session("other", "user-other"));
  });

  it("旧账号的会话验证结果不能覆盖新账号", async () => {
    setAuthSession(session("old"));
    const response = deferred<Response>();
    vi.stubGlobal("fetch", vi.fn(() => response.promise));
    const validation = validateAuthSession();
    setAuthSession(session("other", "user-other"));
    response.resolve(ok(session("old").user));
    await validation;
    expect(getAuthSession()).toEqual(session("other", "user-other"));
  });

  it("较晚返回的旧 401 复用已轮换凭证，不再刷新", async () => {
    setAuthSession(session("old"));
    const oldRequest = deferred<Response>();
    const fetcher = vi.fn((path: RequestInfo | URL, init?: RequestInit) => {
      if (String(path).endsWith("/auth/refresh")) return Promise.resolve(ok(session("new")));
      if (new Headers(init?.headers).get("Authorization") === "Bearer access-old") return oldRequest.promise;
      return Promise.resolve(ok({ ready: true }));
    });
    vi.stubGlobal("fetch", fetcher);
    const client = new ApiClient({ fetcher, accessTokenProvider: inMemoryAccessTokenProvider });
    const request = client.get("/api/v2/workspaces");
    await refreshAuthSession();
    oldRequest.resolve(unauthorized());
    await expect(request).resolves.toEqual({ ready: true });
    expect(fetcher.mock.calls.filter(([path]) => String(path).endsWith("/auth/refresh"))).toHaveLength(1);
  });

  it("旧账号请求失败时不能以新账号重放操作或触发退出", async () => {
    setAuthSession(session("old"));
    const response = deferred<Response>();
    const fetcher = vi.fn(() => response.promise);
    vi.stubGlobal("fetch", fetcher);
    const onUnauthorized = vi.fn(clearAuthSession);
    const client = new ApiClient({ fetcher, accessTokenProvider: inMemoryAccessTokenProvider, onUnauthorized });
    const request = client.get("/api/v2/workspaces");
    setAuthSession(session("other", "user-other"));
    response.resolve(unauthorized());
    await expect(request).rejects.toThrow();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(onUnauthorized).not.toHaveBeenCalled();
    expect(getAuthSession()?.user.user_id).toBe("user-other");
  });
});

function session(key: string, userId = "user-1"): AuthSession {
  return { access_token: `access-${key}`, refresh_token: `refresh-${key}`,
    access_expires_at: "", refresh_expires_at: "",
    user: { user_id: userId, username: userId, email: `${userId}@test.local`, display_name: userId } };
}

function ok(data: unknown) {
  return new Response(JSON.stringify({ success: true, data }), { status: 200 });
}

function unauthorized() {
  return new Response(JSON.stringify({ success: false, code: "AUTHENTICATION_INVALID", message: "登录已过期" }), { status: 401 });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}

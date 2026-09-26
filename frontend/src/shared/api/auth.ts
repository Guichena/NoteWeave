export type AccessTokenProvider = () => string | null | undefined;

export type AuthUser = {
  user_id: string;
  username: string;
  email: string;
  display_name: string;
};

export type AuthSession = {
  access_token: string;
  refresh_token: string;
  access_expires_at: string;
  refresh_expires_at: string;
  user: AuthUser;
};

export type RegisterInput = {
  username: string;
  email: string;
  password: string;
  displayName?: string;
};

const STORAGE_KEY = "noteweave.auth.session.v1";
const AUTH_CHANGED_EVENT = "noteweave:auth-changed";
let memorySession: AuthSession | null = readStoredSession();

export function setAccessToken(token: string | null | undefined) {
  if (!token?.trim()) {
    clearAuthSession();
    return;
  }
  const current = memorySession;
  memorySession = {
    access_token: token.trim(),
    refresh_token: current?.refresh_token ?? "",
    access_expires_at: current?.access_expires_at ?? "",
    refresh_expires_at: current?.refresh_expires_at ?? "",
    user: current?.user ?? { user_id: "", username: "", email: "", display_name: "" }
  };
  persist();
}

export function setAuthSession(session: AuthSession) {
  memorySession = session;
  persist();
}

export function clearAuthSession() {
  memorySession = null;
  if (typeof window !== "undefined") {
    window.sessionStorage.removeItem(STORAGE_KEY);
    window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
  }
}

export function getAuthSession() {
  return memorySession;
}

export const inMemoryAccessTokenProvider: AccessTokenProvider = () =>
  memorySession?.access_token?.trim() || null;

export async function login(loginValue: string, password: string) {
  const session = await authRequest<AuthSession>("/api/v2/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ login: loginValue, password })
  });
  setAuthSession(session);
  return session;
}

export async function register(input: RegisterInput) {
  const session = await authRequest<AuthSession>("/api/v2/auth/register", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      username: input.username,
      email: input.email,
      password: input.password,
      displayName: input.displayName
    })
  });
  setAuthSession(session);
  return session;
}

export async function refreshAuthSession() {
  const refreshToken = memorySession?.refresh_token;
  if (!refreshToken) {
    clearAuthSession();
    return null;
  }
  try {
    const session = await authRequest<AuthSession>("/api/v2/auth/refresh", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refresh_token: refreshToken })
    });
    setAuthSession(session);
    return session;
  } catch (error) {
    clearAuthSession();
    throw error;
  }
}

export async function validateAuthSession() {
  if (!memorySession?.access_token) {
    return null;
  }
  try {
    const user = await authRequest<AuthUser>("/api/v2/auth/session", {
      headers: { Authorization: `Bearer ${memorySession.access_token}` }
    });
    memorySession = { ...memorySession, user };
    persist();
    return memorySession;
  } catch {
    return refreshAuthSession();
  }
}

export async function logoutAuthSession() {
  const accessToken = memorySession?.access_token;
  try {
    if (accessToken) {
      await authRequest<void>("/api/v2/auth/logout", {
        method: "POST",
        headers: { Authorization: `Bearer ${accessToken}` }
      });
    }
  } finally {
    clearAuthSession();
  }
}

export function onAuthSessionChanged(listener: () => void) {
  if (typeof window === "undefined") {
    return () => undefined;
  }
  window.addEventListener(AUTH_CHANGED_EVENT, listener);
  return () => window.removeEventListener(AUTH_CHANGED_EVENT, listener);
}

function readStoredSession(): AuthSession | null {
  if (typeof window === "undefined") {
    return null;
  }
  const raw = window.sessionStorage.getItem(STORAGE_KEY);
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw) as AuthSession;
  } catch {
    window.sessionStorage.removeItem(STORAGE_KEY);
    return null;
  }
}

function persist() {
  if (typeof window === "undefined") {
    return;
  }
  if (memorySession) {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(memorySession));
  } else {
    window.sessionStorage.removeItem(STORAGE_KEY);
  }
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
}

async function authRequest<T>(path: string, init: RequestInit): Promise<T> {
  const baseUrl = import.meta.env.VITE_API_BASE_URL ?? "";
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers
  });
  const payload = await response.json().catch(() => null) as {
    success?: boolean;
    code?: string;
    message?: string;
    data?: T;
  } | null;
  if (!response.ok || !payload?.success) {
    throw new Error(payload?.message || `Authentication request failed (${response.status})`);
  }
  return payload.data as T;
}

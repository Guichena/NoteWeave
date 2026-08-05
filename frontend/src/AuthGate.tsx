import { type FormEvent, type ReactNode, useEffect, useState } from "react";
import {
  getAuthSession,
  login,
  logoutAuthSession,
  onAuthSessionChanged,
  refreshAuthSession,
  validateAuthSession
} from "./shared/api/auth";

export function AuthGate({ children }: { children: ReactNode }) {
  const [session, setSession] = useState(getAuthSession());
  const [checking, setChecking] = useState(Boolean(session));
  const [loginValue, setLoginValue] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => onAuthSessionChanged(() => setSession(getAuthSession())), []);

  useEffect(() => {
    if (!session) {
      setChecking(false);
      return;
    }
    let active = true;
    void validateAuthSession()
      .then((next) => active && setSession(next))
      .finally(() => active && setChecking(false));
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (!session?.access_expires_at) {
      return;
    }
    const delay = Math.max(5_000, new Date(session.access_expires_at).getTime() - Date.now() - 60_000);
    const timer = window.setTimeout(() => {
      void refreshAuthSession().catch(() => undefined);
    }, delay);
    return () => window.clearTimeout(timer);
  }, [session?.access_expires_at]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      setSession(await login(loginValue.trim(), password));
      setPassword("");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "登录失败");
    } finally {
      setSubmitting(false);
    }
  }

  if (checking) {
    return <main className="auth-shell"><section className="auth-card">正在验证会话…</section></main>;
  }
  if (!session) {
    return (
      <main className="auth-shell">
        <form className="auth-card" onSubmit={submit}>
          <p className="eyebrow">NoteWeave</p>
          <h1>登录工作台</h1>
          <label>用户名或邮箱<input autoComplete="username" value={loginValue} onChange={(event) => setLoginValue(event.target.value)} /></label>
          <label>密码<input type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} /></label>
          {error ? <p className="auth-error" role="alert">{error}</p> : null}
          <button disabled={submitting || !loginValue.trim() || password.length < 8}>{submitting ? "登录中…" : "登录"}</button>
        </form>
      </main>
    );
  }
  return (
    <>
      <div className="auth-session-bar">
        <span>{session.user.display_name || session.user.username}</span>
        <button className="secondary-button" onClick={() => void logoutAuthSession()}>退出登录</button>
      </div>
      {children}
    </>
  );
}

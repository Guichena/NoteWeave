import { type FormEvent, type ReactNode, useEffect, useState } from "react";
import { Moon, Sun } from "lucide-react";
import {
  getAuthSession,
  login,
  logoutAuthSession,
  onAuthSessionChanged,
  refreshAuthSession,
  register,
  validateAuthSession
} from "./shared/api/auth";
import { AuthCard } from "./AuthCard";
import { AuthContextPanel } from "./AuthContextPanel";
import { applyTheme, getInitialTheme, type ThemeMode } from "./shared/theme";

export function AuthGate({ children }: { children: ReactNode }) {
  const [session, setSession] = useState(getAuthSession());
  const [checking, setChecking] = useState(Boolean(session));
  const [mode, setMode] = useState<"login" | "register">("login");
  const [theme, setTheme] = useState<ThemeMode>(getInitialTheme);

  // Login form state
  const [loginValue, setLoginValue] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  // Register form state
  const [regLogin, setRegLogin] = useState("");
  const [regEmail, setRegEmail] = useState("");
  const [regDisplayName, setRegDisplayName] = useState("");
  const [regPassword, setRegPassword] = useState("");
  const [regConfirmPassword, setRegConfirmPassword] = useState("");

  useEffect(() => onAuthSessionChanged(() => setSession(getAuthSession())), []);

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  useEffect(() => {
    if (!session) {
      setTheme(getInitialTheme());
    }
  }, [session]);

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

  async function submitLogin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const formData = new FormData(form);
    const submittedLogin = String(formData.get("login") ?? "").trim();
    const submittedPassword = String(formData.get("password") ?? "");
    if (!submittedLogin || submittedPassword.length < 8 || submitting) return;
    setSubmitting(true);
    setError("");
    try {
      setSession(await login(submittedLogin, submittedPassword));
      setPassword("");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "登录失败，请检查账号与密码");
    } finally {
      setSubmitting(false);
    }
  }

  async function submitRegister(event: FormEvent) {
    event.preventDefault();
    if (!isRegisterValid || submitting) return;
    setSubmitting(true);
    setError("");
    try {
      const newSession = await register({
        username: regLogin.trim(),
        email: regEmail.trim(),
        password: regPassword,
        displayName: regDisplayName.trim() || undefined
      });
      setSession(newSession);
      setRegPassword("");
      setRegConfirmPassword("");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "注册失败，请稍后重试");
    } finally {
      setSubmitting(false);
    }
  }

  function handleSwitchMode(targetMode: "login" | "register") {
    setMode(targetMode);
    setError("");
  }

  const isRegUsernameValid =
    regLogin.trim().length >= 3 &&
    regLogin.trim().length <= 80 &&
    /^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(regLogin.trim());
  const isRegEmailValid = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(regEmail.trim());
  const isRegDisplayNameValid = !regDisplayName || regDisplayName.trim().length <= 120;
  const isRegPasswordValid = regPassword.length >= 8;
  const isRegConfirmPasswordValid = regConfirmPassword.length > 0 && regConfirmPassword === regPassword;
  const isRegPasswordMismatch = regConfirmPassword.length > 0 && regConfirmPassword !== regPassword;
  const isRegisterValid =
    isRegUsernameValid &&
    isRegEmailValid &&
    isRegDisplayNameValid &&
    isRegPasswordValid &&
    isRegConfirmPasswordValid;

  const authThemeToggle = (
    <button
      type="button"
      className="auth-theme-toggle"
      aria-label="切换明暗主题"
      onClick={() => setTheme((current) => current === "dark" ? "light" : "dark")}
    >
      {theme === "dark" ? <Sun size={16} aria-hidden="true" /> : <Moon size={16} aria-hidden="true" />}
      <span>{theme === "dark" ? "浅色" : "深色"}</span>
    </button>
  );

  if (checking) {
    return (
      <main className="auth-shell">
        {authThemeToggle}
        <div className="auth-container auth-container-single">
          <section className="auth-card auth-card-checking" aria-label="正在验证身份">
            <div className="auth-checking-indicator">
              <div className="auth-checking-spinner" aria-hidden="true" />
              <div className="auth-checking-text">
                <strong>正在验证会话</strong>
                <span>正在确认 NoteWeave 研究工作台凭证...</span>
              </div>
            </div>
          </section>
        </div>
      </main>
    );
  }

  if (!session) {
    return (
      <main className="auth-shell">
        {authThemeToggle}
        <div className="auth-container">
          <AuthContextPanel />

          <AuthCard
            mode={mode}
            handleSwitchMode={handleSwitchMode}
            submitLogin={submitLogin}
            loginValue={loginValue}
            setLoginValue={setLoginValue}
            password={password}
            setPassword={setPassword}
            submitting={submitting}
            error={error}
            submitRegister={submitRegister}
            regLogin={regLogin}
            setRegLogin={setRegLogin}
            isRegUsernameValid={isRegUsernameValid}
            regEmail={regEmail}
            setRegEmail={setRegEmail}
            isRegEmailValid={isRegEmailValid}
            regDisplayName={regDisplayName}
            setRegDisplayName={setRegDisplayName}
            isRegDisplayNameValid={isRegDisplayNameValid}
            regPassword={regPassword}
            setRegPassword={setRegPassword}
            isRegPasswordValid={isRegPasswordValid}
            regConfirmPassword={regConfirmPassword}
            setRegConfirmPassword={setRegConfirmPassword}
            isRegPasswordMismatch={isRegPasswordMismatch}
            isRegisterValid={isRegisterValid}
          />
        </div>
      </main>
    );
  }

  return <>{children}</>;
}

import { type FormEvent, useEffect, useRef, useState } from "react";
import { BookOpenText, Eye, EyeOff } from "lucide-react";

type AuthMode = "login" | "register";
type TextSetter = (value: string) => void;
type SubmitHandler = (event: FormEvent<HTMLFormElement>) => void | Promise<void>;

type AuthCardProps = {
  mode: AuthMode;
  handleSwitchMode: (mode: AuthMode) => void;
  submitLogin: SubmitHandler;
  loginValue: string;
  setLoginValue: TextSetter;
  password: string;
  setPassword: TextSetter;
  submitting: boolean;
  error: string;
  submitRegister: SubmitHandler;
  regLogin: string;
  setRegLogin: TextSetter;
  isRegUsernameValid: boolean;
  regEmail: string;
  setRegEmail: TextSetter;
  isRegEmailValid: boolean;
  regDisplayName: string;
  setRegDisplayName: TextSetter;
  isRegDisplayNameValid: boolean;
  regPassword: string;
  setRegPassword: TextSetter;
  isRegPasswordValid: boolean;
  regConfirmPassword: string;
  setRegConfirmPassword: TextSetter;
  isRegPasswordMismatch: boolean;
  isRegisterValid: boolean;
};

export function AuthCard(props: AuthCardProps) {
  const {
    mode,
    handleSwitchMode,
    submitLogin,
    loginValue,
    setLoginValue,
    password,
    setPassword,
    submitting,
    error,
    submitRegister,
    regLogin,
    setRegLogin,
    isRegUsernameValid,
    regEmail,
    setRegEmail,
    isRegEmailValid,
    regDisplayName,
    setRegDisplayName,
    isRegDisplayNameValid,
    regPassword,
    setRegPassword,
    isRegPasswordValid,
    regConfirmPassword,
    setRegConfirmPassword,
    isRegPasswordMismatch,
    isRegisterValid
  } = props;
  const loginInputRef = useRef<HTMLInputElement>(null);
  const loginPasswordRef = useRef<HTMLInputElement>(null);
  const [showLoginPassword, setShowLoginPassword] = useState(false);
  const [showRegisterPassword, setShowRegisterPassword] = useState(false);
  const [showRegisterConfirmation, setShowRegisterConfirmation] = useState(false);

  function syncLoginAutofill() {
    if (mode !== "login") return;
    const nextLoginValue = loginInputRef.current?.value ?? "";
    const nextPassword = loginPasswordRef.current?.value ?? "";
    if (nextLoginValue !== loginValue) setLoginValue(nextLoginValue);
    if (nextPassword !== password) setPassword(nextPassword);
  }

  useEffect(() => {
    if (mode !== "login") return;
    const timerIds = [0, 200, 800].map((delay) => window.setTimeout(syncLoginAutofill, delay));
    window.addEventListener("focus", syncLoginAutofill);
    return () => {
      timerIds.forEach((timerId) => window.clearTimeout(timerId));
      window.removeEventListener("focus", syncLoginAutofill);
    };
  }, [mode, loginValue, password]);

  return (
          <section className="auth-card">
            <div className="auth-header">
              <div className="auth-brand-badge">
                <BookOpenText size={18} strokeWidth={1.8} aria-hidden="true" />
                <span>NoteWeave</span>
              </div>
              <p className="auth-card-kicker">YOUR RESEARCH DESK</p>
              <h1>{mode === "login" ? "登录研究工作台" : "创建 NoteWeave 账号"}</h1>
              <p className="auth-subtitle">
                {mode === "login"
                  ? "连接来源，开启深度研究与知识整理"
                  : "来源驱动的研究笔记工作台"}
              </p>
            </div>

            <div className="auth-tabs" role="tablist" aria-label="身份验证模式切换">
              <button
                type="button"
                role="tab"
                aria-selected={mode === "login"}
                aria-controls="auth-login-panel"
                className={mode === "login" ? "active" : ""}
                onClick={() => handleSwitchMode("login")}
              >
                登录
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={mode === "register"}
                aria-controls="auth-register-panel"
                className={mode === "register" ? "active" : ""}
                onClick={() => handleSwitchMode("register")}
              >
                注册
              </button>
            </div>

            {mode === "login" ? (
              <form id="auth-login-panel" role="tabpanel" className="auth-form" onSubmit={submitLogin}>
                <div className="auth-field">
                  <label htmlFor="auth-login-input">用户名或邮箱</label>
                  <input
                    ref={loginInputRef}
                    id="auth-login-input"
                    name="login"
                    autoComplete="username"
                    maxLength={254}
                    placeholder="请输入用户名或注册邮箱"
                    value={loginValue}
                    onChange={(event) => setLoginValue(event.target.value)}
                    disabled={submitting}
                    required
                  />
                </div>

                <div className="auth-field">
                  <div className="auth-field-header">
                    <label htmlFor="auth-password-input">密码</label>
                    {password.length > 0 && password.length < 8 ? (
                      <span id="auth-login-password-hint" className="auth-field-hint auth-hint-warning">密码至少 8 位</span>
                    ) : null}
                  </div>
                  <div className="auth-password-control">
                    <input
                      ref={loginPasswordRef}
                      id="auth-password-input"
                      name="password"
                      type={showLoginPassword ? "text" : "password"}
                      autoComplete="current-password"
                      maxLength={128}
                      placeholder="请输入密码（至少 8 位）"
                      value={password}
                      onChange={(event) => setPassword(event.target.value)}
                      onAnimationStart={syncLoginAutofill}
                      disabled={submitting}
                      aria-invalid={password.length > 0 && password.length < 8}
                      aria-describedby={password.length > 0 && password.length < 8 ? "auth-login-password-hint" : undefined}
                      required
                    />
                    <button
                      type="button"
                      className="auth-password-toggle"
                      aria-label={showLoginPassword ? "隐藏登录密码" : "显示登录密码"}
                      aria-pressed={showLoginPassword}
                      disabled={submitting}
                      onClick={() => setShowLoginPassword((visible) => !visible)}
                    >
                      {showLoginPassword ? <EyeOff size={17} aria-hidden="true" /> : <Eye size={17} aria-hidden="true" />}
                    </button>
                  </div>
                </div>

                {error ? (
                  <div className="auth-error" role="alert">
                    <span>{error}</span>
                  </div>
                ) : null}

                <button
                  type="submit"
                  className="auth-submit-btn"
                  disabled={submitting}
                  aria-busy={submitting}
                >
                  {submitting ? "正在登录..." : "登录"}
                </button>
                <p className="auth-form-note">登录后进入你的独立工作台，资料、研究与 Wiki 共享同一条证据链。</p>
              </form>
            ) : (
              <form id="auth-register-panel" role="tabpanel" className="auth-form" onSubmit={submitRegister}>
                <div className="auth-field">
                  <div className="auth-field-header">
                    <label htmlFor="auth-reg-login">用户名</label>
                    {regLogin.length > 0 && !isRegUsernameValid ? (
                      <span id="auth-reg-username-hint" className="auth-field-hint auth-hint-warning">3-80 位字母、数字或符号</span>
                    ) : null}
                  </div>
                  <input
                    id="auth-reg-login"
                    autoComplete="username"
                    maxLength={80}
                    placeholder="3-80 位字母、数字或符号"
                    value={regLogin}
                    onChange={(event) => setRegLogin(event.target.value)}
                    disabled={submitting}
                    aria-invalid={regLogin.length > 0 && !isRegUsernameValid}
                    aria-describedby={regLogin.length > 0 && !isRegUsernameValid ? "auth-reg-username-hint" : undefined}
                    required
                  />
                </div>

                <div className="auth-field">
                  <div className="auth-field-header">
                    <label htmlFor="auth-reg-email">注册邮箱</label>
                    {regEmail.length > 0 && !isRegEmailValid ? (
                      <span id="auth-reg-email-hint" className="auth-field-hint auth-hint-warning">请输入有效的邮箱格式</span>
                    ) : null}
                  </div>
                  <input
                    id="auth-reg-email"
                    type="email"
                    autoComplete="email"
                    maxLength={254}
                    placeholder="user@example.com"
                    value={regEmail}
                    onChange={(event) => setRegEmail(event.target.value)}
                    disabled={submitting}
                    aria-invalid={regEmail.length > 0 && !isRegEmailValid}
                    aria-describedby={regEmail.length > 0 && !isRegEmailValid ? "auth-reg-email-hint" : undefined}
                    required
                  />
                </div>

                <div className="auth-field">
                  <div className="auth-field-header">
                    <label htmlFor="auth-reg-displayname">显示名称 (可选)</label>
                    {regDisplayName.length > 120 ? (
                      <span id="auth-reg-displayname-hint" className="auth-field-hint auth-hint-warning">显示名称最多 120 字</span>
                    ) : null}
                  </div>
                  <input
                    id="auth-reg-displayname"
                    autoComplete="nickname"
                    maxLength={120}
                    placeholder="如：研究员 小张"
                    value={regDisplayName}
                    onChange={(event) => setRegDisplayName(event.target.value)}
                    disabled={submitting}
                    aria-invalid={regDisplayName.length > 120}
                    aria-describedby={regDisplayName.length > 120 ? "auth-reg-displayname-hint" : undefined}
                  />
                </div>

                <div className="auth-field">
                  <div className="auth-field-header">
                    <label htmlFor="auth-reg-password">设置密码</label>
                    {regPassword.length > 0 && !isRegPasswordValid ? (
                      <span id="auth-reg-password-hint" className="auth-field-hint auth-hint-warning">密码至少 8 位</span>
                    ) : null}
                  </div>
                  <div className="auth-password-control">
                    <input
                      id="auth-reg-password"
                      type={showRegisterPassword ? "text" : "password"}
                      autoComplete="new-password"
                      maxLength={128}
                      placeholder="密码至少 8 位"
                      value={regPassword}
                      onChange={(event) => setRegPassword(event.target.value)}
                      disabled={submitting}
                      aria-invalid={regPassword.length > 0 && !isRegPasswordValid}
                      aria-describedby={regPassword.length > 0 && !isRegPasswordValid ? "auth-reg-password-hint" : undefined}
                      required
                    />
                    <button
                      type="button"
                      className="auth-password-toggle"
                      aria-label={showRegisterPassword ? "隐藏设置密码" : "显示设置密码"}
                      aria-pressed={showRegisterPassword}
                      disabled={submitting}
                      onClick={() => setShowRegisterPassword((visible) => !visible)}
                    >
                      {showRegisterPassword ? <EyeOff size={17} aria-hidden="true" /> : <Eye size={17} aria-hidden="true" />}
                    </button>
                  </div>
                </div>

                <div className="auth-field">
                  <div className="auth-field-header">
                    <label htmlFor="auth-reg-confirm">确认密码</label>
                    {regConfirmPassword.length === 0 ? null : isRegPasswordMismatch ? (
                      <span id="auth-reg-confirm-hint" className="auth-field-hint auth-hint-danger">两次密码不一致</span>
                    ) : null}
                  </div>
                  <div className="auth-password-control">
                    <input
                      id="auth-reg-confirm"
                      type={showRegisterConfirmation ? "text" : "password"}
                      autoComplete="new-password"
                      maxLength={128}
                      placeholder="请再次输入密码"
                      value={regConfirmPassword}
                      onChange={(event) => setRegConfirmPassword(event.target.value)}
                      disabled={submitting}
                      aria-invalid={isRegPasswordMismatch}
                      aria-describedby={isRegPasswordMismatch ? "auth-reg-confirm-hint" : undefined}
                      required
                    />
                    <button
                      type="button"
                      className="auth-password-toggle"
                      aria-label={showRegisterConfirmation ? "隐藏确认密码" : "显示确认密码"}
                      aria-pressed={showRegisterConfirmation}
                      disabled={submitting}
                      onClick={() => setShowRegisterConfirmation((visible) => !visible)}
                    >
                      {showRegisterConfirmation ? <EyeOff size={17} aria-hidden="true" /> : <Eye size={17} aria-hidden="true" />}
                    </button>
                  </div>
                </div>

                {error ? (
                  <div className="auth-error" role="alert">
                    <span>{error}</span>
                  </div>
                ) : null}

                <button
                  type="submit"
                  className="auth-submit-btn"
                  disabled={submitting || !isRegisterValid}
                  aria-busy={submitting}
                >
                  {submitting ? "正在注册..." : "创建账号并登录"}
                </button>
                <p className="auth-form-note">创建后即可建立第一个工作台，你可以随时切换明暗主题。</p>
              </form>
            )}
          </section>
  );
}

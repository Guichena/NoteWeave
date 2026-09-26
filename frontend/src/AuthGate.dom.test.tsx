// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AuthGate } from "./AuthGate";
import * as authApi from "./shared/api/auth";

vi.mock("./shared/api/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof authApi>();
  return {
    ...actual,
    login: vi.fn(),
    register: vi.fn(),
    validateAuthSession: vi.fn().mockResolvedValue(null),
    getAuthSession: vi.fn().mockReturnValue(null),
    logoutAuthSession: vi.fn().mockResolvedValue(undefined)
  };
});

describe("AuthGate", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it("renders login panel by default and validates login input", () => {
    render(<AuthGate><div>Protected Content</div></AuthGate>);
    expect(screen.getAllByText("登录研究工作台")[0]).toBeTruthy();

    const submitBtn = screen.getByRole("button", { name: "登录" }) as HTMLButtonElement;
    expect(submitBtn.disabled).toBe(false);

    fireEvent.change(screen.getByLabelText("用户名或邮箱"), { target: { value: "testuser" } });
    fireEvent.change(screen.getByLabelText("密码"), { target: { value: "12345678" } });
    expect(submitBtn.disabled).toBe(false);
  });

  it("syncs browser autofill and exposes password visibility controls", async () => {
    render(<AuthGate><div>Protected Content</div></AuthGate>);
    const loginInput = screen.getByLabelText("用户名或邮箱") as HTMLInputElement;
    const passwordInput = screen.getByLabelText("密码") as HTMLInputElement;
    const submitBtn = screen.getByRole("button", { name: "登录" }) as HTMLButtonElement;

    Object.defineProperty(loginInput, "value", { configurable: true, writable: true, value: "autofilled-user" });
    Object.defineProperty(passwordInput, "value", { configurable: true, writable: true, value: "autofilled-password" });
    fireEvent.animationStart(passwordInput, { animationName: "noteweave-auth-autofill" });

    await waitFor(() => expect(submitBtn.disabled).toBe(false));
    const toggle = screen.getByRole("button", { name: "显示登录密码" });
    fireEvent.click(toggle);
    expect(passwordInput.type).toBe("text");
    expect(screen.getByRole("button", { name: "隐藏登录密码" })).toBeTruthy();
  });

  it("handles successful login", async () => {
    const mockSession = {
      access_token: "token123",
      refresh_token: "ref123",
      access_expires_at: "",
      refresh_expires_at: "",
      user: { user_id: "u1", username: "testuser", email: "test@example.com", display_name: "Test User" }
    };
    vi.mocked(authApi.login).mockResolvedValueOnce(mockSession);

    render(<AuthGate><div>Protected Content</div></AuthGate>);
    expect(screen.getAllByText("登录研究工作台")[0]).toBeTruthy();

    fireEvent.change(screen.getByLabelText("用户名或邮箱"), { target: { value: "testuser" } });
    fireEvent.change(screen.getByLabelText("密码"), { target: { value: "12345678" } });
    fireEvent.click(screen.getByRole("button", { name: "登录" }));

    await waitFor(() => {
      expect(authApi.login).toHaveBeenCalledWith("testuser", "12345678");
      expect(screen.getByText("Protected Content")).toBeTruthy();
    });
  });

  it("handles login failure and displays error alert", async () => {
    vi.mocked(authApi.login).mockRejectedValueOnce(new Error("密码不正确"));

    render(<AuthGate><div>Protected Content</div></AuthGate>);
    expect(screen.getAllByText("登录研究工作台")[0]).toBeTruthy();

    fireEvent.change(screen.getByLabelText("用户名或邮箱"), { target: { value: "testuser" } });
    fireEvent.change(screen.getByLabelText("密码"), { target: { value: "wrongpass" } });
    fireEvent.click(screen.getByRole("button", { name: "登录" }));

    const errorAlert = await screen.findByRole("alert");
    expect(errorAlert.textContent).toContain("密码不正确");
  });

  it("switches to register tab, enforces validation and registers successfully", async () => {
    const mockSession = {
      access_token: "regtoken",
      refresh_token: "regref",
      access_expires_at: "",
      refresh_expires_at: "",
      user: { user_id: "u2", username: "newuser", email: "new@example.com", display_name: "New User" }
    };
    vi.mocked(authApi.register).mockResolvedValueOnce(mockSession);

    render(<AuthGate><div>Protected Content</div></AuthGate>);
    expect(screen.getAllByText("登录研究工作台")[0]).toBeTruthy();

    fireEvent.click(screen.getByRole("tab", { name: "注册" }));
    expect(screen.getByText("创建 NoteWeave 账号")).toBeTruthy();

    const regBtn = screen.getByRole("button", { name: "创建账号并登录" }) as HTMLButtonElement;
    expect(regBtn.disabled).toBe(true);

    // Fill invalid data
    fireEvent.change(screen.getByLabelText("用户名"), { target: { value: "ab" } }); // too short
    fireEvent.change(screen.getByLabelText("注册邮箱"), { target: { value: "invalid-email" } });
    fireEvent.change(screen.getByLabelText("设置密码"), { target: { value: "123" } });
    fireEvent.change(screen.getByLabelText("确认密码"), { target: { value: "456" } });
    expect(regBtn.disabled).toBe(true);

    // Fill valid data
    fireEvent.change(screen.getByLabelText("用户名"), { target: { value: "newuser" } });
    fireEvent.change(screen.getByLabelText("注册邮箱"), { target: { value: "new@example.com" } });
    fireEvent.change(screen.getByLabelText("显示名称 (可选)"), { target: { value: "New User" } });
    fireEvent.change(screen.getByLabelText("设置密码"), { target: { value: "password123" } });
    fireEvent.change(screen.getByLabelText("确认密码"), { target: { value: "password123" } });

    expect(regBtn.disabled).toBe(false);
    fireEvent.click(regBtn);

    await waitFor(() => {
      expect(authApi.register).toHaveBeenCalledWith({
        username: "newuser",
        email: "new@example.com",
        password: "password123",
        displayName: "New User"
      });
      expect(screen.getByText("Protected Content")).toBeTruthy();
    });
  });
});

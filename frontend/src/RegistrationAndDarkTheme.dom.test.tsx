// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AuthGate } from "./AuthGate";
import * as authApi from "./shared/api/auth";
import { applyTheme, getInitialTheme, toggleTheme } from "./shared/theme";

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

describe("Registration Validation & Dark Theme", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    document.documentElement.removeAttribute("data-theme");
  });

  afterEach(() => {
    cleanup();
  });

  describe("Registration Form Validation", () => {
    it("disables submit button when confirm password is empty or mismatch", () => {
      render(<AuthGate><div>Protected Content</div></AuthGate>);
      fireEvent.click(screen.getByRole("tab", { name: "注册" }));

      const regBtn = screen.getByRole("button", { name: "创建账号并登录" }) as HTMLButtonElement;
      expect(regBtn.disabled).toBe(true);

      fireEvent.change(screen.getByLabelText("用户名"), { target: { value: "validuser" } });
      fireEvent.change(screen.getByLabelText("注册邮箱"), { target: { value: "user@example.com" } });
      fireEvent.change(screen.getByLabelText("设置密码"), { target: { value: "password123" } });
      // Empty confirm password
      expect(regBtn.disabled).toBe(true);

      // Password mismatch
      fireEvent.change(screen.getByLabelText("确认密码"), { target: { value: "differentpass" } });
      expect(regBtn.disabled).toBe(true);
      expect(screen.getByText("两次密码不一致")).toBeTruthy();
    });

    it("disables submit button when username exceeds 80 characters", () => {
      render(<AuthGate><div>Protected Content</div></AuthGate>);
      fireEvent.click(screen.getByRole("tab", { name: "注册" }));

      const regBtn = screen.getByRole("button", { name: "创建账号并登录" }) as HTMLButtonElement;

      const longUsername = "a".repeat(81);
      fireEvent.change(screen.getByLabelText("用户名"), { target: { value: longUsername } });
      fireEvent.change(screen.getByLabelText("注册邮箱"), { target: { value: "user@example.com" } });
      fireEvent.change(screen.getByLabelText("设置密码"), { target: { value: "password123" } });
      fireEvent.change(screen.getByLabelText("确认密码"), { target: { value: "password123" } });

      expect(regBtn.disabled).toBe(true);
      expect(screen.getByText("3-80 位字母、数字或符号")).toBeTruthy();
    });

    it("disables submit button when displayName exceeds 120 characters", () => {
      render(<AuthGate><div>Protected Content</div></AuthGate>);
      fireEvent.click(screen.getByRole("tab", { name: "注册" }));

      const regBtn = screen.getByRole("button", { name: "创建账号并登录" }) as HTMLButtonElement;

      fireEvent.change(screen.getByLabelText("用户名"), { target: { value: "validuser" } });
      fireEvent.change(screen.getByLabelText("注册邮箱"), { target: { value: "user@example.com" } });
      fireEvent.change(screen.getByLabelText("显示名称 (可选)"), { target: { value: "x".repeat(121) } });
      fireEvent.change(screen.getByLabelText("设置密码"), { target: { value: "password123" } });
      fireEvent.change(screen.getByLabelText("确认密码"), { target: { value: "password123" } });

      expect(regBtn.disabled).toBe(true);
      expect(screen.getByText("显示名称最多 120 字")).toBeTruthy();
    });

    it("submits register form successfully with valid inputs and enters workspace", async () => {
      const mockSession = {
        access_token: "regtoken123",
        refresh_token: "regref123",
        access_expires_at: "",
        refresh_expires_at: "",
        user: { user_id: "u123", username: "validuser", email: "user@example.com", display_name: "Test User" }
      };
      vi.mocked(authApi.register).mockResolvedValueOnce(mockSession);

      render(<AuthGate><div>Protected Content</div></AuthGate>);
      fireEvent.click(screen.getByRole("tab", { name: "注册" }));

      const regBtn = screen.getByRole("button", { name: "创建账号并登录" }) as HTMLButtonElement;

      fireEvent.change(screen.getByLabelText("用户名"), { target: { value: "validuser" } });
      fireEvent.change(screen.getByLabelText("注册邮箱"), { target: { value: "user@example.com" } });
      fireEvent.change(screen.getByLabelText("显示名称 (可选)"), { target: { value: "Test User" } });
      fireEvent.change(screen.getByLabelText("设置密码"), { target: { value: "password123" } });
      fireEvent.change(screen.getByLabelText("确认密码"), { target: { value: "password123" } });

      expect(regBtn.disabled).toBe(false);
      fireEvent.click(regBtn);

      await waitFor(() => {
        expect(authApi.register).toHaveBeenCalledWith({
          username: "validuser",
          email: "user@example.com",
          password: "password123",
          displayName: "Test User"
        });
        expect(screen.getByText("Protected Content")).toBeTruthy();
      });
    });

    it("displays error alert message when backend returns an error", async () => {
      vi.mocked(authApi.register).mockRejectedValueOnce(new Error("用户名已被占用"));

      render(<AuthGate><div>Protected Content</div></AuthGate>);
      fireEvent.click(screen.getByRole("tab", { name: "注册" }));

      fireEvent.change(screen.getByLabelText("用户名"), { target: { value: "existinguser" } });
      fireEvent.change(screen.getByLabelText("注册邮箱"), { target: { value: "existing@example.com" } });
      fireEvent.change(screen.getByLabelText("设置密码"), { target: { value: "password123" } });
      fireEvent.change(screen.getByLabelText("确认密码"), { target: { value: "password123" } });

      fireEvent.click(screen.getByRole("button", { name: "创建账号并登录" }));

      const alert = await screen.findByRole("alert");
      expect(alert.textContent).toContain("用户名已被占用");
    });
  });

  describe("Dark Theme Switching", () => {
    it("restores and toggles the selected theme on the authentication screen", () => {
      localStorage.setItem("noteweave.theme.v1", "dark");

      render(<AuthGate><div>Protected Content</div></AuthGate>);

      expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
      fireEvent.click(screen.getByRole("button", { name: "切换明暗主题" }));
      expect(document.documentElement.getAttribute("data-theme")).toBe("light");
      expect(localStorage.getItem("noteweave.theme.v1")).toBe("light");
    });

    it("initializes theme and toggles data-theme attribute on root element", () => {
      expect(document.documentElement.getAttribute("data-theme")).toBeNull();
      const initial = getInitialTheme();
      expect(initial).toBe("light");

      applyTheme("dark");
      expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
      expect(localStorage.getItem("noteweave.theme.v1")).toBe("dark");

      const next = toggleTheme();
      expect(next).toBe("light");
      expect(document.documentElement.getAttribute("data-theme")).toBe("light");
      expect(localStorage.getItem("noteweave.theme.v1")).toBe("light");
    });
  });
});

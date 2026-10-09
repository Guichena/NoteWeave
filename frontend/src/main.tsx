// 必须保持在第一行：开发环境的 mock 需要在任何 API client 绑定 fetch 之前完成包装。
import "./dev/installMockApi";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { AuthGate } from "./AuthGate";
import "./styles/index.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <AuthGate><App /></AuthGate>
  </StrictMode>
);

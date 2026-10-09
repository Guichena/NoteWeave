import { configDefaults, defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { loadEnv } from "vite";
import { fileURLToPath } from "node:url";

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  test: {
    exclude: [...configDefaults.exclude, "e2e/**"]
  },
  server: {
    port: 5173,
    proxy: {
      // 可在 .env.local 中改为其他后端地址，例如 compose 栈中的 nginx。
      "/api": loadEnv(mode, fileURLToPath(new URL(".", import.meta.url)), "NOTEWEAVE_").NOTEWEAVE_API_PROXY || "http://localhost:8081"
    }
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes("node_modules")) return undefined;
          if (id.includes("react") || id.includes("scheduler")) return "vendor-react";
          if (id.includes("lucide-react")) return "vendor-icons";
          if (id.includes("marked") || id.includes("dompurify") || id.includes("katex")) {
            return "vendor-markdown";
          }
          return "vendor-ui";
        }
      }
    }
  }
}));

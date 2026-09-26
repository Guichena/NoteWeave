import { configDefaults, defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  test: {
    exclude: [...configDefaults.exclude, "e2e/**"]
  },
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8081"
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
});

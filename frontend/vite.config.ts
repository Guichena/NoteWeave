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
  }
});

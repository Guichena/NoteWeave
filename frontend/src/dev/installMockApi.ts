/**
 * 仅开发环境使用的接口 mock：在没有模型 Key 的情况下，为研究运行、问答、产物等
 * 依赖模型输出的页面提供设计数据。通过 frontend/.env.local 中的
 * VITE_NOTEWEAVE_MOCK=1 开启；登录、工作台等其余请求仍然访问真实后端。
 * 生产构建会移除这段分支，fixtures 也只在分支内部通过动态 import 加载。
 *
 * 必须先于任何绑定 `fetch` 的模块导入（见 main.tsx）。
 */
if (import.meta.env.DEV && import.meta.env.VITE_NOTEWEAVE_MOCK === "1") {
  const realFetch = globalThis.fetch.bind(globalThis);
  let routes: Promise<typeof import("./mockRoutes")> | null = null;

  globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = new Request(input, init);
    const url = new URL(request.url, window.location.origin);
    routes ??= import("./mockRoutes");
    const response = await (await routes).handleMockRequest(request.method, url, request);
    return response ?? realFetch(input, init);
  };
  console.info("[noteweave] mock API enabled for design fixtures");
}

export {};

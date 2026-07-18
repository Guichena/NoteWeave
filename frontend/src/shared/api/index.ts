import { inMemoryAccessTokenProvider } from "./auth";
import { ApiClient } from "./client";

export { setAccessToken, type AccessTokenProvider } from "./auth";
export { ApiClient, type ApiClientOptions, type ApiEnvelope } from "./client";
export { ApiError } from "./error";

export const apiClient = new ApiClient({
  baseUrl: import.meta.env.VITE_API_BASE_URL ?? "",
  accessTokenProvider: inMemoryAccessTokenProvider
});

export const apiUrl = (path: string) => apiClient.url(path);
export const get = <T>(path: string, init?: RequestInit) => apiClient.get<T>(path, init);
export const post = <T>(path: string, body?: unknown, init?: RequestInit) => apiClient.post<T>(path, body, init);
export const put = <T>(path: string, body?: unknown, init?: RequestInit) => apiClient.put<T>(path, body, init);
export const patch = <T>(path: string, body?: unknown, init?: RequestInit) => apiClient.patch<T>(path, body, init);
export const del = (path: string, init?: RequestInit) => apiClient.delete(path, init);
export const delJson = <T>(path: string, init?: RequestInit) => apiClient.deleteJson<T>(path, init);
export const request = (path: string, init?: RequestInit) => apiClient.raw(path, init);
export const requestText = (path: string, init?: RequestInit) => apiClient.text(path, init);

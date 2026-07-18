export type AccessTokenProvider = () => string | null | undefined;

let accessToken = "";

export function setAccessToken(token: string | null | undefined) {
  accessToken = token?.trim() ?? "";
}

export const inMemoryAccessTokenProvider: AccessTokenProvider = () => accessToken || null;

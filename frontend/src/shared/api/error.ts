export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string,
    readonly requestId: string,
    readonly correlationId: string
  ) {
    super(message);
    this.name = "ApiError";
  }
}

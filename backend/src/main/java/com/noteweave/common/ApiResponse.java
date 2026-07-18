package com.noteweave.common;

public record ApiResponse<T>(boolean success, String code, String message, T data, String requestId) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "OK", "ok", data, RequestContext.currentRequestId());
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, code, message, null, RequestContext.currentRequestId());
    }
}

package com.noteweave.common;

public record ApiResponse<T>(boolean success, String code, String message, T data) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "OK", "ok", data);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(false, code, message, null);
    }
}

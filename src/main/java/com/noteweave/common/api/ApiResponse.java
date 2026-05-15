package com.noteweave.common.api;

import com.noteweave.common.error.ErrorCode;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApiResponse<T> {

    private final boolean success;
    private final String code;
    private final String message;
    private final T data;
    private final Instant timestamp;
    private final String requestId;

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .code(ErrorCode.OK.name())
                .message("success")
                .data(data)
                .timestamp(Instant.now())
                .requestId(RequestIdHolder.get())
                .build();
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .code(errorCode.name())
                .message(message)
                .data(null)
                .timestamp(Instant.now())
                .requestId(RequestIdHolder.get())
                .build();
    }
}

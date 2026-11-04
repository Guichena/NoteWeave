package com.noteweave.common;

import java.io.IOException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MissingRequestHeaderException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException ex, jakarta.servlet.http.HttpServletRequest request) {
        if (ex.code().startsWith("RESEARCH_AGENT_COMPLETION")) {
            log.warn("Research agent completion rejected: code={}, reason={}", ex.code(), ex.getMessage());
        } else if (request != null && request.getRequestURI() != null
                && request.getRequestURI().startsWith("/internal/")) {
            // Worker 回调被拒时没有用户界面可看，只能靠日志定位
            log.warn("Internal callback rejected: path={}, code={}, reason={}",
                    request.getRequestURI(), ex.code(), ex.getMessage());
        }
        return ResponseEntity.status(ex.status()).body(ApiResponse.error(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_FAILED", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> handleMalformedRequest(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error("MALFORMED_REQUEST", "request body is malformed"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.error("UNSUPPORTED_MEDIA_TYPE", "request content type is not supported"));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiResponse<Void>> handleMissingRequestHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(
                "MISSING_REQUEST_HEADER",
                "required request header is missing: " + ex.getHeaderName()
        ));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void handleDisconnectedAsyncClient(AsyncRequestNotUsableException ex) {
        log.debug("Async client disconnected before the response completed: {}", ex.getMessage());
    }

    @ExceptionHandler(IOException.class)
    Object handleDisconnectedIo(IOException ex) {
        // SSE clients commonly close the socket while the server is still flushing.
        // Do not try to serialize an ApiResponse into an already-committed stream;
        // that produces a misleading 500 and a second converter exception.
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        if (message.contains("broken pipe")
                || message.contains("connection reset")
                || message.contains("stream closed")) {
            log.debug("I/O response interrupted before completion: {}", ex.getMessage());
            return null;
        }
        log.error("Unhandled response I/O exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "服务内部错误"));
    }

    /** 请求方法不对属于调用方错误，返回 405，不记成服务内部错误。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        var response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            response.allow(ex.getSupportedHttpMethods().toArray(org.springframework.http.HttpMethod[]::new));
        }
        return response.body(ApiResponse.error("METHOD_NOT_ALLOWED", "request method is not supported: " + ex.getMethod()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("RESOURCE_NOT_FOUND", "request resource does not exist"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception ex) {
        log.error("Unhandled API exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "服务内部错误"));
    }
}

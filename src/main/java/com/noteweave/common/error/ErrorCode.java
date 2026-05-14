package com.noteweave.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    OK(HttpStatus.OK, "success"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "bad request"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "unauthorized"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "not found"),
    CONFLICT(HttpStatus.CONFLICT, "conflict"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "validation failed"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal error"),

    USER_ALREADY_EXISTS(HttpStatus.CONFLICT, "user already exists"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "invalid credentials"),
    SPACE_NOT_FOUND(HttpStatus.NOT_FOUND, "space not found"),
    SPACE_ACCESS_DENIED(HttpStatus.FORBIDDEN, "space access denied"),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "member not found"),
    OWNER_CANNOT_BE_REMOVED(HttpStatus.BAD_REQUEST, "owner cannot be removed");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}

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
    OWNER_CANNOT_BE_REMOVED(HttpStatus.BAD_REQUEST, "owner cannot be removed"),

    TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "task not found"),
    TASK_ACCESS_DENIED(HttpStatus.FORBIDDEN, "task access denied"),
    TASK_INVALID_STATUS(HttpStatus.BAD_REQUEST, "task status is invalid"),
    TASK_RETRY_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "task retry is not allowed"),
    TASK_WORKER_NOT_FOUND(HttpStatus.BAD_REQUEST, "task worker not found"),

    KNOWLEDGE_BASE_NOT_FOUND(HttpStatus.NOT_FOUND, "knowledge base not found"),
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "document not found"),
    UPLOAD_NOT_FOUND(HttpStatus.NOT_FOUND, "upload not found"),
    UPLOAD_ACCESS_DENIED(HttpStatus.FORBIDDEN, "upload access denied"),
    UPLOAD_INVALID_CHUNK(HttpStatus.BAD_REQUEST, "upload invalid chunk"),
    UPLOAD_CHUNK_INCOMPLETE(HttpStatus.BAD_REQUEST, "upload chunk incomplete"),
    UPLOAD_MERGE_FAILED(HttpStatus.BAD_REQUEST, "upload merge failed"),
    STORAGE_OBJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "storage object not found"),
    STORAGE_OPERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "storage operation failed"),
    KAFKA_DISPATCH_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "kafka dispatch failed"),

    DOCUMENT_PARSE_FAILED(HttpStatus.BAD_REQUEST, "document parse failed"),
    DOCUMENT_EMPTY_TEXT(HttpStatus.BAD_REQUEST, "document text is empty"),
    DOCUMENT_CHUNK_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "document chunk failed"),
    DOCUMENT_INDEX_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "document index failed"),
    ES_INDEX_NOT_AVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "elasticsearch index is not available"),
    ES_QUERY_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "elasticsearch query failed"),
    UNSUPPORTED_DOCUMENT_TYPE(HttpStatus.BAD_REQUEST, "unsupported document type"),

    CHAT_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "chat session not found"),
    CHAT_SESSION_ACCESS_DENIED(HttpStatus.FORBIDDEN, "chat session access denied"),
    CHAT_SESSION_TYPE_UNSUPPORTED(HttpStatus.BAD_REQUEST, "chat session type unsupported"),
    CHAT_DRAFT_INVALID_STATE(HttpStatus.BAD_REQUEST, "chat draft state is invalid"),
    CHAT_MESSAGE_EMPTY(HttpStatus.BAD_REQUEST, "chat message is empty"),
    RAG_RETRIEVAL_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "rag retrieval failed"),
    RAG_NO_EVIDENCE(HttpStatus.BAD_REQUEST, "rag evidence is empty"),
    LLM_CONFIG_MISSING(HttpStatus.BAD_REQUEST, "llm config missing"),
    LLM_CALL_FAILED(HttpStatus.BAD_GATEWAY, "llm call failed"),
    CITATION_SAVE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "citation save failed"),
    WS_TICKET_INVALID(HttpStatus.UNAUTHORIZED, "websocket ticket is invalid"),
    WS_TICKET_EXPIRED(HttpStatus.UNAUTHORIZED, "websocket ticket is expired"),
    CHAT_RUNTIME_NOT_FOUND(HttpStatus.NOT_FOUND, "chat runtime not found"),
    CHAT_RUNTIME_ALREADY_RUNNING(HttpStatus.CONFLICT, "chat runtime already running"),
    CHAT_RUNTIME_STOP_FAILED(HttpStatus.BAD_REQUEST, "chat runtime stop failed"),
    CHAT_STREAM_FAILED(HttpStatus.BAD_GATEWAY, "chat stream failed");

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

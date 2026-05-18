package com.noteweave.admin.model;

public enum AuditAction {
    USER_DISABLE,
    USER_ENABLE,
    SPACE_ARCHIVE,
    TASK_RETRY,
    TASK_CANCEL,
    TASK_MARK_FAILED,
    DOCUMENT_DELETE,
    RESOURCE_CLEANUP,
    PROMPT_ACTIVATE,
    EVAL_RUN_START
}

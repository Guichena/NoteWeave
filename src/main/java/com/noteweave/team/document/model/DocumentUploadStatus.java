package com.noteweave.team.document.model;

public enum DocumentUploadStatus {
    INIT,
    UPLOADING,
    MERGED,
    PROCESSING,
    INDEXED,
    FAILED,
    CANCELLED,
    EXPIRED,
    DELETED
}

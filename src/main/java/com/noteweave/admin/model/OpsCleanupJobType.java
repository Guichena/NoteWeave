package com.noteweave.admin.model;

public enum OpsCleanupJobType {
    UPLOAD_EXPIRED,
    MINIO_ORPHAN_OBJECT,
    ES_ORPHAN_INDEX,
    SOFT_DELETE_PURGE,
    FAILED_MERGE_OBJECT
}

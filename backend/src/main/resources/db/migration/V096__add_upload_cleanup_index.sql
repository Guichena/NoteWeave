create index idx_document_upload_cleanup
    on document_upload(status, updated_at);

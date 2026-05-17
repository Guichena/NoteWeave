ALTER TABLE retrieval_trace
    ADD COLUMN retrieval_mode VARCHAR(32) NULL AFTER retrieved_chunk_count,
    ADD COLUMN bm25_count INT NULL AFTER retrieval_mode,
    ADD COLUMN vector_count INT NULL AFTER bm25_count,
    ADD COLUMN fusion_count INT NULL AFTER vector_count,
    ADD COLUMN fallback_used BIT NOT NULL DEFAULT b'0' AFTER fusion_count,
    ADD COLUMN trace_json TEXT NULL AFTER fallback_used;

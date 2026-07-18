alter table source_chunk
    add constraint uk_source_chunk_snapshot_chunk_no unique (source_snapshot_id, chunk_no);

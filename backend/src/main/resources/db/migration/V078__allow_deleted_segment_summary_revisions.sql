alter table segment_summary_revision drop constraint ck_segment_summary_revision_status;

alter table segment_summary_revision add constraint ck_segment_summary_revision_status
    check (status in ('BUILDING', 'READY', 'FAILED', 'STALE', 'DELETED'));

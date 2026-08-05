alter table worker_callback_receipt
    add column payload_digest char(64) null;

alter table worker_callback_receipt
    add column result_ref varchar(255) null;

alter table worker_callback_receipt
    add column completed_at timestamp null;

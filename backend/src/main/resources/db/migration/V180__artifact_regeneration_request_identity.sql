alter table artifact_job_run
    add column client_request_id varchar(120) null,
    add column client_request_payload_json longtext null;

create unique index uq_artifact_run_client_request
    on artifact_job_run(artifact_job_id, client_request_id);

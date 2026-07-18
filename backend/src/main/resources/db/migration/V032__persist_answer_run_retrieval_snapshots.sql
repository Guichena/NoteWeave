alter table answer_run
    add column retrieval_plan_json longtext null;

alter table answer_run
    add column evidence_bundle_json longtext null;

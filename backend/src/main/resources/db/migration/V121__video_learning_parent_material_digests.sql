alter table video_learning_request
    add column material_content_digest char(64) null;

alter table video_learning_request
    add column knowledge_plan_content_digest char(64) null;

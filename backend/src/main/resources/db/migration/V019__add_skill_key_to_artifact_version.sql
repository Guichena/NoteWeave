alter table artifact_version add column skill_key varchar(64) null;

update artifact_version av
set skill_key = (
    select aj.skill_key
    from artifact_job aj
    where aj.id = av.artifact_job_id
)
where skill_key is null;

create index idx_artifact_version_skill_created on artifact_version(skill_key, created_at);

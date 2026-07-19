alter table research_run
    add column retrieval_mode varchar(32) not null default 'WEB_ONLY';

update research_run
set retrieval_mode = 'SOURCES_ONLY'
where source_scope_json is not null and source_scope_json <> '[]';

alter table research_run
    add constraint ck_research_run_retrieval_mode
        check (retrieval_mode in ('WEB_ONLY', 'WEB_PLUS_SEEDS', 'SOURCES_ONLY'));

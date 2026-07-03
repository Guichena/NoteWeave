alter table workspace add column wiki_enabled boolean not null default false;

create index idx_workspace_wiki_enabled on workspace(wiki_enabled, updated_at);

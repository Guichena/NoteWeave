alter table workspace
    add column source_catalog_version bigint not null default 1;

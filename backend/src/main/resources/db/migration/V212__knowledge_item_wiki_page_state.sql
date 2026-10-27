-- WIKI-03：区分自动生成的 Wiki 页面与人工编辑过的页面。
-- 自动 ingest 命中人工页面时不再覆盖最新版本，只把页面标记为待确认；
-- 用户再次编辑会把状态改回 MANUAL，自动更新继续让路。
alter table knowledge_item add column wiki_page_state varchar(32) null;

-- 目录类查询按状态过滤，只回填可判定的存量行：由系统写入且未被人工改动的页面视为自动页面。
update knowledge_item
set wiki_page_state = case
    when updated_by like 'SYSTEM:%' then 'AUTO'
    else 'MANUAL'
end
where item_type = 'WIKI';

-- WIKI-03 数据侧跟进：把被 V212 回填错判成 MANUAL 的自动页面重置为 AUTO。
--
-- 背景：V212__knowledge_item_wiki_page_state.sql 用 `updated_by like 'SYSTEM:%'` 回填，
-- 自动生成、但最后一次写入者不是 SYSTEM 的页面会被标成 MANUAL，此后自动 ingest 永远让路。
--
-- 用法（先看命中，再决定是否更新）：
--   mysql -h <host> -u <user> -p <db> -e "set @apply = 0; source scripts/repair-wiki-page-state.sql"
--   mysql -h <host> -u <user> -p <db> -e "set @apply = 1; source scripts/repair-wiki-page-state.sql"
--
-- 前置条件：库已升到 V212 及以上（knowledge_item.wiki_page_state 存在）。
-- 判定依据只能是 created_by / updated_by：正文里的自动页标记会被写入路径剥掉，不能用来反查。
-- 更新后可重复执行，第二次命中 0 行。

set @apply = coalesce(@apply, 0);

-- 1. 预览命中：自动创建、却被回填成人工页的行
select ki.workspace_id,
       count(*) as pages,
       min(ki.created_at) as oldest,
       max(ki.updated_at) as newest
from knowledge_item ki
where ki.item_type = 'WIKI'
  and ki.wiki_page_state = 'MANUAL'
  and ki.created_by like 'SYSTEM:%'
group by ki.workspace_id
order by pages desc;

-- 2. 逐行明细（确认这些页面确实由自动 ingest 创建、用户没有编辑过）
select ki.id, ki.workspace_id, ki.title, ki.created_by, ki.updated_by,
       ki.created_at, ki.updated_at
from knowledge_item ki
where ki.item_type = 'WIKI'
  and ki.wiki_page_state = 'MANUAL'
  and ki.created_by like 'SYSTEM:%'
order by ki.workspace_id, ki.created_at
limit 500;

-- 3. 重置（@apply = 0 时不会命中任何行）
update knowledge_item
set wiki_page_state = 'AUTO'
where @apply = 1
  and item_type = 'WIKI'
  and wiki_page_state = 'MANUAL'
  and created_by like 'SYSTEM:%';

select row_count() as updated_rows, @apply as applied;

-- 4. 复核：应为 0
select count(*) as remaining
from knowledge_item
where item_type = 'WIKI'
  and wiki_page_state = 'MANUAL'
  and created_by like 'SYSTEM:%';

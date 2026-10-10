-- 结构感知切片记录片段所在的页码范围，引用据此给出页码；非分页资料（Markdown、转写稿等）为空。
alter table source_chunk add column page_start int null;
alter table source_chunk add column page_end int null;

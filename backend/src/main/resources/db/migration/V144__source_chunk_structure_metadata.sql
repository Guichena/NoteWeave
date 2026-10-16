-- 结构解析产生的元素类型、章节路径、页码和坐标；旧片段保持为空。
alter table source_chunk add column structure_metadata_json longtext null;

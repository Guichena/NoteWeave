-- 记忆展示文本的向量缓存。相关度计算优先读这里，不在每次编译时重新请求嵌入模型。
-- revision.confidence 与证据后验仍然分开存放。

alter table memory_item
    add column text_embedding longtext null;

alter table memory_item
    add column text_embedding_hash varchar(64) null;

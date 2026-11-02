-- 第六期 F6-03：Wiki 自动页的可信编译标记。
-- 只有发布时写入受支持策略版本、且全部 citation 依赖仍然合法的自动页才算当前证据；
-- 存量自动页保持 NULL，显式表示未认证，不按依赖为空推断为已认证；人工页不要求该标记。
alter table knowledge_version
    add column provenance_schema_version varchar(64) null;

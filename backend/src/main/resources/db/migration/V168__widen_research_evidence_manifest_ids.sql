-- 外部网页的快照路径由网址生成，长网址会超过 128 个字符，写入证据清单失败后整次研究无法收尾
alter table research_evidence_manifest_item modify column source_snapshot_id varchar(512) null;
alter table research_evidence_manifest_item modify column passage_id varchar(512) null;
alter table research_evidence_manifest_item modify column source_id varchar(255) null;
-- 证据表的快照路径与快照归档表保持同样的长度上限
alter table source_evidence modify column snapshot_key varchar(512) null;

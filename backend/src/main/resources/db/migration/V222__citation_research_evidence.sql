-- 问答可以依据研究报告里核对过的网页原文作答（REPORT 通道）。这类引用没有工作台资料，
-- 改为记录研究运行编号和证据键；资料引用照旧写 source/snapshot/chunk，外键不变。
-- 回答里的 [n] 按引用顺序对应引用卡片，研究引用也必须写进 citation，编号才不会错位。
alter table citation modify column source_id varchar(36) null;
alter table citation modify column source_snapshot_id varchar(36) null;
-- 研究引用的定位信息是网页地址，与 source_evidence.source_url 同宽
alter table citation modify column location_info varchar(1000) null;
alter table citation add column research_run_id varchar(36) null;
alter table citation add column research_evidence_key varchar(64) null;

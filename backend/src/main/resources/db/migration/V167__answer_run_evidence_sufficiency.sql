-- 本次回答的证据充分性。旧行留空，接口省略该字段。
alter table answer_run add column evidence_sufficiency varchar(32) null;

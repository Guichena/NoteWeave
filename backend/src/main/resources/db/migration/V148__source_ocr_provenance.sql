-- 旧片段没有可核验的文字来源，历史数据保持空值。
alter table source_chunk add column text_origin varchar(16) null;
alter table source_chunk add column ocr_confidence double null;

-- 回答保存当时片段的来源，历史回答不通过后来的片段补填来源。
alter table answer_run_evidence_manifest add column text_origin varchar(16) null;
alter table answer_run_evidence_manifest add column ocr_confidence double null;

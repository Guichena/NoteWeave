-- Wiki 扩展页的到达路径。种子页和旧行留空，已有插入语句会在应用启动时带上这一列。
alter table answer_run_evidence_manifest add column graph_path_json varchar(4000) null;

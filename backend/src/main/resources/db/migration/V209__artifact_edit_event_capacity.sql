-- NR06：编辑事件容量修正。
-- 前端默认请求键 edit-{jobId}-{versionNo}-{UUID} 约 80 字符，V205 的 request_id
-- varchar(64) 会让普通保存直接失败并回滚新版本；event_json varchar(8000) 也在
-- 嵌入完整版本载荷时被长 Markdown 撑爆。本迁移追加加宽：request_id 到 128
-- （与服务端 8—128 校验一致），event_json 到 text（事件只保留身份、差异统计与
-- 受限差异片段，不再内嵌完整版本正文——完整正文属于 artifact_version.payload_json）。
alter table artifact_edit_event modify column request_id varchar(128) not null;
alter table artifact_edit_event modify column event_json text not null;

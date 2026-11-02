-- 第三期 F3-01/F3-03：研究简报与对话投影的结构化持久化。
alter table research_run
    add column compiled_brief_json longtext null;

alter table conversation_message
    add column research_projection_json longtext null;

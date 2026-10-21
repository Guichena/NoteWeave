-- 约束的极性（要求 / 禁止）此前只以中文句子落在 constraint_text 里（「不要使用表格」），
-- 库侧无法区分「用表格」与「不要表格」，其它消费者也只能重新解析正文。这里补一列结构化极性：
-- 旧行一律按「要求」处理（与合并器对缺字段的缺省口径一致），新行由 ConstraintOpMerger 写入。
alter table conversation_constraint_v2
    add column polarity varchar(16) not null default 'REQUIRE';

alter table conversation_constraint_v2
    add constraint ck_constraint_v2_polarity check (polarity in ('REQUIRE', 'FORBID'));

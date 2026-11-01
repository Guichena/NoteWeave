-- 生成契约：冻结本次回答允许使用的系统提示、编号绑定与允许引用的证据集合。
-- 历史运行没有契约，读接口按旧协议处理，不回填。
alter table answer_run add column generation_contract_json text null;

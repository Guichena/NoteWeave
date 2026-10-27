-- SRC-04：任务代次。人工重驱复用同一个 task id，旧死信必须能据此判定自己已过期，
-- 否则刚被重驱（重新变成 RUNNING）的资料会被一条旧投递重新打成失败。
-- 每次 redriveTask 递增；消息负载携带 taskGeneration，收尾时在任务锁内比对。
alter table task add column generation int not null default 1;

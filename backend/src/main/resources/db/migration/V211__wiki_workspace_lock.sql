-- WIKI-02：Wiki 重建/撤回按工作台互斥。
-- 同一工作台的并发重灌会互相删除对方正在写入的概念页（appendVersion 遇到已删除条目会整体失败），
-- 这里用带过期租约的单行做轻量互斥：租约到期可被新任务接管，进程崩溃不会留下永久锁。
create table wiki_workspace_lock (
    lock_key varchar(120) not null,
    locked_until timestamp not null,
    owner varchar(120) null,
    constraint pk_wiki_workspace_lock primary key (lock_key)
);

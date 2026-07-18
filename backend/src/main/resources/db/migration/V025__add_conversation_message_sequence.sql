alter table conversation add column next_message_seq int not null default 1;

update conversation c
set next_message_seq = (
    select coalesce(max(cm.message_seq), 0) + 1
    from conversation_message cm
    where cm.conversation_id = c.id
);

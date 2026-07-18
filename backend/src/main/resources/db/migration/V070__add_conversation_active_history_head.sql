alter table conversation add column active_head_message_id varchar(36) null;
alter table conversation add column lock_version int not null default 0;

alter table conversation_message add column reply_to_message_id varchar(36) null;
alter table conversation_message add column context_status varchar(32) not null default 'CURRENT';
alter table conversation_message add column content_hash char(64) null;

alter table conversation add constraint fk_conversation_active_head
    foreign key (active_head_message_id) references conversation_message(id);
alter table conversation_message add constraint fk_conversation_message_reply_to
    foreign key (reply_to_message_id) references conversation_message(id);

create index idx_conversation_message_active_path
    on conversation_message(conversation_id, context_status, message_seq);


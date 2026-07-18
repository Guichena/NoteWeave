alter table research_run add column conversation_id varchar(36) null;
alter table research_run add column query_message_id varchar(36) null;
alter table research_run add column answer_message_id varchar(36) null;

alter table research_run add constraint fk_research_run_conversation
    foreign key (conversation_id) references conversation(id);
alter table research_run add constraint fk_research_run_query_message
    foreign key (query_message_id) references conversation_message(id);
alter table research_run add constraint fk_research_run_answer_message
    foreign key (answer_message_id) references conversation_message(id);

create index idx_research_run_conversation_created
    on research_run(conversation_id, created_at);

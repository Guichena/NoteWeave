alter table video_learning_request
    add column actor_user_id varchar(36) null;

alter table video_learning_request
    add constraint fk_video_learning_request_actor
    foreign key (actor_user_id) references users(id);

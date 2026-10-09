alter table video_learning_request_choice drop constraint ck_video_learning_choice_skill;

alter table video_learning_request_choice add constraint ck_video_learning_choice_skill
    check (skill_key in ('video_summary', 'knowledge_blog', 'interview_qa',
                         'video_learning_deck', 'bilibili_course_note_pdf'));

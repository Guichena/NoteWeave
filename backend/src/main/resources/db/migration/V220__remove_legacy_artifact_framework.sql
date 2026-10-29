-- 只清理旧产物执行框架，当前版本、文件、资料和冻结输入保留。
-- 父请求与素材包存在相互外键，先解除回边及版本的旧素材引用。
alter table artifact_video_material_bundle drop foreign key fk_artifact_video_bundle_parent;
alter table artifact_version drop foreign key fk_artifact_version_material_bundle;
alter table artifact_version drop column material_bundle_id;

drop table video_learning_request_choice;
drop table video_learning_request;
drop table artifact_video_knowledge_plan;
drop table artifact_video_material_file;
drop table artifact_video_material_bundle;
drop table artifact_context_v2_shadow_ref;
drop table artifact_context_v2_shadow_snapshot;

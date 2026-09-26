-- The parent request already owns material_bundle_id. A published child Version
-- keeps its own immutable Bundle reference, including append-only rollback copies.
alter table artifact_version add column material_bundle_id varchar(36) null;

alter table artifact_version add constraint fk_artifact_version_material_bundle
    foreign key (material_bundle_id) references artifact_video_material_bundle(id);

create index idx_artifact_version_material_bundle
    on artifact_version(material_bundle_id);

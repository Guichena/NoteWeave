export type SourceAsset = {
  source_id: string;
  title: string;
  source_type: string;
  status: string;
  parse_status: string;
  index_status: string;
  generated_by: string;
  generated_ref_id: string;
  updated_at: string;
};

export type CompletedSourceUpload = {
  source_id: string;
  task_id: string;
  parse_status: string;
  index_status: string;
};

export type DeletedSource = {
  source_id: string;
  status: string;
  wiki_retract_task_id: string;
};

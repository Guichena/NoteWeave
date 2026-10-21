export type Workspace = {
  workspace_id: string;
  name: string;
  status: string;
  created_at?: string;
};

export type WorkspaceMember = {
  user_id: string;
  display_name: string;
  role: "OWNER" | "EDITOR" | "VIEWER";
  status: "ACTIVE" | "SUSPENDED" | "REMOVED";
  updated_at: string;
};

export type UpdateWorkspaceMemberInput = {
  role: "EDITOR" | "VIEWER";
  status: "ACTIVE" | "SUSPENDED";
};

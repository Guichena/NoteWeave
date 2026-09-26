import { useEffect, useState } from "react";
import { ChevronDown, Settings2 } from "lucide-react";
import { ApiError } from "../../shared/api";
import { workspaceApi, type WorkspaceApi } from "./api";
import {
  type UpdateWorkspaceMemberInput,
  type WorkspaceMember,
  type WorkspaceRetrievalSettings
} from "./model";

type WorkspaceSettingsPanelProps = {
  workspaceId: string;
  onClose: () => void;
  api?: WorkspaceApi;
  /** 本阶段默认不做协作产品面；测试或后续可显式打开。 */
  showMembers?: boolean;
};

export function WorkspaceSettingsPanel({
  workspaceId,
  onClose,
  api = workspaceApi,
  showMembers = false
}: WorkspaceSettingsPanelProps) {
  const [retrieval, setRetrieval] = useState<WorkspaceRetrievalSettings | null>(null);
  const [members, setMembers] = useState<WorkspaceMember[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [mutating, setMutating] = useState(false);
  const [error, setError] = useState("");
  const [newUserId, setNewUserId] = useState("");
  const [newRole, setNewRole] = useState<UpdateWorkspaceMemberInput["role"]>("VIEWER");

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    void Promise.all([
      api.getRetrievalSettings(workspaceId),
      showMembers
        ? api.listMembers(workspaceId).catch((cause) => {
          if (cause instanceof ApiError && cause.status === 403) {
            return null;
          }
          throw cause;
        })
        : Promise.resolve(null)
    ])
      .then(([nextRetrieval, nextMembers]) => {
        if (active) {
          setRetrieval(nextRetrieval);
          setMembers(nextMembers);
        }
      })
      .catch((cause) => active && setError(errorMessage(cause, "工作台设置加载失败")))
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [api, workspaceId, showMembers]);

  async function updateRetrieval(enabled: boolean) {
    await mutate(async () => {
      setRetrieval(await api.updateRetrievalSettings(workspaceId, enabled));
    });
  }

  async function addMember() {
    const userId = newUserId.trim();
    if (!userId) {
      return;
    }
    await mutate(async () => {
      const saved = await api.putMember(workspaceId, userId, { role: newRole, status: "ACTIVE" });
      setMembers((current) => upsertMember(current ?? [], saved));
      setNewUserId("");
    });
  }

  async function updateMember(member: WorkspaceMember, input: UpdateWorkspaceMemberInput) {
    await mutate(async () => {
      const saved = await api.putMember(workspaceId, member.user_id, input);
      setMembers((current) => upsertMember(current ?? [], saved));
    });
  }

  async function removeMember(member: WorkspaceMember) {
    await mutate(async () => {
      await api.removeMember(workspaceId, member.user_id);
      setMembers((current) => (current ?? []).filter((item) => item.user_id !== member.user_id));
    });
  }

  async function mutate(action: () => Promise<void>) {
    setMutating(true);
    setError("");
    try {
      await action();
    } catch (cause) {
      setError(errorMessage(cause, "工作台设置更新失败"));
    } finally {
      setMutating(false);
    }
  }

  return (
    <section className="workspace-settings-panel" aria-label="工作台设置">
      <header className="workspace-settings-header">
        <div>
          <p className="section-label">Workspace Settings</p>
          <h2>{showMembers ? "检索与成员" : "检索设置"}</h2>
          <p className="workspace-settings-intro">控制当前工作台的检索策略与访问权限。</p>
        </div>
        <button className="secondary-button" onClick={onClose}>关闭</button>
      </header>

      {loading ? (
        <div className="workspace-settings-state is-loading" aria-busy="true" aria-live="polite">
          <span className="workspace-settings-state-icon" aria-hidden="true"><Settings2 size={19} /></span>
          <div>
            <strong>正在读取工作台设置</strong>
            <span>同步检索策略与成员权限。</span>
          </div>
          <div className="view-loading-skeleton" aria-hidden="true">
            <span className="skeleton-line w-70" />
            <span className="skeleton-line w-40" />
          </div>
        </div>
      ) : (
        <>
          <div className="workspace-retrieval-setting">
            <div>
              <strong>检索策略 V2</strong>
              <p>{retrieval?.retrieval_strategy_v2_enabled ? "已启用，可使用新版召回策略" : "已停用，继续使用兼容策略"}</p>
            </div>
            <label className="workspace-toggle">
              <input
                type="checkbox"
                checked={retrieval?.retrieval_strategy_v2_enabled ?? false}
                disabled={mutating}
                onChange={(event) => void updateRetrieval(event.target.checked)}
              />
              <span className="workspace-toggle-track" aria-hidden="true">
                <span className="workspace-toggle-thumb" />
              </span>
              <span>{retrieval?.retrieval_strategy_v2_enabled ? "已启用" : "已停用"}</span>
            </label>
          </div>

          {showMembers ? (
            members === null ? (
              <p className="workspace-settings-state is-note">当前账号没有成员管理权限。</p>
            ) : (
              <div className="workspace-members">
                <div className="workspace-member-add">
                  <label>
                    <span>用户 ID</span>
                    <input value={newUserId} onChange={(event) => setNewUserId(event.target.value)} />
                  </label>
                  <label>
                    <span>角色</span>
                    <span className="workspace-select-control">
                      <select value={newRole} onChange={(event) => setNewRole(event.target.value as UpdateWorkspaceMemberInput["role"])}>
                        <option value="VIEWER">Viewer</option>
                        <option value="EDITOR">Editor</option>
                      </select>
                      <ChevronDown size={14} aria-hidden="true" />
                    </span>
                  </label>
                  <button className="primary-action" disabled={mutating || !newUserId.trim()} onClick={() => void addMember()}>添加成员</button>
                </div>
                <div className="workspace-member-list">
                  {members.map((member) => (
                    <WorkspaceMemberRow
                      key={member.user_id}
                      member={member}
                      disabled={mutating}
                      onUpdate={updateMember}
                      onRemove={removeMember}
                    />
                  ))}
                </div>
              </div>
            )
          ) : (
            <p className="workspace-settings-state is-note">当前为单人工作台模式，成员协作入口未开放。</p>
          )}
        </>
      )}
      {error && <p className="workspace-settings-error" role="alert">{error}</p>}
    </section>
  );
}

function WorkspaceMemberRow({
  member,
  disabled,
  onUpdate,
  onRemove
}: {
  member: WorkspaceMember;
  disabled: boolean;
  onUpdate: (member: WorkspaceMember, input: UpdateWorkspaceMemberInput) => Promise<void>;
  onRemove: (member: WorkspaceMember) => Promise<void>;
}) {
  const [role, setRole] = useState<UpdateWorkspaceMemberInput["role"]>(
    member.role === "EDITOR" ? "EDITOR" : "VIEWER"
  );
  const [status, setStatus] = useState<UpdateWorkspaceMemberInput["status"]>(
    member.status === "SUSPENDED" ? "SUSPENDED" : "ACTIVE"
  );
  const owner = member.role === "OWNER";

  return (
    <div className="workspace-member-row">
      <div className="workspace-member-identity">
        <strong>{member.display_name || member.user_id}</strong>
        <span title={member.user_id}>{formatUserId(member.user_id)}</span>
      </div>
      {owner ? <span className="status-chip">Owner</span> : (
        <>
          <span className="workspace-select-control">
            <select aria-label={`${member.display_name} 角色`} value={role} disabled={disabled} onChange={(event) => setRole(event.target.value as UpdateWorkspaceMemberInput["role"])}>
              <option value="VIEWER">Viewer</option>
              <option value="EDITOR">Editor</option>
            </select>
            <ChevronDown size={14} aria-hidden="true" />
          </span>
          <span className="workspace-select-control">
            <select aria-label={`${member.display_name} 状态`} value={status} disabled={disabled} onChange={(event) => setStatus(event.target.value as UpdateWorkspaceMemberInput["status"])}>
              <option value="ACTIVE">Active</option>
              <option value="SUSPENDED">Suspended</option>
            </select>
            <ChevronDown size={14} aria-hidden="true" />
          </span>
          <div className="workspace-member-actions">
            <button disabled={disabled} onClick={() => void onUpdate(member, { role, status })}>保存</button>
            <button className="danger-button" disabled={disabled} onClick={() => void onRemove(member)}>移除</button>
          </div>
        </>
      )}
    </div>
  );
}

function upsertMember(members: WorkspaceMember[], saved: WorkspaceMember) {
  const next = members.filter((member) => member.user_id !== saved.user_id);
  return [...next, saved].sort((left, right) => roleOrder(left.role) - roleOrder(right.role)
    || left.display_name.localeCompare(right.display_name));
}

function roleOrder(role: WorkspaceMember["role"]) {
  return role === "OWNER" ? 0 : role === "EDITOR" ? 1 : 2;
}

function formatUserId(userId: string) {
  return userId.length > 18 ? `${userId.slice(0, 8)}…${userId.slice(-5)}` : userId;
}

function errorMessage(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback;
}

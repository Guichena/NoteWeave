import { useEffect, useState } from "react";
import { conversationsApi } from "./api";
import type { PendingMemoryProposal, SessionPreference } from "./model";

type SessionPreferenceApi = {
  listSessionPreferences: (workspaceId: string, conversationId: string) => Promise<SessionPreference[]>;
  addSessionPreference: (workspaceId: string, conversationId: string, text: string) => Promise<SessionPreference>;
  removeSessionPreference: (workspaceId: string, conversationId: string, requirementId: string) => Promise<void>;
  listPendingProposals: (workspaceId: string, conversationId: string) => Promise<PendingMemoryProposal[]>;
  listActiveMemories: (workspaceId: string, conversationId: string) => Promise<PendingMemoryProposal[]>;
  sessionOnlyRevision: (workspaceId: string, conversationId: string, revisionId: string) => Promise<SessionPreference>;
  reviewMemoryProposal: (workspaceId: string, revisionId: string, decision: "ACCEPT" | "REJECT" | "REVOKE") => Promise<void>;
};

type Props = {
  workspaceId: string;
  conversationId: string;
  api?: SessionPreferenceApi;
};

/**
 * F5-03：只用于本对话的会话要求，以及本对话里的待确认 Memory 候选。
 * 候选通过来源消息归属会话；记住/忽略走既有 Memory 生命周期，只用于本对话会拒绝候选并生成会话要求。
 */
export function SessionPreferencePanel({ workspaceId, conversationId, api = conversationsApi }: Props) {
  const [items, setItems] = useState<SessionPreference[]>([]);
  const [proposals, setProposals] = useState<PendingMemoryProposal[]>([]);
  const [activeMemories, setActiveMemories] = useState<PendingMemoryProposal[]>([]);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    setError("");
    if (!workspaceId || !conversationId) {
      setItems([]);
      setProposals([]);
      setActiveMemories([]);
      return () => {
        cancelled = true;
      };
    }
    Promise.all([
      api.listSessionPreferences(workspaceId, conversationId),
      api.listPendingProposals(workspaceId, conversationId),
      api.listActiveMemories(workspaceId, conversationId)
    ])
      .then(([loadedItems, loadedProposals, loadedActive]) => {
        if (!cancelled) {
          setItems((current) => (current.length > 0 ? current : loadedItems));
          setProposals((current) => (current.length > 0 ? current : loadedProposals));
          setActiveMemories((current) => (current.length > 0 ? current : loadedActive));
        }
      })
      .catch(() => {
        if (!cancelled) setError("暂时读不到会话偏好");
      });
    return () => {
      cancelled = true;
    };
  }, [api, workspaceId, conversationId]);

  async function add() {
    const value = text.trim();
    if (!value || busy) return;
    setBusy(true);
    setError("");
    try {
      const created = await api.addSessionPreference(workspaceId, conversationId, value);
      setItems((current) => [...current, created]);
      setText("");
    } catch {
      setError("保存失败，请稍后重试");
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: string) {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await api.removeSessionPreference(workspaceId, conversationId, id);
      setItems((current) => current.filter((item) => item.id !== id));
    } catch {
      setError("删除失败，请稍后重试");
    } finally {
      setBusy(false);
    }
  }

  async function resolveProposal(proposal: PendingMemoryProposal, decision: "ACCEPT" | "REJECT" | "SESSION") {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      if (decision === "SESSION") {
        const created = await api.sessionOnlyRevision(workspaceId, conversationId, proposal.revision_id);
        setItems((current) => [...current, created]);
      } else {
        await api.reviewMemoryProposal(workspaceId, proposal.revision_id, decision);
      }
      setProposals((current) => current.filter((item) => item.revision_id !== proposal.revision_id));
    } catch {
      setError("处理失败，请稍后重试");
    } finally {
      setBusy(false);
    }
  }

  async function revoke(revisionId: string) {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await api.reviewMemoryProposal(workspaceId, revisionId, "REVOKE");
      setActiveMemories((current) => current.filter((item) => item.revision_id !== revisionId));
    } catch {
      setError("撤销失败，请稍后重试");
    } finally {
      setBusy(false);
    }
  }

  return (
    <details className="session-preference-panel">
      <summary>会话偏好（只用于本对话）{items.length > 0 ? ` · ${items.length}` : ""}</summary>
      <div className="session-preference-body">
        {proposals.length > 0 ? (
          <div className="session-preference-proposals">
            <strong>待确认记忆</strong>
            <ul aria-label="待确认记忆">
              {proposals.map((proposal) => (
                <li key={proposal.revision_id}>
                  <span>{proposal.display_text}</span>
                  <button type="button" className="link-button" disabled={busy}
                          onClick={() => void resolveProposal(proposal, "ACCEPT")}>
                    记住
                  </button>
                  <button type="button" className="link-button" disabled={busy}
                          onClick={() => void resolveProposal(proposal, "REJECT")}>
                    忽略
                  </button>
                  <button type="button" className="link-button" disabled={busy}
                          onClick={() => void resolveProposal(proposal, "SESSION")}>
                    只用于本对话
                  </button>
                </li>
              ))}
            </ul>
          </div>
        ) : null}
        {activeMemories.length > 0 ? (
          <div className="session-preference-active">
            <strong>已记住</strong>
            <ul aria-label="已记住的记忆">
              {activeMemories.map((memory) => (
                <li key={memory.revision_id}>
                  <span>{memory.display_text}</span>
                  <button type="button" className="link-button" disabled={busy}
                          onClick={() => void revoke(memory.revision_id)}>
                    撤销
                  </button>
                </li>
              ))}
            </ul>
          </div>
        ) : null}
        <div className="session-preference-input">
          <input
            aria-label="会话要求"
            value={text}
            maxLength={500}
            placeholder="例如：回答只用工作台资料"
            onChange={(event) => setText(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                void add();
              }
            }}
          />
          <button type="button" className="secondary-button" disabled={busy || !text.trim()} onClick={() => void add()}>
            添加
          </button>
        </div>
        {error ? <p role="alert">{error}</p> : null}
        {items.length > 0 ? (
          <ul aria-label="会话要求列表">
            {items.map((item) => (
              <li key={item.id}>
                <span>{item.requirement_text}</span>
                <button type="button" className="link-button" disabled={busy} onClick={() => void remove(item.id)}>
                  删除
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="session-preference-empty">还没有只用于本对话的要求。</p>
        )}
      </div>
    </details>
  );
}

import { useEffect, useState } from "react";
import { conversationsApi } from "./api";
import type { SessionPreference } from "./model";

type SessionPreferenceApi = {
  listSessionPreferences: (workspaceId: string, conversationId: string) => Promise<SessionPreference[]>;
  addSessionPreference: (workspaceId: string, conversationId: string, text: string) => Promise<SessionPreference>;
  removeSessionPreference: (workspaceId: string, conversationId: string, requirementId: string) => Promise<void>;
};

type Props = {
  workspaceId: string;
  conversationId: string;
  api?: SessionPreferenceApi;
};

/**
 * F5-03：只用于本对话的会话要求。用户直接写一条要求，后续回答只在当前对话使用；
 * 不写入长期记忆，删除即停用。
 */
export function SessionPreferencePanel({ workspaceId, conversationId, api = conversationsApi }: Props) {
  const [items, setItems] = useState<SessionPreference[]>([]);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    setError("");
    if (!workspaceId || !conversationId) {
      setItems([]);
      return () => {
        cancelled = true;
      };
    }
    api.listSessionPreferences(workspaceId, conversationId)
      .then((loaded) => {
        if (!cancelled) setItems(loaded);
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

  return (
    <details className="session-preference-panel">
      <summary>会话偏好（只用于本对话）{items.length > 0 ? ` · ${items.length}` : ""}</summary>
      <div className="session-preference-body">
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

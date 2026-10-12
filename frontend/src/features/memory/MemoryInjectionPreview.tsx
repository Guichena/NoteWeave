import { useState, type FormEvent } from "react";
import { LoaderCircle, Search } from "lucide-react";
import type { MemoryApi } from "./api";
import type { MemoryItem, MemoryReference } from "./model";
import { errorMessage } from "./useMemoryItems";

const MODES = [["QA", "问答"], ["NOTE", "精读"], ["WIKI", "Wiki 问答"]] as const;

export const MEMORY_CLASS_LABELS: Record<string, string> = {
  ALWAYS_ON: "常驻",
  ON_DEMAND: "按需"
};

type MemoryInjectionPreviewProps = {
  workspaceId: string;
  items: MemoryItem[];
  api: Pick<MemoryApi, "previewChatControlPack">;
};

/** 输入一个问题，预览回答时会编入哪些记忆以及入选原因。 */
export function MemoryInjectionPreview({ workspaceId, items, api }: MemoryInjectionPreviewProps) {
  const [mode, setMode] = useState<string>("QA");
  const [query, setQuery] = useState("");
  const [references, setReferences] = useState<MemoryReference[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!query.trim() || loading) return;
    setLoading(true);
    setError("");
    try {
      const pack = await api.previewChatControlPack(workspaceId, mode, query.trim());
      setReferences(pack.memory_references ?? []);
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="memory-section memory-preview" aria-labelledby="memory-preview-heading">
      <div className="memory-section-heading">
        <h3 id="memory-preview-heading">回答时会用到哪些记忆</h3>
        <small>常驻记忆每次都会编入，其余记忆按与问题的相关度选取</small>
      </div>
      <form className="memory-preview-form" onSubmit={(event) => void submit(event)}>
        <select aria-label="回答模式" value={mode} onChange={(event) => setMode(event.target.value)}>
          {MODES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
        <input
          aria-label="预览用的问题"
          value={query}
          maxLength={500}
          placeholder="输入一个问题，例如：帮我总结这份资料的结论"
          onChange={(event) => setQuery(event.target.value)}
        />
        <button type="submit" className="secondary-button" disabled={loading || !query.trim()}>
          {loading ? <LoaderCircle className="is-spinning" size={14} aria-hidden="true" /> : <Search size={14} aria-hidden="true" />}
          预览
        </button>
      </form>
      {error ? <p className="memory-error" role="alert">{error}</p> : null}
      {references ? <MemoryReferenceList references={references} items={items} /> : null}
    </section>
  );
}

/** 记忆引用列表，回答证据面板和记忆页共用。 */
export function MemoryReferenceList({ references, items = [] }: { references: MemoryReference[]; items?: MemoryItem[] }) {
  if (references.length === 0) {
    return <p className="memory-quiet">这个问题不会编入任何记忆。</p>;
  }
  const textById = new Map(items.map((item) => [item.memory_item_id, item.display_text]));
  return (
    <ul className="memory-reference-list" aria-label="编入的记忆">
      {references.map((reference) => (
        <li key={reference.memory_version_id ?? reference.memory_object_id}>
          <span className={`memory-class-tag is-${(reference.memory_class ?? "unknown").toLowerCase()}`}>
            {MEMORY_CLASS_LABELS[reference.memory_class ?? ""] ?? "记忆"}
          </span>
          <div>
            <strong>{reference.display_text || textById.get(reference.memory_object_id) || reference.memory_object_id}</strong>
            <small>
              {typeof reference.relevance === "number" ? `相关度 ${Math.round(reference.relevance * 100)}%` : ""}
              {typeof reference.relevance === "number" && reference.selection_reason ? " · " : ""}
              {reference.selection_reason ?? ""}
            </small>
          </div>
        </li>
      ))}
    </ul>
  );
}

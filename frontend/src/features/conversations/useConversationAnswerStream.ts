import { useEffect, useRef, useState } from "react";
import { type AnswerRunState } from "../answers/model";
import { type AnswerRunStore } from "../answers/store";
import { ConversationStreamClient } from "../../shared/event-stream";

type ConversationAnswerStreamOptions = {
  workspaceId: string;
  conversationId: string;
  store: AnswerRunStore;
  onUpdates: (updates: AnswerRunState[]) => void;
  onStatus: (message: string) => void;
};

export function useConversationAnswerStream({
  workspaceId,
  conversationId,
  store,
  onUpdates,
  onStatus
}: ConversationAnswerStreamOptions) {
  const [connected, setConnected] = useState(false);
  const onUpdatesRef = useRef(onUpdates);
  const onStatusRef = useRef(onStatus);
  onUpdatesRef.current = onUpdates;
  onStatusRef.current = onStatus;

  useEffect(() => {
    if (!workspaceId || !conversationId) {
      setConnected(false);
      return;
    }
    const path = `/api/v2/workspaces/${workspaceId}/conversations/${conversationId}/events`;
    const client = new ConversationStreamClient(path, {
      onConnectionChange: (nextConnected) => {
        if (!nextConnected) {
          setConnected(false);
        }
      },
      onError: () => onStatusRef.current("实时更新暂不可用，回答将从任务快照同步"),
      onEvent: (event) => {
        try {
          const updates = store.applyConversationEvent(event);
          if (event.event === "conversation.snapshot") {
            setConnected(true);
          }
          if (updates.length > 0) {
            onUpdatesRef.current(updates);
          }
        } catch {
          onStatusRef.current("会话快照格式无效，将等待下一次重连恢复");
        }
      }
    });

    client.start();
    return () => {
      client.stop();
      setConnected(false);
      store.clear("会话已切换");
    };
  }, [workspaceId, conversationId, store]);

  return connected;
}

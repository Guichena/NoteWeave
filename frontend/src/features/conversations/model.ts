export type Conversation = {
  conversation_id: string;
  title: string;
  conversation_type?: string;
  created_at?: string;
};

export type ConversationSummary = {
  conversation_id: string;
  title: string;
  conversation_type: string;
  status: string;
  active_head_message_id: string | null;
  created_at: string;
  last_active_at: string;
};

export type ConversationMessage = {
  message_id: string;
  message_seq: number;
  role: string;
  requested_turn_mode: string | null;
  content: string;
  reply_to_message_id: string | null;
  context_status: string | null;
  content_hash: string | null;
  answer_status?: string | null;
  answer_error?: string | null;
  created_at: string;
  /** 助手消息对应的回答运行 ID，用于读取证据清单。 */
  answer_run_id?: string | null;
  /** 引用文本，格式为 `标题 | 摘录`。 */
  citations?: string[];
};

export type CreateConversationInput = {
  title: string;
  conversation_type: string;
};

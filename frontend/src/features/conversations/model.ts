export type Conversation = {
  conversation_id: string;
  title: string;
};

export type CreateConversationInput = {
  title: string;
  conversation_type: string;
};

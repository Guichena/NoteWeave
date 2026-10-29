/** 与共享 Schema 对齐的阅读数据；不在这里定义生成或校验规则。 */
export type FactText = { text: string; anchors: string[] };
export type QuizIr = {
  schema_version: 1;
  kind: "QUIZ";
  title: string;
  questions: Array<{ id: string; prompt: FactText; options: Array<{ id: string; text: string }>; answer: string; explanation: FactText }>;
};
export type CardsIr = {
  schema_version: 1;
  kind: "CARDS";
  title: string;
  cards: Array<{ id: string; front: string; back: FactText }>;
};
export type TableIr = {
  schema_version: 1;
  kind: "TABLE";
  title: string;
  columns: Array<{ id: string; label: string }>;
  rows: Array<{ id: string; cells: FactText[] }>;
};

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function text(value: unknown): value is string {
  return typeof value === "string";
}

function fact(value: unknown): value is FactText {
  return record(value) && text(value.text) && Array.isArray(value.anchors) && value.anchors.every(text);
}

/** 旧版本或不完整响应继续使用 Markdown，避免阅读页因缺字段而出错。 */
export function readInteractiveIr(value: unknown, kind: string): QuizIr | CardsIr | TableIr | null {
  if (!record(value) || value.schema_version !== 1 || value.kind !== kind || !text(value.title)) return null;
  if (kind === "QUIZ" && Array.isArray(value.questions) && value.questions.length > 0
      && value.questions.every((question: unknown) => record(question) && text(question.id) && fact(question.prompt)
        && fact(question.explanation) && text(question.answer) && Array.isArray(question.options) && question.options.length >= 2
        && question.options.every((option: unknown) => record(option) && text(option.id) && text(option.text))
        && question.options.some((option: { id: string }) => option.id === question.answer))) {
    return value as QuizIr;
  }
  if (kind === "CARDS" && Array.isArray(value.cards) && value.cards.length > 0
      && value.cards.every((card: unknown) => record(card) && text(card.id) && text(card.front) && fact(card.back))) {
    return value as CardsIr;
  }
  if (kind === "TABLE" && Array.isArray(value.columns) && value.columns.length > 0
      && value.columns.every((column: unknown) => record(column) && text(column.id) && text(column.label))
      && Array.isArray(value.rows) && value.rows.every((row: unknown) => record(row) && text(row.id)
        && Array.isArray(row.cells) && row.cells.length === (value.columns as unknown[]).length && row.cells.every(fact))) {
    return value as TableIr;
  }
  return null;
}

/** 与服务端相同的引用位置约定：编号 1..9999、标记最多 64 字符。 */
export type InlineToken = { text: string; literal?: boolean; code?: string };
const identifier = (value: string) => /[A-Za-z0-9_$]/.test(value);

export function inlineTokens(value: string): InlineToken[] {
  const tokens: InlineToken[] = [];
  let plain = "";
  const push = (token: InlineToken) => {
    if (plain) { tokens.push({ text: plain, literal: true }); plain = ""; }
    tokens.push(token);
  };
  for (let i = 0; i < value.length;) {
    const c = value[i];
    if (c === "\\" && i + 1 < value.length) { plain += value.slice(i, i + 2); i += 2; continue; }
    if (c === "`") {
      let run = 1;
      while (value[i + run] === "`") run++;
      let close = i + run;
      while (close < value.length) {
        if (value[close] !== "`") { close++; continue; }
        let length = 1;
        while (value[close + length] === "`") length++;
        if (length === run) break;
        close += length;
      }
      const end = close < value.length ? close + run : value.length;
      push({ text: value.slice(i, end), code: value.slice(i + run, close), literal: true });
      i = end; continue;
    }
    if (value.startsWith("**", i)) {
      const close = value.indexOf("**", i + 2);
      if (close >= 0) { push({ text: value.slice(i, close + 2) }); i = close + 2; continue; }
    }
    const pending = value.slice(i).match(/^(?:（待核实）|（未经核实）|（该项尚未完成核验）|\(unverified\))/);
    if (pending) { push({ text: pending[0] }); i += pending[0].length; continue; }
    if (c !== "[") { plain += c; i++; continue; }
    let depth = 1;
    let end = i + 1;
    for (; end < value.length && depth > 0; end++) {
      if (value[end] === "\\") { end++; continue; }
      if (value[end] === "[") depth++;
      if (value[end] === "]") depth--;
    }
    if (depth > 0) { plain += value.slice(i); break; }
    const label = value.slice(i, end);
    // 完整标签后接目标或引用标签时整体为链接，不能把标签内数字解释为引用。
    if (value[end] === "(") {
      let nested = 1; let targetEnd = end + 1;
      for (; targetEnd < value.length && nested > 0; targetEnd++) {
        if (value[targetEnd] === "\\") { targetEnd++; continue; }
        if (value[targetEnd] === "(") nested++;
        if (value[targetEnd] === ")") nested--;
      }
      const text = value.slice(i, targetEnd);
      push({ text, literal: !/^\[[^\]]+\]\(https?:\/\/[^\s)]+\)$/.test(text) });
      i = targetEnd; continue;
    }
    if (value[end] === "[" && !/[0-9]/.test(value[end + 1] ?? "")) {
      const close = value.indexOf("]", end + 1);
      const targetEnd = close < 0 ? value.length : close + 1;
      push({ text: value.slice(i, targetEnd), literal: true }); i = targetEnd; continue;
    }
    const numeric = /^\[[0-9]{1,4}\]$/.test(label);
    push({ text: label, literal: numeric && (identifier(value[i - 1] ?? "") || value[end] === ":" && value.slice(0, i).trim() === "") });
    i = end;
  }
  if (plain) tokens.push({ text: plain, literal: true });
  return tokens;
}

/** 用相同语法统计所有展示区域的引用，不把代码或数组算入已引用。 */
export function citationNumbers(content: string): number[] {
  const numbers: number[] = [];
  let fence = "";
  for (const line of content.replaceAll("\r\n", "\n").split("\n")) {
    const marker = line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/);
    if (fence) {
      if (marker && marker[1][0] === fence[0] && marker[1].length >= fence.length && marker[2].trim() === "") fence = "";
      continue;
    }
    if (marker) { fence = marker[1]; continue; }
    for (const token of inlineTokens(line)) {
      if (token.literal) continue;
      if (/^\*\*.*\*\*$/.test(token.text)) numbers.push(...citationNumbers(token.text.slice(2, -2)));
      else if (/^\[[0-9]{1,4}\]$/.test(token.text)) numbers.push(Number(token.text.slice(1, -1)));
    }
  }
  return numbers;
}

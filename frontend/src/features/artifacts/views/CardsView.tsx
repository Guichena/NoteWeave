import { useId, useState } from "react";
import type { CardsIr } from "./ir";

export function CardsView({ ir }: { ir: CardsIr }) {
  const cardTextId = useId();
  const [index, setIndex] = useState(0);
  const [flipped, setFlipped] = useState(false);
  const card = ir.cards[index];
  function select(next: number) { setIndex(next); setFlipped(false); }
  return (
    <section className="artifact-kind-view artifact-cards-view" aria-label="闪卡复习">
      <p className="artifact-card-progress" aria-live="polite">第 {index + 1} / {ir.cards.length} 张</p>
      <button type="button" className="artifact-flashcard" aria-pressed={flipped}
        aria-describedby={cardTextId}
        aria-label={flipped ? "显示题面" : "翻开答案"} onClick={() => setFlipped((current) => !current)}>
        <span className="artifact-card-side">{flipped ? "答案" : "题面"}</span>
        <span id={cardTextId} className="artifact-card-text">{flipped ? card.back.text : card.front}</span>
        <span className="artifact-card-hint">{flipped ? "点击返回题面" : "点击查看答案"}</span>
      </button>
      <div className="artifact-card-nav">
        <button type="button" className="secondary-button" disabled={index === 0} onClick={() => select(index - 1)}>上一张</button>
        <button type="button" className="secondary-button" disabled={index === ir.cards.length - 1} onClick={() => select(index + 1)}>下一张</button>
      </div>
    </section>
  );
}

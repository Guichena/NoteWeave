import { useId, useState } from "react";
import type { QuizIr } from "./ir";

export function QuizView({ ir }: { ir: QuizIr }) {
  const formId = useId();
  const [selected, setSelected] = useState(() => new Map<string, string>());
  const [submitted, setSubmitted] = useState(() => new Set<string>());
  const answered = ir.questions.filter((question) => submitted.has(question.id)).length;
  const correct = ir.questions.filter((question) => submitted.has(question.id) && selected.get(question.id) === question.answer).length;
  return (
    <section className="artifact-kind-view artifact-quiz-view" aria-label="测验作答">
      <div className="artifact-kind-toolbar">
        <p role="status">已作答 {answered} / {ir.questions.length} 题 · 答对 {correct} 题</p>
        <button type="button" className="secondary-button" disabled={answered === 0 && selected.size === 0}
          onClick={() => { setSelected(new Map()); setSubmitted(new Set()); }}>重新作答</button>
      </div>
      {ir.questions.map((question, index) => {
        const checked = submitted.has(question.id);
        const right = selected.get(question.id) === question.answer;
        return (
          <form key={question.id} className="artifact-quiz-question" onSubmit={(event) => {
            event.preventDefault();
            if (selected.has(question.id)) setSubmitted((current) => new Set(current).add(question.id));
          }}>
            <fieldset disabled={checked}>
              <legend>{index + 1}. {question.prompt.text}</legend>
              {question.options.map((option) => (
                <label key={option.id} className="artifact-quiz-option">
                  <input type="radio" name={`${formId}-${question.id}`} value={option.id}
                    checked={selected.get(question.id) === option.id}
                    onChange={() => setSelected((current) => new Map(current).set(question.id, option.id))} />
                  <span>{option.text}</span>
                </label>
              ))}
            </fieldset>
            {!checked ? <button type="submit" className="secondary-button" disabled={!selected.has(question.id)}>检查答案</button> : null}
            {checked ? (
              <div className={`artifact-quiz-feedback${right ? " is-correct" : " is-incorrect"}`} role="status">
                <strong>{right ? "回答正确" : "回答有误"}</strong>
                {!right ? <p>正确答案：{question.options.find((option) => option.id === question.answer)?.text}</p> : null}
                <p>{question.explanation.text}</p>
              </div>
            ) : null}
          </form>
        );
      })}
    </section>
  );
}

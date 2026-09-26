import type { AnswerMode } from "../../routes";

const ANSWER_MODE_PREFERENCE_KEY = "noteweave.answer-mode";

type PreferenceStorage = Pick<Storage, "getItem" | "setItem">;

export function readAnswerModePreference(storage = getBrowserStorage()): AnswerMode {
  if (!storage) return "qa";
  try {
    const stored = storage.getItem(ANSWER_MODE_PREFERENCE_KEY);
    return stored === "note" || stored === "wiki" || stored === "qa" ? stored : "qa";
  } catch {
    return "qa";
  }
}

export function writeAnswerModePreference(mode: AnswerMode, storage = getBrowserStorage()) {
  if (!storage) return;
  try {
    storage.setItem(ANSWER_MODE_PREFERENCE_KEY, mode);
  } catch {
    // Storage can be unavailable in private or quota-limited browser contexts.
  }
}

function getBrowserStorage(): PreferenceStorage | null {
  return typeof window === "undefined" ? null : window.localStorage;
}

import { describe, expect, it, vi } from "vitest";
import { readAnswerModePreference, writeAnswerModePreference } from "./answerModePreference";

describe("answer mode preference", () => {
  it("restores a supported mode and falls back for unknown values", () => {
    expect(readAnswerModePreference(storageWith("wiki"))).toBe("wiki");
    expect(readAnswerModePreference(storageWith("unknown"))).toBe("qa");
    expect(readAnswerModePreference(null)).toBe("qa");
  });

  it("persists the mode without failing when storage rejects writes", () => {
    const setItem = vi.fn();
    writeAnswerModePreference("note", { getItem: vi.fn(), setItem });
    expect(setItem).toHaveBeenCalledWith("noteweave.answer-mode", "note");

    expect(() => writeAnswerModePreference("wiki", {
      getItem: vi.fn(),
      setItem: () => { throw new Error("quota"); }
    })).not.toThrow();
  });
});

function storageWith(value: string | null) {
  return {
    getItem: vi.fn(() => value),
    setItem: vi.fn()
  };
}

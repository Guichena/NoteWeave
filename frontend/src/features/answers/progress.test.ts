import { describe, expect, it } from "vitest";
import { answerProgressLabel } from "./progress";

describe("answerProgressLabel", () => {
  it("正文出现前按阶段提示", () => {
    expect(answerProgressLabel({ content: "", answerStatus: "PREPARING" })).toBe("正在理解问题");
    expect(answerProgressLabel({ content: "", answerStage: "understanding" })).toBe("正在理解问题");
    expect(answerProgressLabel({ content: "", answerStage: "retrieving" })).toBe("正在检索资料");
    expect(answerProgressLabel({ content: "", answerStage: "retrieved", answerEvidenceCount: 6 }))
      .toBe("检索到 6 条资料，正在整理");
    expect(answerProgressLabel({ content: "", answerStage: "writing", answerEvidenceCount: 6 }))
      .toBe("检索到 6 条资料，正在撰写");
    expect(answerProgressLabel({ content: "", answerStage: "writing", answerEvidenceCount: 0 }))
      .toBe("没有检索到相关资料，正在撰写");
    expect(answerProgressLabel({ content: "", answerStage: "writing" })).toBe("正在撰写回答");
  });

  it("正文开始后统一显示正在生成", () => {
    expect(answerProgressLabel({ content: "部分正文", answerStage: "writing", answerEvidenceCount: 3 }))
      .toBe("正在生成回答");
    expect(answerProgressLabel({ content: "", answerStatus: "GENERATING" })).toBe("正在生成回答");
  });
});

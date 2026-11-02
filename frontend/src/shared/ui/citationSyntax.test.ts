import { describe, expect, it } from "vitest";
import { citationNumbers, inlineTokens } from "./citationSyntax";
describe("共享引用语法", () => {
  it("只把实际展示为编号的正文位置计为已引用", () => {
    expect(citationNumbers("依据[1][2]；array[3]，`[4]` 与 ``a`[5]``，\\[6]，[7](url)，[8][ref]，**依据[9]**\n~~~\n[10]\n~~~\n[1000]")).toEqual([1, 2, 9, 1000]);
  });
  it("保留研究待核实标记，同时代码中的标记仍为代码", () => {
    expect(inlineTokens("（待核实）[evidence:x]").filter((token) => !token.literal).map((token) => token.text)).toEqual(["（待核实）", "[evidence:x]"]);
    expect(inlineTokens("`（待核实）`")[0].code).toBe("（待核实）");
  });
});

it("链接定义和伪代码围栏不产生引用", () => {
  expect(citationNumbers("[7]: https://example.org\n```\n``` not closed\narray [7]\n``` ")).toEqual([]);
});

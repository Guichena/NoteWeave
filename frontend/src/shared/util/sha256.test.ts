import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import { hashBlobInline, Sha256 } from "./sha256";

function expected(bytes: Uint8Array) {
  return createHash("sha256").update(bytes).digest("hex");
}

describe("Sha256", () => {
  it("与标准实现一致：空输入、短文本和跨越填充边界的长度", () => {
    for (const length of [0, 3, 55, 56, 63, 64, 65, 119, 120, 1000]) {
      const bytes = new Uint8Array(length).map((_, index) => (index * 31 + 7) % 256);
      expect(new Sha256().update(bytes).digestHex()).toBe(expected(bytes));
    }
    expect(new Sha256().update(new TextEncoder().encode("abc")).digestHex())
      .toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  });

  it("任意切分输入得到相同结果", () => {
    const bytes = new Uint8Array(5000).map((_, index) => (index * 13) % 251);
    const hash = new Sha256();
    for (const [start, end] of [[0, 1], [1, 70], [70, 200], [200, 4097], [4097, 5000]]) {
      hash.update(bytes.subarray(start, end));
    }
    expect(hash.digestHex()).toBe(expected(bytes));
  });

  it("按块读取文件计算并逐块回报进度", async () => {
    const bytes = new Uint8Array(10_000).map((_, index) => index % 256);
    const progress: number[] = [];
    const hex = await hashBlobInline(new Blob([bytes]), (hashed) => progress.push(hashed), 4096);
    expect(hex).toBe(expected(bytes));
    expect(progress).toEqual([4096, 8192, 10_000]);
  });
});

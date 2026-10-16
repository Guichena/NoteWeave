import { Sha256 } from "./sha256";

// 按块读取文件并计算 SHA-256，每读完一块回报一次进度
self.onmessage = async (event: MessageEvent<{ blob: Blob; chunkBytes: number }>) => {
  const { blob, chunkBytes } = event.data;
  try {
    const hash = new Sha256();
    for (let offset = 0; offset < blob.size; offset += chunkBytes) {
      const end = Math.min(blob.size, offset + chunkBytes);
      hash.update(new Uint8Array(await blob.slice(offset, end).arrayBuffer()));
      self.postMessage({ type: "progress", hashedBytes: end });
    }
    self.postMessage({ type: "done", hex: hash.digestHex() });
  } catch (error) {
    self.postMessage({ type: "error", message: error instanceof Error ? error.message : "文件指纹计算失败" });
  }
};

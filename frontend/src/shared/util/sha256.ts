// 可分块输入的 SHA-256。Web Crypto 只能一次性计算整个缓冲区，大文件会占用等量内存，
// 这里按块累加，配合 Web Worker 在后台计算秒传与续传用的文件指纹。

const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
]);

export class Sha256 {
  private readonly state = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
  ]);
  private readonly block = new Uint8Array(64);
  private readonly words = new Uint32Array(64);
  private blockLength = 0;
  private totalBytes = 0;

  update(data: Uint8Array) {
    let offset = 0;
    this.totalBytes += data.length;
    if (this.blockLength > 0) {
      const take = Math.min(64 - this.blockLength, data.length);
      this.block.set(data.subarray(0, take), this.blockLength);
      this.blockLength += take;
      offset = take;
      if (this.blockLength === 64) {
        this.compress(this.block, 0);
        this.blockLength = 0;
      }
    }
    for (; offset + 64 <= data.length; offset += 64) {
      this.compress(data, offset);
    }
    if (offset < data.length) {
      this.block.set(data.subarray(offset), 0);
      this.blockLength = data.length - offset;
    }
    return this;
  }

  digestHex() {
    const bitLength = this.totalBytes * 8;
    const padding = new Uint8Array(this.blockLength < 56 ? 64 - this.blockLength : 128 - this.blockLength);
    padding[0] = 0x80;
    const view = new DataView(padding.buffer);
    view.setUint32(padding.length - 8, Math.floor(bitLength / 0x100000000));
    view.setUint32(padding.length - 4, bitLength >>> 0);
    const total = this.totalBytes;
    this.update(padding);
    this.totalBytes = total;
    return Array.from(this.state, (word) => word.toString(16).padStart(8, "0")).join("");
  }

  private compress(data: Uint8Array, offset: number) {
    const w = this.words;
    for (let i = 0; i < 16; i += 1) {
      const j = offset + i * 4;
      w[i] = ((data[j] << 24) | (data[j + 1] << 16) | (data[j + 2] << 8) | data[j + 3]) >>> 0;
    }
    for (let i = 16; i < 64; i += 1) {
      const a = w[i - 15];
      const b = w[i - 2];
      const s0 = ((a >>> 7) | (a << 25)) ^ ((a >>> 18) | (a << 14)) ^ (a >>> 3);
      const s1 = ((b >>> 17) | (b << 15)) ^ ((b >>> 19) | (b << 13)) ^ (b >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }
    let [a, b, c, d, e, f, g, h] = this.state;
    for (let i = 0; i < 64; i += 1) {
      const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
      const ch = (e & f) ^ (~e & g);
      const t1 = (h + S1 + ch + K[i] + w[i]) >>> 0;
      const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (S0 + maj) >>> 0;
      h = g;
      g = f;
      f = e;
      e = (d + t1) >>> 0;
      d = c;
      c = b;
      b = a;
      a = (t1 + t2) >>> 0;
    }
    const s = this.state;
    s[0] += a; s[1] += b; s[2] += c; s[3] += d;
    s[4] += e; s[5] += f; s[6] += g; s[7] += h;
  }
}

export const HASH_CHUNK_BYTES = 4 * 1024 * 1024;

/** 读取 Blob 的字节；个别环境的 Blob 没有 arrayBuffer 方法时改用 FileReader。 */
export async function readBlobBytes(blob: Blob): Promise<Uint8Array> {
  if (typeof blob.arrayBuffer === "function") return new Uint8Array(await blob.arrayBuffer());
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer));
    reader.onerror = () => reject(reader.error ?? new Error("文件读取失败"));
    reader.readAsArrayBuffer(blob);
  });
}

/** 在当前线程按块读取文件计算 SHA-256；Worker 不可用时使用。 */
export async function hashBlobInline(
  blob: Blob,
  onProgress?: (hashedBytes: number) => void,
  chunkBytes = HASH_CHUNK_BYTES
) {
  const hash = new Sha256();
  for (let offset = 0; offset < blob.size; offset += chunkBytes) {
    const end = Math.min(blob.size, offset + chunkBytes);
    hash.update(await readBlobBytes(blob.slice(offset, end)));
    onProgress?.(end);
  }
  return hash.digestHex();
}

type WorkerMessage = { type: "progress"; hashedBytes: number } | { type: "done"; hex: string } | { type: "error"; message: string };

/** 在 Web Worker 中计算文件指纹，避免大文件阻塞页面；环境不支持 Worker 时退回当前线程。 */
export function hashFile(blob: Blob, onProgress?: (hashedBytes: number) => void): Promise<string> {
  if (typeof Worker === "undefined") return hashBlobInline(blob, onProgress);
  return new Promise((resolve, reject) => {
    let worker: Worker;
    try {
      worker = new Worker(new URL("./sha256.worker.ts", import.meta.url), { type: "module" });
    } catch {
      resolve(hashBlobInline(blob, onProgress));
      return;
    }
    worker.onmessage = (event: MessageEvent<WorkerMessage>) => {
      const message = event.data;
      if (message.type === "progress") {
        onProgress?.(message.hashedBytes);
        return;
      }
      worker.terminate();
      if (message.type === "done") resolve(message.hex);
      else reject(new Error(message.message));
    };
    worker.onerror = () => {
      worker.terminate();
      resolve(hashBlobInline(blob, onProgress));
    };
    worker.postMessage({ blob, chunkBytes: HASH_CHUNK_BYTES });
  });
}

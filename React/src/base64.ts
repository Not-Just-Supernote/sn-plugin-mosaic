const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'

/** 字符码 → 6 位值；非字母表字符（含 '='）为 -1。 */
const DECODE_TABLE: Int16Array = (() => {
  const table = new Int16Array(256).fill(-1)
  for (let i = 0; i < ALPHABET.length; i++) table[ALPHABET.charCodeAt(i)] = i
  return table
})()

/** 6 位值 → 字符码。 */
const ENCODE_TABLE: Uint8Array = (() => {
  const table = new Uint8Array(64)
  for (let i = 0; i < ALPHABET.length; i++) table[i] = ALPHABET.charCodeAt(i)
  return table
})()

const PAD_CODE = 61 // '='

// 每块 3×2048 字节 → 8192 个字符码，一次 String.fromCharCode.apply 拼成字符串
//（各引擎对 apply 实参个数都有上限，8K 远在安全范围内）。
// Hermes 没有 JIT，逐字符 `result +=` 拼接（每 3 字节 4 次字符串分配）在几百 KB 的
// 画板容器上要走秒级；查表 + 按块 fromCharCode 只有常数级的字符串对象，快一个数量级。
const CHUNK_BYTES = 3 * 2048

export function base64ToBytes(base64: string): Uint8Array {
  const length = base64.length
  const padding = base64.endsWith('==') ? 2 : base64.endsWith('=') ? 1 : 0
  const bytes = new Uint8Array((length >> 2) * 3 - padding)
  let position = 0
  for (let offset = 0; offset < length; offset += 4) {
    const a = DECODE_TABLE[base64.charCodeAt(offset)]
    const b = DECODE_TABLE[base64.charCodeAt(offset + 1)]
    const c = DECODE_TABLE[base64.charCodeAt(offset + 2)]
    const d = DECODE_TABLE[base64.charCodeAt(offset + 3)]
    const value = (a << 18) | (b << 12) | ((c < 0 ? 0 : c) << 6) | (d < 0 ? 0 : d)
    if (position < bytes.length) bytes[position++] = value >>> 16
    if (position < bytes.length) bytes[position++] = value >>> 8
    if (position < bytes.length) bytes[position++] = value
  }
  return bytes
}

export function bytesToBase64(bytes: Uint8Array): string {
  const length = bytes.length
  const parts: string[] = []
  const codes: number[] = new Array((CHUNK_BYTES / 3) * 4)
  for (let start = 0; start < length; start += CHUNK_BYTES) {
    const end = Math.min(start + CHUNK_BYTES, length)
    let n = 0
    let offset = start
    // 完整 3 字节组
    for (; offset + 3 <= end; offset += 3) {
      const value = (bytes[offset] << 16) | (bytes[offset + 1] << 8) | bytes[offset + 2]
      codes[n++] = ENCODE_TABLE[(value >>> 18) & 63]
      codes[n++] = ENCODE_TABLE[(value >>> 12) & 63]
      codes[n++] = ENCODE_TABLE[(value >>> 6) & 63]
      codes[n++] = ENCODE_TABLE[value & 63]
    }
    // 尾部 1 或 2 字节（只会出现在最后一块）
    if (offset < end) {
      const b0 = bytes[offset]
      const b1 = offset + 1 < end ? bytes[offset + 1] : 0
      const value = (b0 << 16) | (b1 << 8)
      codes[n++] = ENCODE_TABLE[(value >>> 18) & 63]
      codes[n++] = ENCODE_TABLE[(value >>> 12) & 63]
      codes[n++] = offset + 1 < end ? ENCODE_TABLE[(value >>> 6) & 63] : PAD_CODE
      codes[n++] = PAD_CODE
    }
    codes.length = n
    parts.push(String.fromCharCode.apply(null, codes))
  }
  return parts.join('')
}

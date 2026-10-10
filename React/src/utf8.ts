export function encodeUtf8(text: string): Uint8Array {
  const bytes: number[] = []
  for (const character of text) {
    let code = character.codePointAt(0)!
    if (code >= 0xd800 && code <= 0xdfff) code = 0xfffd
    if (code < 0x80) bytes.push(code)
    else if (code < 0x800) bytes.push(0xc0 | (code >> 6), 0x80 | (code & 63))
    else if (code < 0x10000) bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 63), 0x80 | (code & 63))
    else bytes.push(0xf0 | (code >> 18), 0x80 | ((code >> 12) & 63), 0x80 | ((code >> 6) & 63), 0x80 | (code & 63))
  }
  return Uint8Array.from(bytes)
}

export function decodeUtf8(bytes: Uint8Array): string {
  let result = ''
  let offset = 0
  while (offset < bytes.length) {
    const first = bytes[offset++]
    if (first < 0x80) { result += String.fromCharCode(first); continue }
    const length = first >= 0xc2 && first <= 0xdf ? 2 : first >= 0xe0 && first <= 0xef ? 3 : first >= 0xf0 && first <= 0xf4 ? 4 : 0
    if (!length) { result += '\ufffd'; continue }
    let code = first & (0x7f >> length)
    let valid = true
    for (let index = 1; index < length; index++) {
      const next = bytes[offset]
      const lower = index === 1 && first === 0xe0 ? 0xa0 : index === 1 && first === 0xf0 ? 0x90 : 0x80
      const upper = index === 1 && first === 0xed ? 0x9f : index === 1 && first === 0xf4 ? 0x8f : 0xbf
      if (next === undefined || next < lower || next > upper) { valid = false; break }
      offset++
      code = (code << 6) | (next & 63)
    }
    result += valid ? String.fromCodePoint(code) : '\ufffd'
  }
  return result
}

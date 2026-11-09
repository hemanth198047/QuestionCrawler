/**
 * Cleans text collected from other sites before it is shown: HTML character codes (&rsquo; &bull; &#8217;) become the
 * characters they stand for, invisible characters are removed, garbled characters from a wrong character set
 * ("donâ€™t") are repaired, and Markdown escapes (\_ \* \|) lose their backslash. Pages collected before these fixes
 * look right without collecting them again.
 */

// eslint-disable-next-line no-control-regex -- removing control characters is the point
const INVISIBLE = /[​-‍⁠﻿­\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F�]/g
// Windows-1252 bytes 0x80-0x9F as characters, for repairing UTF-8 read as Windows-1252
const CP1252: Record<string, number> = {
  '€': 0x80, '‚': 0x82, 'ƒ': 0x83, '„': 0x84, '…': 0x85, '†': 0x86, '‡': 0x87, 'ˆ': 0x88, '‰': 0x89, 'Š': 0x8a, '‹': 0x8b, 'Œ': 0x8c,
  'Ž': 0x8e, '‘': 0x91, '’': 0x92, '“': 0x93, '”': 0x94, '•': 0x95, '–': 0x96, '—': 0x97, '˜': 0x98, '™': 0x99, 'š': 0x9a, '›': 0x9b,
  'œ': 0x9c, 'ž': 0x9e, 'Ÿ': 0x9f,
}
const MOJIBAKE = /[Â-ô][\u0080-¿ŒœŠšŸŽžƒˆ˜–—‘-‚“-„†-•…‰‹›€™]{1,3}/g

function repairMojibake(text: string): string {
  if (!/[Â-ô]/.test(text)) return text
  return text.replace(MOJIBAKE, (bad) => {
    const bytes: number[] = []
    for (const ch of bad) {
      const c = ch.charCodeAt(0)
      if (c <= 0xff) bytes.push(c)
      else if (CP1252[ch] !== undefined) bytes.push(CP1252[ch])
      else return bad
    }
    try {
      return new TextDecoder('utf-8', { fatal: true }).decode(new Uint8Array(bytes))
    } catch {
      return bad
    }
  })
}

let decoder: HTMLTextAreaElement | null = null

/** &rsquo; &amp; &#8217; &#x2019; ... as characters (a textarea decodes them without running or building any HTML). */
export function decodeEntities(text: string): string {
  if (!text.includes('&') || !/&(#\d+|#x[0-9a-f]+|[a-z][a-z0-9]*);/i.test(text)) return text
  if (typeof document === 'undefined') return text
  decoder ??= document.createElement('textarea')
  return text.replace(/&(#\d+|#x[0-9a-f]+|[a-z][a-z0-9]*);/gi, (m) => {
    decoder!.innerHTML = m
    return decoder!.value
  })
}

/** Whole-text cleaning: invisible and garbled characters, ¶ marks after headings, "link to this heading" anchors. */
export function cleanText(text: string | null | undefined): string {
  if (!text) return ''
  return repairMojibake(text)
    .replace(INVISIBLE, '')
    .replace(/[  ]/g, ' ')
    .replace(/\[\s*[¶§#🔗⚓]+\s*\]\(#[^)\s]*\)/gu, '') // [¶](#heading)
    .replace(/<#[^>\s]*>/g, '') // <#heading>
    .replace(/^(#{1,6} .*?)[ \t]*[¶§⚓🔗]+[ \t]*$/gmu, '$1')
}

/** A title or label: cleaned and decoded, without trailing ¶. */
export function cleanTitle(text: string | null | undefined): string {
  return decodeEntities(cleanText(text)).replace(/\s*[¶§]+\s*$/, '').replace(/\s+/g, ' ').trim()
}

/** Plain text inside Markdown: decoded, and Markdown escapes (\_ \* \| \[ ...) shown without the backslash. */
export function plainText(text: string): string {
  return decodeEntities(text).replace(/\\([\\`*_{}[\]()#+\-.!|>~<])/g, '$1')
}

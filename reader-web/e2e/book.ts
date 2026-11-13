import JSZip from 'jszip'

/**
 * A long synthetic book for the end-to-end tests (no copyrighted text): one chapter of PARAGRAPHS paragraphs, each of
 * three sentences of Spanish words, with segments and their literal translations, glosses and notes, so pages fill
 * and blocks get cut. Deterministic, so the expected order of the words is known.
 */
// Words of the windmills passage (Don Quijote, part I, chapter 8), each once.
const WORDS = ['en', 'esto', 'descubrieron', 'treinta', 'o', 'cuarenta', 'molinos', 'de', 'viento', 'que', 'hay', 'aquel',
  'campo', 'y', 'así', 'como', 'don', 'quijote', 'los', 'vio', 'dijo', 'a', 'su', 'escudero', 'la', 'ventura', 'va',
  'guiando', 'nuestras', 'cosas', 'mejor', 'acertáramos', 'desear', 'porque', 'ves', 'allí']
export const PARAGRAPHS = 120

export function sentenceIds(): string[] {
  return Array.from({ length: PARAGRAPHS }, (_, p) => [1, 2, 3].map((s) => `c01p${String(p + 1).padStart(3, '0')}s${s}`)).flat()
}

/** The e2e book: one long chapter, or with [second] a short second chapter; [id] names another book. */
export async function longBook(second = false, id = 'e2e'): Promise<Buffer> {
  let seed = 7
  const next = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648
  const paragraphs = Array.from({ length: PARAGRAPHS }, (_, p) => ({
    id: `c01p${String(p + 1).padStart(3, '0')}`,
    sentences: [1, 2, 3].map((s) => {
      const n = 10 + Math.floor(next() * 12)
      const tokens = Array.from({ length: n }, (_, i) => (i === 0 ? 'Entonces' : WORDS[Math.floor(next() * WORDS.length)]))
      tokens.push('.')
      const ws = tokens.map((_, i) => (i >= n - 1 ? (i === n ? ' ' : '') : ' '))
      const segments: [number, number, string, null][] = []
      for (let a = 0; a < tokens.length; a += 4) segments.push([a, Math.min(a + 3, tokens.length - 1), 'буквальный перевод сегмента', null])
      const lexical = tokens.flatMap((t, i) => (t.length > 6 && next() < 0.5 ? [{ t: i, lemma: t, key: `${t}|NOUN#1`, n: 1, gloss: 'значение слова' }] : []))
      return {
        id: `c01p${String(p + 1).padStart(3, '0')}s${s}`, tokens, ws, segments, lexical,
        grammar: s === 1 ? [[0, 1, 'topic', 'Короткая заметка о грамматике этого места.']] : [], context: [], variants: [], expressions: [],
      }
    }),
  }))
  const chapter = { index: 1, title: 'Capítulo largo', paragraphs }
  const short = { index: 2, title: 'Capítulo corto', paragraphs: paragraphs.slice(0, 6).map((p) => ({
    ...p, id: p.id.replace('c01', 'c02'), sentences: p.sentences.map((s) => ({ ...s, id: s.id.replace('c01', 'c02') })) })) }
  const zip = new JSZip()
  zip.file('manifest.json', JSON.stringify({ schema: '1.5', id, title: id === 'e2e' ? 'E2E' : id, author: 'Test', studied: 'es', support: 'ru',
    maxOccurrence: 20, chapters: [{ index: 1, title: chapter.title, file: 'chapters/001.json' },
      ...(second ? [{ index: 2, title: short.title, file: 'chapters/002.json' }] : [])] }))
  zip.file('chapters/001.json', JSON.stringify(chapter))
  if (second) zip.file('chapters/002.json', JSON.stringify(short))
  return zip.generateAsync({ type: 'nodebuffer' })
}

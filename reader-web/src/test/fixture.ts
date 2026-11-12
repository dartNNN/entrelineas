import JSZip from 'jszip'
import chapter1 from '@format/fixtures/chapter-001.json'
import chapter2 from '@format/fixtures/chapter-002.json'
import manifest from '@format/fixtures/manifest.json'

/**
 * The shared test package (format/fixtures, also read by the Android tests): a title paragraph, "Hola mundo." with
 * every annotation kind, a dictionary entry, a second chapter. brokenChapter drops a required field.
 */
export async function testBook(opts: { brokenChapter?: boolean } = {}): Promise<Uint8Array> {
  const first = structuredClone(chapter1)
  if (opts.brokenChapter) delete (first.paragraphs[1].sentences[0] as { ws?: unknown }).ws
  const zip = new JSZip()
  zip.file('manifest.json', JSON.stringify(manifest))
  zip.file('chapters/001.json', JSON.stringify(first))
  zip.file('chapters/002.json', JSON.stringify(chapter2))
  return zip.generateAsync({ type: 'uint8array' })
}

import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { loadBook } from './book'
import { testBook } from '../test/fixture'

describe('loadBook', () => {
  it('reads a package in the shared format with every annotation kind', async () => {
    const book = await loadBook(await testBook())
    expect([book.studied, book.support]).toEqual(['es', 'ru'])
    const chapter = await book.chapter(0)
    const [title, first] = chapter.paragraphs
    expect(title.heading).toBe(true)
    const s = first.sentences[0]
    expect(s.segments.map((g) => g.literal)).toEqual(['Привет', 'мир.'])
    expect(s.notes.map((n) => n.kind)).toEqual(['grammar', 'context', 'variants', 'expression'])
    expect(s.notes[2].text).toBe('мир, свет — оттенок')
    expect(s.lexical.get(1)?.from).toBe('dictionary')
    expect(s.reference).toMatchObject({ text: 'Привет, мир.', notes: ['Первая программа.'] })
    expect(s.authorNotes).toEqual(['Saludo.'])
    expect(first.sentences[1].reference?.text).toBeNull()
    expect(chapter.entries['mundo|NOUN'].senses).toHaveLength(2)
    expect(book.chapters.map((c) => c.title)).toEqual(['Capítulo de prueba', 'Dos'])
  })

  it('names what breaks the format when the chapter is opened', async () => {
    const book = await loadBook(await testBook({ brokenChapter: true }))
    await expect(book.chapter(0)).rejects.toThrow(/chapters\/001\.json.*ws/)
    await expect(book.chapter(5)).rejects.toThrow('В книге нет главы 6')
  })

  it('refuses a file that is not a package', async () => {
    await expect(loadBook(new TextEncoder().encode('not a zip'))).rejects.toThrow('Это не файл книги')
  })

  // The demo package in demo/, and the packages built locally in build/ (those may hold copyrighted text and are
  // never part of the repository).
  const packages = ['../../../demo/', '../../../build/'].flatMap((dir) => {
    const url = new URL(dir, import.meta.url)
    return existsSync(url) ? readdirSync(url).filter((name) => name.endsWith('.bbook.zip')).map((name) => new URL(name, url)) : []
  })
  for (const path of packages) {
    it(`opens ${path.pathname.split('/').slice(-2).join('/')}`, async () => {
      const book = await loadBook(readFileSync(path))
      expect((await book.chapter(0)).paragraphs.length).toBeGreaterThan(10)
    })
  }
})

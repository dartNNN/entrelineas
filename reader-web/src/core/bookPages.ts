/**
 * Pages of the whole book for one layout (settings and page size), as in the Android reader (BookPages.kt). Chapters
 * already laid out count exactly; the others are estimated from their length at the pages-per-length of the laid-out
 * ones, as FBReader estimates pages from characters per page. Exact once every chapter was laid out.
 */
export class BookPages {
  private readonly perWeight: number | null
  private readonly weights: number[]
  private readonly exact: ReadonlyMap<number, number>
  constructor(weights: number[], exact: ReadonlyMap<number, number>) {
    this.weights = weights
    this.exact = exact
    let pages = 0, weight = 0
    for (const [i, n] of exact) if (i < weights.length) { pages += n; weight += weights[i] }
    this.perWeight = weight > 0 ? pages / weight : null
  }
  /** Whether there is anything to count from (at least one chapter laid out). */
  get available() { return this.perWeight != null }
  /** Whether some chapters are estimated, not counted. */
  get estimated() { return this.weights.some((_, i) => !this.exact.has(i)) }
  pages(chapter: number) { return this.exact.get(chapter) ?? Math.max(1, Math.round((this.perWeight ?? 0) * this.weights[chapter])) }
  get total() { return this.weights.reduce((sum, _, i) => sum + this.pages(i), 0) }
  /** The page number in the book (from 1) of [page] (from 0) of [chapter]. */
  pageInBook(chapter: number, page: number) {
    let n = 0
    for (let i = 0; i < chapter; i++) n += this.pages(i)
    return n + page + 1
  }
}

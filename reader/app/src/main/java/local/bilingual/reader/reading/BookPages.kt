package local.bilingual.reader.reading
import kotlin.math.roundToInt


/**
 * Pages of the whole book for one layout (settings and screen size). Chapters already laid out count exactly; the
 * others are estimated from their length at the pages-per-length of the laid-out ones, as FBReader estimates pages
 * from characters per page. The estimate sharpens as chapters are read, and is exact once every chapter was laid out.
 * weights: relative length of each chapter (Book.chapterWeights); exact: pages of the laid-out chapters by index.
 */
class BookPages(private val weights: List<Long>, private val exact: Map<Int, Int>) {
    private val perWeight: Double? = exact.filterKeys { it in weights.indices }.let { known ->
        val w = known.keys.sumOf { weights[it] }
        if (known.isEmpty() || w <= 0) null else known.values.sum().toDouble() / w
    }

    /** Whether there is anything to count from (at least one chapter laid out). */
    val available get() = perWeight != null
    /** Whether some chapters are estimated, not counted. */
    val estimated get() = weights.indices.any { it !in exact }

    fun pages(chapter: Int): Int = exact[chapter] ?: ((perWeight ?: 0.0) * weights[chapter]).roundToInt().coerceAtLeast(1)
    val total get() = weights.indices.sumOf { pages(it) }
    /** The page number in the book (from 1) of [page] (from 0) of [chapter]. */
    fun pageInBook(chapter: Int, page: Int) = (0 until chapter).sumOf { pages(it) } + page + 1
}

package local.bilingual.reader
import local.bilingual.reader.reading.BookPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


/** S4: the book's page count, exact for laid-out chapters and estimated from length for the others. */
class BookPagesTest {
    @Test
    fun estimatesUnreadChaptersAtThePagesPerLengthOfTheReadOnes() {
        val pages = BookPages(listOf(100, 200, 50), mapOf(0 to 10))
        assertTrue(pages.available)
        assertTrue(pages.estimated)
        assertEquals(listOf(10, 20, 5), (0..2).map { pages.pages(it) })
        assertEquals(35, pages.total)
        assertEquals(13, pages.pageInBook(1, 2))
    }

    @Test
    fun isExactOnceEveryChapterIsLaidOut() {
        val pages = BookPages(listOf(100, 200), mapOf(0 to 9, 1 to 31))
        assertFalse(pages.estimated)
        assertEquals(40, pages.total)
    }

    @Test
    fun hasNothingToCountFromBeforeAnyLayout() {
        assertFalse(BookPages(listOf(100, 200), emptyMap()).available)
    }
}

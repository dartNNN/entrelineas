package local.bilingual.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** S4: read a chapter. */
@RunWith(AndroidJUnit4::class)
class ReadingTest : ReaderTestBase() {
    @Test
    fun s4_1_firstPageShowsHintedNotesAndPlainPass() {
        launchWithBook()
        assertTrue(piece(0, 1).text().contains("Hola (Привет) mundo [мир]. (мир.)"))
        assertTrue(piece(0, 2).text().startsWith("◦ Существительное"))
        assertEquals("Hola mundo.", piece(0, 3).text())
    }

    @Test
    fun s4_2_swipesTurnPagesBothWays() {
        launchWithBook()
        swipeForward(3)
        assertEquals(4, currentPage())
        swipeBack(2)
        assertEquals(2, currentPage())
    }

    @Test
    fun s4_3_counterShowsTotalAfterBackgroundLayout() {
        launchWithBook()
        val total = pageCount()
        assertTrue(total > 3)
        assertFalse(counter().contains("…"))
    }

    @Test
    fun s4_4_positionIsStoredWhileReading() {
        launchWithBook()
        swipeForward(2)
        val stored = local.bilingual.reader.data.Store(context).library().first().paragraph
        assertEquals(currentParagraph(), stored)
    }
}

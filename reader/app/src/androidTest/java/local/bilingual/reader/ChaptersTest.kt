package local.bilingual.reader

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** S8: move between chapters. */
@RunWith(AndroidJUnit4::class)
class ChaptersTest : ReaderTestBase() {
    @Test
    fun s8_1_switchToAnotherChapter() {
        launchWithBook(TestBook.bytes(paragraphs = 10, chapters = 2))
        swipeForward(2)
        openMenu()
        compose.onNodeWithTag("chapter1").performClick()
        compose.waitForIdle()
        assertEquals("Capítulo 2", piece(0, 0).text())
        assertEquals(1, currentPage())
    }

    @Test
    fun s8_3_swipePastTheLastPageOpensTheNextChapter() {
        launchWithBook(TestBook.bytes(paragraphs = 10, chapters = 2))
        swipeForward(pageCount() - 1)
        assertEquals(pageCount(), currentPage())
        compose.onNodeWithTag("page${currentPage() - 1}").performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("page0-piece0").fetchSemanticsNodes().isNotEmpty() &&
            piece(0, 0).text() == "Capítulo 2" }
        assertEquals(1, currentPage())
    }

    @Test
    fun s8_4_swipeBackFromTheFirstPageOpensThePreviousChapterOnItsLastPage() {
        launchWithBook(TestBook.bytes(paragraphs = 10, chapters = 2))
        val pages = pageCount()
        openMenu()
        compose.onNodeWithTag("chapter1").performClick()
        compose.waitForIdle()
        assertEquals("Capítulo 2", piece(0, 0).text())
        pageCount()  // the layout is complete: the turn page before the first one is there
        compose.onNodeWithTag("page0").performTouchInput { swipeRight() }
        compose.waitUntil(10_000) { runCatching { pages() == pages to pages }.getOrDefault(false) }
    }

    @Test
    fun s8_5_aLongChapterListIsFoldedUntilOpened() {
        launchWithBook(TestBook.bytes(paragraphs = 2, chapters = 15))
        openMenu()
        assertEquals(0, compose.onAllNodesWithTag("chapter0").fetchSemanticsNodes().size)
        compose.onNodeWithTag("chapters").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chapter0").assertExists()
    }

    @Test
    fun s8_2_chapterChoiceSurvivesRelaunch() {
        launchWithBook(TestBook.bytes(paragraphs = 10, chapters = 2))
        openMenu()
        compose.onNodeWithTag("chapter1").performClick()
        compose.waitForIdle()
        relaunch()
        assertEquals("Capítulo 2", piece(0, 0).text())
    }
}

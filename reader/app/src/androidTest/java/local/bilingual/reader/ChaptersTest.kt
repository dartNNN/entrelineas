package local.bilingual.reader

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
    fun s8_2_chapterChoiceSurvivesRelaunch() {
        launchWithBook(TestBook.bytes(paragraphs = 10, chapters = 2))
        openMenu()
        compose.onNodeWithTag("chapter1").performClick()
        compose.waitForIdle()
        relaunch()
        assertEquals("Capítulo 2", piece(0, 0).text())
    }
}

package local.bilingual.reader

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** S6: mark a sense as familiar. */
@RunWith(AndroidJUnit4::class)
class FamiliarTest : ReaderTestBase() {
    private fun markMundoKnown() {
        piece(0, 1).tapWord("mundo")
        compose.onNodeWithTag("toggleKnown").performClick()
        closeSheet()
    }

    @Test
    fun s6_1_knownHidesGlossImmediately() {
        launchWithBook()
        markMundoKnown()
        assertFalse(piece(0, 1).text().contains("[мир]"))
    }

    @Test
    fun s6_2_knownSurvivesRelaunch() {
        launchWithBook()
        markMundoKnown()
        relaunch()
        assertFalse(piece(0, 1).text().contains("[мир]"))
    }

    @Test
    fun s6_3_unmarkRestoresGloss() {
        launchWithBook()
        markMundoKnown()
        piece(0, 3).tapWord("mundo")
        compose.onNodeWithTag("toggleKnown").performClick()
        closeSheet()
        assertTrue(piece(0, 1).text().contains("[мир]"))
    }

    @Test
    fun s6_4_knownAppliesToTheWholeChapter() {
        launchWithBook()
        swipeForward(2)
        val page = currentPage() - 1
        val n = (0..5).first { n -> runCatching { piece(page, n).text() }.getOrDefault("").contains("tomaron") }
        piece(page, n).tapWord("tomaron")
        compose.onNodeWithTag("toggleKnown").performClick()
        closeSheet()
        swipeBack(2)
        swipeForward(5)
        val later = currentPage() - 1
        assertFalse((0..5).any { n -> runCatching { piece(later, n).text() }.getOrDefault("").contains("[взять]") })
    }
}

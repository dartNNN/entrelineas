package local.bilingual.reader

import local.bilingual.reader.actions.*
import android.content.ClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** S9 (read with hidden controls) and S10 (act on a selection). */
@RunWith(AndroidJUnit4::class)
class ShellSelectionTest : ReaderTestBase() {
    private fun shown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun s9_1_shellHidesItselfAndComesBackOnTheCounterLine() {
        launchWithBook()
        assertTrue("the top bar hides after the idle delay", !shown("settings"))
        compose.onNodeWithTag("pageCounter").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings").assertIsDisplayed()
        compose.onNodeWithTag("menu").assertIsDisplayed()
    }

    @Test
    fun s9_2_menuListsChaptersAndOpensOne() {
        launchWithBook(TestBook.bytes(paragraphs = 3, chapters = 2))
        openMenu()
        compose.onNodeWithTag("chapter1").assertIsDisplayed()
        compose.onNodeWithTag("openBook").assertIsDisplayed()
        compose.onNodeWithTag("chapter1").performClick()
        compose.waitForIdle()
        assertTrue(currentParagraph().startsWith("c02"))
    }

    @Test
    fun s9_5_backClosesTheMenuNotTheApp() {
        launchWithBook()
        openMenu()
        compose.onNodeWithTag("openBook").assertIsDisplayed()
        closeSheet()
        // The closed drawer stays composed off screen: it must not be displayed, and the reader must still be there.
        compose.onNodeWithTag("openBook").assertIsNotDisplayed()
        compose.onNodeWithTag("pageCounter").assertIsDisplayed()
    }

    @Test
    fun s9_6_verticalSwipeCallsUpTheShell() {
        launchWithBook()
        assertTrue(!shown("settings"))
        compose.onNodeWithTag("page0").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("settings").assertIsDisplayed()
    }

    @Test
    fun s9_7_allSettingsHaveTabsForTextLookAndActions() {
        launchWithBook()
        openSettings()
        compose.onNodeWithTag("allSettings").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("toggle:Выравнивание по ширине").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("tab:actions").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("action:$ACTION_COPY").assertIsDisplayed()
        compose.onNodeWithTag("what:$ACTION_COPY").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Выделенное и перевод переводчика").assertIsDisplayed()
    }

    @Test
    fun s9_3_fullScreenSwapsTheCounterForTheStatusLine() {
        launchWithBook()
        openSettings()
        toggle("Полноэкранный режим")
        closeSheet()
        assertTrue(!shown("pageCounter"))
        compose.onNodeWithTag("statusLine").assertIsDisplayed()
    }

    @Test
    fun s9_6_closingTheWordCardInFullScreenDoesNotFlashTheStatusBar() {
        launchWithBook()
        openSettings()
        toggle("Полноэкранный режим")
        closeSheet()
        waitForShellToHide()
        piece(0, 1).tapWord("mundo")
        compose.onNodeWithTag("wordCard").assertIsDisplayed()
        closeSheet()
        // Watched for a while after closing: the status bar must not show even for a moment.
        val until = android.os.SystemClock.uptimeMillis() + 1500
        while (android.os.SystemClock.uptimeMillis() < until) {
            var visible = false
            scenario!!.onActivity { a ->
                visible = androidx.core.view.ViewCompat.getRootWindowInsets(a.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()) == true
            }
            assertTrue("the status bar showed after the card closed", !visible)
            Thread.sleep(50)
        }
    }

    @Test
    fun s9_4_schemeAndScreenSettingsSurviveRelaunch() {
        launchWithBook()
        openSettings()
        compose.onNodeWithTag("allSettings").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("fullSettings").assertIsDisplayed()
        compose.onNodeWithTag("tab:view").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("scheme:SEPIA").performClick()
        compose.waitForIdle()
        relaunch()
        openSettings()
        compose.onNodeWithTag("allSettings").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tab:view").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("scheme:SEPIA").assertIsDisplayed()
        val prefs = context.getSharedPreferences("reader", android.content.Context.MODE_PRIVATE)
        assertEquals("SEPIA", prefs.getString("scheme", null))
    }

    @Test
    fun s10_1_longPressSelectsTheWordAndUnitsWidenIt() {
        launchWithBook()
        piece(0, 1).longPressWord("mundo")
        compose.onNodeWithTag("selectionPanel").assertIsDisplayed()
        assertEquals("mundo", compose.onNodeWithTag("selectionText").text())
        compose.onNodeWithTag("unit:SENTENCE").performClick()
        compose.waitForIdle()
        assertEquals("Hola mundo.", compose.onNodeWithTag("selectionText").text())
        compose.onNodeWithTag("layer:LITERAL").performClick()
        compose.waitForIdle()
        assertEquals("Привет мир.", compose.onNodeWithTag("selectionText").text())
        compose.onNodeWithTag("closeSelection").performClick()
        compose.waitForIdle()
        assertTrue(!shown("selectionPanel"))
    }

    @Test
    fun s10_2_copyPutsTheTemplateTextOnTheClipboard() {
        launchWithBook()
        piece(0, 1).longPressWord("mundo")
        compose.onNodeWithTag("layer:GLOSS").performClick()
        compose.onNodeWithTag("run:$ACTION_COPY").performClick()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val cm = context.getSystemService(ClipboardManager::class.java)
            assertEquals("mundo — мир", cm.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }

    @Test
    fun s10_3_knownActionMarksTheSense() {
        launchWithBook()
        piece(0, 1).longPressWord("mundo")
        // "I know this sense" is one of the actions shown at once.
        compose.onNodeWithTag("run:$ACTION_KNOWN").performScrollTo().performClick()
        compose.waitForIdle()
        val prefs = context.getSharedPreferences("reader", android.content.Context.MODE_PRIVATE)
        assertTrue("mundo|NOUN#1" in prefs.getStringSet("known", emptySet())!!)
    }
}

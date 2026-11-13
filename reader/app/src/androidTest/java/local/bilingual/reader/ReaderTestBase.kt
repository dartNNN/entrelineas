package local.bilingual.reader

import android.content.Context
import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import java.io.File

/** Launch, navigation and inspection helpers shared by the scenario tests. */
abstract class ReaderTestBase {
    @get:Rule
    val compose = createEmptyComposeRule()
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun close() {
        scenario?.close()
    }

    /** Starts the app with a fresh book (clears stored settings, marks and position). */
    protected fun launchWithBook(bytes: ByteArray = TestBook.bytes()) {
        val file = File(context.cacheDir, "test.bbook.zip").apply { writeBytes(bytes) }
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).putExtra(EXTRA_BOOK_PATH, file.absolutePath))
        waitForShellToHide()
    }

    /** The top bar is shown on start and covers the first lines until it hides itself (real time, a few seconds). */
    protected fun waitForShellToHide() {
        compose.waitForIdle()
        // Without a book (the open screen) the bar stays.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("settings").fetchSemanticsNodes().isEmpty() ||
                compose.onAllNodesWithTag("open").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    /** Restarts the app with another book added to the library (the library, positions and settings are kept). */
    protected fun addBook(bytes: ByteArray) {
        scenario?.close()
        val file = File(context.cacheDir, "added.bbook.zip").apply { writeBytes(bytes) }
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).putExtra(EXTRA_ADD_BOOK, file.absolutePath))
        waitForShellToHide()
    }

    /** Restarts the app as the owner would, without passing a book. */
    protected fun relaunch() {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForShellToHide()
    }

    protected fun clearAppData() {
        File(context.filesDir, "book.zip").delete()
        File(context.filesDir, "books").deleteRecursively()
        context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** The chapter's "page / pages" (or "page / …" while it is laid out), whatever progress items the counter shows. */
    protected fun counter() = compose.onNodeWithTag("pageCounter").fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    /** Waits for the background layout, then returns (current page, total pages). */
    protected fun pages(): Pair<Int, Int> {
        compose.waitUntil(10_000) { !counter().contains("…") }
        val (current, total) = counter().split("/").map { it.trim().toInt() }
        return current to total
    }

    protected fun pageCount() = pages().second
    protected fun currentPage() = pages().first

    protected fun currentParagraph(): String =
        compose.onNodeWithTag("page${currentPage() - 1}").fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    protected fun piece(page: Int, n: Int) = compose.onNodeWithTag("page$page-piece$n")

    protected fun SemanticsNodeInteraction.text(): String =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""

    /** Taps the centre of the first occurrence of [word] inside a text node. */
    protected fun SemanticsNodeInteraction.tapWord(word: String) = touchWord(word, long = false)

    /** Long-presses the first occurrence of [word]: starts a selection. */
    protected fun SemanticsNodeInteraction.longPressWord(word: String) = touchWord(word, long = true)

    private fun SemanticsNodeInteraction.touchWord(word: String, long: Boolean) {
        val node = fetchSemanticsNode()
        val text = node.config[SemanticsProperties.Text].joinToString("") { it.text }
        val index = text.indexOf(word)
        assertTrue("'$word' not found in: $text", index >= 0)
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val box = layouts.first().getBoundingBox(index + word.length / 2)
        performTouchInput { if (long) longClick(Offset(box.center.x, box.center.y)) else click(Offset(box.center.x, box.center.y)) }
        compose.waitForIdle()
    }

    protected fun swipeForward(times: Int = 1) = repeat(times) {
        compose.onNodeWithTag("page${currentPage() - 1}").performTouchInput { swipeLeft() }
        compose.waitForIdle()
    }

    protected fun swipeBack(times: Int = 1) = repeat(times) {
        compose.onNodeWithTag("page${currentPage() - 1}").performTouchInput { swipeRight() }
        compose.waitForIdle()
    }

    /** Calls up the top bar (it hides itself after a few seconds) by tapping the page counter line. */
    protected fun showShell() {
        if (compose.onAllNodesWithTag("settings").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("pageCounter").performClick()
            compose.waitForIdle()
        }
    }

    protected fun openSettings() {
        showShell()
        compose.onNodeWithTag("settings").performClick()
        compose.waitForIdle()
    }

    /** Opens the navigation drawer with the chapters. */
    protected fun openMenu() {
        showShell()
        compose.onNodeWithTag("menu").performClick()
        compose.waitForIdle()
    }

    protected fun closeSheet() {
        Espresso.pressBack()
        compose.waitForIdle()
    }

    // Settings are long lists: every helper scrolls its control into view first.
    protected fun setSlider(tag: String, value: Float) {
        compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        compose.waitForIdle()
    }

    protected fun choose(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    protected fun toggle(label: String) {
        compose.onNodeWithTag("toggle:$label").performScrollTo().performClick()
        compose.waitForIdle()
    }
}

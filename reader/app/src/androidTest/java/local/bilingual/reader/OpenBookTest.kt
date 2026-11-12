package local.bilingual.reader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** S3: open a book. */
@RunWith(AndroidJUnit4::class)
class OpenBookTest : ReaderTestBase() {
    @Test
    fun s3_1_withoutBookShowsOpenButton() {
        clearAppData()
        relaunch()
        compose.onNodeWithTag("open").assertIsDisplayed()
    }

    @Test
    fun s3_3_invalidFileFallsBackToOpenScreen() {
        val garbage = File(context.cacheDir, "garbage.zip").apply { writeText("not a book") }
        scenario = androidx.test.core.app.ActivityScenario.launch(
            android.content.Intent(context, MainActivity::class.java).putExtra(EXTRA_BOOK_PATH, garbage.absolutePath))
        compose.waitForIdle()
        compose.onNodeWithTag("open").assertIsDisplayed()
    }

    @Test
    fun s3_4_bookOpensAtChapterStart() {
        launchWithBook()
        compose.onNodeWithTag("page0").assertIsDisplayed()
        assertEquals(1, currentPage())
        assertTrue(pageCount() > 3)
        assertEquals("Capítulo de prueba", piece(0, 0).text())
    }

    @Test
    fun s3_5_relaunchRestoresBookAndPosition() {
        launchWithBook()
        swipeForward(4)
        val paragraph = currentParagraph()
        relaunch()
        assertEquals(paragraph, currentParagraph())
    }
}

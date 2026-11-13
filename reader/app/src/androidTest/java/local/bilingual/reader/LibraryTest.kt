package local.bilingual.reader

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** S3: several books, each where it was left. */
@RunWith(AndroidJUnit4::class)
class LibraryTest : ReaderTestBase() {
    @Test
    fun s3_5_eachOpenedBookKeepsItsPosition() {
        launchWithBook(TestBook.bytes(paragraphs = 30))
        swipeForward(2)
        assertEquals(3, currentPage())
        addBook(TestBook.bytes(paragraphs = 30, id = "other", title = "Other"))
        assertEquals(1, currentPage())
        openMenu()
        compose.onNodeWithTag("book:test").performClick()
        compose.waitForIdle()
        assertEquals(3, currentPage())
    }

    @Test
    fun s3_7_openingTheSameBookAgainKeepsItsPositionAndDoesNotHang() {
        launchWithBook(TestBook.bytes(paragraphs = 30, chapters = 3))
        swipeForward(2)
        addBook(TestBook.bytes(paragraphs = 30, chapters = 3))  // a rebuilt package of the same book
        assertEquals(3, currentPage())
    }

    @Test
    fun s3_6_theLibrarySurvivesRelaunch() {
        launchWithBook(TestBook.bytes(paragraphs = 30))
        addBook(TestBook.bytes(paragraphs = 30, id = "other", title = "Other"))
        relaunch()
        openMenu()
        compose.onNodeWithTag("book:test").assertExists()
    }
}

package local.bilingual.reader

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** S7: tune the presentation while reading. */
@RunWith(AndroidJUnit4::class)
class SettingsTest : ReaderTestBase() {
    private fun firstPageText() = (0..4).joinToString("\n") { n -> runCatching { piece(0, n).text() }.getOrDefault("") }

    @Test
    fun s7_1_thresholdHidesLaterOccurrences() {
        launchWithBook()
        swipeForward(1)
        assertTrue(piece(1, 0).text().contains("[взять]") || piece(1, 1).text().contains("[взять]"))
        openSettings()
        setSlider("threshold", 1f)
        closeSheet()
        swipeBack(1)
        assertTrue(firstPageText().contains("[мир]"))
        swipeForward(2)
        val page = currentPage() - 1
        assertFalse((0..3).any { n -> runCatching { piece(page, n).text() }.getOrDefault("").contains("[взять]") })
    }

    @Test
    fun s7_2_fontSizeChangesPageCount() {
        launchWithBook()
        val before = pageCount()
        openSettings()
        setSlider("fontSize", 26f)
        assertEquals(26f, context.getSharedPreferences("reader", android.content.Context.MODE_PRIVATE).getFloat("fontSize", 0f))
        closeSheet()
        assertTrue("bigger font, more pages: $before -> ${pageCount()}", pageCount() > before)
    }

    @Test
    fun s7_3_togglesRemoveTheirParts() {
        launchWithBook()
        openSettings()
        toggle("Дословный перевод сегментов")
        toggle("Грамматика и контекст")
        closeSheet()
        val text = firstPageText()
        assertFalse(text.contains("(Привет)"))
        assertFalse(text.contains("◦ Существительное"))
        assertTrue(text.contains("[мир]"))
    }

    @Test
    fun s7_4_secondPassAndGlossesOffShrinkTheChapter() {
        launchWithBook()
        val before = pageCount()
        openSettings()
        choose("repeat:OFF")
        toggle("Подсказки к словам")
        closeSheet()
        assertTrue(pageCount() < before)
        assertFalse(firstPageText().contains("[мир]"))
    }

    @Test
    fun s7_5_settingChangeKeepsReadingPosition() {
        launchWithBook()
        swipeForward(5)
        val paragraph = currentParagraph()
        openSettings()
        toggle("Дословный перевод сегментов")
        closeSheet()
        assertEquals(paragraph, currentParagraph())
    }

    @Test
    fun s7_6_settingsSurviveRelaunch() {
        launchWithBook()
        openSettings()
        choose("repeat:OFF")
        setSlider("threshold", 3f)
        closeSheet()
        val pages = pageCount()
        relaunch()
        assertEquals(pages, pageCount())
        openSettings()
        val threshold = compose.onNodeWithTag("threshold").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertEquals(3f, threshold)
    }

    @Test
    fun s7_8_publishedTranslationTogglesForTextAndCard() {
        launchWithBook(TestBook.bytes(references = true))
        assertFalse(firstPageText().contains("Привет, мир."))
        openSettings()
        toggle("Перевод переводчика после абзаца")
        toggle("Перевод переводчика в карточке")
        closeSheet()
        assertTrue(firstPageText().contains("Привет, мир."))
        piece(0, 1).tapWord("mundo")
        compose.onAllNodesWithText("Перевод переводчика").assertCountEquals(0)
    }

    @Test
    fun s7_10_footnotesToggleRemovesThemFromTextAndCard() {
        launchWithBook(TestBook.bytes(references = true))
        openSettings()
        toggle("Перевод переводчика после абзаца")
        closeSheet()
        assertTrue(firstPageText().contains("* Hola mundo — первая программа."))
        openSettings()
        toggle("Сноски переводчика")
        closeSheet()
        assertFalse(firstPageText().contains("первая программа"))
        piece(0, 1).tapWord("mundo")
        compose.onAllNodesWithText("Сноска:", substring = true).assertCountEquals(0)
    }

    @Test
    fun s7_11_authorFootnotesToggle() {
        launchWithBook(TestBook.bytes(authorNotes = true))
        assertTrue(firstPageText().contains("* Saludo de los programadores."))
        openSettings()
        toggle("Сноски автора")
        closeSheet()
        assertFalse(firstPageText().contains("Saludo de los programadores"))
    }

    @Test
    fun s7_9_noTranslationTogglesWithoutPublishedTranslation() {
        launchWithBook()
        openSettings()
        compose.onAllNodesWithTag("toggle:Перевод переводчика после абзаца").assertCountEquals(0)
        compose.onAllNodesWithTag("toggle:Сноски переводчика").assertCountEquals(0)
        compose.onAllNodesWithTag("toggle:Сноски автора").assertCountEquals(0)
    }

    @Test
    fun s7_12_screenRepeatShowsTheSameTextWithoutHints() {
        launchWithBook()
        openSettings()
        choose("repeat:SCREEN")
        closeSheet()
        val hinted = (0..4).joinToString("\n") { n -> runCatching { piece(0, n).text() }.getOrDefault("") }
        assertTrue(hinted.contains("[мир]") && !hinted.contains("Hola mundo."))
        swipeForward(1)
        val repeat = (0..6).joinToString("\n") { n -> runCatching { piece(1, n).text() }.getOrDefault("") }
        assertTrue(repeat, repeat.startsWith("Hola mundo.") && !repeat.contains("[") && !repeat.contains("(Привет)"))
        piece(1, 0).tapWord("mundo")
        compose.onNodeWithTag("wordCard").assertExists()
        relaunch()
        openSettings()
        compose.onNodeWithTag("repeat:SCREEN").assertIsSelected()
    }

    @Test
    fun s7_7_layoutTimeIsShown() {
        launchWithBook()
        openSettings()
        val label = compose.onNodeWithTag("layoutMs").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")
        assertTrue(label, Regex("Разбивка главы на страницы: \\d+ мс").matches(label))
    }
}

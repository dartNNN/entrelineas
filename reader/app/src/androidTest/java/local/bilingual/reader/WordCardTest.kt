package local.bilingual.reader

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** S5: look up a word. */
@RunWith(AndroidJUnit4::class)
class WordCardTest : ReaderTestBase() {
    private fun inCard(text: String, substring: Boolean = false) =
        compose.onNode(hasText(text, substring = substring) and hasAnyAncestor(hasTestTag("wordCard")))

    @Test
    fun s5_1_tapInHintedPassOpensCardWithEntry() {
        launchWithBook()
        piece(0, 1).tapWord("mundo")
        compose.onNodeWithTag("wordCard").assertIsDisplayed()
        inCard("mundo").assertIsDisplayed()
        inCard("mundo · м.").assertIsDisplayed()
        inCard("мир").assertIsDisplayed()
        inCard("появление №1").assertIsDisplayed()
    }

    @Test
    fun s5_2_cardShowsSegmentAndEveryNoteKind() {
        launchWithBook()
        piece(0, 1).tapWord("mundo")
        inCard("mundo.").assertIsDisplayed()
        inCard("мир.").assertIsDisplayed()
        inCard("Грамматика: Существительное мужского рода.").assertIsDisplayed()
        inCard("Варианты: мир, свет — оттенок").assertIsDisplayed()
        inCard("Выражение: hola mundo — привет, мир").assertIsDisplayed()
    }

    @Test
    fun s5_3_tapInPlainPassOpensTheSameCard() {
        launchWithBook()
        piece(0, 3).tapWord("mundo")
        inCard("появление №1").assertIsDisplayed()
    }

    @Test
    fun s5_4_tapOnGlossOpensItsWord() {
        launchWithBook()
        piece(0, 1).tapWord("[мир]")
        inCard("mundo").assertIsDisplayed()
    }

    @Test
    fun s5_6_cardShowsPublishedTranslationOfTheSentence() {
        launchWithBook(TestBook.bytes(references = true))
        piece(0, 1).tapWord("mundo")
        inCard("Перевод переводчика").assertIsDisplayed()
        inCard("Привет, мир.").assertIsDisplayed()
        inCard("Сноска: Hola mundo — первая программа.").assertIsDisplayed()
    }

    @Test
    fun s5_8_cardShowsAuthorFootnote() {
        launchWithBook(TestBook.bytes(authorNotes = true))
        piece(0, 1).tapWord("mundo")
        inCard("Сноска автора: Saludo de los programadores.").assertIsDisplayed()
    }

    @Test
    fun s5_7_cardMarksFreeAndLeftOutTranslation() {
        launchWithBook(TestBook.bytes(references = true, paragraphs = 3))
        // the first hinted "Luego …" piece in the book belongs to paragraph 2, the free rendering
        val n = (0..5).first { runCatching { piece(0, it).text() }.getOrDefault("").startsWith("Luego") }
        piece(0, n).tapWord("jaula")
        inCard("Перевод переводчика · вольный").assertIsDisplayed()
        inCard("Тогда призраки подняли клетку на плечи 2.").assertIsDisplayed()
    }

    @Test
    fun s5_9_tapOnPunctuationOpensNoCard() {
        launchWithBook()
        piece(0, 3).tapWord(".")  // the plain pass "Hola mundo.": its first full stop is the token
        compose.onAllNodesWithTag("wordCard").assertCountEquals(0)
    }

    @Test
    fun s5_5_wordWithoutEntryShowsContextButNoKnownButton() {
        launchWithBook()
        piece(0, 1).tapWord("Hola")
        compose.onAllNodes(hasText("Hola") and hasAnyAncestor(hasTestTag("wordCard"))).onFirst().assertIsDisplayed()
        inCard("Контекст: Приветствие.").assertIsDisplayed()
        compose.onAllNodesWithTag("toggleKnown").assertCountEquals(0)
    }
}

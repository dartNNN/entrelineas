package local.bilingual.reader
import local.bilingual.reader.reading.isWord
import org.junit.Assert.assertEquals
import org.junit.Test


/** S5: what a tap opens a word card for. */
class TokensTest {
    @Test
    fun wordsHaveALetterOrADigitAndPunctuationDoesNot() {
        val words = listOf("mundo", "Quijote", "1866", "¿Qué", "vuestra", "aspa-", "o")
        val punctuation = listOf(".", ",", "—", "-", "¿", "¡", "...", "…", "«", "»", "\"", ";", "", null)
        assertEquals(words.map { true }, words.map(::isWord))
        assertEquals(punctuation.map { false }, punctuation.map(::isWord))
    }
}

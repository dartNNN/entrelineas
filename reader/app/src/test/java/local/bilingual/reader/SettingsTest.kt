package local.bilingual.reader
import local.bilingual.reader.reading.*
import org.junit.Assert.assertEquals
import org.junit.Test


/** S7: settings rules that do not need rendering. */
class SettingsTest {
    @Test
    fun repeatIsOffWhileEveryHintIsOffAndComesBackAfter() {
        val noHints = Settings(showLiteral = false, showLexical = false, showNotes = false, repeat = Repeat.SCREEN)
        assertEquals(Repeat.OFF, noHints.effectiveRepeat)
        assertEquals(Repeat.SCREEN, noHints.repeat, "the chosen mode is kept")
        assertEquals(Repeat.SCREEN, noHints.copy(showNotes = true).effectiveRepeat)
        assertEquals(Repeat.PARAGRAPH, Settings(showLiteral = false, showNotes = false).effectiveRepeat)
    }

    private fun assertEquals(expected: Any?, actual: Any?, message: String) = org.junit.Assert.assertEquals(message, expected, actual)
}

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

    @Test
    fun progressItemsShowWhatIsSwitchedOnForTheChapterAndTheBook() {
        val p = local.bilingual.reader.ui.Progress(4, 10, 1, 4, "", bookShare = 0.375f, bookPage = 30, bookPages = 80, estimated = true)
        org.junit.Assert.assertEquals(listOf("30 / ~80 (37%)"), p.items(ShellSettings()))  // the defaults: the book only
        org.junit.Assert.assertEquals(listOf("глава 5 / 10 (50%)", "37%"),
            p.items(ShellSettings(page = true, chapterProgress = true, bookPages = false)))
        org.junit.Assert.assertEquals(emptyList<String>(), p.items(ShellSettings(bookPages = false, bookProgress = false)))
    }

    private fun assertEquals(expected: Any?, actual: Any?, message: String) = org.junit.Assert.assertEquals(message, expected, actual)
}

package local.bilingual.reader
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import local.bilingual.reader.format.*
import local.bilingual.reader.reading.*
import local.bilingual.reader.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith


/** Scenario S4 (read) and S7 (settings) at the text-building level: what the hinted, notes and plain passes contain. */
@RunWith(AndroidJUnit4::class)
class BlocksTest {
    private val palette = Palette(Color.Gray, Color.Blue, Color.DarkGray)
    private val chapter = BookParser.parse(TestBook.bytes(paragraphs = 3).inputStream()).chapters[0]

    private fun blocks(s: Settings = Settings(), known: Set<String> = emptySet()) = buildBlocks(chapter, s, known)
    private fun hinted(s: Settings = Settings(), known: Set<String> = emptySet(), paragraph: Int = 0) =
        blocks(s, known).filter { it.kind == BlockKind.HINTED }[paragraph].plain

    @Test
    fun blockOrderIsTitleThenHintedNotesPlainPerParagraph() {
        assertEquals(listOf(BlockKind.TITLE, BlockKind.HINTED, BlockKind.NOTES, BlockKind.PLAIN, BlockKind.HINTED, BlockKind.PLAIN),
            blocks().map { it.kind }.take(6))
        assertEquals("Capítulo de prueba", blocks().first().plain)
    }

    @Test
    fun annotatedHeadingIsTheTitleWithItsHints() {
        val withHeading = BookParser.parse(TestBook.bytes(paragraphs = 2, schema = "1.3", heading = true).inputStream()).chapters[0]
        val b = buildBlocks(withHeading, Settings(), emptySet())
        assertEquals(listOf(BlockKind.TITLE, BlockKind.PLAIN, BlockKind.HINTED), b.map { it.kind }.take(3))
        assertEquals("Capítulo de prueba [проверка] (Глава проверки) ", b.first().plain)
        assertEquals("c01p000", b.first().paragraphId)
    }

    @Test
    fun hintedPassInterleavesGlossesAndSegmentTranslations() {
        assertEquals("Hola (Привет) mundo [мир]. (мир.) ", hinted())
    }

    @Test
    fun selectionPaintsTheChosenLayerOnly() {
        val hinted = blocks().first { it.kind == BlockKind.HINTED }
        val word = setOf("c01p001s1" to 1)
        fun painted(layer: Layer): String {
            val text = highlighted(hinted, annotated(hinted, palette), Highlight(word, setOf("c01p001"), layer, Color.Yellow))
            return text.spanStyles.filter { it.item.background == Color.Yellow }.joinToString("|") { text.text.substring(it.start, it.end) }
        }
        assertEquals("mundo", painted(Layer.ORIGINAL))
        assertEquals(" [мир]", painted(Layer.GLOSS))
        assertEquals(" (мир.)", painted(Layer.LITERAL))
    }

    @Test
    fun noRepeatWhileEveryHintIsOff() {
        val off = Settings(showLiteral = false, showLexical = false, showNotes = false, repeat = Repeat.PARAGRAPH)
        assertTrue(blocks(off).none { it.kind == BlockKind.PLAIN })
        val page = Page(blocks(off).filter { it.kind == BlockKind.HINTED }.take(1), "c01p001")
        val sentences = chapter.paragraphs.flatMap { it.sentences }.associateBy { it.id }
        assertEquals(1, withRepeats(listOf(page), off.copy(repeat = Repeat.SCREEN), sentences).size)
        assertTrue(blocks(off.copy(showNotes = true)).any { it.kind == BlockKind.PLAIN })
    }

    @Test
    fun plainPassIsTheOriginalWithItsSpacing() {
        assertEquals("Hola mundo.", blocks().first { it.kind == BlockKind.PLAIN }.plain)
    }

    @Test
    fun notesBlockListsGrammarAndContext() {
        val notes = blocks().first { it.kind == BlockKind.NOTES }.plain
        assertEquals("◦ Существительное мужского рода.\n※ Приветствие.", notes)
    }

    @Test
    fun thresholdHidesLaterOccurrences() {
        // paragraph 2 holds occurrence #2 of tomar and jaula
        assertTrue(hinted(Settings(threshold = 2), paragraph = 1).contains("[взять]"))
        assertFalse(hinted(Settings(threshold = 1), paragraph = 1).contains("[взять]"))
        assertTrue(hinted(Settings(threshold = 1)).contains("[мир]"))
    }

    @Test
    fun knownSenseHidesItsGlossOnly() {
        val text = hinted(known = setOf("tomar|NOUN#1"), paragraph = 1)
        assertFalse(text.contains("[взять]"))
        assertTrue(text.contains("[клетка]"))
    }

    @Test
    fun togglesRemoveTheirParts() {
        assertFalse(hinted(Settings(showLiteral = false)).contains("(Привет)"))
        assertFalse(hinted(Settings(showLexical = false)).contains("[мир]"))
        assertTrue(blocks(Settings(showNotes = false)).none { it.kind == BlockKind.NOTES })
        assertTrue(blocks(Settings(repeat = Repeat.OFF)).none { it.kind == BlockKind.PLAIN })
        assertTrue(blocks(Settings(repeat = Repeat.SCREEN)).none { it.kind == BlockKind.PLAIN })
        assertEquals("Hola mundo. ", hinted(Settings(showLiteral = false, showLexical = false)))
    }

    @Test
    fun literaryTranslationFollowsLiteralWithEqualsSign() {
        val sentence = Sentence("s", listOf("Hola", "."), listOf("", ""), listOf(Segment(0, 1, "Привет.", "Здравствуй.")), emptyList(), emptyMap())
        val ch = Chapter(1, "T", listOf(Paragraph("c01p001", listOf(sentence))))
        assertEquals("Hola. (Привет. = Здравствуй.) ", buildBlocks(ch, Settings(), emptySet())[1].plain)
    }

    @Test
    fun sentenceWithoutSegmentsIsStillRendered() {
        val sentence = Sentence("s", listOf("Sí", "."), listOf("", ""), emptyList(), emptyList(), emptyMap())
        val ch = Chapter(1, "T", listOf(Paragraph("c01p001", listOf(sentence))))
        assertEquals("Sí. ", buildBlocks(ch, Settings(), emptySet())[1].plain)
    }

    @Test
    fun everyTokenIsTappableInBothPasses() {
        for (block in blocks().filter { it.kind == BlockKind.HINTED || it.kind == BlockKind.PLAIN }) {
            val tags = annotated(block, palette).let { t -> t.getStringAnnotations(TOKEN_TAG, 0, t.length) }.map { it.item }.toSet()
            val sentences = chapter.paragraphs.first { it.id == block.paragraphId }.sentences
            val expected = sentences.flatMap { s -> s.tokens.indices.map { "${s.id}:$it" } }.toSet()
            assertEquals(expected, tags)
        }
    }

    @Test
    fun glossTapOpensItsWord() {
        val text = annotated(blocks().first { it.kind == BlockKind.HINTED }, palette)
        val offset = text.text.indexOf("[мир]") + 1
        assertEquals("c01p001s1:1", text.getStringAnnotations(TOKEN_TAG, offset, offset).single().item)
    }

    @Test
    fun publishedTranslationFollowsNotesOnlyWhenEnabled() {
        val ch = BookParser.parse(TestBook.bytes(paragraphs = 3, references = true).inputStream()).chapters[0]
        assertTrue(buildBlocks(ch, Settings(), emptySet()).none { it.kind == BlockKind.REFERENCE })
        val b = buildBlocks(ch, Settings(referenceInText = true), emptySet())
        assertEquals(listOf(BlockKind.TITLE, BlockKind.HINTED, BlockKind.NOTES, BlockKind.REFERENCE, BlockKind.PLAIN, BlockKind.HINTED,
            BlockKind.REFERENCE, BlockKind.PLAIN), b.map { it.kind }.take(8))
        val refs = b.filter { it.kind == BlockKind.REFERENCE }.map { it.plain }
        // footnotes follow their translation; free and uncertain renderings are marked; a left-out sentence adds nothing
        assertEquals(listOf("Привет, мир.\n* Hola mundo — первая программа.", "≈ Тогда призраки подняли клетку на плечи 2.",
            "? Тогда призраки подняли клетку на плечи 3."), refs)
        val noNotes = buildBlocks(ch, Settings(referenceInText = true, showFootnotes = false), emptySet())
        assertEquals("Привет, мир.", noNotes.first { it.kind == BlockKind.REFERENCE }.plain)
    }

    @Test
    fun authorFootnotesFollowTheParagraphNotesWhenOn() {
        val ch = BookParser.parse(TestBook.bytes(paragraphs = 2, authorNotes = true).inputStream()).chapters[0]
        val b = buildBlocks(ch, Settings(), emptySet())
        assertEquals(listOf(BlockKind.TITLE, BlockKind.HINTED, BlockKind.NOTES, BlockKind.AUTHOR_NOTES, BlockKind.PLAIN), b.map { it.kind }.take(5))
        assertEquals("* Saludo de los programadores.", b[3].plain)
        assertTrue(buildBlocks(ch, Settings(showAuthorNotes = false), emptySet()).none { it.kind == BlockKind.AUTHOR_NOTES })
    }

    @Test
    fun groupSpanningParagraphsIsShownAfterItsLastParagraph() {
        val ref = Reference(0, "Две фразы в одной.", false)
        fun par(n: Int) = Paragraph("c01p00$n", listOf(Sentence("c01p00${n}s1", listOf("Sí", "."), listOf("", ""), emptyList(), emptyList(), emptyMap(), ref)))
        val b = buildBlocks(Chapter(1, "T", listOf(par(1), par(2))), Settings(referenceInText = true, repeat = Repeat.OFF), emptySet())
        assertEquals(listOf(BlockKind.TITLE, BlockKind.HINTED, BlockKind.HINTED, BlockKind.REFERENCE), b.map { it.kind })
        assertEquals("c01p002", b.last().paragraphId)
    }

    @Test
    fun screenRepeatHoldsExactlyTheTokensOfItsScreen() {
        val sentences = chapter.paragraphs.flatMap { it.sentences }.associateBy { it.id }
        val hinted = blocks(Settings(repeat = Repeat.SCREEN)).filter { it.kind == BlockKind.HINTED }
        // a screen with paragraph 1 and the first half of paragraph 2, cut inside a sentence
        val cut = hinted[1].plain.indexOf("jaula") + "jaula".length
        val page = Page(listOf(hinted[0], hinted[1].slice(0, cut, continues = true)), "c01p001")
        val repeat = repeatPage(page, sentences)!!
        assertTrue(repeat.repeat)
        assertEquals(listOf("c01p001", "c01p002"), repeat.pieces.map { it.paragraphId })
        assertEquals("Hola mundo.", repeat.pieces[0].plain)
        assertEquals("Luego tomaron la jaula", repeat.pieces[1].plain)
        assertTrue(repeat.pieces.all { it.kind == BlockKind.PLAIN })
        val tags = annotated(repeat.pieces[1], palette).let { t -> t.getStringAnnotations(TOKEN_TAG, 0, t.length) }.map { it.item }
        assertEquals(listOf("c01p002s1:0", "c01p002s1:1", "c01p002s1:2", "c01p002s1:3"), tags)
        assertEquals(null, repeatPage(Page(listOf(blocks().first()), "c01p001"), sentences))
        assertEquals(listOf(false, true), withRepeats(listOf(page), Settings(repeat = Repeat.SCREEN), sentences).map { it.repeat })
        assertEquals(1, withRepeats(listOf(page), Settings(), sentences).size)
    }

    @Test
    fun anchorBlockFindsParagraphStart() {
        val b = blocks()
        assertEquals(0, anchorBlock(b, ""))
        assertEquals(0, anchorBlock(b, "c01p001"))
        val i = anchorBlock(b, "c01p002")
        assertEquals("c01p002", b[i].paragraphId)
        assertEquals(BlockKind.HINTED, b[i].kind)
        assertEquals(0, anchorBlock(b, "c99p999"))
    }
}

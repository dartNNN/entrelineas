package local.bilingual.reader
import local.bilingual.reader.actions.*
import local.bilingual.reader.format.*
import local.bilingual.reader.reading.*
import local.bilingual.reader.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test


/** Scenario S10 (act on a selection) at the model level, and the colour schemes of S9. */
class SelectionTest {
    private val book = BookParser.parse(TestBook.bytes(paragraphs = 3, schema = "1.2", references = true).inputStream())
    private val chapter = book.chapters[0]
    private val resolver = SelectionResolver(book, chapter)

    @Test
    fun wordCarriesItsGlossSegmentTranslationAndContext() {
        val c = resolver.content(TextSelection("c01p001s1", 1))
        assertEquals("mundo", c.original)
        assertEquals("mundo — мир", c.layer(Layer.GLOSS))
        assertEquals("мир.", c.literal)
        assertEquals("Привет, мир.", c.reference)
        assertEquals("Hola mundo.", c.sentence)
        assertEquals("mundo|NOUN#1", c.key)
        assertTrue(c.notes.contains("Существительное мужского рода."))
        assertEquals(setOf("c01p001s1" to 1), resolver.tokens(TextSelection("c01p001s1", 1)))
    }

    @Test
    fun segmentTakesTheTokensOfItsLiteralTranslation() {
        val sel = TextSelection("c01p002s1", 3, SelectionUnit.SEGMENT)
        assertEquals((0..5).map { "c01p002s1" to it }.toSet(), resolver.tokens(sel))
        val c = resolver.content(sel)
        assertEquals("Luego tomaron la jaula en hombros", c.original)
        assertEquals("Потом взяли клетку на плечи", c.literal)
        assertEquals("", c.gloss)
    }

    @Test
    fun sentenceExtendsToTheNextSentencesAndJoinsTheirTranslationOnce() {
        val one = resolver.content(TextSelection("c01p002s1", 0, SelectionUnit.SENTENCE))
        val two = resolver.content(TextSelection("c01p002s1", 0, SelectionUnit.SENTENCE, more = 1))
        assertTrue(two.original.startsWith(one.original + " Luego"))
        assertEquals("Тогда призраки подняли клетку на плечи 2.", two.reference)
        assertEquals(2 * 18, resolver.tokens(TextSelection("c01p002s1", 0, SelectionUnit.SENTENCE, more = 1)).size)
    }

    @Test
    fun paragraphCoversItsSentencesAndExtendsByParagraphs() {
        val sel = TextSelection("c01p002s2", 5, SelectionUnit.PARAGRAPH)
        assertEquals(setOf("c01p002"), resolver.paragraphs(sel))
        assertEquals(setOf("c01p002", "c01p003"), resolver.paragraphs(sel.copy(more = 1)))
        assertEquals(3 * 18, resolver.tokens(sel).size)
    }

    @Test
    fun onlyLayersWithTextAreOffered() {
        assertEquals(Layer.entries.toList(), resolver.content(TextSelection("c01p001s1", 1)).layers())
        val withoutGloss = resolver.content(TextSelection("c01p002s3", 0, SelectionUnit.SENTENCE)).layers()
        assertEquals(listOf(Layer.ORIGINAL, Layer.LITERAL), withoutGloss)
    }

    @Test
    fun templateFillsFieldsAndDropsLinesWithNothingInThem() {
        val word = resolver.content(TextSelection("c01p001s1", 1))
        assertEquals("mundo — мир\nHola mundo.", fillTemplate("{original} — {gloss}\\n{sentence}", word, Layer.ORIGINAL))
        val sentence = resolver.content(TextSelection("c01p002s3", 0, SelectionUnit.SENTENCE))
        assertEquals(sentence.original, fillTemplate("{text}\\n{gloss}", sentence, Layer.ORIGINAL))
        assertEquals("мир.", fillTemplate("{text}", word, Layer.LITERAL))
        assertEquals("{unknown} mundo", fillTemplate("{unknown} {original}", word, Layer.ORIGINAL))
    }

    @Test
    fun actionConfigsKeepOrderDropGoneAppsAndAppendNewOnes() {
        val saved = listOf(ActionConfig(ACTION_SHARE, pinned = true), ActionConfig("gone"), ActionConfig(ACTION_COPY, enabled = false))
        val merged = ActionConfigs.merge(saved, listOf(ACTION_COPY, ACTION_TRANSLATE, ACTION_SHARE, ACTION_KNOWN, ACTION_OPEN_IN_APP))
        assertEquals(listOf(ACTION_SHARE, ACTION_COPY, ACTION_KNOWN, ACTION_TRANSLATE, ACTION_OPEN_IN_APP), merged.map { it.id })
        assertEquals(false, merged[1].enabled)
        assertEquals(ActionConfig(ACTION_OPEN_IN_APP), merged.last())
        assertEquals(merged, ActionConfigs.fromJson(ActionConfigs.toJson(merged)))
        assertEquals(listOf(ACTION_COPY, ACTION_SHARE), ActionConfigs.move(merged.take(2), 1, -1).map { it.id })
        assertEquals(merged.take(2), ActionConfigs.move(merged.take(2), 0, -1))
        assertEquals(emptyList<ActionConfig>(), ActionConfigs.fromJson("not json"))
    }

    @Test
    fun defaultsPutTheReadersOwnActionsFirstAndOtherAppsBehindOneAction() {
        val available = listOf(ACTION_COPY, ACTION_TRANSLATE, ACTION_SHARE, ACTION_KNOWN, ACTION_SPEAK, ACTION_ANKI_WORD,
            ACTION_ANKI_SENTENCE, ACTION_OPEN_IN_APP)
        val fresh = ActionConfigs.merge(emptyList(), available)
        assertEquals(listOf(ACTION_KNOWN, ACTION_COPY), fresh.filter { it.pinned }.map { it.id })
        assertEquals(ActionConfigs.DEFAULTS.map { it.id }, fresh.map { it.id })
        assertTrue(fresh.all { it.enabled })
    }

    @Test
    fun appListPutsRecentAppsFirstThenTextAppsThenShareTargets() {
        val a = AppTarget("a", "A", "Словарь", process = true)
        val b = AppTarget("b", "B", "Блокнот", process = false)
        val c = AppTarget("c", "C", "Атлас", process = true)
        assertEquals(listOf(c, a, b), AppTargets.order(listOf(b, a, c), emptyList()))
        assertEquals(listOf(b, c, a), AppTargets.order(listOf(a, b, c), listOf(b.id)))
    }

    @Test
    fun ankiWordCardHasTheWordItsSentenceAndMeaning() {
        val c = resolver.content(TextSelection("c01p001s1", 1))
        val note = AnkiNotes.word(c)
        assertEquals(AnkiNotes.WORD, note.model)
        assertEquals(AnkiNotes.WORD.fields.size, note.fields.size)
        assertEquals("mundo", note.key)
        assertEquals("мир", note.fields[2])
        assertEquals("Hola <b>mundo</b>.", note.fields[3])
        assertEquals("Привет, мир.", note.fields[4])
        val sentence = AnkiNotes.sentence(resolver.content(TextSelection("c01p001s1", 0, SelectionUnit.SENTENCE)))
        assertEquals(listOf("Hola mundo.", "Привет, мир.", "Привет мир."), sentence.fields.take(3))
        assertEquals("a &lt;b&gt; &amp; c", AnkiNotes.escape("a <b> & c"))
    }

    @Test
    fun everyLayerHasTheLanguageTheBookDeclares() {
        val c = resolver.content(TextSelection("c01p001s1", 1))
        assertEquals("es", c.language(Layer.ORIGINAL))
        assertEquals(listOf("ru"), listOf(Layer.LITERAL, Layer.REFERENCE, Layer.GLOSS, Layer.NOTE).map { c.language(it) }.distinct())
        val english = SelectionResolver(book.copy(studied = "en", support = "de"), chapter).content(TextSelection("c01p001s1", 1))
        assertEquals("en" to "de", english.language(Layer.ORIGINAL) to english.language(Layer.REFERENCE))
    }

    @Test
    fun segmentIsOfferedOnlyWhereTheSentenceHasSegments() {
        assertEquals(SelectionUnit.entries.toList(), resolver.units(TextSelection("c01p001s1", 1)))
        val bare = chapter.copy(paragraphs = chapter.paragraphs.map { p -> p.copy(sentences = p.sentences.map { it.copy(segments = emptyList()) }) })
        assertEquals(listOf(SelectionUnit.WORD, SelectionUnit.SENTENCE, SelectionUnit.PARAGRAPH),
            SelectionResolver(book, bare).units(TextSelection("c01p001s1", 1)))
        assertEquals("Сегмент", SelectionUnit.SEGMENT.title)
    }

    @Test
    fun schemesKeepTextSoftAndSmallTextReadable() {
        for (id in SchemeId.entries) {
            val c = Schemes.resolve(id, systemDark = false)
            val text = Schemes.contrast(c.page, c.text)
            assertTrue("$id text $text", text in 8.5..13.0)
            for ((name, color) in listOf("literal" to c.literal, "gloss" to c.gloss, "note" to c.note)) {
                val ratio = Schemes.contrast(c.page, color)
                assertTrue("$id $name $ratio", ratio in 4.5..7.5)
            }
        }
        assertEquals(Schemes.DUSK, Schemes.resolve(SchemeId.SYSTEM, systemDark = true))
        assertEquals(Schemes.PAPER, Schemes.resolve(SchemeId.SYSTEM, systemDark = false))
    }
}

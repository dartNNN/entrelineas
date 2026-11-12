package local.bilingual.reader
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import local.bilingual.reader.format.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test


/** Scenario S3 step "parse package": the reader side of the format contract. */
class BookParserTest {
    @Test
    fun parsesStructureAndAnnotations() {
        val book = BookParser.parse(TestBook.bytes(paragraphs = 5).inputStream())
        assertEquals("test", book.id)
        assertEquals(20, book.maxOccurrence)
        val chapter = book.chapters.single()
        assertEquals(5, chapter.paragraphs.size)
        val first = chapter.paragraphs[0].sentences[0]
        assertEquals(listOf("Hola", "mundo", "."), first.tokens)
        assertEquals(listOf(" ", "", ""), first.ws)
        assertEquals(Segment(1, 2, "мир.", null), first.segments[1])
        assertEquals("мир", first.lexical[1]!!.gloss)
        assertEquals("mundo|NOUN#1", first.lexical[1]!!.key)
        assertNull(first.lexical[1]!!.form)
    }

    @Test
    fun parsesAllNoteKinds() {
        val notes = BookParser.parse(TestBook.bytes(paragraphs = 1).inputStream()).chapters[0].paragraphs[0].sentences[0].notes
        assertEquals(setOf("grammar", "context", "variants", "expression"), notes.map { it.kind }.toSet())
        assertEquals("мир, свет — оттенок", notes.single { it.kind == "variants" }.text)
        assertEquals("hola mundo — привет, мир", notes.single { it.kind == "expression" }.text)
        assertEquals(Note(0, 0, "context", "Приветствие."), notes.single { it.kind == "context" })
    }

    @Test
    fun parsesChaptersInManifestOrder() {
        val book = BookParser.parse(TestBook.bytes(paragraphs = 2, chapters = 3).inputStream())
        assertEquals(listOf(1, 2, 3), book.chapters.map { it.index })
        assertEquals("Capítulo 3", book.chapters[2].title)
        assertEquals("c03p001", book.chapters[2].paragraphs[0].id)
    }

    @Test
    fun parsesPublishedTranslationPerSentenceGroup() {
        val sentences = BookParser.parse(TestBook.bytes(paragraphs = 3, schema = "1.2", references = true).inputStream())
            .chapters[0].paragraphs.flatMap { it.sentences }
        assertEquals(Reference(0, "Привет, мир.", false, false, listOf("Hola mundo — первая программа.")), sentences[0].reference)
        val (s1, s2, s3) = sentences.subList(1, 4)
        assertEquals(s1.reference, s2.reference)
        assertEquals(Reference(1, "Тогда призраки подняли клетку на плечи 2.", true), s1.reference)
        assertEquals(Reference(2, null, false), s3.reference)
        assertTrue(sentences[4].reference!!.uncertain)
    }

    @Test
    fun parsesAuthorFootnotesSeparatelyFromTranslatorFootnotes() {
        val first = BookParser.parse(TestBook.bytes(paragraphs = 1, schema = "1.2", references = true, authorNotes = true).inputStream())
            .chapters[0].paragraphs[0].sentences[0]
        assertEquals(listOf("Saludo de los programadores."), first.authorNotes)
        assertEquals(listOf("Hola mundo — первая программа."), first.reference!!.notes)
    }

    @Test
    fun schema10HasNoPublishedTranslation() {
        val sentences = BookParser.parse(TestBook.bytes(paragraphs = 2).inputStream()).chapters[0].paragraphs.flatMap { it.sentences }
        assertTrue(sentences.all { it.reference == null })
    }

    @Test
    fun rejectsUnknownMajorSchema() {
        assertThrows(IllegalArgumentException::class.java) { BookParser.parse(TestBook.bytes(schema = "2.0").inputStream()) }
    }

    @Test
    fun parsesTheTitleAsAnAnnotatedHeadingParagraph() {
        val chapter = BookParser.parse(TestBook.bytes(paragraphs = 2, schema = "1.3", heading = true).inputStream()).chapters[0]
        val title = chapter.paragraphs[0]
        assertEquals("c01p000", title.id)
        assertEquals(true, title.heading)
        assertEquals(false, chapter.paragraphs[1].heading)
        assertEquals("проверка", title.sentences[0].lexical[2]?.gloss)
        assertEquals(false, BookParser.parse(TestBook.bytes(paragraphs = 1).inputStream()).chapters[0].paragraphs[0].heading)
    }

    @Test
    fun parsesTheDictionaryEntriesAndWhoChoseTheSense() {
        val chapter = BookParser.parse(TestBook.bytes(paragraphs = 1, schema = "1.4", dictionary = true).inputStream()).chapters[0]
        val lex = chapter.paragraphs[0].sentences[0].lexical[1]!!
        assertEquals("dictionary", lex.from)
        assertEquals("mundo|NOUN", lex.entryKey)
        assertEquals("1", lex.sense)
        assertEquals(listOf("1" to "мир, свет", "2" to "общество, люди"), chapter.entries.getValue("mundo|NOUN").senses.map { it.id to it.text })
        assertEquals(null, BookParser.parse(TestBook.bytes(paragraphs = 1).inputStream()).chapters[0].paragraphs[0].sentences[0].lexical[1]!!.from)
    }

    @Test
    fun bookBuiltWithAModelCarriesTheEntryAndTheChosenSenseInIt() {
        val chapter = BookParser.parse(TestBook.bytes(paragraphs = 1, schema = "1.5", modelEntry = true).inputStream()).chapters[0]
        val lex = chapter.paragraphs[0].sentences[0].lexical[1]!!
        assertEquals("model", lex.from)
        assertEquals("mundo|NOUN#1", lex.key)
        assertEquals("2", lex.sense)
        assertEquals("mundo", chapter.entries.getValue(lex.entryKey).headword)
    }

    @Test
    fun acceptsMinorSchemaRevision() {
        assertEquals("test", BookParser.parse(TestBook.bytes(paragraphs = 1, schema = "1.3").inputStream()).id)
    }

    @Test
    fun failsClearlyWithoutManifest() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("chapters/001.json")); z.write("{}".toByteArray()); z.closeEntry() }
        }.toByteArray()
        val error = assertThrows(IllegalStateException::class.java) { BookParser.parse(bytes.inputStream()) }
        assertEquals("manifest.json missing", error.message)
    }

    @Test
    fun failsOnGarbageInput() {
        assertThrows(Exception::class.java) { BookParser.parse("not a zip".byteInputStream()) }
    }
}

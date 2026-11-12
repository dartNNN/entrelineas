package local.bilingual.reader

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The reader and the builder share one package format, defined by the schemas in format/ at the project root.
 * Every test book the reader's tests use must match it, and the real package too when it has been built.
 */
class FormatTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "format/chapter.schema.json").exists() }
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val manifest = factory.getSchema(File(root, "format/manifest.schema.json").inputStream())
    private val chapter = factory.getSchema(File(root, "format/chapter.schema.json").inputStream())
    private val json = ObjectMapper()

    private fun errors(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val node = json.readTree(zip.readBytes())
                val schema = if (e.name == "manifest.json") manifest else chapter
                out += schema.validate(node).map { "${e.name}: ${it.message}" }
            }
        }
        return out
    }

    @Test
    fun everyTestBookMatchesTheSharedFormat() {
        val books = listOf(
            TestBook.bytes(paragraphs = 3),
            TestBook.bytes(paragraphs = 3, schema = "1.2", references = true, authorNotes = true),
            TestBook.bytes(paragraphs = 2, schema = "1.3", heading = true, chapters = 2),
            TestBook.bytes(paragraphs = 2, schema = "1.4", dictionary = true),
            TestBook.bytes(paragraphs = 2, schema = "1.5", modelEntry = true),
        )
        books.forEach { assertEquals(emptyList<String>(), errors(it)) }
    }

    @Test
    fun theFormatRejectsWhatTheReaderCouldNotRead() {
        val broken = String(TestBook.bytes(paragraphs = 1).let { b ->
            ZipInputStream(b.inputStream()).use { z -> generateSequence { z.nextEntry }.first { it.name.startsWith("chapters/") }; z.readBytes() }
        }).replace("\"ws\"", "\"spaces\"")
        val node = json.readTree(broken)
        assertTrue(chapter.validate(node).any { it.message.contains("ws") })
    }

    @Test
    fun theBuiltBooksMatchTooWhenPresent() {
        val built = listOf("demo", "build").flatMap { dir -> File(root, dir).listFiles { f -> f.name.endsWith(".bbook.zip") }.orEmpty().toList() }
        for (file in built) assertEquals(file.name, emptyList<String>(), errors(file.readBytes()))
    }
}

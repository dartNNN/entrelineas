package local.bilingual.reader
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import local.bilingual.reader.format.*
import local.bilingual.reader.reading.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test


/**
 * Golden results shared with the web reader (format/fixtures/expected.json, written and checked by golden.test.ts):
 * the blocks of the shared test chapter under several settings run by run, what selections resolve to, and the colour schemes
 * (format/schemes.json). Both readers port the same logic; here they must agree.
 */
class GoldenTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "format/fixtures/expected.json").exists() }
    private val fixtures = File(root, "format/fixtures")
    private val expected = JSONObject(File(fixtures, "expected.json").readText())

    private val book: Book = run {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            listOf("manifest.json" to "manifest.json", "chapters/001.json" to "chapter-001.json", "chapters/002.json" to "chapter-002.json")
                .forEach { (name, file) -> zip.putNextEntry(ZipEntry(name)); zip.write(File(fixtures, file).readBytes()); zip.closeEntry() }
        }
        BookParser.parse(out.toByteArray().inputStream())
    }
    private val chapter = book.chapters[0]

    private val settings = mapOf(
        "defaults" to Settings(),
        "referenceInText" to Settings(referenceInText = true),
        "noHints" to Settings(showLiteral = false, showLexical = false, showNotes = false),
        "thresholdNoRepeat" to Settings(threshold = 12, repeat = Repeat.OFF, showAuthorNotes = false),
    )
    private val selections = mapOf(
        "word" to TextSelection("c01p001s1", 1),
        "segment" to TextSelection("c01p001s1", 2, SelectionUnit.SEGMENT),
        "sentenceAndNext" to TextSelection("c01p001s1", 0, SelectionUnit.SENTENCE, more = 1),
        "paragraph" to TextSelection("c01p001s2", 0, SelectionUnit.PARAGRAPH),
        "plainWord" to TextSelection("c01p001s2", 0),
    )

    /** Block kinds named as in the shared file: HINTED → hinted, AUTHOR_NOTES → authorNotes. */
    private fun kind(k: BlockKind) = k.name.lowercase().split('_').mapIndexed { i, w -> if (i == 0) w else w.replaceFirstChar { it.uppercase() } }.joinToString("")

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    @Test
    fun blocksMatchTheSharedGoldenFile() {
        fun runKind(r: Run) = when (r) {
            is Run.Token -> "token"
            is Run.Gloss -> "gloss"
            is Run.Literal -> "literal"
            is Run.Text -> "text"
        }
        for ((name, s) in settings) {
            val known = if (name == "thresholdNoRepeat") setOf("mundo|NOUN#1") else emptySet()
            val actual = buildBlocks(chapter, s, known).map { b -> kind(b.kind) to b.runs.map { runKind(it) to it.text } }
            val want = expected.getJSONObject("blocks").getJSONArray(name).let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                    o.getString("kind") to o.getJSONArray("runs").let { r -> (0 until r.length()).map { r.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } } }
                }
            }
            assertEquals(name, want, actual)
        }
    }

    @Test
    fun selectionsMatchTheSharedGoldenFile() {
        val resolver = SelectionResolver(book, chapter)
        for ((name, sel) in selections) {
            val want = expected.getJSONObject("selections").getJSONObject(name)
            val c = resolver.content(sel)
            val actual = listOf(c.original, c.literal, c.reference, c.lemma, c.gloss, c.key, c.notes, c.sentence)
            val wanted = listOf("original", "literal", "reference", "lemma", "gloss", "key", "notes", "sentence").map { want.getString(it) }
            assertEquals(name, wanted, actual)
            assertEquals(name, strings(want.getJSONArray("units")), resolver.units(sel).map { it.name.lowercase() })
            assertEquals(name, strings(want.getJSONArray("tokens")), resolver.tokens(sel).map { "${it.first}:${it.second}" }.sorted())
        }
    }

    @Test
    fun schemesMatchTheSharedColours() {
        val json = JSONObject(File(root, "format/schemes.json").readText())
        // #RRGGBB, or #RRGGBBAA for a translucent colour, as CSS writes them.
        fun hex(argb: Long): String {
            val alpha = (argb shr 24) and 0xFF
            val rgb = String.format("#%06X", argb and 0xFFFFFF)
            return if (alpha == 0xFFL) rgb else rgb + String.format("%02X", alpha)
        }
        for ((name, c) in mapOf("paper" to Schemes.PAPER, "sepia" to Schemes.SEPIA, "dusk" to Schemes.DUSK, "night" to Schemes.NIGHT)) {
            val want = json.getJSONObject(name)
            val actual = mapOf("page" to c.page, "text" to c.text, "literal" to c.literal, "gloss" to c.gloss, "note" to c.note,
                "bar" to c.bar, "accent" to c.accent, "selection" to c.selection).mapValues { hex(it.value) }
            assertEquals(name, actual.keys.associateWith { want.getString(it) }, actual)
            assertEquals(name, want.getBoolean("dark"), c.dark)
        }
    }
}

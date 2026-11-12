package local.bilingual.reader.format

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * In-memory model of a book package (schema 1.x; 1.1 adds the published translation per sentence group,
 * 1.2 the translator's footnotes and the uncertain mark).
 */
/**
 * The chapters of a package, parsed on first access (from any thread) and kept: opening a book costs its manifest and
 * the chapter being read. titles come from the manifest without parsing.
 */
class LazyChapters(val titles: List<String>, private val texts: List<String>, private val parse: (String) -> Chapter) : AbstractList<Chapter>() {
    private val parsed = arrayOfNulls<Chapter>(texts.size)
    override val size get() = texts.size
    override fun get(index: Int): Chapter = synchronized(parsed) { parsed[index] ?: parse(texts[index]).also { parsed[index] = it } }
}

/** studied, support: language codes of the original and of the translations and notes (manifest). */
data class Book(val id: String, val title: String, val author: String, val maxOccurrence: Int, val chapters: List<Chapter>,
                val studied: String = "es", val support: String = "ru") {
    /** Chapter titles from the manifest, without parsing the chapters. */
    val chapterTitles: List<String> get() = (chapters as? LazyChapters)?.titles ?: chapters.map { it.title }
}

/** entries: dictionary entries of the chapter's words by "lemma|POS" (schema 1.4, books built from a dictionary). */
data class Chapter(val index: Int, val title: String, val paragraphs: List<Paragraph>, val entries: Map<String, Entry> = emptyMap())

data class Entry(val headword: String, val senses: List<EntrySense>)

data class EntrySense(val id: String, val text: String, val gram: String?)

/** heading: the chapter title as an annotated paragraph (schema 1.3), shown in the title's style. */
data class Paragraph(val id: String, val sentences: List<Sentence>, val heading: Boolean = false)

data class Segment(val first: Int, val last: Int, val literal: String, val literary: String?)

data class Note(val first: Int, val last: Int, val kind: String, val text: String)

/**
 * Published translation of one aligned group of sentences; text is null where the translator left them out.
 * loose: a free rendering; uncertain: the segment model found it does not render these sentences; notes: footnote texts.
 */
data class Reference(val index: Int, val text: String?, val loose: Boolean, val uncertain: Boolean = false,
                     val notes: List<String> = emptyList())

/**
 * from: who chose the sense (schema 1.4) — "model", or "dictionary" when the book was built without a model.
 * entrySense: id of the chosen sense in the dictionary entry when the key numbers senses its own way (schema 1.5).
 */
data class Lexical(val token: Int, val lemma: String, val key: String, val ordinal: Int,
                   val gloss: String, val gram: String?, val form: String?, val from: String? = null, val entrySense: String? = null) {
    /** "lemma|POS", the dictionary entry's key. */
    val entryKey get() = key.substringBefore('#')
    /** The chosen sense in the dictionary entry: the explicit id, otherwise the sense id after '#'. */
    val sense get() = entrySense ?: key.substringAfter('#', "")
}

data class Sentence(
    val id: String,
    val tokens: List<String>,
    val ws: List<String>,
    val segments: List<Segment>,
    val notes: List<Note>,
    val lexical: Map<Int, Lexical>,
    val reference: Reference? = null,
    /** The author's footnotes referenced in this sentence (schema 1.2, footnotes with side "source"). */
    val authorNotes: List<String> = emptyList(),
)

object BookParser {
    fun parse(input: InputStream): Book {
        val files = HashMap<String, String>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) files[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        val manifest = JSONObject(files["manifest.json"] ?: error("manifest.json missing"))
        require(manifest.getString("schema").startsWith("1.")) { "unsupported schema ${manifest.getString("schema")}" }
        val listed = manifest.getJSONArray("chapters").objects()
        val texts = listed.map { c -> files[c.getString("file")] ?: error("missing ${c.getString("file")}") }
        val chapters = LazyChapters(listed.map { it.getString("title") }, texts, ::parseChapter)
        return Book(manifest.getString("id"), manifest.getString("title"), manifest.getString("author"),
            manifest.getInt("maxOccurrence"), chapters, manifest.getString("studied"), manifest.getString("support"))
    }

    private fun parseChapter(text: String): Chapter = parseChapter(JSONObject(text))

    private fun parseChapter(o: JSONObject): Chapter {
        val all = o.optJSONArray("footnotes")?.objects() ?: emptyList()
        val footnotes = all.filter { it.optString("side", "reference") == "reference" }.associate { it.getString("id") to it.getString("text") }
        val authorNotes = all.filter { it.optString("side") == "source" }.associate { it.getString("id") to it.getString("text") }
        val references = o.optJSONArray("references")?.objects()?.mapIndexed { i, r ->
            val notes = r.optJSONArray("notes")?.strings()?.mapNotNull { footnotes[it] } ?: emptyList()
            Reference(i, if (r.isNull("text")) null else r.getString("text"), r.optBoolean("loose"), r.optBoolean("uncertain"), notes)
        } ?: emptyList()
        val entries = o.optJSONObject("entries")?.let { e ->
            e.keys().asSequence().associateWith { k ->
                val entry = e.getJSONObject(k)
                Entry(entry.getString("headword"), entry.getJSONArray("senses").objects().map { s ->
                    EntrySense(s.getString("id"), s.getString("text"), s.optString("gram").takeIf { it.isNotBlank() && it != "null" })
                })
            }
        } ?: emptyMap()
        return Chapter(o.getInt("index"), o.getString("title"),
            o.getJSONArray("paragraphs").objects().map { p ->
                Paragraph(p.getString("id"), p.getJSONArray("sentences").objects().map { parseSentence(it, references, authorNotes) },
                    p.optBoolean("heading"))
            }, entries)
    }

    private fun parseSentence(s: JSONObject, references: List<Reference>, authorNotes: Map<String, String>): Sentence {
        val notes = ArrayList<Note>()
        s.getJSONArray("grammar").arrays().forEach { if (it.length() >= 4) notes += Note(it.getInt(0), it.getInt(1), "grammar", it.getString(3)) }
        s.getJSONArray("context").arrays().forEach { if (it.length() >= 3) notes += Note(it.getInt(0), it.getInt(1), "context", it.getString(2)) }
        s.getJSONArray("variants").arrays().forEach {
            if (it.length() >= 3) {
                val options = it.optJSONArray(2)?.let { a -> (0 until a.length()).joinToString(", ") { i -> a.getString(i) } } ?: it.getString(2)
                val note = if (it.length() >= 4) " — ${it.optString(3)}" else ""
                notes += Note(it.getInt(0), it.getInt(1), "variants", options + note)
            }
        }
        s.getJSONArray("expressions").arrays().forEach {
            if (it.length() >= 4) notes += Note(it.getInt(0), it.getInt(1), "expression", "${it.getString(2)} — ${it.getString(3)}")
        }
        return Sentence(
            id = s.getString("id"),
            tokens = s.getJSONArray("tokens").strings(),
            ws = s.getJSONArray("ws").strings(),
            segments = s.getJSONArray("segments").arrays().filter { it.length() >= 3 }.map {
                Segment(it.getInt(0), it.getInt(1), it.getString(2), it.optString(3).takeIf { v -> v.isNotBlank() && v != "null" })
            },
            notes = notes,
            lexical = s.getJSONArray("lexical").objects().associate { l ->
                l.getInt("t") to Lexical(l.getInt("t"), l.getString("lemma"), l.getString("key"), l.getInt("n"),
                    l.getString("gloss"), l.optString("gram").takeIf { it.isNotBlank() && it != "null" },
                    l.optString("form").takeIf { it.isNotBlank() && it != "null" },
                    l.optString("from").takeIf { it.isNotBlank() && it != "null" },
                    l.optString("sense").takeIf { it.isNotBlank() && it != "null" })
            },
            reference = if (s.has("ref") && !s.isNull("ref")) references.getOrNull(s.getInt("ref")) else null,
            authorNotes = s.optJSONArray("notes")?.strings()?.mapNotNull { authorNotes[it] } ?: emptyList(),
        )
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.arrays() = (0 until length()).mapNotNull { optJSONArray(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
}

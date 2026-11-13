package local.bilingual.reader.data
import android.content.Context
import androidx.core.content.edit
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import local.bilingual.reader.actions.*
import local.bilingual.reader.reading.*


/**
 * A book in the library: its package file in the app's storage, where it was left, and when it was last open.
 * progress: the share of the book read (0..1) when it was last left, for the list of books.
 */
data class LibraryBook(val id: String, val title: String, val author: String, val file: String,
                       val chapter: Int = 0, val paragraph: String = "", val opened: Long = 0, val progress: Float = 0f)

/**
 * The reader's saved state in SharedPreferences: settings, shell settings, actions, familiar marks, and the library
 * (every opened book with its own position, most recent first; the first one is the book being read). Package files
 * live in files/books/<id>.bbook.zip; a book opened again replaces its file and keeps its position.
 */
internal class Store(context: Context) {
    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    val booksDir = File(context.filesDir, "books")
    /** The single book file of versions up to 0.6, moved into the library on first start. */
    val legacyBookFile = File(context.filesDir, "book.zip")

    fun loadSettings() = Settings(
        threshold = prefs.getInt("threshold", 10),
        showLiteral = prefs.getBoolean("literal", true),
        showLexical = prefs.getBoolean("lexical", true),
        showNotes = prefs.getBoolean("notes", true),
        // "twoPass" was the boolean before screen repeat existed
        repeat = prefs.getString("repeat", null)?.let { runCatching { Repeat.valueOf(it) }.getOrNull() }
            ?: if (prefs.getBoolean("twoPass", true)) Repeat.PARAGRAPH else Repeat.OFF,
        fontSize = prefs.getFloat("fontSize", 18f),
        margin = prefs.getInt("margin", 20),
        referenceInCard = prefs.getBoolean("refCard", true),
        referenceInText = prefs.getBoolean("refText", false),
        showFootnotes = prefs.getBoolean("footnotes", true),
        showAuthorNotes = prefs.getBoolean("authorNotes", true),
        justify = prefs.getBoolean("justify", true),
    )

    fun saveSettings(s: Settings) = prefs.edit {
        putInt("threshold", s.threshold).putBoolean("literal", s.showLiteral).putBoolean("lexical", s.showLexical)
        .putBoolean("notes", s.showNotes).putString("repeat", s.repeat.name).putFloat("fontSize", s.fontSize).putInt("margin", s.margin)
        .putBoolean("refCard", s.referenceInCard).putBoolean("refText", s.referenceInText)
        .putBoolean("footnotes", s.showFootnotes).putBoolean("authorNotes", s.showAuthorNotes)
        .putBoolean("justify", s.justify) }

    /** Version 2 of the progress items: chapter ones off, book pages and percent on; older saved choices give way once. */
    private val progressDefaults get() = prefs.getInt("statusVersion", 1) < 2

    fun loadShell() = ShellSettings(
        scheme = prefs.getString("scheme", null)?.let { runCatching { SchemeId.valueOf(it) }.getOrNull() } ?: SchemeId.SYSTEM,
        fullscreen = prefs.getBoolean("fullscreen", false),
        statusOnTop = prefs.getBoolean("statusOnTop", true),
        clock = prefs.getBoolean("statusClock", true),
        battery = prefs.getBoolean("statusBattery", true),
        page = !progressDefaults && prefs.getBoolean("statusPage", false),
        chapterProgress = !progressDefaults && prefs.getBoolean("statusChapter", false),
        bookPages = progressDefaults || prefs.getBoolean("statusBookPages", true),
        bookProgress = progressDefaults || prefs.getBoolean("statusBook", true),
        chapterTitle = prefs.getBoolean("statusTitle", false),
        autoHideSeconds = prefs.getInt("autoHide", 3),
    )

    fun saveShell(s: ShellSettings) = prefs.edit {
        putString("scheme", s.scheme.name).putBoolean("fullscreen", s.fullscreen).putBoolean("statusOnTop", s.statusOnTop)
        .putBoolean("statusClock", s.clock).putBoolean("statusBattery", s.battery).putBoolean("statusPage", s.page)
        .putBoolean("statusChapter", s.chapterProgress).putBoolean("statusBook", s.bookProgress)
        .putBoolean("statusBookPages", s.bookPages).putInt("statusVersion", 2)
        .putBoolean("statusTitle", s.chapterTitle).putInt("autoHide", s.autoHideSeconds) }

    /** Saved action setup; one saved with older defaults is dropped once, so the new defaults apply. */
    fun actionConfigs() =
        if (prefs.getInt("actionsVersion", 1) < ActionConfigs.VERSION) emptyList() else ActionConfigs.fromJson(prefs.getString("actions", null))
    fun saveActions(list: List<ActionConfig>) = prefs.edit { putString("actions", ActionConfigs.toJson(list))
        .putInt("actionsVersion", ActionConfigs.VERSION) }

    /** Apps chosen in "Открыть в приложении…", most recent first. */
    fun recentApps(): List<String> = prefs.getString("recentApps", "")!!.split('\n').filter { it.isNotEmpty() }
    fun useApp(id: String) = prefs.edit { putString("recentApps", (listOf(id) + recentApps().filter { it != id }).take(8).joinToString("\n")) }

    fun known(): Set<String> = prefs.getStringSet("known", emptySet())!!.toSet()
    fun saveKnown(k: Set<String>) = prefs.edit { putStringSet("known", k) }
    /** Position of the single book of versions up to 0.6. */
    fun legacyPosition(): Pair<Int, String> = prefs.getInt("chapter", 0) to (prefs.getString("paragraph", "") ?: "")

    fun library(): List<LibraryBook> = runCatching {
        val a = JSONArray(prefs.getString("library", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            LibraryBook(o.getString("id"), o.getString("title"), o.optString("author"), o.getString("file"),
                o.optInt("chapter"), o.optString("paragraph"), o.optLong("opened"), o.optDouble("progress", 0.0).toFloat())
        }
    }.getOrDefault(emptyList()).sortedByDescending { it.opened }

    fun saveLibrary(books: List<LibraryBook>) = prefs.edit {
        putString("library", JSONArray(books.map { b ->
            JSONObject().put("id", b.id).put("title", b.title).put("author", b.author).put("file", b.file)
                .put("chapter", b.chapter).put("paragraph", b.paragraph).put("opened", b.opened).put("progress", b.progress.toDouble())
        }).toString())
    }

    /** The library with [book] put first (added, or replacing the entry with its id). */
    fun touch(book: LibraryBook): List<LibraryBook> =
        (listOf(book) + library().filter { it.id != book.id }).also { saveLibrary(it) }

    fun savePosition(id: String, chapter: Int, paragraph: String, progress: Float) {
        saveLibrary(library().map { if (it.id == id) it.copy(chapter = chapter, paragraph = paragraph, progress = progress) else it })
    }

    /** Pages of the laid-out chapters of book [id] for layout [key]; another key's counts are dropped. */
    fun pageCounts(id: String, key: String): Map<Int, Int> = runCatching {
        val o = JSONObject(prefs.getString("pages:$id", "{}")!!)
        if (o.optString("key") != key) emptyMap() else o.getJSONObject("counts").let { c -> c.keys().asSequence().associate { it.toInt() to c.getInt(it) } }
    }.getOrDefault(emptyMap())

    fun savePageCounts(id: String, key: String, counts: Map<Int, Int>) = prefs.edit {
        putString("pages:$id", JSONObject().put("key", key).put("counts", JSONObject(counts.mapKeys { it.key.toString() })).toString())
    }

    fun remove(id: String) {
        library().firstOrNull { it.id == id }?.let { File(booksDir, it.file).delete() }
        saveLibrary(library().filter { it.id != id })
        prefs.edit { remove("pages:$id") }
    }
}

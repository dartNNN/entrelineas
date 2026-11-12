package local.bilingual.reader.data
import android.content.Context
import androidx.core.content.edit
import java.io.File
import local.bilingual.reader.actions.*
import local.bilingual.reader.reading.*


/** The reader's saved state in SharedPreferences: settings, shell settings, actions, familiar marks, position, the book file. */
internal class Store(context: Context) {
    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    val bookFile = File(context.filesDir, "book.zip")

    fun loadSettings() = Settings(
        threshold = prefs.getInt("threshold", 10),
        showLiteral = prefs.getBoolean("literal", true),
        showLexical = prefs.getBoolean("lexical", true),
        showNotes = prefs.getBoolean("notes", true),
        // "twoPass" was the boolean before screen repeat existed
        repeat = prefs.getString("repeat", null)?.let { runCatching { Repeat.valueOf(it) }.getOrNull() }
            ?: if (prefs.getBoolean("twoPass", true)) Repeat.PARAGRAPH else Repeat.OFF,
        fontSize = prefs.getFloat("fontSize", 18f),
        referenceInCard = prefs.getBoolean("refCard", true),
        referenceInText = prefs.getBoolean("refText", false),
        showFootnotes = prefs.getBoolean("footnotes", true),
        showAuthorNotes = prefs.getBoolean("authorNotes", true),
        justify = prefs.getBoolean("justify", true),
    )

    fun saveSettings(s: Settings) = prefs.edit {
        putInt("threshold", s.threshold).putBoolean("literal", s.showLiteral).putBoolean("lexical", s.showLexical)
        .putBoolean("notes", s.showNotes).putString("repeat", s.repeat.name).putFloat("fontSize", s.fontSize)
        .putBoolean("refCard", s.referenceInCard).putBoolean("refText", s.referenceInText)
        .putBoolean("footnotes", s.showFootnotes).putBoolean("authorNotes", s.showAuthorNotes)
        .putBoolean("justify", s.justify) }

    fun loadShell() = ShellSettings(
        scheme = prefs.getString("scheme", null)?.let { runCatching { SchemeId.valueOf(it) }.getOrNull() } ?: SchemeId.SYSTEM,
        fullscreen = prefs.getBoolean("fullscreen", false),
        statusOnTop = prefs.getBoolean("statusOnTop", true),
        clock = prefs.getBoolean("statusClock", true),
        battery = prefs.getBoolean("statusBattery", true),
        page = prefs.getBoolean("statusPage", true),
        chapterProgress = prefs.getBoolean("statusChapter", false),
        bookProgress = prefs.getBoolean("statusBook", false),
        chapterTitle = prefs.getBoolean("statusTitle", false),
        autoHideSeconds = prefs.getInt("autoHide", 3),
    )

    fun saveShell(s: ShellSettings) = prefs.edit {
        putString("scheme", s.scheme.name).putBoolean("fullscreen", s.fullscreen).putBoolean("statusOnTop", s.statusOnTop)
        .putBoolean("statusClock", s.clock).putBoolean("statusBattery", s.battery).putBoolean("statusPage", s.page)
        .putBoolean("statusChapter", s.chapterProgress).putBoolean("statusBook", s.bookProgress)
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
    fun position(): Pair<Int, String> = prefs.getInt("chapter", 0) to (prefs.getString("paragraph", "") ?: "")
    fun savePosition(chapter: Int, paragraph: String) = prefs.edit { putInt("chapter", chapter).putString("paragraph", paragraph) }
}

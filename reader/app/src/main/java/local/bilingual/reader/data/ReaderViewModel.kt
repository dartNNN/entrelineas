package local.bilingual.reader.data
import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.bilingual.reader.actions.*
import local.bilingual.reader.format.*
import local.bilingual.reader.reading.*


/**
 * The reader's state, kept across configuration changes (rotation, dark theme): the book and the chapter being read,
 * settings, familiar marks, actions, the open card and selection. The book file is read and chapters are parsed off
 * the main thread; settings and marks are saved as they change (Store).
 */
class ReaderViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var book by mutableStateOf<Book?>(null); private set
    var chapterIndex by mutableIntStateOf(store.position().first); private set
    var chapter by mutableStateOf<Chapter?>(null); private set

    var settings by mutableStateOf(store.loadSettings()); private set
    var shell by mutableStateOf(store.loadShell()); private set
    var known by mutableStateOf(store.known()); private set
    val actions: List<TextAction> = TextActions.available()
    var actionConfigs by mutableStateOf(ActionConfigs.merge(store.actionConfigs(), actions.map { it.id })); private set

    var wordTap by mutableStateOf<WordTap?>(null)
    var selection by mutableStateOf<TextSelection?>(null)
    var layoutMs by mutableLongStateOf(0L)

    init {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { if (store.bookFile.exists()) BookParser.parse(store.bookFile.inputStream()) else null }.getOrNull() }
            show(saved)
            loading = false
        }
    }

    /** Copies the chosen package into the app's storage and opens it at its first chapter. */
    fun open(uri: Uri) {
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input -> store.bookFile.outputStream().use { input.copyTo(it) } }
                    BookParser.parse(store.bookFile.inputStream())
                }
            }
            opened.onSuccess {
                chapterIndex = 0
                store.savePosition(0, "")
                selection = null
                error = null
                show(it)
            }.onFailure { error = it.message }
        }
    }

    fun selectChapter(index: Int) {
        chapterIndex = index
        store.savePosition(index, "")
        selection = null
        loadChapter()
    }

    private fun show(opened: Book?) {
        book = opened
        chapter = null
        loadChapter()
    }

    /** Parses the chapter being read off the main thread (chapters are parsed on first use). */
    private fun loadChapter() {
        val current = book ?: return
        val index = chapterIndex.coerceIn(0, current.chapters.lastIndex)
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.Default) { runCatching { current.chapters[index] } }
            if (book === current && chapterIndex.coerceIn(0, current.chapters.lastIndex) == index) {
                parsed.onSuccess { chapter = it }.onFailure { error = it.message; book = null }
            }
        }
    }

    fun savePosition(paragraph: String) = store.savePosition(chapterIndex, paragraph)

    /** Apps chosen in "Открыть в приложении…", most recent first, and remembering a choice. */
    fun recentApps(): List<String> = store.recentApps()
    fun useApp(id: String) = store.useApp(id)
    fun startParagraph(): String = store.position().second

    fun updateSettings(s: Settings) { settings = s; store.saveSettings(s) }
    fun updateShell(s: ShellSettings) { shell = s; store.saveShell(s) }
    fun updateActions(list: List<ActionConfig>) { actionConfigs = list; store.saveActions(list) }
    fun toggleKnown(key: String) {
        known = if (key in known) known - key else known + key
        store.saveKnown(known)
    }

    override fun onCleared() = releaseSpeech()
}

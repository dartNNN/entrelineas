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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.bilingual.reader.actions.*
import local.bilingual.reader.format.*
import local.bilingual.reader.reading.*


/** Where a chapter opens: at a saved paragraph, at its start, or on its last page (turning back from the next one). */
const val CHAPTER_END = "￿"

/**
 * The book last opened in this process. The system may destroy the activity (and its view model) to free memory while
 * the reader is in the background; coming back then takes the book from here instead of reading the file again.
 */
private object OpenBook {
    var file: File? = null
    var modified = 0L
    var book: Book? = null

    fun get(f: File): Book {
        book?.let { if (file == f && modified == f.lastModified()) return it }
        return BookParser.open(f).also { book = it; file = f; modified = f.lastModified() }
    }
}

/**
 * The reader's state, kept across configuration changes (rotation, dark theme): the library and the book being read,
 * the chapter and where it opens, settings, familiar marks, actions, the open card and selection. Book files are read
 * and chapters parsed off the main thread; settings, marks and positions are saved as they change (Store).
 */
class ReaderViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var library by mutableStateOf(store.library()); private set
    var book by mutableStateOf<Book?>(null); private set
    var chapterIndex by mutableIntStateOf(0); private set
    var chapter by mutableStateOf<Chapter?>(null); private set
    /** The paragraph the chapter being shown opens at ("" its start, [CHAPTER_END] its last page). */
    var startParagraph by mutableStateOf(""); private set

    var settings by mutableStateOf(store.loadSettings()); private set
    var shell by mutableStateOf(store.loadShell()); private set
    var known by mutableStateOf(store.known()); private set
    val actions: List<TextAction> = TextActions.available()
    var actionConfigs by mutableStateOf(ActionConfigs.merge(store.actionConfigs(), actions.map { it.id })); private set

    /** Pages of the laid-out chapters of the book for the current layout ([countKey]), for the book's page count. */
    var pageCounts by mutableStateOf<Map<Int, Int>>(emptyMap()); private set
    private var countKey = ""

    var wordTap by mutableStateOf<WordTap?>(null)
    var selection by mutableStateOf<TextSelection?>(null)
    var layoutMs by mutableLongStateOf(0L)

    /** The library restored on start (and the book of 0.6 moved into it); opening a book waits for it. */
    private val restored = viewModelScope.launch {
        withContext(Dispatchers.IO) { migrate() }
        library = store.library()
        library.firstOrNull()?.let { show(it) }
        loading = false
    }

    /** Versions up to 0.6 kept one book in files/book.zip and its position in two keys: it becomes the library's first book. */
    private fun migrate() {
        if (!store.legacyBookFile.exists() || store.library().isNotEmpty()) return
        // On failure the old file stays where it was, so nothing is lost and the next start tries again.
        runCatching {
            val (chapter, paragraph) = store.legacyPosition()
            add(store.legacyBookFile, keep = false).let { store.touch(it.copy(chapter = chapter, paragraph = paragraph)) }
        }
    }

    /** Moves (or copies) a package into the library under its book id; returns its entry, position kept if it was there. */
    private fun add(source: File, keep: Boolean): LibraryBook {
        val opened = BookParser.open(source)
        store.booksDir.mkdirs()
        val name = opened.id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".bbook.zip"
        val target = File(store.booksDir, name)
        if (keep) source.copyTo(target, overwrite = true) else if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true); source.delete()
        }
        val old = store.library().firstOrNull { it.id == opened.id }
        return (old ?: LibraryBook(opened.id, opened.title, opened.author, name))
            .copy(title = opened.title, author = opened.author, file = name, opened = System.currentTimeMillis())
    }

    /** Copies the chosen package into the library and opens it: where it was left if it is there already, else at the start. */
    fun open(uri: Uri) = open { getApplication<Application>().contentResolver.openInputStream(uri)!! }

    /** The same for a file on disk (the test and adb hook EXTRA_ADD_BOOK). */
    fun open(file: File) = open { file.inputStream() }

    private fun open(source: () -> java.io.InputStream) {
        viewModelScope.launch {
            restored.join()
            val added = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = File(getApplication<Application>().cacheDir, "opening.bbook.zip")
                    source().use { input -> temp.outputStream().use { input.copyTo(it) } }
                    add(temp, keep = false)
                }
            }
            added.onSuccess { entry ->
                library = store.touch(entry)
                error = null
                show(entry)
            }.onFailure { error = it.message }
        }
    }

    /** Switches to another book of the library, where it was left. */
    fun openFromLibrary(id: String) {
        val entry = library.firstOrNull { it.id == id } ?: return
        if (entry.id == book?.id) return
        library = store.touch(entry.copy(opened = System.currentTimeMillis()))
        show(entry)
    }

    /** Removes a book (not the one being read) from the library and deletes its file. */
    fun removeFromLibrary(id: String) {
        if (id == book?.id) return
        store.remove(id)
        library = store.library()
    }

    private fun show(entry: LibraryBook) {
        selection = null
        wordTap = null
        chapter = null
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) { runCatching { OpenBook.get(File(store.booksDir, entry.file)) } }
            opened.onSuccess {
                book = it
                countKey = ""
                pageCounts = emptyMap()
                chapterIndex = entry.chapter.coerceIn(0, it.chapters.lastIndex)
                startParagraph = entry.paragraph
                loadChapter()
            }.onFailure { error = it.message; book = null }
        }
    }

    /** Opens chapter [index] at its start, or on its last page when [atEnd] (turning back from the next chapter). */
    fun selectChapter(index: Int, atEnd: Boolean = false) {
        val current = book ?: return
        if (index !in current.chapters.indices) return
        chapterIndex = index
        startParagraph = if (atEnd) CHAPTER_END else ""
        selection = null
        store.savePosition(current.id, index, "", current.progress(index, 0, 0))
        loadChapter()
    }

    /** Parses the chapter being read off the main thread, then the next one in the background for a quick turn. */
    private fun loadChapter() {
        val current = book ?: return
        val index = chapterIndex.coerceIn(0, current.chapters.lastIndex)
        chapter = null
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.Default) { runCatching { current.chapters[index] } }
            if (book === current && chapterIndex == index) {
                parsed.onSuccess { chapter = it }.onFailure { error = it.message; book = null }
                if (index + 1 < current.chapters.size) withContext(Dispatchers.Default) { runCatching { current.chapters[index + 1] } }
            }
        }
    }

    /** The page now on screen: its first paragraph and the share of the book read. */
    fun savePosition(paragraph: String, progress: Float) {
        val id = book?.id ?: return
        store.savePosition(id, chapterIndex, paragraph, progress)
        library = library.map { if (it.id == id) it.copy(chapter = chapterIndex, paragraph = paragraph, progress = progress) else it }
    }

    /**
     * A chapter's layout started ([pages] null) or is complete: switching to another layout key brings back what was
     * counted for it before; a complete layout records the chapter's pages.
     */
    fun counted(key: String, chapter: Int, pages: Int?) {
        val id = book?.id ?: return
        if (key != countKey) { countKey = key; pageCounts = store.pageCounts(id, key) }
        if (pages != null && pageCounts[chapter] != pages) {
            pageCounts = pageCounts + (chapter to pages)
            store.savePageCounts(id, key, pageCounts)
        }
    }

    /** Apps chosen in "Открыть в приложении…", most recent first, and remembering a choice. */
    fun recentApps(): List<String> = store.recentApps()
    fun useApp(id: String) = store.useApp(id)

    fun updateSettings(s: Settings) { settings = s; store.saveSettings(s) }
    fun updateShell(s: ShellSettings) { shell = s; store.saveShell(s) }
    fun updateActions(list: List<ActionConfig>) { actionConfigs = list; store.saveActions(list) }
    fun toggleKnown(key: String) {
        known = if (key in known) known - key else known + key
        store.saveKnown(known)
    }

    override fun onCleared() = releaseSpeech()
}

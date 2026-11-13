package local.bilingual.reader.ui
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import local.bilingual.reader.data.LibraryBook
import local.bilingual.reader.format.*
import local.bilingual.reader.reading.*



fun Long.color() = Color(this.toInt())

/** Material 3 roles from a reading scheme, so bars, menus and switches take the page's family and the petrol accent. */
fun materialColors(c: SchemeColors): ColorScheme {
    val page = c.page.color(); val text = c.text.color(); val bar = c.bar.color(); val accent = c.accent.color()
    val muted = c.literal.color(); val selection = c.selection.color().copy(alpha = 0.35f)
    // Outlined buttons and dividers: halfway between the bar and the muted text, visible on both page and bar.
    val line = lerp(bar, muted, 0.5f)
    return if (c.dark) darkColorScheme(
        primary = accent, onPrimary = page, primaryContainer = selection, onPrimaryContainer = text,
        secondary = c.note.color(), onSecondary = page, secondaryContainer = selection, onSecondaryContainer = text,
        tertiary = c.gloss.color(), background = page, onBackground = text, surface = page, onSurface = text,
        surfaceVariant = bar, onSurfaceVariant = muted, outline = muted, outlineVariant = line,
        surfaceContainerLowest = page, surfaceContainerLow = bar, surfaceContainer = bar, surfaceContainerHigh = bar,
        surfaceContainerHighest = bar,
    ) else lightColorScheme(
        primary = accent, onPrimary = page, primaryContainer = selection, onPrimaryContainer = text,
        secondary = c.note.color(), onSecondary = page, secondaryContainer = selection, onSecondaryContainer = text,
        tertiary = c.gloss.color(), background = page, onBackground = text, surface = page, onSurface = text,
        surfaceVariant = bar, onSurfaceVariant = muted, outline = muted, outlineVariant = line,
        surfaceContainerLowest = page, surfaceContainerLow = bar, surfaceContainer = bar, surfaceContainerHigh = bar,
        surfaceContainerHighest = bar,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(title: String, onMenu: () -> Unit, onSettings: () -> Unit) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
        navigationIcon = { IconButton(onMenu, Modifier.testTag("menu")) { Icon(Icons.Filled.Menu, "Меню") } },
        actions = { IconButton(onSettings, Modifier.testTag("settings")) { Icon(Icons.Filled.Settings, "Настройки") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

/**
 * Navigation drawer as one lazy list: the book being read, opening a book, the other books of the library (where
 * they were left, a long press removes one), then the chapters. A long chapter list is folded (header with the current
 * chapter) so the books stay at hand; unfolded, it starts scrolled to the current chapter.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DrawerContent(book: Book?, chapterIndex: Int, library: List<LibraryBook>, onChapter: (Int) -> Unit, onOpen: () -> Unit,
                  onBook: (String) -> Unit, onRemoveBook: (String) -> Unit) {
    val titles = book?.chapterTitles ?: emptyList()
    var unfolded by remember(book?.id) { mutableStateOf(titles.size <= FOLD_CHAPTERS) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val others = library.filter { it.id != book?.id }
    val firstChapterItem = 2 + (if (book != null) 1 else 0) + others.size + (if (others.isNotEmpty()) 1 else 0)
    androidx.compose.runtime.LaunchedEffect(unfolded, book?.id) {
        if (unfolded && titles.size > FOLD_CHAPTERS) list.scrollToItem((firstChapterItem + chapterIndex - 2).coerceAtLeast(0))
    }
    androidx.compose.foundation.lazy.LazyColumn(state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
        if (book != null) item {
            Column {
                Text(book.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 28.dp))
                Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
            }
        }
        item {
            NavigationDrawerItem(label = { Text("Открыть книгу…") }, selected = false, onClick = onOpen,
                modifier = Modifier.padding(horizontal = 12.dp).testTag("openBook"))
        }
        if (others.isNotEmpty()) {
            item { DrawerHeader("Другие книги") }
            others.forEach { b ->
                item(key = "book:${b.id}") {
                    var confirm by remember { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                        .combinedClickable(onLongClick = { confirm = true }) { onBook(b.id) }
                        .padding(horizontal = 16.dp, vertical = 8.dp).testTag("book:${b.id}")) {
                        Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(b.author, "${(100 * b.progress).toInt()}%").filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (confirm) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Убрать из списка?", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            androidx.compose.material3.TextButton({ confirm = false; onRemoveBook(b.id) }) { Text("Убрать") }
                            androidx.compose.material3.TextButton({ confirm = false }) { Text("Нет") }
                        }
                    }
                }
            }
        }
        if (book != null) {
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Row(Modifier.fillMaxWidth().clickable(enabled = titles.size > FOLD_CHAPTERS) { unfolded = !unfolded }
                    .padding(start = 28.dp, end = 16.dp, top = 4.dp, bottom = 4.dp).testTag("chapters"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Главы (${titles.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        if (!unfolded) Text(titles.getOrNull(chapterIndex) ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (titles.size > FOLD_CHAPTERS) Icon(if (unfolded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        if (unfolded) "Свернуть" else "Развернуть")
                }
            }
            if (unfolded) titles.forEachIndexed { idx, title ->
                item(key = "chapter:$idx") {
                    NavigationDrawerItem(label = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        selected = idx == chapterIndex, onClick = { onChapter(idx) },
                        modifier = Modifier.padding(horizontal = 12.dp).testTag("chapter$idx"))
                }
            }
        }
    }
}

/** Chapter lists longer than this start folded in the drawer. */
private const val FOLD_CHAPTERS = 12

@Composable
private fun DrawerHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 4.dp))
}

/**
 * Progress shown by the status line and the bottom counter. bookShare: the share of the book read (0..1), chapters
 * weighted by their length (Book.progress); null until the chapter's pages are counted.
 */
data class Progress(val page: Int, val pages: Int?, val chapterIndex: Int, val chapters: Int, val chapterTitle: String,
                    val bookShare: Float? = null, val bookPage: Int? = null, val bookPages: Int? = null, val estimated: Boolean = false) {
    val chapterPercent get() = pages?.let { 100 * (page + 1) / it }
    val bookPercent get() = bookShare?.let { (100 * it).toInt() }
    /**
     * The progress items switched on in [s]: "глава 12 / 40 (30%)" and the book's "123 / ~860 (14%)" (no label: it is
     * the main one), each with its pages, its percent or both ("~" while some chapters of the book are estimated).
     */
    fun items(s: ShellSettings): List<String> = listOfNotNull(
        item("глава", if (s.page) "${page + 1} / ${pages ?: "…"}" else null, if (s.chapterProgress) chapterPercent else null),
        item(null, if (s.bookPages && bookPage != null && bookPages != null) "$bookPage / ${if (estimated) "~" else ""}$bookPages" else null,
            if (s.bookProgress) bookPercent else null),
    )

    private fun item(name: String?, count: String?, percent: Int?): String? {
        val text = when {
            count != null && percent != null -> "$count ($percent%)"
            count != null -> count
            percent != null -> "$percent%"
            else -> return null
        }
        return if (name != null) "$name $text" else text
    }
}

/** The full-screen status line: only the items switched on, in one thin row. */
@Composable
fun StatusLine(s: ShellSettings, progress: Progress, color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000 - value % 60_000)
        }
    }
    val battery = batteryState(s.battery)
    val items = buildList {
        if (s.chapterTitle) add(progress.chapterTitle)
        addAll(progress.items(s))
        if (s.battery) battery?.let { (level, charging) -> add((if (charging) "⚡" else "") + "$level%") }
        if (s.clock) add(android.text.format.DateFormat.getTimeFormat(context).format(now))
    }
    if (items.isEmpty()) return
    Row(modifier.fillMaxWidth().testTag("statusLine"), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        val title = if (s.chapterTitle) items.first() else null
        title?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp)) }
        Text(items.drop(if (title != null) 1 else 0).joinToString("   "), style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** Battery level in percent and whether it charges, from the sticky ACTION_BATTERY_CHANGED; null while off. */
@Composable
private fun batteryState(enabled: Boolean): Pair<Int, Boolean>? {
    val context = LocalContext.current
    var state by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        fun read(i: Intent?) {
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            if (level >= 0 && scale > 0) state = (100 * level / scale) to
                (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = read(i)
        }
        // The battery broadcast comes from the system (a protected broadcast), so the receiver must be exported to
        // get it; the sticky intent returned here is the current state.
        read(ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

/** A small sample of a scheme: page colour with text, gloss and literal in their colours. */
@Composable
fun SchemeSwatch(c: SchemeColors, selected: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(width = 64.dp, height = 44.dp).background(c.page.color(), RoundedCornerShape(8.dp))
        .border(if (selected) 2.dp else 1.dp, if (selected) c.accent.color() else c.bar.color(), RoundedCornerShape(8.dp))
        .padding(6.dp)) {
        Column {
            Text("Aa", color = c.text.color(), style = MaterialTheme.typography.labelLarge)
            Row {
                Box(Modifier.size(16.dp, 3.dp).background(c.gloss.color()))
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(16.dp, 3.dp).background(c.literal.color()))
            }
        }
    }
}

/** Scheme choice as swatches; used in the quick panel and on the settings screen. */
@Composable
fun SchemePicker(current: SchemeId, systemDark: Boolean, onPick: (SchemeId) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SchemeId.entries.forEach { id ->
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).selectable(current == id) { onPick(id) }.testTag("scheme:${id.name}")) {
                SchemeSwatch(Schemes.resolve(id, systemDark), current == id)
                Text(id.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Bottom sheets live in their own dialog window, which gets the system scrim under the navigation buttons (a white
 * band under a dark sheet). Called inside a sheet, this drops the scrim and matches the bar icons to the scheme. A
 * dialog window shows the system bars on its own, so in full screen ([hideBars]) it hides them as the page does.
 */
@Composable
fun SheetWindow(dark: Boolean, hideBars: Boolean = false) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@SideEffect
        window.isNavigationBarContrastEnforced = false
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightNavigationBars = !dark
        if (hideBars) {
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
}

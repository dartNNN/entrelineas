package local.bilingual.reader.ui
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

/** Navigation drawer: the book, its chapters (the current one selected), opening another book. */
@Composable
fun DrawerContent(book: Book?, chapterIndex: Int, onChapter: (Int) -> Unit, onOpen: () -> Unit) {
    Column(Modifier.padding(vertical = 12.dp).verticalScroll(rememberScrollState())) {
        if (book != null) {
            Text(book.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 28.dp))
            Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            book.chapterTitles.forEachIndexed { idx, title ->
                NavigationDrawerItem(label = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    selected = idx == chapterIndex, onClick = { onChapter(idx) },
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("chapter$idx"))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
        NavigationDrawerItem(label = { Text("Открыть книгу") }, selected = false, onClick = onOpen,
            modifier = Modifier.padding(horizontal = 12.dp).testTag("openBook"))
    }
}

/** Progress shown by the status line and the bottom counter. */
data class Progress(val page: Int, val pages: Int?, val chapterIndex: Int, val chapters: Int, val chapterTitle: String) {
    val chapterPercent get() = pages?.let { 100 * (page + 1) / it }
    val bookPercent get() = pages?.let { (100 * (chapterIndex + (page + 1).toFloat() / it) / chapters).toInt() }
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
        if (s.page) add("${progress.page + 1} / ${progress.pages ?: "…"}")
        if (s.chapterProgress) progress.chapterPercent?.let { add("глава $it%") }
        if (s.bookProgress) progress.bookPercent?.let { add("книга $it%") }
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
 * band under a dark sheet). Called inside a sheet, this drops the scrim and matches the bar icons to the scheme.
 */
@Composable
fun SheetWindow(dark: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@SideEffect
        window.isNavigationBarContrastEnforced = false
        androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
    }
}

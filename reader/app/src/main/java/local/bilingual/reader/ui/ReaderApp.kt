package local.bilingual.reader.ui
import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.bilingual.reader.R
import local.bilingual.reader.actions.*
import local.bilingual.reader.data.*
import local.bilingual.reader.reading.*


/**
 * The reader's screen: the page with its selection panel, the top bar, the drawer, the sheets (card, quick settings,
 * apps) and the full settings. State that must outlive a rotation lives in [ReaderViewModel]; what is only on screen
 * (open panels, the shell) is kept here, panels across rotation through rememberSaveable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderApp(activity: ComponentActivity, model: ReaderViewModel) {
    val context: Context = activity
    val book = model.book
    val chapter = model.chapter
    val settings = model.settings
    val shell = model.shell
    var quickSettings by rememberSaveable { mutableStateOf(false) }
    var fullSettings by rememberSaveable { mutableStateOf(false) }
    var appText by rememberSaveable { mutableStateOf<String?>(null) }
    // The shell (top bar, and the system bars in full screen) is shown on start and on a tap off any word.
    var chrome by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val systemDark = isSystemInDarkTheme()
    val colors = Schemes.resolve(shell.scheme, systemDark)

    val actions = model.actionConfigs.mapNotNull { cfg -> model.actions.firstOrNull { it.id == cfg.id }?.let { it to cfg } }
    var onPermission by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermission?.invoke(granted)
        onPermission = null
    }
    val permissionLauncher by rememberUpdatedState<(String) -> Unit>({ permission.launch(it) })
    val host = remember(model) {
        object : ActionHost {
            override val context = context
            override val scope = scope
            override fun isKnown(key: String) = key in model.known
            override fun toggleKnown(key: String) = model.toggleKnown(key)
            override fun requestPermission(permission: String, then: (Boolean) -> Unit) {
                onPermission = then
                permissionLauncher(permission)
            }
            override fun chooseApp(text: String) {
                appText = text
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) model.open(uri) }

    val busy = drawer.isOpen || quickSettings || fullSettings || model.selection != null || model.wordTap != null
    LaunchedEffect(chrome, touched, busy, book) {
        if (chrome && book != null && !busy) {
            delay(shell.autoHideSeconds * 1000L)
            chrome = false
        }
    }
    val view = LocalView.current
    val showSystemBars = !shell.fullscreen || chrome || busy
    SideEffect {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showSystemBars) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.isAppearanceLightStatusBars = !colors.dark
        controller.isAppearanceLightNavigationBars = !colors.dark
        // No system scrim under the navigation buttons: the page colour runs to the screen edge.
        activity.window.isNavigationBarContrastEnforced = false
    }

    MaterialTheme(colorScheme = materialColors(colors)) {
        ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
            ModalDrawerSheet {
                DrawerContent(book, model.chapterIndex,
                    onChapter = { model.selectChapter(it); scope.launch { drawer.close() } },
                    onOpen = { scope.launch { drawer.close() }; open.launch(arrayOf("*/*")) })
            }
        }) {
            Box(Modifier.fillMaxSize().background(colors.page.color())) {
                // In full screen the text takes the whole screen (only the cutout is avoided), so showing the system
                // bars over it does not move or re-paginate the page.
                val area = if (shell.fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout) else Modifier.safeDrawingPadding()
                if (book != null && chapter != null) {
                    val resolver = remember(book, chapter) { SelectionResolver(book, chapter) }
                    val highlight = model.selection?.let { sel ->
                        Highlight(resolver.tokens(sel), resolver.paragraphs(sel), sel.layer, colors.selection.color())
                    }
                    Pages(chapter, settings, shell, colors, model.known, model.startParagraph(), highlight,
                        progress = { page, pages -> Progress(page, pages, model.chapterIndex, book.chapters.size, chapter.title) },
                        onWord = { model.wordTap = it; chrome = false },
                        onLongPress = { model.selection = it; chrome = false },
                        onBlank = { if (model.selection != null) model.selection = null else { chrome = !chrome; touched++ } },
                        onPage = { model.savePosition(it) },
                        onTurn = { chrome = false },
                        onLayout = { model.layoutMs = it },
                        onReveal = { chrome = true; touched++ },
                        modifier = area)
                    model.selection?.let { sel ->
                        SelectionPanel(sel, resolver.content(sel), resolver.units(sel), actions, host,
                            onChange = { model.selection = it },
                            onClose = { model.selection = null },
                            modifier = Modifier.align(Alignment.BottomCenter))
                    }
                } else if (model.loading || (book != null && model.error == null)) {
                    Text("Загрузка…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
                } else {
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Откройте файл книги (.bbook.zip)", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { open.launch(arrayOf("*/*")) }, Modifier.testTag("open")) { Text("Открыть") }
                        model.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
                    }
                }
                AnimatedVisibility(visible = chrome || book == null, modifier = Modifier.align(Alignment.TopCenter),
                    enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
                    ReaderTopBar(chapter?.title ?: stringResource(R.string.app_name),
                        onMenu = { scope.launch { drawer.open() } },
                        onSettings = { quickSettings = true })
                }
                if (fullSettings) {
                    FullSettingsScreen(settings, shell, systemDark, colors, book, chapter, model.layoutMs, actions, host,
                        onSettings = model::updateSettings,
                        onShell = model::updateShell,
                        onActions = model::updateActions,
                        onBack = { fullSettings = false })
                }
            }
        }
        BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
        BackHandler(enabled = model.selection != null && !fullSettings && !drawer.isOpen) { model.selection = null }
        BackHandler(enabled = fullSettings) { fullSettings = false }

        model.wordTap?.let { tap ->
            ModalBottomSheet(onDismissRequest = { model.wordTap = null }) {
                SheetWindow(colors.dark)
                WordCard(tap, model.known, settings, chapter?.entries ?: emptyMap(), onToggleKnown = model::toggleKnown)
            }
        }
        appText?.let { text ->
            ModalBottomSheet(onDismissRequest = { appText = null }) {
                SheetWindow(colors.dark)
                val targets = remember { AppTargets.order(AppTargets.list(context), model.recentApps()) }
                AppChooser(targets) { target ->
                    appText = null
                    model.useApp(target.id)
                    AppTargets.send(context, target, text)
                }
            }
        }
        if (quickSettings) {
            ModalBottomSheet(onDismissRequest = { quickSettings = false }) {
                SheetWindow(colors.dark)
                QuickSettingsPanel(settings, shell, systemDark, colors, book, chapter, model.layoutMs,
                    onChange = model::updateSettings,
                    onShell = model::updateShell,
                    onAll = { quickSettings = false; fullSettings = true })
            }
        }
    }
}

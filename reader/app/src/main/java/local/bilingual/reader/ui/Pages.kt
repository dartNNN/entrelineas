package local.bilingual.reader.ui
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.bilingual.reader.format.*
import local.bilingual.reader.reading.*



/** What the page highlights for the active selection; drawing it never re-paginates. */
internal data class Highlight(val tokens: Set<Pair<String, Int>>, val paragraphs: Set<String>, val layer: Layer, val color: Color)

@OptIn(ExperimentalMaterial3Api::class)

/** Pages of one layout: first screens synchronously at the anchor, the rest of the chapter in the background. */
private class PageFlow(val anchorParagraph: String) {
    val pages = mutableStateListOf<Page>()
    var complete by mutableStateOf(false)
    val pager = PagerState(currentPage = 0) { pages.size }
}

@Composable
internal fun Pages(chapter: Chapter, settings: Settings, shell: ShellSettings, colors: SchemeColors, known: Set<String>,
                  startParagraph: String, highlight: Highlight?, progress: (Int, Int?) -> Progress,
                  onWord: (WordTap) -> Unit, onLongPress: (TextSelection) -> Unit, onBlank: () -> Unit,
                  onPage: (String) -> Unit, onTurn: () -> Unit, onLayout: (Long) -> Unit, onReveal: () -> Unit,
                  modifier: Modifier = Modifier) {
    val palette = remember(colors) { Palette(colors.literal.color(), colors.gloss.color(), colors.note.color()) }
    val textColor = colors.text.color()
    val base = remember(textColor) { TextStyle(fontFamily = FontFamily.Serif, color = textColor, lineHeight = 1.4.em) }
    val measurer = rememberTextMeasurer(cacheSize = 0)
    // The background measurer must not share the view's font resolver: its snapshot state is observed on the main thread.
    val appContext = LocalContext.current.applicationContext
    val backgroundResolver = remember { createFontFamilyResolver(appContext) }
    val layoutDirection = LocalLayoutDirection.current
    val sentences = remember(chapter) { chapter.paragraphs.flatMap { it.sentences }.associateBy { it.id } }
    val firstSentence = remember(chapter) { chapter.paragraphs.associate { p -> p.id to p.sentences.firstOrNull()?.id } }
    // Reading position survives re-layouts: a new layout starts at the paragraph currently on screen.
    var currentParagraph by remember(chapter) { mutableStateOf(startParagraph) }
    // The status line and the counter take their natural height (they follow the system font scale); pages are laid
    // out in whatever height is left, measured by BoxWithConstraints, so no part of the screen is sized by hand.
    var shown by remember(chapter) { mutableStateOf<PageFlow?>(null) }
    val statusColor = colors.literal.color()
    @Composable
    fun status(padding: Modifier) {
        val flow = shown
        StatusLine(shell, progress(flow?.pager?.currentPage ?: 0, flow?.takeIf { it.complete }?.pages?.size), statusColor,
            padding.pointerInput(Unit) { detectTapGestures { onBlank() } })
    }
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        if (shell.fullscreen && shell.statusOnTop) status(Modifier.padding(bottom = 8.dp))
        // A vertical swipe anywhere on the page calls up the shell (horizontal swipes turn pages).
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
            var travel = 0f
            detectVerticalDragGestures(onDragStart = { travel = 0f }, onDragEnd = { if (abs(travel) > 48.dp.toPx()) onReveal() }) { change, dy ->
                travel += dy
                change.consume()
            }
        }) {
            val density = LocalDensity.current
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val gap = with(density) { 10.dp.roundToPx() }
            val blocks = remember(chapter, settings, known) { buildBlocks(chapter, settings, known) }
            // Font size does not change the blocks, only their measurement, so settings must be part of the key.
            val flow = remember(blocks, settings, width, height) { PageFlow(currentParagraph) }
            SideEffect { shown = flow }
            LaunchedEffect(flow) {
                withContext(Dispatchers.Main.immediate) {
                val started = SystemClock.elapsedRealtime()
                val anchor = anchorBlock(blocks, flow.anchorParagraph)
                val forward = Paginator(blocks, anchor, blocks.size, settings, base, palette, width, height, gap)
                repeat(FIRST_SCREENS) { forward.next(measurer)?.let { flow.pages += withRepeats(listOf(it), settings, sentences) } }
                onLayout(SystemClock.elapsedRealtime() - started)
                val background = TextMeasurer(backgroundResolver, Density(density.density, density.fontScale), layoutDirection, cacheSize = 0)
                while (true) {
                    val batch = withContext(Dispatchers.Default) {
                        withRepeats(generateSequence { forward.next(background) }.take(BATCH).toList(), settings, sentences)
                    }
                    if (batch.isEmpty()) break
                    withContext(Dispatchers.Main.immediate) { flow.pages += batch }
                }
                if (anchor > 0) {
                    val before = withContext(Dispatchers.Default) {
                        withRepeats(paginate(blocks, background, settings, base, palette, width, height, gap, start = 0, end = anchor),
                            settings, sentences)
                    }
                    withContext(Dispatchers.Main.immediate) {
                        val page = flow.pager.currentPage
                        flow.pages.addAll(0, before)
                        flow.pager.scrollToPage(page + before.size)
                    }
                }
                // UI state is only ever touched on the main thread; layout work above runs on Dispatchers.Default.
                withContext(Dispatchers.Main.immediate) { flow.complete = true }
                }
            }
            LaunchedEffect(flow) {
                var first = true
                snapshotFlow { flow.pager.currentPage }.collect { index ->
                    flow.pages.getOrNull(index)?.let { currentParagraph = it.firstParagraph; onPage(it.firstParagraph) }
                    if (!first) onTurn()
                    first = false
                }
            }
            HorizontalPager(flow.pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { index ->
                val page = flow.pages[index]
                Column(Modifier.fillMaxSize().testTag("page$index").semantics { stateDescription = page.firstParagraph },
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    page.pieces.forEachIndexed { n, piece ->
                        PieceText(piece, settings, base, palette, sentences, highlight,
                            onWord = onWord,
                            onLongPress = onLongPress,
                            onLongPressBlock = { layer -> firstSentence[piece.paragraphId]?.let { sid ->
                                onLongPress(TextSelection(sid, 0, SelectionUnit.PARAGRAPH, layer = layer)) } },
                            onBlank = onBlank,
                            modifier = Modifier.testTag("page$index-piece$n"))
                    }
                    // The rest of the page: a tap here shows or hides the shell.
                    Spacer(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) { detectTapGestures { onBlank() } })
                }
            }
        }
        if (shell.fullscreen) {
            if (!shell.statusOnTop) status(Modifier.padding(top = 8.dp))
        } else {
            // Always composed, so its height is known before the first layout and the page area never changes under it.
            val flow = shown
            val total = if (flow != null && flow.complete) flow.pages.size.toString() else "…"
            Text("${(flow?.pager?.currentPage ?: 0) + 1} / $total", style = MaterialTheme.typography.bodySmall, color = statusColor,
                // The counter line is also a dependable place to call up the shell on a page full of words.
                modifier = Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { onBlank() } }.padding(top = 8.dp)
                    .testTag("pageCounter"))
        }
    }
}

private const val FIRST_SCREENS = 3
private const val BATCH = 20

private val Double.em get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)

/**
 * The page text with the selection painted over it: background spans only, so the layout stays the same. What is
 * painted follows the layer: the original words without their glosses, the glosses, or the literal translations;
 * notes and the published translation paint their blocks (and the original words, to show what they belong to).
 */
internal fun highlighted(piece: Block, text: AnnotatedString, h: Highlight?): AnnotatedString {
    if (h == null) return text
    val style = SpanStyle(background = h.color)
    val builder = AnnotatedString.Builder(text)
    val whole = piece.paragraphId in h.paragraphs &&
        ((h.layer == Layer.NOTE && piece.kind == BlockKind.NOTES) || (h.layer == Layer.REFERENCE && piece.kind == BlockKind.REFERENCE))
    if (whole) {
        builder.addStyle(style, 0, text.length)
        return builder.toAnnotatedString()
    }
    if (piece.kind == BlockKind.NOTES || piece.kind == BlockKind.REFERENCE) return text
    fun parse(item: String) = item.split(":")
    val glosses = text.getStringAnnotations(GLOSS_TAG, 0, text.length)
    val ranges: List<IntRange> = when (h.layer) {
        Layer.GLOSS -> glosses.filter { a -> parse(a.item).let { (it[0] to it[1].toInt()) in h.tokens } }.map { it.start until it.end }
        Layer.LITERAL -> text.getStringAnnotations(LITERAL_TAG, 0, text.length).filter { a ->
            val (sid, first, last) = parse(a.item)
            (first.toInt()..last.toInt()).any { (sid to it) in h.tokens }
        }.map { it.start until it.end }
        else -> text.getStringAnnotations(TOKEN_TAG, 0, text.length)
            .filter { a -> glosses.none { g -> a.start >= g.start && a.start < g.end } }
            .filter { a -> parse(a.item).let { (it[0] to it[1].toInt()) in h.tokens } }
            .map { it.start until it.end }
    }
    // Neighbours separated only by spaces join into one band, so a selected sentence is not striped.
    val merged = ArrayList<IntRange>()
    ranges.sortedBy { it.first }.forEach { r ->
        val last = merged.lastOrNull()
        if (last != null && text.text.substring(last.last + 1, maxOf(last.last + 1, r.first)).isBlank()) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, r.last)
        } else merged += r
    }
    merged.forEach { builder.addStyle(style, it.first, it.last + 1) }
    return builder.toAnnotatedString()
}

@Composable
private fun PieceText(piece: Block, settings: Settings, base: TextStyle, palette: Palette, sentences: Map<String, Sentence>,
                      highlight: Highlight?, onWord: (WordTap) -> Unit, onLongPress: (TextSelection) -> Unit,
                      onLongPressBlock: (Layer) -> Unit, onBlank: () -> Unit, modifier: Modifier = Modifier) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val styled = remember(piece, palette) { annotated(piece, palette) }
    val text = remember(styled, highlight) { highlighted(piece, styled, highlight) }
    // Drawn ragged first; once laid out, justified by widening the spaces (same line breaks, see [justify]).
    val density = LocalDensity.current
    var justified by remember(text, settings.justify) { mutableStateOf<AnnotatedString?>(null) }
    fun tokenAt(pos: androidx.compose.ui.geometry.Offset): Pair<String, Int>? {
        val l = layout ?: return null
        val offset = l.getOffsetForPosition(pos)
        val tag = styled.getStringAnnotations(TOKEN_TAG, offset, offset).firstOrNull() ?: return null
        return tag.item.split(":").let { it[0] to it[1].toInt() }
    }
    Text(
        justified ?: text,
        style = styleFor(piece.kind, settings, base, palette).let { if (piece.kind == BlockKind.TITLE) it.copy(fontWeight = FontWeight.SemiBold) else it },
        onTextLayout = { l ->
            layout = l
            if (justified == null && settings.justify && piece.kind != BlockKind.TITLE && l.layoutInput.text == text) {
                justified = justify(text, l, piece.continues, density.density, density.fontScale)
            }
        },
        modifier = modifier.fillMaxWidth().pointerInput(piece) {
            detectTapGestures(
                onTap = { pos ->
                    val t = tokenAt(pos)
                    val sentence = t?.let { sentences[it.first] }
                    if (sentence != null) onWord(WordTap(sentence, t.second)) else onBlank()
                },
                onLongPress = { pos ->
                    val t = tokenAt(pos)
                    when {
                        t != null -> onLongPress(TextSelection(t.first, t.second))
                        piece.kind == BlockKind.NOTES -> onLongPressBlock(Layer.NOTE)
                        piece.kind == BlockKind.REFERENCE -> onLongPressBlock(Layer.REFERENCE)
                    }
                },
            )
        },
    )
}

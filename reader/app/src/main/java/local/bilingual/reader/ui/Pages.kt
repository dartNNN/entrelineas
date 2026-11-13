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
import local.bilingual.reader.data.CHAPTER_END
import local.bilingual.reader.format.*
import local.bilingual.reader.reading.*



/** What the page highlights for the active selection; drawing it never re-paginates. */
internal data class Highlight(val tokens: Set<Pair<String, Int>>, val paragraphs: Set<String>, val layer: Layer, val color: Color)

@OptIn(ExperimentalMaterial3Api::class)

/**
 * Pages of one layout: first screens synchronously at the anchor, the rest of the chapter in the background. The pager
 * has one more page at each end where there is a neighbouring chapter ([lead], [trail]): settling on it turns to that
 * chapter. The end one appears once the chapter is fully laid out, so it is never reached before the last real page.
 */
private class PageFlow(val anchorParagraph: String, hasPrevious: Boolean, private val hasNext: Boolean, cached: List<Page>?) {
    val pages = mutableStateListOf<Page>().apply { cached?.let { addAll(it) } }
    var complete by mutableStateOf(cached != null)
    val lead = if (hasPrevious) 1 else 0
    val trail get() = if (hasNext && complete) 1 else 0
    val pager = PagerState(currentPage = lead + (cached?.let { startIndex(it) } ?: 0)) { lead + pages.size + trail }

    /** The real page shown at pager index [index], or null on a turn page. */
    fun page(index: Int): Page? = pages.getOrNull(index - lead)

    private fun startIndex(list: List<Page>): Int =
        if (anchorParagraph == CHAPTER_END) list.lastIndex.coerceAtLeast(0)
        else list.indexOfLast { it.firstParagraph <= anchorParagraph && !it.repeat }.coerceAtLeast(0)
}

/**
 * The last complete layout, kept for the life of the process: when the system destroys the activity in the background
 * and it comes back with the same chapter, settings and screen, its pages are reused instead of laid out again.
 */
private object LayoutCache {
    var key: Any? = null
    var pages: List<Page> = emptyList()
}

@Composable
internal fun Pages(chapter: Chapter, settings: Settings, shell: ShellSettings, colors: SchemeColors, known: Set<String>,
                  startParagraph: String, highlight: Highlight?, progress: (Int, Int?) -> Progress,
                  onWord: (WordTap) -> Unit, onLongPress: (TextSelection) -> Unit, onBlank: () -> Unit,
                  onPage: (paragraph: String, page: Int, pages: Int?) -> Unit, onTurn: () -> Unit, onLayout: (Long) -> Unit,
                  onReveal: () -> Unit, modifier: Modifier = Modifier, cacheKey: Any? = null,
                  previous: String? = null, next: String? = null, onPrevious: () -> Unit = {}, onNext: () -> Unit = {},
                  onCount: (layout: String, pages: Int?) -> Unit = { _, _ -> }) {
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
        StatusLine(shell, progress(flow?.let { realPage(it) } ?: 0, flow?.takeIf { it.complete }?.pages?.size), statusColor,
            padding.pointerInput(Unit) { detectTapGestures { onBlank() } })
    }
    Column(modifier.fillMaxSize().padding(horizontal = settings.margin.dp, vertical = 12.dp)) {
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
            val key = listOf(cacheKey, settings, known, width, height)
            val flow = remember(blocks, settings, width, height) {
                PageFlow(currentParagraph, previous != null, next != null,
                    LayoutCache.pages.takeIf { cacheKey != null && LayoutCache.key == key && it.isNotEmpty() })
            }
            SideEffect { shown = flow }
            // The layout as the book's page count knows it: what changes line breaks, not the familiar marks.
            val layout = "${settings.toString().hashCode()}:${width}x$height"
            LaunchedEffect(flow) {
                onCount(layout, null)
                if (flow.complete) { onLayout(0); onCount(layout, flow.pages.size); return@LaunchedEffect }
                withContext(Dispatchers.Main.immediate) {
                val started = SystemClock.elapsedRealtime()
                val atEnd = flow.anchorParagraph == CHAPTER_END
                val anchor = if (atEnd) blocks.size else anchorBlock(blocks, flow.anchorParagraph)
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
                        val page = flow.pager.currentPage - flow.lead
                        flow.pages.addAll(0, before)
                        flow.pager.scrollToPage(flow.lead + if (atEnd) flow.pages.lastIndex.coerceAtLeast(0) else page + before.size)
                    }
                }
                // UI state is only ever touched on the main thread; layout work above runs on Dispatchers.Default.
                withContext(Dispatchers.Main.immediate) {
                    flow.complete = true
                    if (cacheKey != null) { LayoutCache.key = key; LayoutCache.pages = flow.pages.toList() }
                    onCount(layout, flow.pages.size)
                }
                }
            }
            LaunchedEffect(flow) {
                var first = true
                snapshotFlow { flow.pager.currentPage to flow.complete }.collect { (index, complete) ->
                    flow.page(index)?.let {
                        currentParagraph = it.firstParagraph
                        onPage(it.firstParagraph, index - flow.lead, if (complete) flow.pages.size else null)
                    }
                    if (!first) onTurn()
                    first = false
                }
            }
            // Settling on a turn page opens the neighbouring chapter; before the layout is complete the start one only
            // leads to pages not laid out yet, so the pager goes back to the first real page instead.
            LaunchedEffect(flow) {
                snapshotFlow { flow.pager.settledPage to flow.complete }.collect { (index, complete) ->
                    when {
                        flow.pages.isEmpty() -> Unit
                        index < flow.lead -> if (complete) onPrevious() else flow.pager.scrollToPage(flow.lead)
                        index >= flow.lead + flow.pages.size -> onNext()
                    }
                }
            }
            HorizontalPager(flow.pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { index ->
                val page = flow.page(index)
                if (page == null) {
                    TurnPage(if (index < flow.lead) "← $previous" else "$next →", palette)
                    return@HorizontalPager
                }
                // Tags count real pages only (a turn page before them does not shift the numbers).
                val real = index - flow.lead
                Column(Modifier.fillMaxSize().testTag("page$real").semantics { stateDescription = page.firstParagraph },
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    page.pieces.forEachIndexed { n, piece ->
                        PieceText(piece, settings, base, palette, sentences, highlight,
                            onWord = onWord,
                            onLongPress = onLongPress,
                            onLongPressBlock = { layer -> firstSentence[piece.paragraphId]?.let { sid ->
                                onLongPress(TextSelection(sid, 0, SelectionUnit.PARAGRAPH, layer = layer)) } },
                            onBlank = onBlank,
                            modifier = Modifier.testTag("page$real-piece$n"))
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
            val current = flow?.let { realPage(it) } ?: 0
            // The items chosen in the settings; the chapter's "page / pages" is always in the semantics, for tests.
            val items = progress(current, flow?.takeIf { it.complete }?.pages?.size).items(shell).joinToString("   ·   ")
            Text(items.ifEmpty { " " }, style = MaterialTheme.typography.bodySmall, color = statusColor,
                // The counter line is also a dependable place to call up the shell on a page full of words.
                modifier = Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { onBlank() } }.padding(top = 8.dp)
                    .testTag("pageCounter").semantics { stateDescription = "${current + 1} / $total" })
        }
    }
}

/** The real page under the pager's current index (a turn page counts as the nearest real one). */
private fun realPage(flow: PageFlow): Int = (flow.pager.currentPage - flow.lead).coerceIn(0, (flow.pages.size - 1).coerceAtLeast(0))

/** The page between two chapters: the title of the one it turns to, shown while the pager settles on it. */
@Composable
private fun TurnPage(title: String, palette: Palette) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = palette.hint,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(24.dp).testTag("turnPage"))
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
                    // Punctuation is not a word: a tap on it is a tap off any word.
                    val sentence = t?.let { sentences[it.first] }?.takeIf { s -> isWord(s.tokens.getOrNull(t.second)) }
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

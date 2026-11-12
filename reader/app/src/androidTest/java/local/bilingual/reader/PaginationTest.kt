package local.bilingual.reader
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import local.bilingual.reader.format.*
import local.bilingual.reader.reading.*
import local.bilingual.reader.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith


/** Checks pagination invariants with the real text engine; also times a real chapter if the asset is present. */
@RunWith(AndroidJUnit4::class)
class PaginationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val density = Density(context)
    private val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
    private val palette = Palette(Color.Gray, Color.Blue, Color.DarkGray)
    private val base = TextStyle(fontFamily = FontFamily.Serif, lineHeight = TextUnit(1.4f, TextUnitType.Em))
    private val metrics = context.resources.displayMetrics
    private val width = metrics.widthPixels - (40 * metrics.density).toInt()
    private val height = metrics.heightPixels - (140 * metrics.density).toInt()
    private val gap = (10 * metrics.density).toInt()

    /** Own justification (ColorOS drops Android's): same line breaks, full lines reach the right margin. */
    @Test
    fun justifyKeepsLineBreaksAndFillsFullLines() {
        val chapter = BookParser.parse(TestBook.bytes(paragraphs = 3).inputStream()).chapters[0]
        val block = buildBlocks(chapter, Settings(), emptySet()).first { it.kind == BlockKind.HINTED && it.plain.length > 200 }
        val style = styleFor(block.kind, Settings(), base, palette)
        val ragged = measurer.measure(annotated(block, palette), style, constraints = Constraints(maxWidth = width))
        val text = justify(annotated(block, palette), ragged, continues = false, density.density, density.fontScale)
        val justified = measurer.measure(text, style, constraints = Constraints(maxWidth = width))
        assertEquals(ragged.lineCount, justified.lineCount)
        for (line in 0 until ragged.lineCount) assertEquals(ragged.getLineEnd(line), justified.getLineEnd(line))
        for (line in 0 until justified.lineCount - 1) {
            assertTrue("line $line ends at ${justified.getLineRight(line)} of $width", justified.getLineRight(line) > width - 4)
        }
        assertTrue("the last line stays ragged", justified.getLineRight(justified.lineCount - 1) <= ragged.getLineRight(ragged.lineCount - 1) + 1)
    }

    private fun check(book: Book, settings: Settings): Pair<Int, Long> {
        var pagesTotal = 0
        var millis = 0L
        for (chapter in book.chapters) {
            val started = SystemClock.elapsedRealtime()
            val blocks = buildBlocks(chapter, settings, emptySet())
            val pages = paginate(blocks, measurer, settings, base, palette, width, height, gap)
            millis += SystemClock.elapsedRealtime() - started
            pagesTotal += pages.size
            // Every page fits.
            for ((n, page) in pages.withIndex()) {
                val used = page.pieces.sumOf {
                    measurer.measure(annotated(it, palette), styleFor(it.kind, settings, base, palette), constraints = Constraints(maxWidth = width)).size.height
                } + gap * (page.pieces.size - 1)
                assertTrue("page $n overflows: $used > $height", used <= height)
                assertTrue("page $n is empty", page.pieces.isNotEmpty())
            }
            // No text is lost or duplicated: pieces re-joined equal the blocks, ignoring whitespace at cuts.
            val original = blocks.joinToString("") { it.plain }.filterNot(Char::isWhitespace)
            val paged = pages.flatMap { it.pieces }.joinToString("") { it.plain }.filterNot(Char::isWhitespace)
            assertEquals(original, paged)
        }
        return pagesTotal to millis
    }

    @Test
    fun syntheticBookInvariants() {
        val book = BookParser.parse(TestBook.bytes().inputStream())
        for (settings in listOf(Settings(), Settings(fontSize = 26f), Settings(repeat = Repeat.OFF, showNotes = false), Settings(threshold = 1))) {
            val (pages, _) = check(book, settings)
            assertTrue("expected several pages for $settings", pages > 3)
        }
    }

    /** Lazy layout: pages after the anchor plus pages before it cover the text exactly once. */
    private fun checkAnchored(book: Book, settings: Settings): Long {
        val chapter = book.chapters.first()
        val blocks = buildBlocks(chapter, settings, emptySet())
        val anchorParagraph = chapter.paragraphs[chapter.paragraphs.size / 2].id
        val anchor = anchorBlock(blocks, anchorParagraph)
        assertEquals(anchorParagraph, blocks[anchor].paragraphId)
        val started = SystemClock.elapsedRealtime()
        val forward = Paginator(blocks, anchor, blocks.size, settings, base, palette, width, height, gap)
        val first = (1..3).mapNotNull { forward.next(measurer) }
        val firstScreensMs = SystemClock.elapsedRealtime() - started
        assertEquals(anchorParagraph, first.first().firstParagraph)
        val rest = generateSequence { forward.next(measurer) }.toList()
        val before = paginate(blocks, measurer, settings, base, palette, width, height, gap, start = 0, end = anchor)
        val original = blocks.joinToString("") { it.plain }.filterNot(Char::isWhitespace)
        val paged = (before + first + rest).flatMap { it.pieces }.joinToString("") { it.plain }.filterNot(Char::isWhitespace)
        assertEquals(original, paged)
        return firstScreensMs
    }

    @Test
    fun anchoredLayoutCoversTextOnce() {
        checkAnchored(BookParser.parse(TestBook.bytes().inputStream()), Settings())
    }

    @Test
    fun realChapterInvariantsAndTiming() {
        val assets = instrumentation.context.assets
        if ("book.bbook.zip" !in (assets.list("") ?: emptyArray())) return
        val book = assets.open("book.bbook.zip").use { BookParser.parse(it) }
        val (pages, millis) = check(book, Settings())
        val (pagesLarge, millisLarge) = check(book, Settings(fontSize = 22f))
        checkAnchored(book, Settings())  // warm-up
        val firstScreens = (1..5).map { checkAnchored(book, Settings(fontSize = 18f + it)) }.sorted()
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "real book: full chapter $pages pages in $millis ms (18sp), $pagesLarge pages in $millisLarge ms (22sp); " +
                "first 3 screens from mid-chapter: median ${firstScreens[2]} ms, max ${firstScreens.last()} ms\n")
        })
    }
}

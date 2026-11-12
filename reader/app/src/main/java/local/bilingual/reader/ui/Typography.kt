package local.bilingual.reader.ui
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import local.bilingual.reader.reading.*


/**
 * How blocks look and break into pages on Android: styled text from runs, styles per block kind, pagination with the
 * text measurer, and justification by widening spaces (ColorOS drops Android's own).
 */
const val TOKEN_TAG = "tok"
/** Marks a gloss ("sentenceId:token") so a selection can paint the original and the glosses apart. */
const val GLOSS_TAG = "gloss"
/** Marks a segment's literal translation ("sentenceId:first:last"). */
const val LITERAL_TAG = "literal"

class Palette(val hint: Color, val gloss: Color, val note: Color)


/**
 * A block as styled text: glosses and literal translations in their colours and sizes, every word and its gloss
 * tagged TOKEN_TAG ("sentenceId:index") for taps, glosses and literals tagged for painting a selection.
 */
fun annotated(block: Block, p: Palette): AnnotatedString = buildAnnotatedString {
    for (r in block.runs) when (r) {
        is Run.Token -> { pushStringAnnotation(TOKEN_TAG, "${r.sentence}:${r.token}"); append(r.text); pop() }
        is Run.Gloss -> withStyle(SpanStyle(color = p.gloss, fontSize = 0.8.em)) {
            // The gloss answers taps like its word (TOKEN_TAG) and is marked as a gloss for highlighting.
            pushStringAnnotation(TOKEN_TAG, "${r.sentence}:${r.token}")
            pushStringAnnotation(GLOSS_TAG, "${r.sentence}:${r.token}")
            append(r.text)
            pop()
            pop()
        }
        is Run.Literal -> withStyle(SpanStyle(color = p.hint, fontSize = 0.85.em, fontStyle = FontStyle.Italic)) {
            pushStringAnnotation(LITERAL_TAG, "${r.sentence}:${r.first}:${r.last}")
            append(r.text)
            pop()
        }
        is Run.Text -> append(r.text)
    }
}

fun styleFor(kind: BlockKind, s: Settings, base: TextStyle, p: Palette): TextStyle = when (kind) {
    BlockKind.TITLE -> base.copy(fontSize = (s.fontSize * 1.3f).toSp())
    BlockKind.NOTES -> base.copy(fontSize = (s.fontSize * 0.75f).toSp(), color = p.note)
    BlockKind.REFERENCE -> base.copy(fontSize = (s.fontSize * 0.85f).toSp(), color = p.hint)
    BlockKind.AUTHOR_NOTES -> base.copy(fontSize = (s.fontSize * 0.8f).toSp())
    else -> base.copy(fontSize = s.fontSize.toSp())
}
// Justification is not set here: some OEM builds (ColorOS) silently drop Android's justification mode, so the
// reader justifies drawn text itself (see [justify]) with the same line breaks as this ragged layout.

private fun Float.toSp() = androidx.compose.ui.unit.TextUnit(this, androidx.compose.ui.unit.TextUnitType.Sp)

/**
 * Resumable pagination over blocks[start, end). Each next() returns one page; a block that does not fit is split at a
 * line boundary. The measurer is passed per call so the work can move between threads.
 */
class Paginator(
    private val blocks: List<Block>, start: Int, private val end: Int, private val s: Settings,
    private val base: TextStyle, private val p: Palette, private val width: Int, private val height: Int, private val gap: Int,
) {
    private var blockIdx = start
    private var rest: Block? = null

    fun next(measurer: TextMeasurer): Page? {
        val current = ArrayList<Block>()
        var used = 0
        while (true) {
            if (rest == null) {
                if (blockIdx >= end) break
                rest = blocks[blockIdx]
            }
            val block = blocks[blockIdx]
            var r = rest!!
            val style = styleFor(block.kind, s, base, p)
            val constraints = Constraints(maxWidth = width)
            val text = annotated(r, p)
            val layout = measurer.measure(text, style, constraints = constraints)
            val available = height - used - (if (current.isEmpty()) 0 else gap)
            if (layout.size.height <= available) {
                current += r
                used += layout.size.height + (if (current.size == 1) 0 else gap)
                rest = null
                blockIdx++
                continue
            }
            var fit = -1
            for (line in 0 until layout.lineCount) {
                if (layout.getLineBottom(line) <= available) fit = line else break
            }
            if (fit < 0) {
                if (current.isNotEmpty()) return Page(current, current.first().paragraphId)
                fit = 0
            }
            var cut = layout.getLineEnd(fit, visibleEnd = false).coerceAtLeast(1)
            // A re-measured prefix can be taller than the line bottom it was cut at; step back until it fits.
            while (fit > 0 && measurer.measure(text.subSequence(0, cut), style, constraints = constraints).size.height > available) {
                fit -= 1
                cut = layout.getLineEnd(fit, visibleEnd = false).coerceAtLeast(1)
            }
            current += r.slice(0, cut, continues = true)
            val plain = r.plain
            var next = cut
            while (next < plain.length && plain[next].isWhitespace()) next++
            if (next >= plain.length) { rest = null; blockIdx++ } else rest = r.slice(next, plain.length)
            return Page(current, current.first().paragraphId)
        }
        return if (current.isEmpty()) null else Page(current, current.first().paragraphId)
    }
}

/** Eager pagination of the whole block list (tests, tools). */
fun paginate(blocks: List<Block>, measurer: TextMeasurer, s: Settings, base: TextStyle, p: Palette,
             width: Int, height: Int, gap: Int, start: Int = 0, end: Int = blocks.size): List<Page> {
    val paginator = Paginator(blocks, start, end, s, base, p, width, height, gap)
    return generateSequence { paginator.next(measurer) }.toList()
}

/**
 * Justifies laid-out text by widening the spaces of each full line: the line's slack is shared by its inner spaces
 * as letter spacing on the space characters only, so glyphs keep their places and line breaks stay those of
 * [layout] (a pixel of slack is kept so rounding cannot push a word to the next line). The last line of a paragraph
 * (before a line break or at the end) stays ragged unless the text [continues] on the next page.
 */
fun justify(text: AnnotatedString, layout: TextLayoutResult, continues: Boolean, density: Float, fontScale: Float): AnnotatedString {
    val width = layout.layoutInput.constraints.maxWidth.toFloat()
    val builder = AnnotatedString.Builder(text)
    for (line in 0 until layout.lineCount) {
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line, visibleEnd = true)
        val hardEnd = layout.getLineEnd(line)
        val lastOfParagraph = (line == layout.lineCount - 1 && !continues) ||
            (hardEnd > 0 && hardEnd <= text.length && text.text[hardEnd - 1] == '\n')
        if (lastOfParagraph) continue
        val spaces = (start until end).filter { text.text[it] == ' ' }
        if (spaces.isEmpty()) continue
        val slack = width - (layout.getLineRight(line) - layout.getLineLeft(line)) - 1f
        if (slack <= 0f) continue
        val extra = (slack / spaces.size / (density * fontScale)).sp
        spaces.forEach { builder.addStyle(SpanStyle(letterSpacing = extra), it, it + 1) }
    }
    return builder.toAnnotatedString()
}

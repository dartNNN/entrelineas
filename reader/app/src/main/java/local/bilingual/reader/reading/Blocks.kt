package local.bilingual.reader.reading

import local.bilingual.reader.format.*

/**
 * What the page shows, as plain data (the same model as the web reader's blocks.ts): per paragraph the hinted text,
 * its notes, the author's footnotes, the published translation if wanted, and the plain repeat. Drawing (styles,
 * colours, tap tags) is the UI's business (Typography.kt annotated()); pagination cuts blocks into pieces.
 */
enum class BlockKind { HINTED, NOTES, AUTHOR_NOTES, REFERENCE, PLAIN, TITLE }

/** One run of text; tokens and glosses carry their word, literal translations their segment. */
sealed interface Run {
    val text: String
    data class Token(val sentence: String, val token: Int, override val text: String) : Run
    data class Gloss(val sentence: String, val token: Int, override val text: String) : Run
    data class Literal(val sentence: String, val first: Int, val last: Int, override val text: String) : Run
    data class Text(override val text: String) : Run
}

/** continues: the block was cut at the page end and goes on next page, so its last line is a full line. */
data class Block(val kind: BlockKind, val runs: List<Run>, val paragraphId: String, val continues: Boolean = false) {
    val plain: String get() = runs.joinToString("") { it.text }

    /** Characters [start, end) of the block; runs cut by the range keep their word. */
    fun slice(start: Int, end: Int, continues: Boolean = false): Block {
        val out = ArrayList<Run>()
        var at = 0
        for (r in runs) {
            val from = maxOf(start, at)
            val to = minOf(end, at + r.text.length)
            if (from < to) out += r.cut(r.text.substring(from - at, to - at))
            at += r.text.length
        }
        return copy(runs = out, continues = continues)
    }
}

private fun Run.cut(text: String): Run = when (this) {
    is Run.Token -> copy(text = text)
    is Run.Gloss -> copy(text = text)
    is Run.Literal -> copy(text = text)
    is Run.Text -> copy(text = text)
}

/** One screen; repeat marks the hint-free screen that follows a hinted one in screen repeat. */
data class Page(val pieces: List<Block>, val firstParagraph: String, val repeat: Boolean = false)

fun buildBlocks(chapter: Chapter, s: Settings, known: Set<String>): List<Block> {
    val blocks = ArrayList<Block>()
    // Packages before schema 1.3 carry the title as plain text; later ones as an annotated heading paragraph.
    if (chapter.paragraphs.firstOrNull()?.heading != true) {
        blocks += Block(BlockKind.TITLE, listOf(Run.Text(chapter.title)), chapter.paragraphs.firstOrNull()?.id ?: "")
    }
    // A published translation follows the paragraph where its sentence group ends (groups may span paragraphs).
    val all = chapter.paragraphs.flatMap { it.sentences }
    val groupEnds = all.filterIndexed { i, x -> x.reference != null && all.getOrNull(i + 1)?.reference != x.reference }.map { it.id }.toSet()
    for (par in chapter.paragraphs) {
        blocks += Block(if (par.heading) BlockKind.TITLE else BlockKind.HINTED, hinted(par, s, known), par.id)
        if (s.showNotes) {
            val notes = par.sentences.flatMap { it.notes }.filter { it.kind == "grammar" || it.kind == "context" }
            if (notes.isNotEmpty()) {
                blocks += Block(BlockKind.NOTES, listOf(Run.Text(notes.joinToString("\n") { (if (it.kind == "grammar") "◦ " else "※ ") + it.text })), par.id)
            }
        }
        val authorNotes = par.sentences.flatMap { it.authorNotes }
        if (s.showAuthorNotes && authorNotes.isNotEmpty()) {
            blocks += Block(BlockKind.AUTHOR_NOTES, listOf(Run.Text(authorNotes.joinToString("\n") { "* $it" })), par.id)
        }
        if (s.referenceInText) {
            val refs = par.sentences.filter { it.id in groupEnds }.mapNotNull { it.reference }.filter { it.text != null }
            if (refs.isNotEmpty()) {
                val body = refs.joinToString(" ") { r -> referenceMark(r) + r.text }
                val notes = if (s.showFootnotes) refs.flatMap { it.notes } else emptyList()
                blocks += Block(BlockKind.REFERENCE, listOf(Run.Text((listOf(body) + notes.map { "* $it" }).joinToString("\n"))), par.id)
            }
        }
        if (s.effectiveRepeat == Repeat.PARAGRAPH) blocks += Block(BlockKind.PLAIN, plain(par), par.id)
    }
    return blocks
}

/** "≈ " for a free rendering, "? " where the segment model doubted the published text renders these sentences. */
fun referenceMark(r: Reference) = when {
    r.uncertain -> "? "
    r.loose -> "≈ "
    else -> ""
}

private fun token(sentence: Sentence, i: Int) = Run.Token(sentence.id, i, sentence.tokens[i])

private fun hinted(par: Paragraph, s: Settings, known: Set<String>): List<Run> = buildList {
    for (sentence in par.sentences) {
        val segments = sentence.segments.ifEmpty { listOf(Segment(0, sentence.tokens.lastIndex, "", null)) }
        for (seg in segments) {
            for (i in seg.first..minOf(seg.last, sentence.tokens.lastIndex)) {
                add(token(sentence, i))
                val lex = sentence.lexical[i]
                if (s.showLexical && lex != null && lex.ordinal <= s.threshold && lex.key !in known) {
                    add(Run.Gloss(sentence.id, i, " [" + lex.gloss + (lex.form?.let { "; $it" } ?: "") + "]"))
                }
                if (i < seg.last) add(Run.Text(sentence.ws.getOrElse(i) { " " }))
            }
            if (s.showLiteral && seg.literal.isNotBlank()) {
                add(Run.Literal(sentence.id, seg.first, seg.last, " (" + seg.literal + (seg.literary?.let { " = $it" } ?: "") + ")"))
            }
            add(Run.Text(sentence.ws.getOrElse(seg.last) { " " }.ifEmpty { " " }))
        }
    }
}

private fun plain(par: Paragraph): List<Run> = buildList {
    for (sentence in par.sentences) {
        for (i in sentence.tokens.indices) {
            add(token(sentence, i))
            add(Run.Text(sentence.ws.getOrElse(i) { " " }))
        }
    }
}

/**
 * The hint-free screen for a hinted one: exactly the tokens shown on it, in order, one plain block per paragraph,
 * still tappable. A paragraph cut across two hinted screens is cut the same way here. Null if the screen shows no
 * text (title or notes only).
 */
fun repeatPage(page: Page, sentences: Map<String, Sentence>): Page? {
    val tokens = LinkedHashSet<Pair<String, Int>>()
    for (piece in page.pieces.filter { it.kind == BlockKind.HINTED || it.kind == BlockKind.PLAIN || it.kind == BlockKind.TITLE }) {
        piece.runs.forEach { r ->
            // a gloss carries its word too: the set keeps each token once
            when (r) {
                is Run.Token -> tokens += r.sentence to r.token
                is Run.Gloss -> tokens += r.sentence to r.token
                else -> Unit
            }
        }
    }
    if (tokens.isEmpty()) return null
    val blocks = tokens.groupBy { (sid, _) -> sid.substringBeforeLast("s") }.map { (paragraph, items) ->
        val runs = buildList {
            items.forEachIndexed { n, (sid, i) ->
                val sentence = sentences[sid] ?: return@forEachIndexed
                add(token(sentence, i))
                if (n < items.lastIndex) add(Run.Text(sentence.ws.getOrElse(i) { " " }))
            }
        }
        Block(BlockKind.PLAIN, runs, paragraph)
    }
    return Page(blocks, page.firstParagraph, repeat = true)
}

/** Pages as read: in screen repeat every hinted page is followed by its hint-free twin. */
fun withRepeats(pages: List<Page>, s: Settings, sentences: Map<String, Sentence>): List<Page> =
    if (s.effectiveRepeat != Repeat.SCREEN) pages else pages.flatMap { listOfNotNull(it, repeatPage(it, sentences)) }

/** Index of the block where reading resumes: the first block of the anchor paragraph, or 0 at chapter start. */
fun anchorBlock(blocks: List<Block>, paragraph: String): Int {
    if (paragraph.isEmpty()) return 0
    val i = blocks.indexOfFirst { it.kind != BlockKind.TITLE && it.paragraphId >= paragraph }
    return if (i <= 1) 0 else i
}

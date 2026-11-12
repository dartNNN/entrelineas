package local.bilingual.reader.reading

import local.bilingual.reader.format.*

/**
 * Selection by units of the annotated text, not by characters: a rendered line mixes the original with bracketed
 * glosses and literal translations, so a character range would cut across layers. A selection is an anchor word,
 * a unit around it, how many following sentences or paragraphs it takes in, and the layer to act on. It resolves to
 * a [SelectionContent] that carries every field at once; actions pick what they need through templates.
 */
enum class SelectionUnit(val title: String) { WORD("Слово"), SEGMENT("Сегмент"), SENTENCE("Предложение"), PARAGRAPH("Абзац") }

enum class Layer(val title: String) {
    ORIGINAL("Оригинал"), LITERAL("Дословно"), REFERENCE("Перевод"), GLOSS("Подсказка"), NOTE("Заметки")
}

/** A tap on a word: opens its card. */
data class WordTap(val sentence: Sentence, val token: Int)

data class TextSelection(
    val sentenceId: String,
    val token: Int,
    val unit: SelectionUnit = SelectionUnit.WORD,
    /** Following sentences (unit SENTENCE) or paragraphs (unit PARAGRAPH) taken in as well. */
    val more: Int = 0,
    val layer: Layer = Layer.ORIGINAL,
)

data class SelectionContent(
    val original: String,
    val literal: String,
    val reference: String,
    val lemma: String,
    val gloss: String,
    val form: String,
    /** Familiar-mark key of the word's sense; empty unless a single word with a gloss is selected. */
    val key: String,
    val notes: String,
    val sentence: String,
    val chapter: String,
    val book: String,
    /** Language codes from the manifest: the original's and the one of translations and notes. */
    val studied: String = "es",
    val support: String = "ru",
) {
    fun layer(layer: Layer): String = when (layer) {
        Layer.ORIGINAL -> original
        Layer.LITERAL -> literal
        Layer.REFERENCE -> reference
        Layer.GLOSS -> listOf(lemma, gloss).filter { it.isNotBlank() }.joinToString(" — ")
        Layer.NOTE -> notes
    }

    /** Language a layer is written in: the original is in the studied language, everything else in the support one. */
    fun language(layer: Layer): String = if (layer == Layer.ORIGINAL) studied else support

    /** Layers that have text for this selection, in display order. */
    fun layers(): List<Layer> = Layer.entries.filter { it == Layer.ORIGINAL || layer(it).isNotBlank() }
}

/** Resolves selections against one chapter of a book. */
class SelectionResolver(private val book: Book, private val chapter: Chapter) {
    private val sentences = chapter.paragraphs.flatMap { it.sentences }
    private val paragraphOf = chapter.paragraphs.flatMap { p -> p.sentences.map { it.id to p } }.toMap()

    /** Sentences the selection covers, in order; WORD and SEGMENT stay inside the anchor sentence. */
    private fun coveredSentences(sel: TextSelection): List<Sentence> {
        val anchor = sentences.indexOfFirst { it.id == sel.sentenceId }.coerceAtLeast(0)
        return when (sel.unit) {
            SelectionUnit.WORD, SelectionUnit.SEGMENT -> listOf(sentences[anchor])
            SelectionUnit.SENTENCE -> sentences.subList(anchor, minOf(sentences.size, anchor + 1 + sel.more))
            SelectionUnit.PARAGRAPH -> {
                val first = chapter.paragraphs.indexOf(paragraphOf.getValue(sentences[anchor].id))
                chapter.paragraphs.subList(first, minOf(chapter.paragraphs.size, first + 1 + sel.more)).flatMap { it.sentences }
            }
        }
    }

    /** Units offered for a selection: "segment" only where the sentence has segments (a book built without a model has none). */
    fun units(sel: TextSelection): List<SelectionUnit> {
        val s = sentences.firstOrNull { it.id == sel.sentenceId }
        return SelectionUnit.entries.filter { it != SelectionUnit.SEGMENT || s?.segments?.isNotEmpty() == true }
    }

    private fun segmentOf(s: Sentence, token: Int): Segment =
        s.segments.firstOrNull { token in it.first..it.last } ?: Segment(0, s.tokens.lastIndex, "", null)

    /** Selected tokens as (sentence id, token index); what the page highlights for the original-based layers. */
    fun tokens(sel: TextSelection): Set<Pair<String, Int>> {
        val covered = coveredSentences(sel)
        return when (sel.unit) {
            SelectionUnit.WORD -> setOf(sel.sentenceId to sel.token)
            SelectionUnit.SEGMENT -> segmentOf(covered.first(), sel.token).let { seg -> (seg.first..seg.last).map { sel.sentenceId to it }.toSet() }
            else -> covered.flatMap { s -> s.tokens.indices.map { s.id to it } }.toSet()
        }
    }

    /** Paragraphs the selection touches; notes and published translation blocks of these are highlighted. */
    fun paragraphs(sel: TextSelection): Set<String> = coveredSentences(sel).mapNotNull { paragraphOf[it.id]?.id }.toSet()

    fun content(sel: TextSelection): SelectionContent {
        val covered = coveredSentences(sel)
        val anchor = sentences.firstOrNull { it.id == sel.sentenceId } ?: covered.first()
        val range: (Sentence) -> IntRange = { s ->
            when (sel.unit) {
                SelectionUnit.WORD -> sel.token..sel.token
                SelectionUnit.SEGMENT -> segmentOf(s, sel.token).let { it.first..it.last }
                else -> s.tokens.indices
            }
        }
        val original = covered.joinToString(" ") { s -> text(s, range(s)) }
        val literal = when (sel.unit) {
            SelectionUnit.WORD, SelectionUnit.SEGMENT -> segmentOf(anchor, sel.token).let { seg ->
                listOfNotNull(seg.literal.takeIf { it.isNotBlank() }, seg.literary?.let { "= $it" }).joinToString(" ")
            }
            else -> covered.flatMap { it.segments }.joinToString(" ") { it.literal }
        }
        val reference = covered.mapNotNull { it.reference }.distinctBy { it.index }.mapNotNull { it.text }.joinToString(" ")
        val lex = if (sel.unit == SelectionUnit.WORD) anchor.lexical[sel.token] else null
        val notes = covered.flatMap { s ->
            val r = range(s)
            s.notes.filter { n -> n.first <= r.last && n.last >= r.first }.map { it.text }
        }.joinToString("\n")
        return SelectionContent(
            original = original,
            literal = literal,
            reference = reference,
            lemma = lex?.lemma ?: "",
            gloss = lex?.gloss ?: "",
            form = lex?.form ?: "",
            key = lex?.key ?: "",
            notes = notes,
            sentence = text(anchor, anchor.tokens.indices),
            chapter = chapter.title,
            book = book.title,
            studied = book.studied,
            support = book.support,
        )
    }

    private fun text(s: Sentence, r: IntRange): String =
        r.joinToString("") { i -> s.tokens[i] + if (i < r.last) s.ws.getOrElse(i) { " " } else "" }.trim()
}

/**
 * Fills an action template. Placeholders: {text} (the chosen layer), {original}, {literal}, {reference}, {lemma},
 * {gloss}, {form}, {notes}, {sentence}, {chapter}, {book}; "\n" in a template is a line break. A line whose
 * placeholders are all empty is dropped, so one template serves a word and a paragraph alike.
 */
fun fillTemplate(template: String, c: SelectionContent, layer: Layer): String {
    val values = mapOf(
        "text" to c.layer(layer), "original" to c.original, "literal" to c.literal, "reference" to c.reference,
        "lemma" to c.lemma, "gloss" to c.gloss, "form" to c.form, "notes" to c.notes, "sentence" to c.sentence,
        "chapter" to c.chapter, "book" to c.book,
    )
    val placeholder = Regex("\\{(\\w+)\\}")
    return template.replace("\\n", "\n").lines().mapNotNull { line ->
        val names = placeholder.findAll(line).map { it.groupValues[1] }.filter { it in values }.toList()
        if (names.isNotEmpty() && names.all { values.getValue(it).isBlank() }) null
        else placeholder.replace(line) { m -> values[m.groupValues[1]] ?: m.value }
    }.joinToString("\n").trim()
}

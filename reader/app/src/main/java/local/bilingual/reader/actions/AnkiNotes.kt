package local.bilingual.reader.actions

import local.bilingual.reader.reading.*

/**
 * Flashcards for AnkiDroid, as plain data: the note types the reader creates in Anki and the fields of a note made
 * from a selection. Kept free of Android so the content of a card is tested on the JVM.
 */
data class AnkiModel(val name: String, val fields: List<String>, val front: String, val back: String, val css: String)

data class AnkiNote(val model: AnkiModel, val fields: List<String>) {
    /** The first field identifies duplicates in Anki. */
    val key get() = fields.first()
}

object AnkiNotes {
    const val DECK = "Entrelíneas"
    const val TAG = "entrelineas"

    private const val CSS = ".card { font-family: serif; font-size: 22px; text-align: center; color: #2E2A24; background: #F4EBDD; }" +
        " .context { font-size: 18px; color: #6B6458; margin-top: 12px; } .meaning { font-size: 24px; }" +
        " .translation { font-size: 17px; font-style: italic; color: #6B6458; margin-top: 10px; } .source { font-size: 12px; color: #9A9184; margin-top: 16px; }"

    /** A word: front is the word with its sentence, back its meaning in this book and the published translation. */
    val WORD = AnkiModel(
        name = "Entrelíneas: слово",
        fields = listOf("Слово", "Форма", "Значение", "Предложение", "Перевод", "Источник"),
        front = "{{Слово}}<div class=context>{{Предложение}}</div>",
        back = "{{FrontSide}}<hr id=answer><div class=meaning>{{Значение}}</div>{{#Форма}}<div class=context>{{Форма}}</div>{{/Форма}}" +
            "<div class=translation>{{Перевод}}</div><div class=source>{{Источник}}</div>",
        css = CSS,
    )

    /** A sentence: front is the original, back the published translation and the literal one. */
    val SENTENCE = AnkiModel(
        name = "Entrelíneas: предложение",
        fields = listOf("Предложение", "Перевод", "Дословно", "Источник"),
        front = "{{Предложение}}",
        back = "{{FrontSide}}<hr id=answer><div class=meaning>{{Перевод}}</div><div class=translation>{{Дословно}}</div>" +
            "<div class=source>{{Источник}}</div>",
        css = CSS,
    )

    fun word(c: SelectionContent): AnkiNote {
        val headword = c.lemma.ifBlank { c.original }
        val form = if (c.original.isNotBlank() && !c.original.equals(headword, ignoreCase = true)) listOf(c.original, c.form).filter { it.isNotBlank() }.joinToString(" — ") else c.form
        return AnkiNote(WORD, listOf(escape(headword), escape(form), escape(c.gloss), bold(c.sentence, c.original), escape(c.reference), source(c)))
    }

    fun sentence(c: SelectionContent): AnkiNote =
        AnkiNote(SENTENCE, listOf(escape(c.original.ifBlank { c.sentence }), escape(c.reference), escape(c.literal), source(c)))

    private fun source(c: SelectionContent) = escape(listOf(c.book, c.chapter).filter { it.isNotBlank() }.joinToString(" · "))

    /** The sentence with the selected word in bold (first occurrence), HTML-escaped. */
    fun bold(sentence: String, word: String): String {
        val i = if (word.isBlank()) -1 else sentence.indexOf(word)
        if (i < 0) return escape(sentence)
        return escape(sentence.substring(0, i)) + "<b>" + escape(word) + "</b>" + escape(sentence.substring(i + word.length))
    }

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
}

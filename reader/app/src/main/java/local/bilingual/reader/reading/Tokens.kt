package local.bilingual.reader.reading


/** A token with a letter or a digit is a word; one without (punctuation, dashes, quotes) opens no word card. */
fun isWord(token: String?): Boolean = token?.any { it.isLetterOrDigit() } == true

/**
 * The labels to show over an entry's senses in the word card: the label of each sense where it changes from the
 * sense before ("сущ." over 1.1, "гл." over 2.1), and none at all when the whole entry has one label (it stands at the
 * top of the card already). Senses without a label (idioms, phrase entries) get no heading.
 */
fun <T> senseHeadings(senses: List<T>, gram: (T) -> String?): List<String?> {
    val labels = senses.map(gram)
    if (labels.filterNotNull().distinct().size < 2) return labels.map { null }
    return labels.mapIndexed { i, g -> g?.takeIf { i == 0 || labels[i - 1] != it } }
}

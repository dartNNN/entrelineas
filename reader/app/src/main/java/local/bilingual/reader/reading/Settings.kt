package local.bilingual.reader.reading


/**
 * Rereading without hints: none; per paragraph, the plain paragraph right under its hinted version (Frank's books);
 * per screen, after each screen with hints the next one holds exactly the same text without any hints.
 */
enum class Repeat { OFF, PARAGRAPH, SCREEN }

/** Reader settings that change rendering without touching the package. */
data class Settings(
    val threshold: Int = 10,
    val showLiteral: Boolean = true,
    val showLexical: Boolean = true,
    val showNotes: Boolean = true,
    val repeat: Repeat = Repeat.PARAGRAPH,
    val fontSize: Float = 18f,
    val referenceInCard: Boolean = true,
    val referenceInText: Boolean = false,
    val showFootnotes: Boolean = true,
    val showAuthorNotes: Boolean = true,
    /** Body text justified to both margins. */
    val justify: Boolean = true,
) {
    /** Whether the hinted text differs from the original at all: literal translations, glosses or notes. */
    val hasHints get() = showLiteral || showLexical || showNotes

    /**
     * The repeat actually used: with every hint off the text already is the plain original, so a repeat would only
     * duplicate it. The chosen mode is kept and comes back as soon as any hint is on again.
     */
    val effectiveRepeat get() = if (hasHints) repeat else Repeat.OFF
}

/**
 * Settings of the app around the page: colour scheme, full screen and its status line, how long the shell stays.
 * Kept apart from [Settings] so that changing them does not rebuild the page blocks (except the scheme's colours).
 */
data class ShellSettings(
    val scheme: SchemeId = SchemeId.SYSTEM,
    val fullscreen: Boolean = false,
    val statusOnTop: Boolean = true,
    val clock: Boolean = true,
    val battery: Boolean = true,
    val page: Boolean = true,
    val chapterProgress: Boolean = false,
    val bookProgress: Boolean = false,
    val chapterTitle: Boolean = false,
    /** Seconds the top bar stays after the last touch. */
    val autoHideSeconds: Int = 3,
)

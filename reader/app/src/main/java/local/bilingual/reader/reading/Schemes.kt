package local.bilingual.reader.reading

/**
 * Colour schemes, modelled on ReadEra and Cool Reader: a warm neutral page, one cool accent (petrol), no saturated
 * hues. Body text stays 8.5–13:1 against the page, never pure black on white; literal, gloss and note colours
 * 4.8–7:1, above the 4.5:1 WCAG AA minimum for small text. Bars are a shade darker than the page; the accent goes
 * only to active controls and glosses. Colours are ARGB longs so the catalog has no Android dependency.
 */
enum class SchemeId(val title: String) { SYSTEM("Как в системе"), PAPER("Бумага"), SEPIA("Сепия"), DUSK("Сумерки"), NIGHT("Ночь") }

data class SchemeColors(
    val dark: Boolean,
    val page: Long,
    val text: Long,
    val literal: Long,
    val gloss: Long,
    val note: Long,
    val bar: Long,
    val accent: Long,
    /** Background of a selection; translucent so the text under it keeps its colour. */
    val selection: Long,
)

object Schemes {
    val PAPER = SchemeColors(false, 0xFFF7F3EA, 0xFF2E2A24, 0xFF6B6458, 0xFF2F6A80, 0xFF76653F, 0xFFECE5D7, 0xFF2F6A80, 0x552F6A80)
    val SEPIA = SchemeColors(false, 0xFFF6E3CB, 0xFF45372A, 0xFF735F4A, 0xFF215E70, 0xFF765A34, 0xFFEAD5BA, 0xFF215E70, 0x55215E70)
    val DUSK = SchemeColors(true, 0xFF2E2E2E, 0xFFD2CDC4, 0xFFA39D93, 0xFF86B6C5, 0xFFBBA77D, 0xFF252525, 0xFF86B6C5, 0x5586B6C5)
    val NIGHT = SchemeColors(true, 0xFF000000, 0xFFC8B78F, 0xFF8F826A, 0xFF6E9FAE, 0xFF9C8862, 0xFF101010, 0xFF6E9FAE, 0x556E9FAE)

    /** The scheme to draw with: "as in the system" is paper by day and dusk by night. */
    fun resolve(id: SchemeId, systemDark: Boolean): SchemeColors = when (id) {
        SchemeId.SYSTEM -> if (systemDark) DUSK else PAPER
        SchemeId.PAPER -> PAPER
        SchemeId.SEPIA -> SEPIA
        SchemeId.DUSK -> DUSK
        SchemeId.NIGHT -> NIGHT
    }

    /** WCAG 2.x contrast ratio of two opaque colours. */
    fun contrast(a: Long, b: Long): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}

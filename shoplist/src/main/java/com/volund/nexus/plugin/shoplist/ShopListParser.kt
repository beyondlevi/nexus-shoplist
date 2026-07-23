package com.volund.nexus.plugin.shoplist

/**
 * Pure, Android-free parsing of pasted multi-line text into item labels.
 *
 * Splitting lives here (not in the store) so it can be unit-tested without a
 * device: one non-blank line becomes one item, trimmed and capped to the card
 * line limit. Kept deliberately literal — no bullet/number stripping — so what
 * the user pastes is exactly what they get.
 */
object ShopListParser {
    private val LINE_BREAK = Regex("\\r?\\n")

    fun parseLines(raw: String, maxLabelChars: Int = DEFAULT_MAX_LABEL_CHARS): List<String> =
        raw.split(LINE_BREAK)
            .map { it.trim().take(maxLabelChars) }
            .filter { it.isNotEmpty() }

    const val DEFAULT_MAX_LABEL_CHARS = 120
}

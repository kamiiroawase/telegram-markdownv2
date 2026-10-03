package io.github.kamiiroawase.markdownv2

/**
 * Customization knobs for [MarkdownV2.render]; every knob has a library default, so the
 * default instance reproduces the stock behavior.
 *
 * Only table degradation depends on measurement choices (how wide a character is, how much
 * of a huge cell may cost); everything else has a single correct rendering and is not
 * configurable. A value object: equality, hashing and [copy] compare both knobs, so
 * instances work as cache keys and derived variants. Immutable and safe to share across
 * threads; the one user-owned piece is [displayWidthOf] — a custom measure capturing
 * mutable state brings its own thread-safety obligation (the default
 * [defaultDisplayWidth] is a pure function).
 */
public data class RenderOptions(
    /**
     * Display-width cap for a single table cell when a table degrades to a code block;
     * over-long cells are cut to this width (minus the ellipsis) so one huge cell cannot
     * blow the whole table, header included, past the message length limit.
     *
     * @throws IllegalArgumentException if the value is less than 1, or if the display
     * width [displayWidthOf] assigns to the truncation ellipsis (…) exceeds it — every
     * truncated cell would then overshoot the cap.
     */
    public val maxCellWidth: Int = DEFAULT_MAX_CELL_WIDTH,
    /**
     * Display width of a Unicode code point, used to align degraded tables: 0 for
     * zero-width characters (combining marks, variation selectors), 2 for wide characters
     * (CJK, Hangul, kana, fullwidth forms, emoji), 1 otherwise. The default
     * [defaultDisplayWidth] is an approximation of East Asian Width; pass a custom measure
     * to match your target font (e.g. treat Ambiguous characters as wide). Values must be
     * non-negative — a negative result fails table rendering with an IllegalArgumentException;
     * the truncation ellipsis (…) is measured once at construction (see [maxCellWidth]).
     *
     * Equality notes for the data-class members: two instances compare equal only when
     * their measures are the same function instance — distinct lambdas are never equal,
     * whatever they compute.
     */
    public val displayWidthOf: (codePoint: Int) -> Int = ::defaultDisplayWidth,
) {
    init {
        require(maxCellWidth >= 1) { "maxCellWidth must be positive: $maxCellWidth" }
        // The ellipsis is cell content: a measure that makes it wider than the cap would
        // push every truncated cell past maxCellWidth — fail fast, the same trade the
        // render-time negative-width check makes
        val ellipsisWidth = displayWidthOf(ELLIPSIS_CODE_POINT)
        require(ellipsisWidth in 0..maxCellWidth) {
            "maxCellWidth ($maxCellWidth) cannot hold the truncation ellipsis (display width " +
                "$ellipsisWidth under the supplied displayWidthOf); raise maxCellWidth or narrow the measure"
        }
    }

    /** Default values backing [RenderOptions]. */
    public companion object {
        /**
         * The default [maxCellWidth]: 32 display columns per cell.
         */
        public const val DEFAULT_MAX_CELL_WIDTH: Int = 32
    }
}

// The ellipsis appended to over-long table cells; RenderOptions measures it at
// construction to keep the truncation budget non-negative
internal const val ELLIPSIS_CODE_POINT = 0x2026

/**
 * The default display-width measure: 0 for zero-width characters, 2 for wide characters
 * (East Asian Width approximation: CJK, Hangul, kana, fullwidth forms and emoji), 2 for any
 * astral code point (CJK extensions and emoji dominate there), 1 otherwise. Public so a
 * custom [RenderOptions.displayWidthOf] can delegate to it while overriding individual
 * ranges.
 */
public fun defaultDisplayWidth(codePoint: Int): Int =
    when {
        isZeroWidth(codePoint) -> 0
        isWideChar(codePoint) || isWideEmoji(codePoint) -> 2
        codePoint > 0xFFFF -> 2
        else -> 1
    }

// Zero-width: combining diacritical marks, joiners/direction marks and variation selectors
// never advance the cursor (approximate — override via RenderOptions.displayWidthOf)
private fun isZeroWidth(codePoint: Int): Boolean =
    codePoint in 0x0300..0x036F ||
        codePoint in 0x200B..0x200F ||
        codePoint == 0x2060 ||
        codePoint in 0x20D0..0x20F0 ||
        codePoint in 0xFE00..0xFE0F ||
        codePoint == 0xFEFF ||
        codePoint in 0x1AB0..0x1AFF ||
        codePoint in 0x1DC0..0x1DFF ||
        codePoint in 0xE0100..0xE01EF

// Wide BMP ranges of East Asian Width (approximate): Jamo, radicals, kana, Ext-A, CJK, Yi,
// Hangul, compatibility ideographs, vertical forms, fullwidth letters and currency — emoji
// additions live in [isWideEmoji]
private fun isWideChar(codePoint: Int): Boolean =
    codePoint in 0x1100..0x115F ||
        codePoint in 0x2E80..0x303E ||
        codePoint in 0x3041..0x33FF ||
        codePoint in 0x3400..0x4DBF ||
        codePoint in 0x4E00..0x9FFF ||
        codePoint in 0xA000..0xA4CF ||
        codePoint in 0xAC00..0xD7A3 ||
        codePoint in 0xF900..0xFAFF ||
        codePoint in 0xFE30..0xFE4F ||
        codePoint in 0xFF00..0xFF60 ||
        codePoint in 0xFFE0..0xFFE6

// Wide BMP emoji per East Asian Width: clocks, arrows, zodiac, weather, misc symbols — the
// astral emoji planes are covered by the codePoint > 0xFFFF clause above
private fun isWideEmoji(codePoint: Int): Boolean =
    codePoint in 0x231A..0x231B ||
        codePoint in 0x2329..0x232A ||
        codePoint in 0x23E9..0x23EC ||
        codePoint == 0x23F0 ||
        codePoint == 0x23F3 ||
        codePoint in 0x25FD..0x25FE ||
        codePoint in 0x2614..0x2615 ||
        codePoint in 0x2648..0x2653 ||
        codePoint == 0x267F ||
        codePoint == 0x2693 ||
        codePoint == 0x26A1 ||
        codePoint in 0x26AA..0x26AB ||
        codePoint in 0x26BD..0x26BE ||
        codePoint in 0x26C4..0x26C5 ||
        codePoint == 0x26CE ||
        codePoint == 0x26D4 ||
        codePoint == 0x26EA ||
        codePoint in 0x26F2..0x26F3 ||
        codePoint == 0x26F5 ||
        codePoint == 0x26FA ||
        codePoint == 0x26FD ||
        codePoint == 0x2705 ||
        codePoint in 0x270A..0x270B ||
        codePoint == 0x2728 ||
        codePoint == 0x274C ||
        codePoint == 0x274E ||
        codePoint in 0x2753..0x2755 ||
        codePoint == 0x2757 ||
        codePoint in 0x2795..0x2797 ||
        codePoint == 0x27B0 ||
        codePoint == 0x27BF ||
        codePoint in 0x2B1B..0x2B1C ||
        codePoint == 0x2B50 ||
        codePoint == 0x2B55

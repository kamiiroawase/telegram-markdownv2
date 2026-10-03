package io.github.kamiiroawase.markdownv2

// The 18 special characters Telegram officially requires to escape, plus the backslash
// itself (a literal backslash must be written as \\). Shared with Deformat.kt, whose
// text-state unescaping mirrors this set
internal val SPECIAL_CHARS = "_*[]()~`>#+-=|{}.!\\".toSet()

/** Escapes all MarkdownV2 special characters in [text] so the result renders as literal text. */
internal fun escapeText(text: String): String =
    buildString(text.length + 16) {
        for (char in text) {
            if (char in SPECIAL_CHARS) {
                append('\\')
            }
            append(char)
        }
    }

// Inside code entities (pre/code) Telegram only requires escaping the backslash and
// backtick — single-pass, the same shape as escapeText
internal fun escapeCode(text: String): String =
    buildString(text.length + 16) {
        for (char in text) {
            if (char == '\\' || char == '`') append('\\')
            append(char)
        }
    }

// Inside the (...) part of link entities Telegram only requires escaping the backslash
// and ')' — single-pass, the same shape as escapeText
internal fun escapeUrl(url: String): String =
    buildString(url.length + 16) {
        for (char in url) {
            if (char == '\\' || char == ')') append('\\')
            append(char)
        }
    }

/**
 * Escapes char by char and truncates to [maxLength]; shared by all truncation fallback paths.
 * Escape sequences (\ + special char) and complete surrogate pairs advance as atomic units and
 * are never split; a lone high surrogate is not a pair — it is handled as a plain single
 * character and the char that follows escapes normally.
 */
internal fun appendEscapedTruncated(
    sb: StringBuilder,
    text: String,
    maxLength: Int,
) {
    var used = 0
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (isSurrogatePairAt(text, index)) {
            if (used + 2 > maxLength) break
            sb.append(char).append(text[index + 1])
            used += 2
            index += 2
        } else {
            val cost = if (char in SPECIAL_CHARS) 2 else 1
            if (used + cost > maxLength) break
            if (char in SPECIAL_CHARS) sb.append('\\')
            sb.append(char)
            used += cost
            index++
        }
    }
}

/**
 * Escapes char by char and cuts into pieces of at most [limit] chars — the chunking
 * counterpart of [appendEscapedTruncated]: instead of stopping at the budget, it keeps
 * going, so no character is lost. Escape sequences (\ + special char) and complete surrogate
 * pairs advance as atomic units and are never split; [limit] must be at least 2 (the cost of
 * one escaped special character), which guarantees every piece makes progress. The
 * concatenation of all pieces equals escapeText(text).
 */
internal fun escapeChunks(
    text: String,
    limit: Int,
): List<String> {
    // Empty text is one empty piece, not zero: callers take escaped.first()/last()
    // unconditionally (appendVerbatimChunks, flattenWithLead), and listOf("") keeps the
    // concatenation invariant — "" joins to ""
    if (text.isEmpty()) return listOf("")
    val chunks = mutableListOf<String>()
    val sb = StringBuilder()
    var index = 0
    while (index < text.length) {
        if (isSurrogatePairAt(text, index)) {
            if (sb.length + 2 > limit) {
                chunks += sb.toString()
                sb.clear()
            }
            sb.append(text[index]).append(text[index + 1])
            index += 2
        } else {
            val char = text[index]
            val cost = if (char in SPECIAL_CHARS) 2 else 1
            if (sb.length + cost > limit) {
                chunks += sb.toString()
                sb.clear()
            }
            if (char in SPECIAL_CHARS) sb.append('\\')
            sb.append(char)
            index++
        }
    }
    if (sb.isNotEmpty()) chunks += sb.toString()
    return chunks
}

/**
 * Splits already-escaped MarkdownV2 ([escapeText] or [escapeCode] output, where a backslash
 * can only start an escape sequence) into pieces of at most [limit] chars without splitting
 * escape sequences or surrogate pairs. [limit] must be at least 2; the caller is responsible
 * for guaranteeing that.
 */
internal fun splitEscapedUnits(
    text: String,
    limit: Int,
): List<String> {
    val pieces = mutableListOf<String>()
    var start = 0
    var end = 0
    while (end < text.length) {
        val unit =
            if (text[end] == '\\' && end + 1 < text.length) {
                2
            } else if (isSurrogatePairAt(text, end)) {
                2
            } else {
                1
            }
        // end == start forces progress even for a pathological limit below the unit cost;
        // callers pass a limit of at least 2 so this cannot produce an over-long piece
        if (end > start && end - start + unit > limit) {
            pieces += text.substring(start, end)
            start = end
        }
        end += unit
    }
    if (end > start) pieces += text.substring(start, end)
    return pieces
}

internal fun isHighSurrogate(char: Char): Boolean = char.code in 0xD800..0xDBFF

internal fun isLowSurrogate(char: Char): Boolean = char.code in 0xDC00..0xDFFF

/** Whether [index] holds a complete surrogate pair (a lone high surrogate does not count). */
internal fun isSurrogatePairAt(
    text: String,
    index: Int,
): Boolean = isHighSurrogate(text[index]) && index + 1 < text.length && isLowSurrogate(text[index + 1])

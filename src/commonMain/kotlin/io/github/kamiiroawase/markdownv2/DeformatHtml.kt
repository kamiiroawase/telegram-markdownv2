package io.github.kamiiroawase.markdownv2

/**
 * Converts rendered Telegram HTML — a TelegramHtml render result or one renderChunked
 * piece — back into the plain text Telegram would display: the HTML twin of [deformat].
 * Tags drop (a code-family or emphasis tag contributes nothing visible), entity
 * references decode, and an anchor's href survives as `label (url)` — the same
 * no-target-loss trade the MarkdownV2 deformat and the plain-text extraction make.
 *
 * The input is expected to be this library's output; arbitrary strings degrade
 * best-effort but losslessly — a '<' with no closing '>' stays literal, an unrecognized
 * entity reference stays as written, an anchor that never closes flushes its href at
 * end of input — and the call never throws.
 */
internal fun deformatHtml(rendered: String): String {
    if ('<' !in rendered && '&' !in rendered) return rendered
    val sb = StringBuilder(rendered.length)
    // Open anchors' hrefs, innermost last; the " (url)" tail appends when the matching
    // close strips, so a link target is never silently dropped
    val openUrls = ArrayDeque<String>()
    var index = 0
    while (index < rendered.length) {
        val char = rendered[index]
        if (char == '<') {
            val end = rendered.indexOf('>', index + 1)
            // Attribute values in this library's output escape '>' (escapeHtmlAttribute),
            // so the first '>' really ends the tag; an unterminated '<' is not ours —
            // keep it literal
            if (end == -1) {
                sb.append(char)
                index++
                continue
            }
            appendTagText(sb, openUrls, rendered.substring(index + 1, end))
            index = end + 1
        } else {
            sb.append(char)
            index++
        }
    }
    // An anchor that never closed still owns its href: flush the remaining stack here,
    // innermost first — the order a run of matching closes would take — so the
    // no-target-loss trade holds on unterminated shapes too (the MarkdownV2 twin's
    // degradation keeps the target the same way: "[a](u" stays "[a](u")
    while (openUrls.isNotEmpty()) {
        val url = openUrls.removeLast()
        if (url.isNotEmpty()) {
            sb.append(" (").append(url).append(')')
        }
    }
    return decodeHtmlEntities(sb.toString())
}

// Strips one tag's inner text (between the angle brackets), recording anchor hrefs and
// emitting link tails on their closes
private fun appendTagText(
    sb: StringBuilder,
    openUrls: ArrayDeque<String>,
    inner: String,
) {
    val parts =
        parseHtmlTag("<$inner>") ?: run {
            // Not a well-formed tag after all — emit it literally; the caller's scan
            // consumed both angle brackets, so both return here
            sb.append('<').append(inner).append('>')
            return
        }
    if (parts.name == "a") {
        if (parts.closing) {
            val url = openUrls.removeLastOrNull()
            if (!url.isNullOrEmpty()) {
                sb.append(" (").append(url).append(')')
            }
        } else {
            // The raw, still-encoded attribute value: this library's output escaped the
            // href with escapeHtmlAttribute, and the decodeHtmlEntities pass at the end
            // of deformatHtml reverses exactly that — one decode in total. extractHref
            // would decode here already, and the final pass would decode the URL a
            // second time, mangling a href that literally contains an entity-shaped
            // run ("&amp;" in the Markdown source's link destination stays "&amp;")
            openUrls.addLast(extractAttribute(parts.attrs, "href")?.takeIf { it.isNotEmpty() }.orEmpty())
        }
    }
    // Every other tag carries no visible text of its own
}

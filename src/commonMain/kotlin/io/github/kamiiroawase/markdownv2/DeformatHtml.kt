package io.github.kamiiroawase.markdownv2

/**
 * Converts rendered Telegram HTML — a TelegramHtml render result or one renderChunked
 * piece — back into the plain text Telegram would display: the HTML twin of [deformat].
 * Tags drop (a code-family or emphasis tag contributes nothing visible), entity
 * references decode, and an anchor's href survives as `label (url)` — the same
 * no-target-loss trade the MarkdownV2 deformat and the plain-text extraction make —
 * save that a label equal to its URL (an autolink's render) stays the single URL,
 * the MarkdownV2 twin's own rule.
 *
 * The input is expected to be this library's output; arbitrary strings degrade
 * best-effort but losslessly — a '<' with no closing '>' stays literal, an unrecognized
 * entity reference stays as written, an anchor that never closes flushes its href at
 * end of input — and the call never throws.
 */
internal fun deformatHtml(rendered: String): String {
    if ('<' !in rendered && '&' !in rendered) return rendered
    val sb = StringBuilder(rendered.length)
    // Open anchors' hrefs and label starts, innermost last; the " (url)" tail appends
    // when the matching close strips, so a link target is never silently dropped
    val openHrefs = ArrayDeque<OpenHref>()
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
            appendTagText(sb, openHrefs, rendered.substring(index + 1, end))
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
    while (openHrefs.isNotEmpty()) {
        appendHrefTail(sb, openHrefs.removeLast())
    }
    return decodeHtmlEntities(sb.toString())
}

// One open anchor: its (still-encoded) href and where its label began in the output
private class OpenHref(
    val url: String,
    val labelStart: Int,
)

// The " (url)" tail, with the autolink carve-out the MarkdownV2 twin makes: a label
// equal to its URL already shows the URL — the tail would duplicate it. The comparison
// decodes the href first: the label region carries the text encoding (a '"' stays raw)
// while the href carries the attribute encoding ("&quot;"), and comparing the encoded
// spellings never matched a URL that holds a quote — the tail duplicated it where the
// MarkdownV2 twin and appendPlainText keep the single URL
private fun appendHrefTail(
    sb: StringBuilder,
    href: OpenHref,
) {
    if (href.url.isNotEmpty() && sb.substring(href.labelStart) != decodeHtmlEntities(href.url)) {
        sb.append(" (").append(href.url).append(')')
    }
}

// Strips one tag's inner text (between the angle brackets), recording anchor hrefs and
// emitting link tails on their closes
private fun appendTagText(
    sb: StringBuilder,
    openHrefs: ArrayDeque<OpenHref>,
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
            val href = openHrefs.removeLastOrNull()
            if (href != null) {
                appendHrefTail(sb, href)
            }
        } else {
            // The raw, still-encoded attribute value: this library's output escaped the
            // href with escapeHtmlAttribute, and the decodeHtmlEntities pass at the end
            // of deformatHtml reverses exactly that — one decode in total. extractHref
            // would decode here already, and the final pass would decode the URL a
            // second time, mangling a href that literally contains an entity-shaped
            // run ("&amp;" in the Markdown source's link destination stays "&amp;")
            openHrefs.addLast(
                OpenHref(extractAttribute(parts.attrs, "href")?.takeIf { it.isNotEmpty() }.orEmpty(), sb.length),
            )
        }
    }
    // Every other tag carries no visible text of its own
}

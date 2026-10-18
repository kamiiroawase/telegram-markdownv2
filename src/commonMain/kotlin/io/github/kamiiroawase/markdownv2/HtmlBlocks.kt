package io.github.kamiiroawase.markdownv2

import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList

// The HTML twin of Blocks.kt: the block-level kernel shared by the HTML truncation
// (HtmlTruncation.kt) and chunking (HtmlChunking.kt) pipelines. The block-shape
// classification (isFenceWrappedBlock) and applyPrefix are the very functions the
// MarkdownV2 kernel exports — a block's kind and a list item's line shape do not depend
// on the output format; only the strings a shape renders to do, so they live here beside
// their consumers and the two HTML pipelines cannot drift about them.

/**
 * Renders one block node through a fresh HtmlVisitor; nesting-depth capping happens
 * inside the visitor itself (renderChild for structure, enterInline for inline emphasis).
 */
internal fun renderHtmlBlock(
    node: Node,
    depth: Int,
    options: RenderOptions,
): String {
    val visitor = HtmlVisitor(depth, options)
    node.accept(visitor)
    return visitor.output().trimEnd('\n')
}

// A fresh visitor per group: tags left unclosed within the group are completed by its
// own output(), never leaking into the next group
internal fun renderHtmlInlineGroup(
    nodes: List<Node>,
    options: RenderOptions,
): String {
    val visitor = HtmlVisitor(options = options)
    nodes.forEach { it.accept(visitor) }
    return visitor.output()
}

// The open and close tag strings a fence-wrapped block renders to: a fenced code block
// with a language carries it in the <code> wrapper's class attribute (the one attribute
// Telegram's <pre> honors, for syntax highlighting); every other fence-wrapped kind —
// language-less fences, indented code, tables, HTML blocks — degrades to a bare <pre>.
// The rendered shape is always open + content + close with no separators in between, so
// the truncation and chunking surgery below can strip both ends exactly
internal fun htmlFenceOf(node: Node): Pair<String, String> {
    if (node is FencedCodeBlock) {
        val language = htmlLanguageOf(node)
        if (language.isNotEmpty()) {
            return "<pre><code class=\"language-$language\">" to "</code></pre>"
        }
    }
    return "<pre>" to "</pre>"
}

// The info string is untrusted: keep only letters/digits/-/+/# so it can neither leave
// the double-quoted attribute (quotes, '<', '>' and '&' are absent by construction) nor
// inject markup — the same filter the MarkdownV2 fence applies to its own fence line
internal fun htmlLanguageOf(fencedCodeBlock: FencedCodeBlock): String =
    fencedCodeBlock.info
        .orEmpty()
        .trim()
        .filter { it.isLetterOrDigit() || it == '-' || it == '+' || it == '#' }
        .take(32)

// Telegram HTML has no heading entity either: the level spells out as literal hashes
// gluing onto the heading text — the full render, truncation and chunking all prefix
// with this
internal fun htmlHeadingPrefixOf(heading: Heading): String = "#".repeat(heading.level) + " "

// The per-item marker factory for a list: bullets get "• ", ordered lists "N. " with N
// advancing per item from the list's own start number — the dot needs no escaping in HTML
// text (unlike MarkdownV2's). A fresh factory per list; consumers must invoke it exactly
// once per item, blank-bodied ones included, so ordered numbering matches the full
// render's
internal fun htmlListMarkerOf(list: Node): () -> String {
    val ordered = list as? OrderedList ?: return { "• " }
    var number = ordered.markerStartNumber ?: 1
    return { "${number++}. " }
}

/**
 * Walks a list's items the way every HTML rendering path must: non-ListItem children
 * skip, the marker is taken — its number consumed — for every item including ones whose
 * body drops out, and a body that renders to nothing but whitespace is no-content and
 * skips too (HtmlVisitor.renderList makes the same walk, so the full render emits exactly
 * the items this walk visits). [visit] receives each surviving item in document order
 * with its marker and pre-rendered body; returning false stops the walk (truncation drops
 * the items that follow whole — chunking never stops and always returns true).
 */
internal fun forEachHtmlListItem(
    list: Node,
    depth: Int,
    options: RenderOptions,
    visit: (item: ListItem, prefix: String, body: String) -> Boolean,
) {
    val marker = htmlListMarkerOf(list)
    var item = list.firstChild
    while (item != null) {
        if (item is ListItem) {
            val prefix = marker()
            val body = renderHtmlBlock(item, depth + 1, options)
            if (body.isNotBlank() && !visit(item, prefix, body)) break
        }
        item = item.next
    }
}

// The quote wrapper strings of the HTML pipeline — the line-prefix grammar the
// MarkdownV2 quote satisfies has no HTML counterpart, a wrapper tag pair replaces it
internal const val HTML_QUOTE_OPEN = "<blockquote>"
internal const val HTML_QUOTE_CLOSE = "</blockquote>"

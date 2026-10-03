package io.github.kamiiroawase.markdownv2

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList

// The block-level kernel shared by the truncation (Truncation.kt) and chunking
// (Chunking.kt) pipelines: single-block rendering, the block-shape classification both
// dispatch on, the list marker, and the list-item walk. The two pipelines differ in what
// happens at a budget boundary — truncation drops the tail, chunking splits along a seam
// — but they must not differ in how a block is classified or how a list's items are
// walked; defining that knowledge once here is what keeps the two sides from drifting
// apart (a blank-bodied list item, say, must skip on both sides, and its marker must
// consume its number on both, or the truncated prefix and the chunk numbering diverge
// from the full render's).

/**
 * Renders one block node through a fresh Visitor; nesting-depth capping happens inside
 * the visitor itself (renderChild for structure, enterInline for inline emphasis).
 */
internal fun renderBlock(
    node: Node,
    depth: Int,
    options: RenderOptions,
): String {
    val visitor = Visitor(depth, options)
    node.accept(visitor)
    return visitor.output().trimEnd('\n')
}

// A fresh visitor per group: entities left unclosed within the group are completed by its
// own output(), never leaking into the next group
internal fun renderInlineGroup(
    nodes: List<Node>,
    options: RenderOptions,
): String {
    val visitor = Visitor(options = options)
    nodes.forEach { it.accept(visitor) }
    return visitor.output()
}

// The four block kinds whose renderBlock output is always the fence-wrapped shape
// "open\n…\nclose": fenced/indented code blocks, GFM tables and HTML blocks. Truncation
// keeps the fence lines that fit; chunking splits along the enclosed lines; the
// classification itself is shared so the two cannot disagree about which blocks are
// fence-wrapped
internal fun Node.isFenceWrappedBlock(): Boolean =
    this is FencedCodeBlock || this is IndentedCodeBlock || this is TableBlock || this is HtmlBlock

// MarkdownV2 has no heading entity: the level spells out as escaped hashes gluing onto
// the heading text — the full render, truncation and chunking all prefix with this
internal fun headingPrefixOf(heading: Heading): String = escapeText("#".repeat(heading.level)) + " "

// The per-item marker factory for a list: bullets get "• ", ordered lists "N\. " with N
// advancing per item (the dot escapes — it is MarkdownV2-special) from the list's own
// start number. A fresh factory per list; consumers must invoke it exactly once per
// item, blank-bodied ones included, so ordered numbering matches the full render's
internal fun listMarkerOf(list: Node): () -> String {
    val ordered = list as? OrderedList ?: return { "• " }
    var number = ordered.markerStartNumber ?: 1
    return { "${number++}\\. " }
}

/**
 * Walks a list's items the way both budget-aware pipelines must: non-ListItem children
 * skip, the marker is taken — its number consumed — for every item including ones whose
 * body drops out, and a body that renders to nothing but whitespace is no-content and
 * skips too. [visit] receives each surviving item in document order with its marker and
 * pre-rendered body; returning false stops the walk (truncation drops the items that
 * follow whole — chunking never stops and always returns true).
 */
internal fun forEachListItem(
    list: Node,
    depth: Int,
    options: RenderOptions,
    visit: (item: ListItem, prefix: String, body: String) -> Boolean,
) {
    val marker = listMarkerOf(list)
    var item = list.firstChild
    while (item != null) {
        if (item is ListItem) {
            val prefix = marker()
            val body = renderBlock(item, depth + 1, options)
            if (body.isNotBlank() && !visit(item, prefix, body)) break
        }
        item = item.next
    }
}

// Blank lines stay bare — indenting them would only add trailing whitespace
internal fun applyPrefix(
    prefix: String,
    body: String,
): String {
    val indent = " ".repeat(prefix.length)
    return body
        .lines()
        .mapIndexed { index, line ->
            when {
                index == 0 -> prefix + line
                line.isEmpty() -> line
                else -> indent + line
            }
        }.joinToString("\n")
}

// Blank lines keep a bare ">": an empty line would terminate the quote entity
internal fun prefixQuote(block: String): String = block.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }

package com.github.kamiiroawase.markdownv2

import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak

/**
 * Structure-preserving truncation of over-length content: starting from [renderBlocks],
 * blocks that fit are kept whole, blocks that do not are shrunk by type (whole lines /
 * whole items / fence closing); the output is always valid MarkdownV2. When no structural
 * content fits at all, the caller falls back to escaped plain text.
 */
internal fun renderBlocks(
    first: Node?,
    budget: Int,
    depth: Int,
    retriable: Boolean,
    options: RenderOptions,
): String {
    if (budget <= 0) return ""

    val parts = StringBuilder()
    var node = first
    while (node != null) {
        val separator = if (parts.isEmpty()) "" else "\n\n"
        val remaining = budget - parts.length - separator.length
        if (remaining <= 0) break

        val block = renderBlock(node, depth, options)
        if (block.length <= remaining) {
            parts.append(separator).append(block)
            node = node.next
        } else {
            val shrunk = shrink(node, remaining, depth, retriable, options)
            if (shrunk.isNotEmpty()) {
                parts.append(separator).append(shrunk)
            }
            break
        }
    }
    return parts.toString()
}

// Recursion on the truncation path is structurally bounded: retriable is true only at the
// top level; a nested level that does not fit degrades to iterative plain text and goes no
// deeper. Stack safety for deep ASTs is guaranteed by the depth caps in Visitor.renderChild
// and enterInline, so no extra depth guard is needed here. The block-shape classification
// (fence-wrapped kinds, lists, headings) is shared with the chunking pipeline — see
// Blocks.kt
private fun shrink(
    node: Node,
    budget: Int,
    depth: Int,
    retriable: Boolean,
    options: RenderOptions,
): String =
    when {
        node.isFenceWrappedBlock() -> {
            shrinkRenderedCode(renderBlock(node, depth, options), budget)
        }

        node is BulletList || node is OrderedList -> {
            shrinkList(node, budget, depth, retriable, options)
        }

        node is BlockQuote -> {
            shrinkQuote(node, budget, depth, retriable, options)
        }

        node is Heading -> {
            shrinkParagraph(node, budget, options, headingPrefixOf(node))
        }

        node is Paragraph -> {
            shrinkParagraph(node, budget, options, "")
        }

        else -> {
            ""
        }
    }

private fun shrinkRenderedCode(
    rendered: String,
    budget: Int,
): String {
    // The rendered output of the four block kinds above (fenced/indented code blocks,
    // tables, HTML blocks) is always "```\n…\n```": the first and last lines are fences
    // by construction, no further validation needed
    // Defensive: an empty render (comment-only HtmlBlock, row-less TableBlock) cannot
    // reach shrink — renderBlocks shrinks only blocks that did not fit, and empty blocks
    // always fit — but the invariant lives in the caller; keep this function safe on its
    // own ("".lines() is a single line, the subList below needs at least two)
    if (rendered.isEmpty()) return ""
    val lines = rendered.lines()
    val open = lines.first()
    val close = lines.last()
    if (open.length + close.length + 1 > budget) return ""

    val sb = StringBuilder(open)
    for (line in lines.subList(1, lines.size - 1)) {
        if (sb.length + line.length + close.length + 1 > budget) break
        sb.append('\n').append(line)
    }
    return sb.append('\n').append(close).toString()
}

// Truncation stops at the first item that does not fit — later items are dropped whole,
// keeping the surviving prefix's numbering contiguous. The walk itself (marker
// consumption, blank-body skip) is forEachListItem, shared with the chunking side
private fun shrinkList(
    list: Node,
    budget: Int,
    depth: Int,
    retriable: Boolean,
    options: RenderOptions,
): String {
    val items = StringBuilder()
    forEachListItem(list, depth, options) { item, prefix, body ->
        val separator = if (items.isEmpty()) "" else "\n"
        val remaining = budget - items.length - separator.length
        val content = fitListItem(item, body, prefix, remaining, depth, retriable, options)
        if (content == null) {
            false
        } else {
            items.append(separator).append(content)
            true
        }
    }
    return items.toString()
}

/**
 * Fits the item's pre-rendered, known-non-blank [body] into [remaining] by its actual
 * length after prefixing (first-line marker, continuation indent); returns null when it
 * does not fit. The continuation-indent overhead added by applyPrefix is outside
 * renderBlocks' budget, so a shrunk result may still overflow it — dropping lines is not
 * a viable fallback: emphasis/link markers closed on a continuation line would be lost
 * with the line, leaving unclosed entities Telegram rejects. Instead re-shrink with the
 * budget tightened by the overflow; once the budget shrinks to a single line it always
 * fits, so the loop converges.
 *
 * Re-shrinking is allowed only at the top level: each retry re-renders the whole subtree,
 * and per-level retries in nested contexts explode exponentially (deeply nested lists can
 * hang). When a nested level ([retriable] false) does not fit, the item degrades outright
 * to escaped plain text — again without dropping lines; the output is always valid
 * MarkdownV2.
 */
private fun fitListItem(
    item: ListItem,
    body: String,
    prefix: String,
    remaining: Int,
    depth: Int,
    retriable: Boolean,
    options: RenderOptions,
): String? {
    if (prefix.length >= remaining) return null
    val full = applyPrefix(prefix, body)
    if (full.length <= remaining) return full
    if (!retriable) return plainListItem(item, prefix, remaining)

    var budget = remaining - prefix.length
    while (budget > 0) {
        val shrunk = renderBlocks(item.firstChild, budget, depth + 1, retriable = false, options).trim()
        if (shrunk.isEmpty()) return null
        val prefixed = applyPrefix(prefix, shrunk)
        if (prefixed.length <= remaining) return prefixed
        budget -= prefixed.length - remaining
    }
    return null
}

// Fallback for a nested (non-retriable) item that does not fit: keep the marker, escape
// the item's plain text into the remaining budget — valid MarkdownV2 by construction
private fun plainListItem(
    item: ListItem,
    prefix: String,
    remaining: Int,
): String {
    val plain = StringBuilder()
    appendPlainText(plain, item)
    val sb = StringBuilder(prefix)
    appendEscapedTruncated(sb, plain.toString().trim(), remaining - prefix.length)
    return sb.toString()
}

private fun shrinkQuote(
    quote: BlockQuote,
    budget: Int,
    depth: Int,
    retriable: Boolean,
    options: RenderOptions,
): String {
    val parts = StringBuilder()
    var node = quote.firstChild
    while (node != null) {
        val separator = if (parts.isEmpty()) "" else "\n"
        val remaining = budget - parts.length - separator.length
        if (remaining <= 0) break

        val prefixed = prefixQuote(renderBlock(node, depth + 1, options))
        if (prefixed.length <= remaining) {
            parts.append(separator).append(prefixed)
            node = node.next
        } else {
            val shrunk = shrinkQuoteTail(node, remaining, depth, retriable, options)
            if (shrunk.isNotEmpty()) {
                parts.append(separator).append(shrunk)
            }
            break
        }
    }
    return parts.toString()
}

/**
 * Tail blocks of a quote that do not fit: each block is shrunk until it stays ≤ [remaining]
 * after the "> " prefix is applied. The per-line +2 prefix overhead is likewise outside
 * renderBlocks' budget; on overflow, re-shrink with the budget tightened by the excess.
 * Line-dropping truncation is not allowed — emphasis/link markers closed on continuation
 * lines and code fences would be lost with the line. As with list items, re-shrinking
 * happens only at the top level (nested retries re-render exponentially); a nested level
 * that does not fit returns an empty string and the caller falls back to plain text.
 */
private fun shrinkQuoteTail(
    node: Node,
    remaining: Int,
    depth: Int,
    retriable: Boolean,
    options: RenderOptions,
): String {
    if (!retriable) return ""

    var budget = remaining
    while (budget > 0) {
        val shrunk = renderBlocks(node, budget, depth + 1, retriable = false, options).trim()
        if (shrunk.isEmpty()) return ""
        val prefixed = prefixQuote(shrunk)
        if (prefixed.length <= remaining) return prefixed
        budget -= prefixed.length - remaining
    }
    return ""
}

private fun shrinkParagraph(
    node: Node,
    budget: Int,
    options: RenderOptions,
    prefix: String,
): String {
    if (prefix.length >= budget) return ""

    val sb = StringBuilder(prefix)
    val group = mutableListOf<Node>()
    var child = node.firstChild
    while (child != null) {
        if (child is SoftLineBreak || child is HardLineBreak) {
            val fits = appendGroup(sb, group, budget, options)
            // Clear the group whether it fit or not; otherwise the tail fallback below
            // would truncate the same group a second time and append it, duplicating
            // content when only 1-2 budget characters remain
            group.clear()
            if (!fits) break
            // The break itself is droppable: when the budget cannot afford it, the next
            // group appends directly onto the current line
            if (sb.isNotEmpty() && sb.length + 1 <= budget) {
                sb.append('\n')
            }
        } else {
            group += child
        }
        child = child.next
    }
    appendGroup(sb, group, budget, options)
    return sb.toString()
}

// Appends the group's rendered line when it fits within [budget]; otherwise fills the
// remainder with escaped plain text of the same group — a partially cut rendered line
// could split an entity, fully escaped text never does
private fun appendGroup(
    sb: StringBuilder,
    group: List<Node>,
    budget: Int,
    options: RenderOptions,
): Boolean {
    if (group.isEmpty()) return true

    val line = renderInlineGroup(group, options)
    if (sb.length + line.length <= budget) {
        sb.append(line)
        return true
    }

    val remaining = budget - sb.length
    if (remaining > 0) {
        appendEscapedTruncated(sb, plainText(group), remaining)
    }
    return false
}

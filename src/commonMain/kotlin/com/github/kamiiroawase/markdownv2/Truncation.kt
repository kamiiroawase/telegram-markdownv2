package com.github.kamiiroawase.markdownv2

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.IndentedCodeBlock
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

// Depth capping is handled by Visitor.renderChild; the truncation path only calls this at
// small depths
internal fun renderBlock(
    node: Node,
    depth: Int,
    options: RenderOptions,
): String {
    val visitor = Visitor(depth, options)
    node.accept(visitor)
    return visitor.output().trimEnd('\n')
}

// Recursion on the truncation path is structurally bounded: retriable is true only at the
// top level; a nested level that does not fit degrades to iterative plain text and goes no
// deeper. Stack safety for deep ASTs is guaranteed by the depth caps in Visitor.renderChild
// and enterInline, so no extra depth guard is needed here
private fun shrink(
    node: Node,
    budget: Int,
    depth: Int,
    retriable: Boolean,
    options: RenderOptions,
): String =
    when (node) {
        is FencedCodeBlock, is IndentedCodeBlock, is TableBlock, is HtmlBlock -> {
            shrinkRenderedCode(renderBlock(node, depth, options), budget)
        }

        is BulletList -> {
            shrinkList(node, budget, depth, retriable, options) { "• " }
        }

        is OrderedList -> {
            var number = node.markerStartNumber ?: 1
            shrinkList(node, budget, depth, retriable, options) { "${number++}\\. " }
        }

        is BlockQuote -> {
            shrinkQuote(node, budget, depth, retriable, options)
        }

        is Heading -> {
            shrinkParagraph(node, budget, options, escapeText("#".repeat(node.level)) + " ")
        }

        is Paragraph -> {
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
// keeping the surviving prefix's numbering contiguous
private fun shrinkList(
    list: Node,
    budget: Int,
    depth: Int,
    retriable: Boolean,
    options: RenderOptions,
    marker: () -> String,
): String {
    val items = StringBuilder()
    var item = list.firstChild
    while (item != null) {
        if (item is ListItem) {
            val separator = if (items.isEmpty()) "" else "\n"
            val remaining = budget - items.length - separator.length
            val prefix = marker()
            val content = fitListItem(item, prefix, remaining, depth, retriable, options) ?: break
            items.append(separator).append(content)
        }
        item = item.next
    }
    return items.toString()
}

/**
 * Fits list-item content into [remaining] by its actual length after prefixing (first-line
 * marker, continuation indent); returns null when it does not fit. The continuation-indent
 * overhead added by applyPrefix is outside renderBlocks' budget, so a shrunk result may
 * still overflow it — dropping lines is not a viable fallback: emphasis/link markers closed
 * on a continuation line would be lost with the line, leaving unclosed entities Telegram
 * rejects. Instead re-shrink with the budget tightened by the overflow; once the budget
 * shrinks to a single line it always fits, so the loop converges.
 *
 * Re-shrinking is allowed only at the top level: each retry re-renders the whole subtree,
 * and per-level retries in nested contexts explode exponentially (deeply nested lists can
 * hang). When a nested level ([retriable] false) does not fit, the item degrades outright
 * to escaped plain text — again without dropping lines; the output is always valid
 * MarkdownV2.
 */
private fun fitListItem(
    item: ListItem,
    prefix: String,
    remaining: Int,
    depth: Int,
    retriable: Boolean,
    options: RenderOptions,
): String? {
    if (prefix.length >= remaining) return null
    val body = renderBlock(item, depth + 1, options)
    if (body.isEmpty()) return null
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

// Blank lines keep a bare ">": an empty line would terminate the quote entity
internal fun prefixQuote(block: String): String = block.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }

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

internal fun plainText(nodes: List<Node>): String {
    val sb = StringBuilder()
    nodes.forEach { appendPlainText(sb, it) }
    return sb.toString()
}

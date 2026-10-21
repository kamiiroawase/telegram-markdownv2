package io.github.kamiiroawase.markdownv2

import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.task.list.items.TaskListItemMarker
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
 * Structure-preserving truncation of over-length content into Telegram HTML: starting
 * from [renderHtmlBlocks], blocks that fit are kept whole, blocks that do not are shrunk
 * by type (whole lines / whole items / fence closing); the output is always valid
 * Telegram HTML. A block that renders to nothing skips — no separator, no budget —
 * exactly as the chunking side skips it, so the truncated prefix spaces blocks like the
 * full render does. When no structural content fits at all, the caller falls back to
 * escaped plain text.
 *
 * Unlike the MarkdownV2 pipeline there is no in-list-item degradation (an HTML
 * <blockquote> is a wrapper tag, not a line-prefix grammar, so quotes inside list items
 * truncate with their formatting intact), and quotes need no re-shrink retry: the
 * wrapper is a fixed overhead counted in the budget, where the line prefixes of the
 * MarkdownV2 quote add per-line cost that only shows up after packing.
 */
internal fun renderHtmlBlocks(
    first: Node?,
    budget: Int,
    depth: Int,
    retriable: Boolean,
    options: RenderOptions,
): String {
    if (budget <= 0) return ""

    val parts = StringBuilder()
    var node = first
    // Set by a TaskListItemMarker block: its checkbox text is the start of the item's
    // first line, not a block of its own — the next block glues directly onto it (the
    // full render emits the marker inline, see HtmlVisitor.visit(TaskListItemMarker))
    var glueNext = false
    while (node != null) {
        // The budget check precedes the render: once parts fill the budget no block can
        // be placed, and rendering the next one (arbitrarily large) just to discard it is
        // wasted work. Blank skips never touch parts, so re-running the same arithmetic
        // next iteration is equivalent
        val separator = if (parts.isEmpty() || glueNext) "" else "\n\n"
        val remaining = budget - parts.length - separator.length
        if (remaining <= 0) break

        val block = renderHtmlBlock(node, depth, options)
        // The blank-render skip: the block's own visitors emit no separator for a
        // nothing-render, and this walk must not reintroduce one on its behalf —
        // chunkHtmlBlocks makes the same skip
        if (block.isBlank()) {
            glueNext = node is TaskListItemMarker
            node = node.next
            continue
        }
        if (block.length <= remaining) {
            parts.append(separator).append(block)
            glueNext = node is TaskListItemMarker
            node = node.next
        } else {
            val shrunk = shrinkHtml(node, remaining, depth, retriable, options)
            if (shrunk.isNotEmpty()) {
                parts.append(separator).append(shrunk)
            }
            break
        }
    }
    return parts.toString()
}

/**
 * Truncates one block node on its own — the non-Document entry of the HTML render. Mirrors
 * renderHtmlBlocks' loop body for a lone block (whole when it fits, else shrink) but never
 * walks node.next: the node's siblings belong to the caller's document, the same boundary
 * chunkHtmlBlock draws on the chunking side.
 */
internal fun truncateHtmlBlock(
    node: Node,
    budget: Int,
    options: RenderOptions,
): String {
    if (budget <= 0) return ""
    val block = renderHtmlBlock(node, 0, options)
    if (block.length <= budget) return block
    return shrinkHtml(node, budget, 0, retriable = true, options)
}

// Recursion on the truncation path is structurally bounded: retriable is true only at the
// top level; a nested level that does not fit degrades to iterative plain text and goes no
// deeper. Stack safety for deep ASTs is guaranteed by the depth caps in
// HtmlVisitor.renderChild and enterInline, so no extra depth guard is needed here. The
// block-shape classification (fence-wrapped kinds, lists, headings) is the one shared
// with the MarkdownV2 pipeline (Blocks.kt) — see HtmlBlocks.kt for the HTML strings
private fun shrinkHtml(
    node: Node,
    budget: Int,
    depth: Int,
    retriable: Boolean,
    options: RenderOptions,
): String =
    when {
        node.isFenceWrappedBlock() -> {
            shrinkHtmlFence(node, budget, depth, options)
        }

        node is BulletList || node is OrderedList -> {
            shrinkHtmlList(node, budget, depth, retriable, options)
        }

        node is BlockQuote -> {
            shrinkHtmlQuote(node, budget, depth, retriable, options)
        }

        node is FootnoteDefinition -> {
            // See the MarkdownV2 twin: the label prefix glues onto the shrunken child
            // chain; the label is arbitrary source text, so the prefix escapes like the
            // visitor's own emission does
            val prefix = escapeHtmlText("[^${node.label}]: ")
            if (prefix.length >= budget) {
                ""
            } else {
                prefix + renderHtmlBlocks(node.firstChild, budget - prefix.length, depth, retriable, options)
            }
        }

        node is Heading -> {
            shrinkHtmlParagraph(node, budget, options, plainHeadingPrefixOf(node))
        }

        node is Paragraph -> {
            shrinkHtmlParagraph(node, budget, options, "")
        }

        else -> {
            ""
        }
    }

// The rendered output of the four fence-wrapped block kinds is always open + content +
// close with the tags of htmlFenceOf: content lines survive whole while they fit, the
// tags always bookend the cut so the result stays valid HTML
private fun shrinkHtmlFence(
    node: Node,
    budget: Int,
    depth: Int,
    options: RenderOptions,
): String {
    val rendered = renderHtmlBlock(node, depth, options)
    // Defensive: an empty render (comment-only HtmlBlock, row-less TableBlock) cannot
    // reach shrink — renderHtmlBlocks shrinks only blocks that did not fit, and empty
    // blocks always fit — but the invariant lives in the caller; keep this function safe
    // on its own
    if (rendered.isEmpty()) return ""
    val (open, close) = htmlFenceOf(node)
    val lines = rendered.substring(open.length, rendered.length - close.length).split('\n')
    // A fenced block that names a language carries it in the open tag's class attribute;
    // when the budget cannot hold that tag beside any content, the language yields
    // first — a bare <pre> keeps the pre structure (the MarkdownV2 twin's 3-char markers
    // keep their fence at far smaller budgets, and the entities twin loses nothing: its
    // language rides on the range, never the text) where the whole block used to fall
    // through to the caller's plain-text fallback
    val (effOpen, effClose) =
        if (open.length > "<pre>".length && open.length + close.length + 1 > budget) {
            "<pre>" to "</pre>"
        } else {
            open to close
        }
    if (effOpen.length + effClose.length + 1 > budget) return ""

    val sb = StringBuilder(effOpen)
    // A blank first line appends nothing yet still counts as placed: comparing against
    // the open tag's length cannot tell "no line yet" from "an empty line just glued",
    // and the newline joining the NEXT line would be lost
    var hasLine = false
    for (line in lines) {
        // The first content line glues directly onto the open tag (the full render emits
        // no separator there); every later one carries the '\n' that joined it
        val joiner = if (hasLine) "\n" else ""
        if (sb.length + joiner.length + line.length + effClose.length > budget) break
        sb.append(joiner).append(line)
        hasLine = true
    }
    // Only blank lines placed is no content at all: the empty tag pair is not a
    // truncated prefix of this block — "" lets the caller fall back to plain text (the
    // MarkdownV2 and entities twins make the same drop)
    if (sb.length == effOpen.length) return ""
    return sb.append(effClose).toString()
}

// Truncation stops at the first item that does not fit — later items are dropped whole,
// keeping the surviving prefix's numbering contiguous. The walk itself (marker
// consumption, blank-body skip) is forEachHtmlListItem, shared with the chunking side
private fun shrinkHtmlList(
    list: Node,
    budget: Int,
    depth: Int,
    retriable: Boolean,
    options: RenderOptions,
): String {
    val items = StringBuilder()
    forEachHtmlListItem(list, depth, options) { item, prefix, body ->
        val separator = if (items.isEmpty()) "" else "\n"
        val remaining = budget - items.length - separator.length
        val content = fitHtmlListItem(item, body, prefix, remaining, depth, retriable, options)
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
 * renderHtmlBlocks' budget, so a shrunk result may still overflow it — dropping lines is
 * not a viable fallback: tags closed on a continuation line would be lost with the line,
 * leaving unbalanced HTML. Instead re-shrink with the budget tightened by the overflow;
 * once the budget shrinks to a single line it always fits, so the loop converges.
 *
 * Re-shrinking is allowed only at the top level: each retry re-renders the whole subtree,
 * and per-level retries in nested contexts explode exponentially (deeply nested lists can
 * hang). When a nested level ([retriable] false) does not fit, the item degrades outright
 * to escaped plain text — again without dropping lines; the output is always valid HTML.
 */
private fun fitHtmlListItem(
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
    if (!retriable) return plainHtmlListItem(item, prefix, remaining)

    var budget = remaining - prefix.length
    while (budget > 0) {
        val shrunk =
            renderHtmlBlocks(item.firstChild, budget, depth + 1, retriable = false, options).trim()
        if (shrunk.isEmpty()) return null
        val prefixed = applyPrefix(prefix, shrunk)
        if (prefixed.length <= remaining) return prefixed
        budget -= prefixed.length - remaining
    }
    return null
}

// Fallback for a nested (non-retriable) item that does not fit: keep the marker, escape
// the item's plain text into the remaining budget — valid HTML by construction
private fun plainHtmlListItem(
    item: ListItem,
    prefix: String,
    remaining: Int,
): String {
    val plain = StringBuilder()
    appendPlainText(plain, item)
    val sb = StringBuilder(prefix)
    appendHtmlEscapedTruncated(sb, plain.toString().trim(), remaining - prefix.length)
    return sb.toString()
}

// The flattened child chain of a block quote, shared by this pipeline's shrinkHtmlQuote
// and the chunking side's chunkHtmlQuote: nested quote children splice their own children
// into the chain — the flattening visit(blockQuote)/appendQuoteChildren makes in the full
// render — because Bot API formatting rules say blockquote entities can't nest, and a cut
// that wrapped a child quote's own <blockquote> inside this level's wrapper would ship
// exactly that forbidden nesting. A nested quote at or beyond the render depth cap stays
// a BlockQuote entry for the consumer to degrade to escaped plain text (the visitor's own
// cap fallback, see MAX_RENDER_DEPTH); the walk itself recurses at most MAX_RENDER_DEPTH
// levels for the same cap
internal fun flattenedQuoteChildren(
    quote: Node,
    depth: Int,
): List<Node> {
    val flat = mutableListOf<Node>()

    fun walk(
        blockQuote: Node,
        level: Int,
    ) {
        var child = blockQuote.firstChild
        while (child != null) {
            if (child is BlockQuote && depth + level + 1 < MAX_RENDER_DEPTH) {
                walk(child, level + 1)
            } else {
                flat += child
            }
            child = child.next
        }
    }

    walk(quote, 0)
    return flat
}

// The quote wraps its surviving children in one <blockquote> pair. The wrapper is a
// fixed overhead counted up front — no per-line cost outside the budget, so unlike the
// MarkdownV2 shrinkQuoteTail no re-shrink retry exists here: the arithmetic is exact the
// first time. Children walk the flattened chain (see flattenedQuoteChildren) — a nested
// quote never brings its own wrapper into this one
private fun shrinkHtmlQuote(
    quote: BlockQuote,
    budget: Int,
    depth: Int,
    retriable: Boolean,
    options: RenderOptions,
): String {
    val (quoteOpen, quoteClose) = htmlQuoteTagsOf(quote)
    val innerBudget = budget - quoteOpen.length - quoteClose.length
    if (innerBudget <= 0) return ""

    val parts = StringBuilder()
    for (node in flattenedQuoteChildren(quote, depth)) {
        // See renderHtmlBlocks: check the budget before rendering the child
        val separator = if (parts.isEmpty()) "" else "\n\n"
        val remaining = innerBudget - parts.length - separator.length
        if (remaining <= 0) break

        if (node is BlockQuote) {
            // A nested quote the flattening left in place sits at the render depth cap:
            // its subtree is escaped plain text truncated to the room left (the
            // visitor's own cap fallback), never a second wrapper
            val tail = plainHtmlQuotedTail(node, remaining)
            if (tail.isNotEmpty()) parts.append(separator).append(tail)
            continue
        }
        val rendered = renderHtmlBlock(node, depth + 1, options)
        // A blank-rendering child is no-content and skips — exactly as visit(blockQuote)
        // and chunkHtmlQuote skip it. The blank line between quote paragraphs belongs to
        // the "\n\n" join between the surviving pieces, never to a child
        if (rendered.isBlank()) continue
        if (rendered.length <= remaining) {
            parts.append(separator).append(rendered)
        } else {
            val shrunk = shrinkHtml(node, remaining, depth + 1, retriable = false, options)
            // A nested level too small to shrink at all degrades to escaped plain text —
            // the same trade plainHtmlListItem makes for a nested list item; returning
            // empty here dropped the whole quote, and with it content that still fit
            val kept = shrunk.ifEmpty { plainHtmlQuotedTail(node, remaining) }
            if (kept.isNotEmpty()) {
                parts.append(separator).append(kept)
            }
            break
        }
    }
    if (parts.isEmpty()) return ""
    return "$quoteOpen$parts$quoteClose"
}

// Fallback for a nested (non-retriable) quote child that does not fit: the child's
// escaped plain text, kept as the quote's content
private fun plainHtmlQuotedTail(
    node: Node,
    remaining: Int,
): String {
    val plain = StringBuilder()
    appendPlainText(plain, node)
    val sb = StringBuilder()
    appendHtmlEscapedTruncated(sb, plain.toString().trim(), remaining)
    return sb.toString()
}

private fun shrinkHtmlParagraph(
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
            val fits = appendHtmlGroup(sb, group, budget, options)
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
    appendHtmlGroup(sb, group, budget, options)
    return sb.toString()
}

// Appends the group's rendered line when it fits within [budget]; otherwise fills the
// remainder with escaped plain text of the same group — a partially cut rendered line
// could split a tag or entity, fully escaped text never does
private fun appendHtmlGroup(
    sb: StringBuilder,
    group: List<Node>,
    budget: Int,
    options: RenderOptions,
): Boolean {
    if (group.isEmpty()) return true

    val line = renderHtmlInlineGroup(group, options)
    if (sb.length + line.length <= budget) {
        sb.append(line)
        return true
    }

    val remaining = budget - sb.length
    if (remaining > 0) {
        appendHtmlEscapedTruncated(sb, plainText(group), remaining)
    }
    return false
}

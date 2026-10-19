package io.github.kamiiroawase.markdownv2

import org.commonmark.node.BlockQuote
import org.commonmark.node.CustomNode
import org.commonmark.node.HtmlBlock
import org.commonmark.node.Node
import org.commonmark.parser.Parser

/**
 * The inert marker the expandable-quote rewrite plants as the quote's first child. It
 * renders to nothing on every path (the unknown-node traversal visits its children, of
 * which it has none), so the blank-skip machinery every quote walk already makes drops
 * it from the output; only the wrapper-emission sites read it, through
 * [isExpandableQuote].
 *
 * A CustomNode subclass rather than a BlockQuote subclass: the port's node classes are
 * final, but CustomNode is an open abstract base — and the quote machinery keys on the
 * BlockQuote node type either way, so the mark must ride inside a real quote, not be one.
 */
internal class ExpandableQuoteMarker : CustomNode()

/**
 * Whether [quote] is an expandable quote (an `<blockquote expandable>` HTML block the
 * parse-rewrite turned into a quote; see [rewriteExpandableHtmlQuotes]). The marker sits
 * as the first child and nothing moves it — the quote walks never mutate the AST — so
 * the position check is exact. Quotes that flatten (a nested quote child splices into
 * this level's wrapper) keep the OUTER level's form: the marker of a flattened inner
 * level lands mid-children and does not apply — the outermost quote's own spelling wins,
 * the same "the wrapper is this level's" rule the flattening itself lives by.
 */
internal fun isExpandableQuote(quote: Node): Boolean = quote.firstChild is ExpandableQuoteMarker

// The entity type a quote range takes: the expandable spelling its own wire type, the
// blockquoteTypeOf call sites being the entities pipeline's every range emission
internal fun blockquoteTypeOf(quote: Node): MessageEntityType =
    if (isExpandableQuote(quote)) MessageEntityType.EXPANDABLE_BLOCKQUOTE else MessageEntityType.BLOCKQUOTE

// The MarkdownV2 shape of an expandable quote: the official grammar marks ONLY the first
// line ("**>", the two asterisks reading as the empty bold entity that separates an
// expandable quote from whatever quote precedes it), continuation lines keep their
// ordinary ">" markers — so the whole quote's render simply carries "**" in front of its
// first character, every quote line starting with '>' (spaced content, a glued nesting
// run or the bare separator marker alike). The optional "||" expandability spoiler the
// documentation offers is deliberately not emitted: without it the quote renders fully
// expanded, the conservative reading
internal fun expandableQuoteMarkerOf(quote: Node): String = if (isExpandableQuote(quote)) "**" else ""

/** The shape one qualifying HTML block carries. */
internal class HtmlBlockQuote(
    val expandable: Boolean,
    val content: String,
)

// A whole-line `<blockquote>` or `<blockquote expandable>` open tag: the name is
// case-insensitive like every HTML tag, the expandable flag is the bare attribute
// spelled with plain spaces or tabs ahead of it (a tag split across lines is no tag —
// the attribute may not ride a newline), and nothing else may ride along — any other
// attribute keeps the block on the ordinary pre-degradation path
private val HTML_QUOTE_OPEN_TAG = Regex("^<blockquote([ \\t]+expandable)?[ \\t]*>", RegexOption.IGNORE_CASE)

/**
 * Recognizes the one HTML-block spelling whose block meaning the library maps instead of
 * degrading: `<blockquote>…</blockquote>` and `<blockquote expandable>…</blockquote>`
 * covering the whole block. CommonMark hands such lines to the renderer as one raw
 * HtmlBlock (blockquote is a type-6 tag: the block runs to the blank line, so the
 * content cannot contain one); the closing tag must be the last thing on the last line —
 * anything after it, or a missing close, disqualifies the block and it degrades to the
 * escaped `<pre>` every other block-level input HTML takes.
 */
internal fun htmlBlockQuoteOf(literal: String): HtmlBlockQuote? {
    val trimmed = literal.trim()
    val open = HTML_QUOTE_OPEN_TAG.find(trimmed) ?: return null
    if (!trimmed.endsWith(CLOSE_TAG, ignoreCase = true)) return null
    val body = trimmed.substring(open.value.length, trimmed.length - CLOSE_TAG.length)
    return HtmlBlockQuote(expandable = open.groups[1] != null, content = body)
}

private const val CLOSE_TAG = "</blockquote>"

/**
 * Rewrites every qualifying HTML block under [root] into a real BlockQuote — the node
 * type every quote path in all three pipelines already walks (full render, truncation
 * shrink, chunk seams; an HtmlBlock would forever ride the fence-wrapped pre path).
 * Inner content re-parses with the very [parser] that produced the document, so the
 * quote's children are exactly what the same source would have parsed to inline — and a
 * nested `<blockquote>` spelling inside rewrites too, the walk descending into the
 * replacement it just built. The rewrite runs only on trees the library parsed itself
 * (see parseForRender): a tree handed to the render(document) overloads is the caller's
 * AST, spelling and all.
 */
internal fun rewriteExpandableHtmlQuotes(
    root: Node,
    parser: Parser,
    depth: Int = 0,
) {
    var node = root.firstChild
    while (node != null) {
        val next = node.next
        val quote = quoteHtmlBlockOf(node, parser, depth)
        if (quote != null) {
            node.insertAfter(quote)
            node.unlink()
            rewriteExpandableHtmlQuotes(quote, parser, depth + 1)
        } else {
            rewriteExpandableHtmlQuotes(node, parser, depth)
        }
        node = next
    }
}

// The HtmlBlock → BlockQuote conversion; null leaves the node untouched. The depth cap
// matches the render cap: a pathological chain of nested quote spellings (each level
// re-parsing its inner content) flattens the deepest levels to the ordinary html-block
// degradation instead of recursing past every bound the renderer itself honors
private fun quoteHtmlBlockOf(
    node: Node,
    parser: Parser,
    depth: Int,
): BlockQuote? {
    if (node !is HtmlBlock) return null
    val shape = htmlBlockQuoteOf(node.literal.orEmpty()) ?: return null
    if (depth >= MAX_RENDER_DEPTH) return null
    val quote = BlockQuote()
    if (shape.expandable) quote.appendChild(ExpandableQuoteMarker())
    if (shape.content.isNotBlank()) {
        val inner = parser.parse(shape.content)
        var child = inner?.firstChild
        while (child != null) {
            // appendChild moves the node — its next is null the moment it leaves the
            // inner document — so the walk captures the successor first
            val next = child.next
            quote.appendChild(child)
            child = next
        }
    }
    return quote
}

/**
 * The parse every string-taking entry makes: the caller's [parser], then the
 * expandable-quote rewrite over the tree it returned. Null only when the parser itself
 * returned null (the interface types parse() as Node?; the library's own parsers never
 * do).
 */
internal fun parseForRender(
    content: String,
    parser: Parser,
): Node? {
    val document = parser.parse(content) ?: return null
    rewriteExpandableHtmlQuotes(document, parser)
    return document
}

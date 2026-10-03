package com.github.kamiiroawase.markdownv2

import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text

/**
 * Iterative plain-text extraction (explicit stack, no recursion that grows with node nesting
 * depth), shared by table cells, inline-group truncation fallbacks and deep-structure
 * flattening. A link or image whose children contribute no visible text falls back to its
 * destination, mirroring the renderer's empty-label degradation to the bare escaped URL —
 * without it the truncation/chunking fallbacks would drop such content entirely.
 */
internal fun appendPlainText(
    sb: StringBuilder,
    node: Node,
) {
    val stack = ArrayDeque<Any>()
    stack.addLast(node)
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        when (current) {
            is UrlFallback -> {
                if (sb.substring(current.start).isBlank()) {
                    sb.setLength(current.start)
                    sb.append(current.url)
                }
            }

            is Text -> {
                sb.append(current.literal)
            }

            is Code -> {
                sb.append(current.literal)
            }

            is SoftLineBreak, is HardLineBreak -> {
                sb.append(' ')
            }

            is HtmlInline -> {
                // HTML comments carry no visible text — drop them, keep every other tag
                // literally
                if (!current.literal.trimStart().startsWith("<!--")) {
                    sb.append(current.literal)
                }
            }

            is FencedCodeBlock -> {
                sb.append(current.literal.orEmpty())
            }

            is IndentedCodeBlock -> {
                sb.append(current.literal)
            }

            is HtmlBlock -> {
                // Same leaf-with-literal shape as the code blocks: without this branch the
                // plain-text fallbacks (truncation, chunking, deep flattening) would drop
                // the block's content entirely
                sb.append(current.literal.orEmpty())
            }

            is Link -> {
                openLink(current.destination, current, sb, stack)
            }

            is Image -> {
                openLink(current.destination, current, sb, stack)
            }

            else -> {
                pushChildrenReversed(current as Node, stack)
            }
        }
    }
}

// Popped right after a Link/Image's own children (pushed beneath them): when they added no
// visible text, the extraction rewinds to the pre-link position and falls back to the URL
private class UrlFallback(
    val url: String,
    val start: Int,
)

private fun openLink(
    destination: String?,
    node: Node,
    sb: StringBuilder,
    stack: ArrayDeque<Any>,
) {
    if (!destination.isNullOrEmpty()) {
        stack.addLast(UrlFallback(destination, sb.length))
    }
    pushChildrenReversed(node, stack)
}

private fun pushChildrenReversed(
    node: Node,
    stack: ArrayDeque<Any>,
) {
    val children = mutableListOf<Node>()
    var child = node.firstChild
    while (child != null) {
        children += child
        child = child.next
    }
    // Push children reversed so they pop off the stack in document order
    children.asReversed().forEach(stack::addLast)
}

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
 * flattening. A link or image renders as `label (url)` — mirroring deformat's link handling
 * so the flattening paths never silently drop a link target — and one whose children
 * contribute no visible text falls back to its destination, mirroring the renderer's
 * empty-label degradation to the bare escaped URL (without it the truncation/chunking
 * fallbacks would drop such content entirely).
 */
internal fun appendPlainText(
    sb: StringBuilder,
    node: Node,
) {
    val stack = ArrayDeque<Any>()
    stack.addLast(node)
    // Depth of open link/image scopes: CommonMark cannot nest them, so this stays 0/1 in
    // practice — hand-built ASTs may nest, and there only the outermost target survives,
    // matching the renderer's nested-link degradation to label text
    var openLinks = 0
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        when (current) {
            is UrlFallback -> {
                openLinks--
                if (sb.substring(current.start).isBlank()) {
                    sb.setLength(current.start)
                    sb.append(current.url)
                } else if (openLinks == 0) {
                    sb.append(" (").append(current.url).append(')')
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
                // Comments, declarations and processing instructions carry no visible
                // text — the same "<!"/"<?" prefixes the renderer drops
                // (renderHtmlInline); every other tag stays literal
                val literal = current.literal.trimStart()
                if (!literal.startsWith("<!") && !literal.startsWith("<?")) {
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
                // the block's content entirely. Comment/declaration/PI blocks skip like
                // visit(htmlBlock) does, so the fallback cannot resurrect markup the
                // renderer itself never emits
                val literal = current.literal.orEmpty().trim()
                if (literal.isNotEmpty() && !literal.startsWith("<!") && !literal.startsWith("<?")) {
                    sb.append(current.literal.orEmpty())
                }
            }

            is Link -> {
                val destination = current.destination
                if (!destination.isNullOrEmpty()) {
                    stack.addLast(UrlFallback(destination, sb.length))
                    openLinks++
                }
                pushChildrenReversed(current, stack)
            }

            is Image -> {
                val destination = current.destination
                if (!destination.isNullOrEmpty()) {
                    stack.addLast(UrlFallback(destination, sb.length))
                    openLinks++
                }
                pushChildrenReversed(current, stack)
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

// Multi-node convenience over [appendPlainText] for the truncation and chunking fallback
// paths, which flatten whole nodes or node groups
internal fun plainText(nodes: List<Node>): String {
    val sb = StringBuilder()
    nodes.forEach { appendPlainText(sb, it) }
    return sb.toString()
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

package com.github.kamiiroawase.markdownv2

import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text

/**
 * Iterative plain-text extraction (explicit stack, no recursion that grows with node nesting
 * depth), shared by table cells, inline-group truncation fallbacks and deep-structure
 * flattening.
 */
internal fun appendPlainText(
    sb: StringBuilder,
    node: Node,
) {
    val stack = ArrayDeque<Node>()
    stack.addLast(node)
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        when (current) {
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

            else -> {
                val children = mutableListOf<Node>()
                var child = current.firstChild
                while (child != null) {
                    children += child
                    child = child.next
                }
                // Push children reversed so they pop off the stack in document order
                children.asReversed().forEach(stack::addLast)
            }
        }
    }
}

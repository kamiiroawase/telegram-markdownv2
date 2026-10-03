package io.github.kamiiroawase.markdownv2

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.node.Node

/**
 * Renders a GFM table as monospaced aligned multi-line text (for code-block degradation):
 * the first row acts as the header, characters measure via [RenderOptions.displayWidthOf]
 * (wide chars align at double width by default), missing cells are padded empty and surplus
 * cells are dropped.
 */
internal fun renderTableLines(
    table: TableBlock,
    options: RenderOptions,
): List<String> {
    val headRows = mutableListOf<List<TableCell>>()
    val bodyRows = mutableListOf<List<TableCell>>()

    var block = table.firstChild
    while (block != null) {
        when (block) {
            is TableHead -> headRows += block.tableRows()
            is TableBody -> bodyRows += block.tableRows()
        }
        block = block.next
    }

    val rows = headRows + bodyRows
    val columnCount = rows.maxOfOrNull { it.size } ?: return emptyList()

    val texts =
        rows.map { row ->
            (0 until columnCount).map { index ->
                row.getOrNull(index)?.let { truncateDisplay(cellText(it), options) }.orEmpty()
            }
        }
    // GFM stores alignment on header cells; a missing header cell (or a body-only table)
    // yields null and falls back to left alignment in padCell
    val alignments =
        (0 until columnCount).map { index ->
            headRows.firstOrNull()?.getOrNull(index)?.alignment
        }
    val widths =
        (0 until columnCount).map { index ->
            // The floor of 3 keeps the separator row visually a separator
            maxOf(texts.maxOf { displayWidth(it[index], options) }, 3)
        }

    return buildList {
        add(tableLine(texts.first(), widths, alignments, options))
        add(widths.joinToString(" | ", "| ", " |") { "-".repeat(it) })
        texts.drop(1).forEach { add(tableLine(it, widths, alignments, options)) }
    }
}

private fun tableLine(
    cells: List<String>,
    widths: List<Int>,
    alignments: List<TableCell.Alignment?>,
    options: RenderOptions,
): String =
    cells
        .mapIndexed { index, cell -> padCell(cell, widths[index], alignments[index], options) }
        .joinToString(" | ", "| ", " |")

private fun padCell(
    text: String,
    width: Int,
    alignment: TableCell.Alignment?,
    options: RenderOptions,
): String {
    val padding = width - displayWidth(text, options)
    if (padding <= 0) {
        return text
    }

    return when (alignment) {
        TableCell.Alignment.RIGHT -> {
            " ".repeat(padding) + text
        }

        TableCell.Alignment.CENTER -> {
            val left = padding / 2
            " ".repeat(left) + text + " ".repeat(padding - left)
        }

        else -> {
            text + " ".repeat(padding)
        }
    }
}

private fun cellText(cell: TableCell): String {
    val sb = StringBuilder()
    appendPlainText(sb, cell)
    return sb.toString().trim()
}

private fun Node.tableRows(): List<List<TableCell>> {
    val rows = mutableListOf<List<TableCell>>()
    var row = firstChild
    while (row != null) {
        if (row is TableRow) {
            val cells = mutableListOf<TableCell>()
            var cell = row.firstChild
            while (cell != null) {
                if (cell is TableCell) cells += cell
                cell = cell.next
            }
            rows += cells
        }
        row = row.next
    }
    return rows
}

private fun displayWidth(
    text: String,
    options: RenderOptions,
): Int {
    var width = 0
    var index = 0
    while (index < text.length) {
        width += charWidth(text, index, options)
        index += if (isSurrogatePairAt(text, index)) 2 else 1
    }
    return width
}

private fun charWidth(
    text: String,
    index: Int,
    options: RenderOptions,
): Int = measuredWidth(codePointAt(text, index), options)

// A render-time consumption point of the user-supplied measure (the other is the
// construction-time ellipsis check in RenderOptions): a negative width would silently
// break cell truncation (the cut budget grows instead of shrinking), so it fails fast
// with a clear message instead
private fun measuredWidth(
    codePoint: Int,
    options: RenderOptions,
): Int {
    val width = options.displayWidthOf(codePoint)
    require(width >= 0) {
        "RenderOptions.displayWidthOf returned $width for code point ${codePoint.toString(16)}; display widths must be non-negative"
    }
    return width
}

// A complete pair decodes to its code point; anything else (lone surrogate included)
// measures as its single char value
private fun codePointAt(
    text: String,
    index: Int,
): Int =
    if (isSurrogatePairAt(text, index)) {
        0x10000 + ((text[index].code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
    } else {
        text[index].code
    }

// ELLIPSIS_CODE_POINT lives in RenderOptions.kt next to its construction-time check

/**
 * Truncates by display width (measured via [RenderOptions.displayWidthOf]), appending an
 * ellipsis whose width the same measure supplies; the budget stays non-negative because
 * RenderOptions rejects a measure that makes the ellipsis wider than maxCellWidth.
 */
private fun truncateDisplay(
    text: String,
    options: RenderOptions,
): String {
    val cap = options.maxCellWidth
    if (displayWidth(text, options) <= cap) return text

    val budget = cap - measuredWidth(ELLIPSIS_CODE_POINT, options)
    var width = 0
    var index = 0
    while (index < text.length) {
        val cost = charWidth(text, index, options)
        if (width + cost > budget) break
        width += cost
        index += if (isSurrogatePairAt(text, index)) 2 else 1
    }
    return text.take(index) + "…"
}

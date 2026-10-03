package com.github.kamiiroawase.markdownv2

import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.node.BulletList
import org.commonmark.node.Document
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Structure-preserving truncation — `MarkdownV2.render` with a positive `maxLength`:
 * blocks, lines, items and fences survive the cut whole, fallbacks degrade to escaped
 * plain text, and escape sequences / surrogate pairs are never split. Includes the
 * tiny/custom-limit shapes and hand-built AST variants (suffixed `ViaAst`).
 */
class TruncationTest {
    @Test
    fun maxMessageLengthMatchesTelegramDocumentedLimit() {
        // Guards the public constant itself: the shared test helper delegates to it, so a
        // silent drift would otherwise go unnoticed by every test using maxMessageLength
        assertEquals(4096, MarkdownV2.MAX_MESSAGE_LENGTH)
    }

    @Test
    fun longContentTruncatedWithinLimit() {
        val content = "intro\n\n" + "long content ".repeat(2000)
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("intro"))
    }

    @Test
    fun zeroMaxLengthRendersFullContent() {
        val content = "word ".repeat(2000) // ~10k chars, well past the Telegram limit
        assertEquals("word ".repeat(2000).trim(), MarkdownV2.render(content, 0))
    }

    @Test
    fun defaultMaxLengthRendersFullContent() {
        val content = "word ".repeat(2000)
        assertEquals(MarkdownV2.render(content, 0), MarkdownV2.render(content))
    }

    @Test
    fun negativeMaxLengthRendersFullContent() {
        val content = "word ".repeat(2000)
        assertEquals(MarkdownV2.render(content, 0), MarkdownV2.render(content, -1))
    }

    @Test
    fun longParagraphTruncatesWholeLines() {
        val content = "intro\n\n" + (1..2000).joinToString("\n") { "line $it" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.contains("line 1"))
        assertTrue(result.contains("\nline 2"), "soft line breaks were glued together")
        assertTrue(!result.contains("line 2000"))
    }

    @Test
    fun longCodeBlockStaysClosed() {
        val code = (1..1000).joinToString("\n") { "line $it" }
        val content = "intro\n\n```\n$code\n```"
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertEquals(0, Regex("```").findAll(result).count() % 2)
        assertTrue(result.contains("line 1"))
        assertTrue(!result.contains("line 1000"))
    }

    @Test
    fun longListKeepsWholeItems() {
        val content = (1..2000).joinToString("\n") { "- item $it" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.contains("• item 1"))
        assertTrue(!result.contains("item 2000"))
        result.lines().dropLast(1).forEach { line ->
            assertTrue(line.startsWith("• "), "line is not a whole item: $line")
        }
    }

    @Test
    fun longOrderedListKeepsMarkerSpace() {
        val content = (1..2000).joinToString("\n") { "$it. item $it" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.contains("1\\. item 1"), "marker space lost: $result.take(50)")
        assertTrue(!result.contains("item 2000"))
        result.lines().dropLast(1).forEach { line ->
            assertTrue(
                Regex("^\\d+\\\\\\. ").containsMatchIn(line),
                "marker space lost: $line",
            )
        }
    }

    @Test
    fun longBlockQuoteTruncatesWithPrefix() {
        val content = "> " + (1..2000).joinToString("\n") { "quote $it" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.contains("quote 1"))
        result.lines().dropLast(1).forEach { line ->
            assertTrue(line.startsWith("> "), "quote prefix lost: $line")
        }
    }

    @Test
    fun longTableTruncatesAsClosedCodeBlock() {
        val header = "| col1 | col2 |\n| --- | --- |"
        val rows = (1..1000).joinToString("\n") { "| cell$it | data$it |" }
        val result = MarkdownV2.render("$header\n$rows", maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.startsWith("```"), "table did not degrade to a code block: ${result.take(20)}")
        assertTrue(result.contains("col1"), "header row was lost")
        assertTrue(result.contains("cell1"), "first body row was lost")
        assertTrue(!result.contains("cell1000"), "truncation kept the last row")
        assertEquals(0, Regex("```").findAll(result).count() % 2)
    }

    @Test
    fun overlongSingleLineFallsBackToEscapedPlainText() {
        val content = "special*char_content" + "x".repeat(5000)
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        val plain = removeEscapes(result.dropLast(1))
        assertEquals(content.take(plain.length), plain)
    }

    @Test
    fun htmlBoldAcrossLinesStaysBalanced() {
        val content = "prefix<b>bold\n" + "x".repeat(5000) + "\nsecond line</b>tail"
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        val body = removeEscapes(result.dropLast(1))
        assertTrue(body.startsWith("prefix*bold"), "bold opening was lost: ${body.take(20)}")
        assertEquals(0, body.count { it == '*' } % 2)
    }

    @Test
    fun customSmallLimitTruncatesWithinLimit() {
        val content = "**bold** head\n\n" + "body".repeat(500) + "\n\n```\ncode line\n```"
        val result = MarkdownV2.render(content, 200)
        assertTrue(result.length <= 200)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("*bold* head"))
    }

    @Test
    fun customLimitKeepsCodeBlockClosed() {
        val code = (1..100).joinToString("\n") { "line $it" }
        val result = MarkdownV2.render("```\n$code\n```", 300)
        assertTrue(result.length <= 300)
        assertTrue(result.startsWith("```"), "code block was dropped: ${result.take(20)}")
        assertTrue(result.contains("line 1"), "first code line was lost")
        assertTrue(!result.contains("line 100"), "truncation kept the last line")
        assertEquals(0, Regex("```").findAll(result).count() % 2)
    }

    @Test
    fun fallbackTruncationDoesNotSplitEscape() {
        val content = "```kotlin\n\\user\\home\n```"
        // The whole plain text fits: returned as-is, no spurious ellipsis
        assertEquals("\\\\user\\\\home", MarkdownV2.render(content, 12))
        val cut = MarkdownV2.render(content, 11)
        assertTrue(cut.length <= 11)
        assertTrue(cut.endsWith("…"))
        assertFalse(cut.dropLast(1).endsWith("\\"), "truncation split an escape: $cut")
        // The fallback is escaped plain text: code-block content stays literal and the whole output remains valid MarkdownV2
        assertEquals("\\\\user\\\\ho…", cut)
    }

    @Test
    fun minimalLimitRendersEllipsisOnly() {
        assertEquals("…", MarkdownV2.render("abcdef", 1))
    }

    @Test
    fun longHeadingKeepsEscapedPrefixWhenTruncated() {
        val content = "# " + "x".repeat(5000)
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.startsWith("\\# "))
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun surrogatePairIsNotSplitWhenTruncated() {
        val result = MarkdownV2.render("😀".repeat(3000), 100)
        assertTrue(result.length <= 100)
        assertTrue(result.endsWith("…"))
        assertFalse(result.dropLast(1).last().code in 0xD800..0xDBFF, "surrogate pair was split")
    }

    @Test
    fun partialListItemKeepsMarkerPrefix() {
        val content = "- short\n- second line item\n  continued"
        val result = MarkdownV2.render(content, 15)
        assertTrue(result.length <= 15)
        assertTrue(result.endsWith("…"))
        assertEquals("• short", result.lines().first())
        assertTrue(result.lines()[1].startsWith("• "), "marker prefix lost: $result")
    }

    @Test
    fun tinyLimitTruncatesListContent() {
        assertEquals("• it…", MarkdownV2.render("- item", 5))
    }

    @Test
    fun longHtmlBlockTruncatesAsClosedCodeBlock() {
        if (!htmlParsingSupported) return

        val content = "<div>\n" + "x".repeat(5000) + "\n</div>"
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("```\n<div>"), "html block was dropped: ${result.take(20)}")
        assertTrue(!result.contains("</div>"), "truncation kept the closing tag line")
        assertEquals(0, Regex("```").findAll(result).count() % 2)
    }

    @Test
    fun truncatedGroupWithInlineHtmlFallsBackToPlainText() {
        val content = "a<b>bold" + "x".repeat(5000) + "<!-- hidden -->"
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        val plain = removeEscapes(result.dropLast(1))
        assertTrue(plain.startsWith("a<b>"), "html tag should remain as plain text: ${plain.take(20)}")
        assertFalse(plain.contains("hidden"), "html comment should be dropped")
    }

    @Test
    fun truncatedGroupWithInlineDeclarationFallsBackToPlainText() {
        // Same guarantee for the other invisible inline markup the renderer drops
        // (renderHtmlInline's "<!"/"<?" prefixes): the plain-text fallback must not
        // resurrect it (pre-fix only "<!--" was filtered there)
        val paragraph =
            Paragraph().apply {
                appendChild(HtmlInline("<!D hidden-decl>"))
                appendChild(Text("b".repeat(5000)))
            }
        val result = MarkdownV2.render(paragraph, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        val plain = removeEscapes(result.dropLast(1))
        assertTrue(plain.startsWith("bbbb"), "text lost: ${plain.take(20)}")
        assertFalse(plain.contains("hidden-decl"), "declaration leaked into the fallback")
    }

    @Test
    fun fallbackTruncationAvoidsSplittingSurrogatePair() {
        assertEquals("😀😀😀", MarkdownV2.render("```js\n😀😀😀\n```", 8))
        assertEquals("😀😀…", MarkdownV2.render("```js\n😀😀😀\n```", 5))
    }

    @Test
    fun fallbackTruncationEscapesCharAfterLoneSurrogate() {
        // A lone high surrogate is not a pair: treated as a single char, the special char after it escapes normally
        // (the pre-fix surrogate branch swallowed the * alongside it, emitting a raw unescaped char)
        val paragraph = Paragraph()
        paragraph.appendChild(Text("a\uD800*" + "x".repeat(50)))
        assertEquals("a\uD800\\*xxxxx…", MarkdownV2.render(paragraph, 10))
    }

    @Test
    fun unshrinkableBlockFallsBackToPlainText() {
        assertEquals("x", MarkdownV2.render("---\n\nx", 3))
    }

    @Test
    fun quoteWithCodeBlockTruncatesBalanced() {
        val code = (1..500).joinToString("\n") { "> line $it" }
        val result = MarkdownV2.render("> ```\n$code\n> ```", 60)
        assertTrue(result.length <= 60)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("> ```"), "code block was dropped: ${result.take(20)}")
        assertTrue(result.contains("line 1"), "first code line was lost")
        assertTrue(!result.contains("line 500"), "truncation kept the last line")
        assertEquals(0, Regex("```").findAll(result).count() % 2, "unclosed fence in quote: $result")
    }

    @Test
    fun truncatedLooseListKeepsBlankLineAndIndentation() {
        val content = "- a\n\n  b\n" + (1..2000).joinToString("\n") { "- item $it" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        val lines = result.lines()
        assertEquals("• a", lines[0])
        assertEquals("", lines[1])
        assertEquals("  b", lines[2])
    }

    @Test
    fun quoteWithFittingHeadThenHugeBodyKeepsBoth() {
        val content = "> short\n>\n> " + (1..2000).joinToString("\n") { "quote $it" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("> short\n> "), "head quote lost: ${result.take(30)}")
    }

    @Test
    fun htmlBlockPlainTextFallbackKeepsContent() {
        if (!htmlParsingSupported) return

        // The plain-text fallback must carry the HTML block's literal, like it does for
        // fenced code blocks
        assertEquals("<div\\>…", MarkdownV2.render("<div>\nhello world\n</div>", 7))
        val chunks = MarkdownV2.renderChunked("<div>\nhello world\n</div>", 9)
        assertTrue(chunks.isNotEmpty(), "content was lost entirely")
        chunks.forEach { chunk -> assertTrue(chunk.length <= 9, "over-long chunk: $chunk") }
    }

    @Test
    fun indentedCodeBlockTruncatesClosed() {
        val code = (1..100).joinToString("\n") { "    line $it" }
        val result = MarkdownV2.render(code, 300)
        assertTrue(result.length <= 300)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("```"), "code block was dropped: ${result.take(20)}")
        assertTrue(result.contains("line 1"), "first code line was lost")
        assertTrue(!result.contains("line 100"), "truncation kept the last line")
        assertEquals(0, Regex("```").findAll(result).count() % 2)
    }

    @Test
    fun indentedCodeFallsBackToPlainTextOnTinyLimit() {
        assertEquals("abc", MarkdownV2.render("    abc", 5))
    }

    @Test
    fun fencedCodeFallsBackToPlainTextWhenFenceDoesNotFit() {
        assertEquals("code", MarkdownV2.render("```\ncode\n```", 6))
    }

    @Test
    fun headingPrefixTooLongFallsBackToPlainText() {
        assertEquals("x", MarkdownV2.render("# x", 3))
    }

    @Test
    fun tinyLimitListFallsBackToPlainText() {
        // The plain-text fallback keeps item content only; the "-" marker is syntax and never part of plain text
        assertEquals("it…", MarkdownV2.render("- item", 3))
    }

    @Test
    fun unknownCustomBlockShrinksToEmptyViaFootnotes() {
        val parser =
            Parser
                .builder()
                .extensions(MarkdownV2.defaultExtensions + FootnotesExtension.create())
                .build()
        val content = "Text[^1]\n\n[^1]: " + "x".repeat(100)
        assertEquals("Text…", MarkdownV2.render(content, 20, parser))
    }

    @Test
    fun contentExactlyAtLimitIsReturnedUntouched() {
        assertEquals("ab", MarkdownV2.render("ab", 2))
    }

    @Test
    fun quoteWithInnerListTruncates() {
        val content = "> " + (1..100).joinToString("\n> ") { "- item $it" }
        val result = MarkdownV2.render(content, 100)
        assertTrue(result.length <= 100)
        assertTrue(result.endsWith("…"))
        assertTrue(result.contains("> • item 1"))
        result.lines().dropLast(1).forEach { line ->
            assertTrue(line.startsWith("> "), "quote prefix lost: $line")
        }
    }

    @Test
    fun mixedDocumentTruncationKeepsLeadingBlocks() {
        val content =
            buildString {
                appendLine("# Title")
                appendLine()
                appendLine("Intro paragraph with *emphasis* and [link](https://example.com).")
                appendLine()
                appendLine((1..100).joinToString("\n") { "- item $it with filler" })
            }
        val result = MarkdownV2.render(content, 300)
        assertTrue(result.length <= 300)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("\\# Title"))
        assertTrue(result.contains("_emphasis_"))
        assertTrue(result.contains("[link](https://example.com)"))
        assertTrue(result.contains("• item 1"))
        assertFalse(result.contains("item 100"))
    }

    @Test
    fun blockBudgetExhaustedBeforeSecondBlock() {
        assertEquals("aaaa…", MarkdownV2.render("aaaa\n\nb", 5))
    }

    @Test
    fun paragraphLineBreakSkippedWhenBudgetFull() {
        assertEquals("aaaa…", MarkdownV2.render("aaaa\nbb", 5))
    }

    @Test
    fun truncatedGroupIsNotAppendedTwice() {
        // When first-line truncation stops at a special-char boundary (budget 1 left), the pre-fix tail fallback
        // would truncate the same group a second time and append it, duplicating one character
        val paragraph = Paragraph()
        paragraph.appendChild(Text("aaa*"))
        paragraph.appendChild(SoftLineBreak())
        paragraph.appendChild(Text("end"))
        assertEquals("aaa…", MarkdownV2.render(paragraph, 5))
    }

    @Test
    fun hardLineBreakInsideTruncatedParagraph() {
        val content = "keep" + (1..2000).joinToString("  \n") { "row $it" }
        val result = MarkdownV2.render(content, 100)
        assertTrue(result.length <= 100)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("keep"))
    }

    @Test
    fun quoteBudgetExhaustedBeforeInnerSecondBlock() {
        val content = "> " + "a".repeat(50) + "\n>\n> " + "b".repeat(50)
        val result = MarkdownV2.render(content, 54)
        assertTrue(result.length <= 54)
        assertTrue(result.startsWith("> " + "a".repeat(50)))
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun astOrderedListNullStartInTruncationDefaultsToOne() {
        val item =
            ListItem().apply {
                appendChild(Paragraph().apply { appendChild(Text("x".repeat(500))) })
            }
        val list = OrderedList()
        list.appendChild(item)
        val result = MarkdownV2.render(list, 100)
        assertTrue(result.length <= 100, "len=${result.length}")
        assertTrue(result.startsWith("1\\. "), "got: ${result.take(30)}")
    }

    @Test
    fun astTruncatedListSkipsNonListItemChildren() {
        val long = Paragraph().apply { appendChild(Text("x".repeat(500))) }
        val item = ListItem().apply { appendChild(Paragraph().apply { appendChild(Text("y")) }) }
        val list = BulletList()
        list.appendChild(long)
        list.appendChild(item)
        val result = MarkdownV2.render(list, 100)
        assertTrue(result.length <= 100)
        assertTrue(result.startsWith("• y"))
    }

    @Test
    fun astCodeBlocksWithNullLiteralFallBackToPlainText() {
        val document = Document()
        document.appendChild(FencedCodeBlock())
        document.appendChild(IndentedCodeBlock())
        val paragraph = Paragraph()
        paragraph.appendChild(Text("x"))
        document.appendChild(paragraph)
        assertEquals("x", MarkdownV2.render(document, 6))
    }

    @Test
    fun astTruncatedParagraphOpeningWithSoftBreak() {
        val paragraph = Paragraph()
        paragraph.appendChild(SoftLineBreak())
        paragraph.appendChild(Text("x".repeat(500)))
        val result = MarkdownV2.render(paragraph, 100)
        assertTrue(result.length <= 100)
        assertTrue(result.endsWith("…"))
        assertFalse(result.startsWith("\n"))
    }

    @Test
    fun quoteFitsAfterBlockwiseShrinkWithinBudget() {
        // The whole render exceeds the budget, but after block-wise shrinking both sub-blocks fit: the list exhausts normally and exits
        assertEquals("> ab\n> cd…", MarkdownV2.render("> ab\n>\n> cd", 10))
    }

    @Test
    fun truncatedListItemKeepsEmphasisClosedAcrossSoftBreak() {
        // applyPrefix's continuation indent used to push the closing _ into a dropped line, emitting • _abc… with an unclosed entity
        assertEquals("• abc def…", MarkdownV2.render("- *abc\ndef*", 12))
    }

    @Test
    fun truncatedQuoteKeepsEmphasisClosedAcrossSoftBreak() {
        assertEquals("> abc def…", MarkdownV2.render("> *abc\ndef*", 11))
    }

    @Test
    fun truncatedListItemKeepsLinkClosedAcrossSoftBreak() {
        assertEquals("• abc def…", MarkdownV2.render("- [abc\ndef](u)", 15))
    }

    @Test
    fun longListWithEmphasisAcrossLinesStaysBalanced() {
        val content = (1..300).joinToString("\n") { "- *a${it}\nb$it*" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("• _a1"), "first item's emphasis was lost: ${result.take(20)}")
        assertEquals(0, result.count { it == '_' } % 2, "unclosed emphasis: ${result.take(200)}")
    }

    @Test
    fun orderedListIndentOverheadBeyondBudgetFallsBackToPlainText() {
        // When continuation-indent overhead exceeds the whole item budget, item shrinking cannot converge: plain-text fallback;
        // the whole plain text fits, returned as-is without an ellipsis
        assertEquals("a b c d", MarkdownV2.render("1. a\nb\nc\nd", 10))
    }

    @Test
    fun truncatedListSkipsEmptyItem() {
        // The empty item carries no content: it skips instead of ending the surviving
        // prefix, so the following item keeps its marker (pre-fix the structural path
        // gave up at the empty item and the document-level plain-text fallback lost the
        // marker: "zzzzzzzzz…")
        val content = "-\n- " + "z".repeat(50)
        assertEquals("• zzzzzzz…", MarkdownV2.render(content, 10))
    }

    @Test
    fun truncatedListKeepsItemsAfterMidListEmptyItem() {
        assertEquals("• a\n• b…", MarkdownV2.render("- a\n-\n- b", 9))
    }

    @Test
    fun truncatedOrderedListConsumesNumberOfSkippedItem() {
        // The blank first item skips but consumes its number, so the survivor carries the
        // number the full render gives it ("1\. \n2\. zzz…"), not a renumbered "1\."
        assertEquals("2\\. zzzzz…", MarkdownV2.render("1.\n1. " + "z".repeat(50), 10))
    }

    @Test
    fun truncatedListWithUnfittableCodeItemFallsBackToPlainText() {
        val item = ListItem().apply { appendChild(FencedCodeBlock()) }
        val list = BulletList()
        list.appendChild(item)
        assertEquals("…", MarkdownV2.render(list, 8))
    }

    @Test
    fun quoteTailTooSmallForPrefixFallsBackToPlainText() {
        assertEquals("ab", MarkdownV2.render("> ab", 3))
    }

    @Test
    fun nestedListItemOverrunDegradesToPlainText() {
        // Nested lists do not re-shrink (prevents exponential re-rendering): an inner item that does not fit degrades to escaped plain text, still closed
        assertEquals("• • abc def…", MarkdownV2.render("- - *abc\ndef*", 12))
    }

    @Test
    fun nestedQuoteOverrunFallsBackToPlainText() {
        // A quote inside a list does not fit (indent overhead included): the block is abandoned, document-level plain-text fallback;
        // the whole plain text fits, returned as-is without an ellipsis
        assertEquals("abc def", MarkdownV2.render("- > *abc\ndef*", 13))
    }

    @Test
    fun bareUrlDegradationTruncatesToEscapedUrl() {
        // An empty-label image degrades to the bare escaped URL; the plain-text fallback
        // must carry that URL too (pre-fix the extraction found no text and only the
        // ellipsis survived)
        val url = "https://example.com/" + "u".repeat(5000)
        val result = MarkdownV2.render("![]($url)", 100)
        assertTrue(result.length <= 100)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("https"), "URL prefix lost: ${result.take(30)}")
    }

    @Test
    fun lengthBoundHoldsAcrossLimitSweep() {
        // The exact-output tests pin hand-picked limits; the shrink budget arithmetic only
        // breaks at specific boundary lengths, which a sweep crosses. Pre-fix the
        // fence-wrapped line check forgot the newline joining the line, and render
        // returned maxLength + 1 characters at those lengths (the table shape at 23, say)
        for (content in sweepContents(htmlParsingSupported)) {
            for (length in 1..96) {
                val result = MarkdownV2.render(content, length)
                assertTrue(
                    result.length <= length,
                    "len=$length gave ${result.length}: ${result.take(50).replace("\n", "\\n")}",
                )
            }
        }
    }
}

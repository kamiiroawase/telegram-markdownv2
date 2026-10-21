package io.github.kamiiroawase.markdownv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structure-preserving truncation — `TelegramHtml.render` with a positive `maxLength`:
 * blocks, lines, items and fences survive the cut whole, tags stay balanced, entities
 * and surrogate pairs are never split, fallbacks degrade to escaped plain text.
 */
class HtmlTruncationTest {
    @Test
    fun longContentTruncatedWithinLimit() {
        val content = "intro\n\n" + "long content ".repeat(2000)
        val result = TelegramHtml.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("intro"))
        assertTelegramHtmlParseable(result, "longContent")
    }

    @Test
    fun zeroMaxLengthRendersFullContent() {
        val content = "word ".repeat(2000)
        assertEquals("word ".repeat(2000).trim(), TelegramHtml.render(content, 0))
    }

    @Test
    fun defaultAndNegativeMaxLengthRenderFullContent() {
        val content = "word ".repeat(2000)
        assertEquals(TelegramHtml.render(content, 0), TelegramHtml.render(content))
        assertEquals(TelegramHtml.render(content, 0), TelegramHtml.render(content, -1))
    }

    @Test
    fun longParagraphTruncatesWholeLines() {
        val content = "intro\n\n" + (1..2000).joinToString("\n") { "line $it" }
        val result = TelegramHtml.render(content, maxMessageLength)
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
        val result = TelegramHtml.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("intro\n\n<pre>"), "code block shape lost: ${result.take(30)}")
        assertTrue(result.endsWith("</pre>…"), "fence not closed: ${result.takeLast(30)}")
        assertTrue(result.contains("line 1"))
        assertTrue(!result.contains("line 1000"))
        assertTelegramHtmlParseable(result, "longCodeBlock")
    }

    @Test
    fun fencedCodeWithLanguageKeepsWrapperUnderTruncation() {
        val code = (1..1000).joinToString("\n") { "line $it" }
        val content = "```kotlin\n$code\n```"
        val result = TelegramHtml.render(content, maxMessageLength)
        assertTrue(result.startsWith("<pre><code class=\"language-kotlin\">"))
        assertTrue(result.endsWith("</code></pre>…"))
        assertTelegramHtmlParseable(result, "fencedWithLanguage")
    }

    @Test
    fun longListKeepsWholeItems() {
        val content = (1..2000).joinToString("\n") { "- item $it" }
        val result = TelegramHtml.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.contains("• item 1"))
        assertTrue(!result.contains("item 2000"))
        result.lines().dropLast(1).forEach { line ->
            assertTrue(line.startsWith("• "), "line is not a whole item: $line")
        }
        assertTelegramHtmlParseable(result, "longList")
    }

    @Test
    fun longOrderedListKeepsMarkerSpace() {
        val content = (1..2000).joinToString("\n") { "$it. item $it" }
        val result = TelegramHtml.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        // The dot needs no escaping in HTML — the visible marker is "N. "
        assertTrue(result.contains("1. item 1"), "marker space lost: ${result.take(50)}")
        result.lines().dropLast(1).forEach { line ->
            assertTrue(Regex("^\\d+\\. ").containsMatchIn(line), "marker space lost: $line")
        }
    }

    @Test
    fun longBlockQuoteTruncatesBalanced() {
        val content = "> " + (1..2000).joinToString("\n") { "quote $it" }
        val result = TelegramHtml.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("<blockquote>quote 1"), "quote wrapper lost: ${result.take(30)}")
        assertTrue(result.contains("</blockquote>…"), "quote wrapper not closed: ${result.takeLast(30)}")
        assertTelegramHtmlParseable(result, "longBlockQuote")
    }

    @Test
    fun quoteInsideListItemTruncatesAsAQuote() {
        // The differentiator against the MarkdownV2 render: the quote keeps its wrapper
        // behind list structure, truncated or not
        val content = "- > " + (1..300).joinToString(" ") { "w$it" }
        val result = TelegramHtml.render(content, 60)
        assertTrue(result.length <= 60)
        assertTrue(result.startsWith("• <blockquote>w1"), "structure lost: $result.take(40)}")
        assertTrue(result.endsWith("…"))
        assertTelegramHtmlParseable(result, "quoteInsideListItem")
    }

    @Test
    fun escapeUnitsNeverSplit() {
        val content = "a & b < c > d " + "tail ".repeat(2000)
        val result = TelegramHtml.render(content, 40)
        assertTrue(result.length <= 40)
        // No partial entity can appear: every "&" is followed by a complete entity
        Regex("&[a-z]*;?").findAll(result).forEach { match ->
            assertTrue(
                match.value == "&amp;" || match.value == "&lt;" || match.value == "&gt;" || match.value.endsWith(";"),
                "split entity '${match.value}' in $result",
            )
        }
        assertTelegramHtmlParseable(result, "escapeUnits")
    }

    @Test
    fun surrogatePairsNeverSplit() {
        val content = "😀".repeat(3000)
        val result = TelegramHtml.render(content, 100)
        assertTrue(result.length <= 100)
        assertTrue(result.endsWith("…"))
        // No lone surrogates survived: every 😀 counts two chars, and the length parity holds
        assertEquals(0, result.count { it.isHighSurrogate() } - result.count { it.isLowSurrogate() })
    }

    @Test
    fun truncatedListSkipsEmptyItem() {
        val content = "-\n- " + "z".repeat(50)
        assertEquals("• zzzzzzz…", TelegramHtml.render(content, 10))
    }

    @Test
    fun truncatedOrderedListConsumesNumberOfSkippedItem() {
        assertEquals("2. zzzzzz…", TelegramHtml.render("1.\n1. " + "z".repeat(50), 10))
    }

    @Test
    fun structureTooSmallForContentFallsBackToPlainText() {
        // The quote wrapper (12 + 13 chars) cannot fit into 10 characters at all — the
        // document-level plain-text fallback takes over
        val result = TelegramHtml.render("> ab", 10)
        assertEquals("ab", result)
    }

    @Test
    fun quoteTailTooSmallForWrapperDegradesChildToPlainText() {
        // The wrapper (25 chars) leaves innerBudget = 26 - 1 - 25 = 0: nothing structural
        // fits, and the document-level plain-text fallback takes the whole message
        assertEquals("ab", TelegramHtml.render("> ab", 26))
        // With room for the wrapper and 14 content characters, the over-long child
        // degrades to escaped plain text inside the wrapper, not to a dropped quote
        assertEquals(
            "<blockquote>" + "x".repeat(14) + "</blockquote>…",
            TelegramHtml.render("> " + "x".repeat(50), 40),
        )
    }

    @Test
    fun nestedQuoteTruncatesNested() {
        val content = "> > " + "x".repeat(50)
        val result = TelegramHtml.render(content, 45)
        assertTrue(result.length <= 45)
        assertTrue(result.startsWith("<blockquote>"))
        assertTrue(result.endsWith("…"))
        assertTelegramHtmlParseable(result, "nestedQuote")
    }

    @Test
    fun blankBlockSkipsWithoutSeparatorOrBudget() {
        val content = "A\n\n![]()\n\n" + "x".repeat(50)
        assertEquals("A\n\nxxxxxxxxxxxxxxxx…", TelegramHtml.render(content, 20))
    }

    @Test
    fun unclosedTagInsideTruncatedParagraphStaysBalanced() {
        val content = "a <b>" + "x".repeat(200)
        val result = TelegramHtml.render(content, 30)
        assertTrue(result.length <= 30)
        assertTrue(result.endsWith("</b>…") || result.endsWith("…"))
        assertTelegramHtmlParseable(result, "unclosedTagTruncation")
    }

    @Test
    fun tableTruncatesWholeRows() {
        val rows = (1..600).joinToString("\n") { "| cell $it | data $it |" }
        val content = "| h1 | h2 |\n| --- | --- |\n$rows"
        val result = TelegramHtml.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.startsWith("<pre>| h1"))
        assertTrue(result.endsWith("</pre>…"))
        assertTrue(result.contains("| cell 1"))
        assertTrue(!result.contains("| cell 600"))
        assertTelegramHtmlParseable(result, "tableTruncation")
    }

    @Test
    fun tinyLimitYieldsEllipsisOnly() {
        assertEquals("…", TelegramHtml.render("- aaaa\n- bbbb", 1))
    }

    @Test
    fun lengthBoundHoldsAcrossLimitSweep() {
        // The exact-output tests pin hand-picked limits; the shrink budget arithmetic only
        // breaks at specific boundary lengths, which a sweep crosses
        for (content in sweepContents()) {
            for (length in 1..96) {
                val result = TelegramHtml.render(content, length)
                assertTrue(
                    result.length <= length,
                    "len=$length gave ${result.length}: ${result.take(50).replace("\n", "\\n")}",
                )
                assertTelegramHtmlParseable(
                    result,
                    "sweep len=$length of ${content.replace("\n", "\\n")}",
                )
            }
        }
    }

    @Test
    fun fenceThatFitsNoLineDegradesToPlainTextNotAnEmptyTagPair() {
        // See the MarkdownV2 twin: pre-fix this returned the "<pre></pre>" shell — a
        // code block with no content — instead of falling back to plain text
        val result = TelegramHtml.render("```\n\n" + "a".repeat(26) + "\n```", 13)
        assertEquals("\naaaaaaaaaaa…", result)
        assertTelegramHtmlParseable(result, "emptyFenceShell")
    }

    @Test
    fun truncatedTaskItemKeepsCheckboxInlineLikeTheFullRender() {
        // See the MarkdownV2 twin: the marker glues onto the body with no blank line
        val item = "- [ ] " + "b".repeat(40)
        val result = TelegramHtml.render(item, 20)
        assertEquals("• [ ] bbbbbbbbbbbbb…", result)
        assertTelegramHtmlParseable(result, "taskItemTruncated")
    }

    // ---------- nested quotes keep the full render's single flattened wrapper ----------

    @Test
    fun nestedQuoteTruncatesFlattenedLikeTheFullRender() {
        // Bot API formatting rules say blockquote entities can't nest: the full render
        // flattens a nested quote into the outer wrapper, and the truncated prefix must
        // keep that shape — pre-fix the shrink seam wrapped the inner quote's own
        // <blockquote> inside the outer one whenever the inner level happened to fit
        val content = "> > small\n>\n> " + "y".repeat(100)
        val result = TelegramHtml.render(content, 60)
        assertEquals("<blockquote>small\n\n" + "y".repeat(27) + "</blockquote>…", result)
        assertTelegramHtmlParseable(result, "nestedQuoteTruncationFlatten")
    }

    @Test
    fun tripleNestedQuoteTruncatesToOneWrapper() {
        val result = TelegramHtml.render("> > > " + "x".repeat(80), 50)
        assertEquals(1, Regex("<blockquote>").findAll(result).count())
        assertTrue(result.startsWith("<blockquote>"))
        assertTrue(result.endsWith("</blockquote>…"))
        assertTelegramHtmlParseable(result, "tripleNestedQuoteTruncation")
    }

    @Test
    fun plainFallbackKeepsAutolinkAndCustomEmojiDisplayText() {
        // See the MarkdownV2 twin: plainText's UrlFallback carve-outs keep the flattened
        // text at the display text — an autolink's URL is not duplicated and the custom
        // emoji spelling's internal tg:// destination never leaks into the cut
        assertEquals(
            "see https://e.com/x end plus…",
            TelegramHtml.render("see https://e.com/x end plus many words to overflow the budget", 30),
        )
        assertEquals(
            "• 😀 tttttt…",
            TelegramHtml.render("- ![😀](tg://emoji?id=5368324170671202286) " + "t".repeat(40), 12),
        )
    }

    // ---------- the tenth audit round: the language fence's yielding tier, separated quote fallbacks ----------

    @Test
    fun languageFenceDegradesToBarePreWhenTheClassTagCannotFit() {
        // The language rides the open tag's class attribute (35 chars with a short
        // language); a budget too small for that tag beside any content used to drop
        // the whole block to the plain-text fallback — pre structure and language both
        // gone at budgets where the MarkdownV2 twin keeps its 3-char fence and the
        // entities twin keeps its language-carrying range. The language yields first:
        // a bare <pre> keeps the block a code block (and the chunk seam degrades
        // alike)
        val content = "```kotlin\nval x = 1\n```"
        assertEquals("<pre>val x = 1</pre>…", TelegramHtml.render(content, 30))
        assertEquals(
            listOf("<pre>val x = 1</pre>"),
            TelegramHtml.renderChunked(content, 30),
        )
        // Contrast: a budget that holds the class tag keeps the language
        assertEquals(
            "<pre><code class=\"language-kotlin\">val x = 1</code></pre>",
            TelegramHtml.render(content, 0),
        )
    }

    @Test
    fun quotePlainFallbackSeparatesTheQuoteParagraphs() {
        // The plain fallback of a quote too small for its wrapper used to weld the
        // quote's paragraphs together ("aaaaaabbbbbb"); the block separator keeps
        // them apart, the display the structured paths ship
        assertEquals("aaaaaa\n\nbbb…", TelegramHtml.render("> aaaaaa\n>\n> bbbbbb", 12))
    }
}

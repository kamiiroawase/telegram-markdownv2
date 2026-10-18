package io.github.kamiiroawase.markdownv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structure-preserving truncation — `TelegramEntities.render` with a positive
 * `maxLength`: text stays within the limit with a trailing ellipsis, entity ranges clamp
 * to the surviving text (straddling ranges shorten, fully-beyond ones drop), blocks and
 * items survive the cut whole, fallbacks degrade to entity-free plain text.
 */
class EntityTruncationTest {
    @Test
    fun longContentTruncatedWithinLimit() {
        val content = "intro\n\n" + "long content ".repeat(2000)
        val result = TelegramEntities.render(content, maxMessageLength)
        assertTrue(result.text.length <= maxMessageLength)
        assertTrue(result.text.endsWith("…"))
        assertTrue(result.text.startsWith("intro"))
        assertEntitiesValid(result, "longContent")
    }

    @Test
    fun zeroAndNegativeMaxLengthRenderFullContent() {
        val content = "word ".repeat(2000)
        assertEquals(TelegramEntities.render(content, 0), TelegramEntities.render(content))
        assertEquals(TelegramEntities.render(content, 0), TelegramEntities.render(content, -1))
    }

    @Test
    fun fittingEntitySurvivesWholeWithEllipsisOutside() {
        // The bold spans the first line group, which fits whole; the over-long second
        // group degrades to plain text — the bold range survives the cut untouched
        val content = "**bold** line\n" + "tail ".repeat(100)
        val result = TelegramEntities.render(content, 20)
        assertTrue(result.text.length <= 20)
        assertTrue(result.text.endsWith("…"))
        val bold = result.entities.single()
        assertEquals(MessageEntityType.BOLD, bold.type)
        assertEquals(0, bold.offset)
        assertEquals(4, bold.length)
        assertEquals("bold", result.text.substring(bold.offset, bold.offset + bold.length))
        assertEntitiesValid(result, "fittingEntity")
    }

    @Test
    fun longParagraphTruncatesWholeLines() {
        val content = "intro\n\n" + (1..2000).joinToString("\n") { "line $it" }
        val result = TelegramEntities.render(content, maxMessageLength)
        assertTrue(result.text.length <= maxMessageLength)
        assertTrue(result.text.endsWith("…"))
        assertTrue(result.text.contains("line 1"))
        assertTrue(!result.text.contains("line 2000"))
    }

    @Test
    fun codeBlockLinesKeepPreRangeOverKeptText() {
        val code = (1..1000).joinToString("\n") { "line $it" }
        val result = TelegramEntities.render("```\n$code\n```", maxMessageLength)
        assertTrue(result.text.length <= maxMessageLength)
        assertTrue(result.text.endsWith("…"))
        val pre = result.entities.single()
        assertEquals(MessageEntityType.PRE, pre.type)
        assertEquals(result.text.length - 1, pre.offset + pre.length, "pre range covers the kept text only")
        assertTrue(result.text.contains("line 1"))
        assertTrue(!result.text.contains("line 1000"))
        assertEntitiesValid(result, "codeBlockLines")
    }

    @Test
    fun fencedLanguageRidesOnTheRange() {
        val code = (1..1000).joinToString("\n") { "line $it" }
        val result = TelegramEntities.render("```kotlin\n$code\n```", maxMessageLength)
        assertEquals("kotlin", result.entities.single().language)
    }

    @Test
    fun longListKeepsWholeItems() {
        val content = (1..2000).joinToString("\n") { "- item $it" }
        val result = TelegramEntities.render(content, maxMessageLength)
        assertTrue(result.text.length <= maxMessageLength)
        assertTrue(result.text.endsWith("…"))
        result.text.lines().dropLast(1).forEach { line ->
            assertTrue(line.startsWith("• "), "line is not a whole item: $line")
        }
    }

    @Test
    fun longBlockQuoteKeepsRangeOverKeptText() {
        val content = "> " + (1..2000).joinToString("\n> ") { "quote $it" }
        val result = TelegramEntities.render(content, maxMessageLength)
        assertTrue(result.text.length <= maxMessageLength)
        assertTrue(result.text.endsWith("…"))
        val quote = result.entities.single()
        assertEquals(MessageEntityType.BLOCKQUOTE, quote.type)
        assertEquals(0, quote.offset)
        assertEquals(result.text.length - 1, quote.offset + quote.length)
        assertEntitiesValid(result, "longBlockQuote")
    }

    @Test
    fun quoteInsideListItemTruncatesAsAQuote() {
        val content = "- > " + (1..300).joinToString(" ") { "w$it" }
        val result = TelegramEntities.render(content, 60)
        assertTrue(result.text.length <= 60)
        assertTrue(result.text.startsWith("• "), "marker lost: ${result.text.take(20)}")
        assertTrue(result.text.endsWith("…"))
        assertEntitiesValid(result, "quoteInsideListItem")
    }

    @Test
    fun truncatedListSkipsEmptyItem() {
        assertEquals(
            EntityText("• zzzzzzz…", emptyList()),
            TelegramEntities.render("-\n- " + "z".repeat(50), 10),
        )
    }

    @Test
    fun truncatedOrderedListConsumesNumberOfSkippedItem() {
        assertEquals(
            EntityText("2. zzzzzz…", emptyList()),
            TelegramEntities.render("1.\n1. " + "z".repeat(50), 10),
        )
    }

    @Test
    fun tinyLimitKeepsQuoteStructureInPlainTextBudget() {
        // The blockquote range costs no characters in this protocol: even a 3-character
        // budget keeps the quote's two fitting characters as quoted text
        val result = TelegramEntities.render("> abcdef", 3)
        assertEquals("ab…", result.text)
        assertEquals(listOf(MessageEntity(MessageEntityType.BLOCKQUOTE, 0, 2)), result.entities)
        assertEntitiesValid(result, "tinyLimit")
    }

    @Test
    fun surrogatePairsNeverSplit() {
        val result = TelegramEntities.render("😀".repeat(3000), 100)
        assertTrue(result.text.length <= 100)
        assertTrue(result.text.endsWith("…"))
        assertEquals(0, result.text.count { it.isHighSurrogate() } - result.text.count { it.isLowSurrogate() })
        assertEntitiesValid(result, "surrogates")
    }

    @Test
    fun tinyLimitYieldsEllipsisOnly() {
        assertEquals(EntityText("…", emptyList()), TelegramEntities.render("- aaaa\n- bbbb", 1))
    }

    @Test
    fun blankBlockSkipsWithoutSeparatorOrBudget() {
        val content = "A\n\n![]()\n\n" + "x".repeat(50)
        assertEquals(EntityText("A\n\nxxxxxxxxxxxxxxxx…", emptyList()), TelegramEntities.render(content, 20))
    }

    @Test
    fun lengthBoundAndValidityHoldAcrossLimitSweep() {
        for (content in sweepContents()) {
            for (length in 1..96) {
                val result = TelegramEntities.render(content, length)
                assertTrue(
                    result.text.length <= length,
                    "len=$length gave ${result.text.length}: ${result.text.take(50).replace("\n", "\\n")}",
                )
                assertEntitiesValid(result, "sweep len=$length of ${content.replace("\n", "\\n")}")
            }
        }
    }
}

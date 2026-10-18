package io.github.kamiiroawase.markdownv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lossless chunking — `TelegramEntities.renderChunked`: every piece's text is at most
 * maxLength long, its entities reference its own text (offsets re-based per piece),
 * structural formatting re-establishes per piece (a split quote carries a blockquote
 * range on every piece, a split code block a pre range with its language), and the
 * flatten seams never split a surrogate pair.
 */
class EntityChunkingTest {
    @Test
    fun shortContentYieldsSingleChunk() {
        val result = TelegramEntities.renderChunked("hello **world**")
        assertEquals(listOf(EntityText("hello world", listOf(MessageEntity(MessageEntityType.BOLD, 6, 5)))), result)
    }

    @Test
    fun emptyRenderYieldsEmptyList() {
        assertEquals(emptyList(), TelegramEntities.renderChunked("<!-- nothing -->"))
    }

    @Test
    fun belowMinChunkLengthReturnsFullRender() {
        val content = "aaaa"
        assertEquals(listOf(TelegramEntities.render(content)), TelegramEntities.renderChunked(content, 1))
    }

    @Test
    fun longContentSplitsWithinLimit() {
        val content = "word ".repeat(2000)
        val chunks = TelegramEntities.renderChunked(content, maxMessageLength)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= maxMessageLength, "chunk of ${chunk.text.length}")
            assertEntitiesValid(chunk, "longContent")
        }
        // Losslessness: the pieces' text joins back to the full render's text
        assertEquals(TelegramEntities.render(content).text, chunks.joinToString("") { it.text })
    }

    @Test
    fun codeBlockLinesCarryPreRangePerChunk() {
        val code = (1..1000).joinToString("\n") { "line $it" }
        val chunks = TelegramEntities.renderChunked("```kotlin\n$code\n```", maxMessageLength)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= maxMessageLength)
            val pre = chunk.entities.single()
            assertEquals(MessageEntityType.PRE, pre.type)
            assertEquals("kotlin", pre.language)
            assertEquals(0, pre.offset)
            assertEquals(chunk.text.length, pre.offset + pre.length)
            assertEntitiesValid(chunk, "codeBlockPerChunk")
        }
        // No line was lost or duplicated: the code lines survive exactly once, in order
        val seen = chunks.flatMap { it.text.split('\n') }.filter { it.startsWith("line ") }
        assertEquals((1..1000).map { "line $it" }, seen)
    }

    @Test
    fun listNumberingContinuesAcrossChunks() {
        val content = (1..2000).joinToString("\n") { "$it. item $it" }
        val chunks = TelegramEntities.renderChunked(content, maxMessageLength)
        assertTrue(chunks.size > 1)
        val lastOfFirst =
            Regex("(\\d+)\\. ")
                .findAll(chunks.first().text)
                .last()
                .groupValues[1]
                .toInt()
        val firstOfSecond = Regex("^(\\d+)\\. ").find(chunks[1].text)!!.groupValues[1].toInt()
        assertEquals(lastOfFirst + 1, firstOfSecond)
        val marker = Regex("^\\d+\\. ", setOf(RegexOption.MULTILINE))
        assertEquals(2000, marker.findAll(chunks.joinToString("\n") { it.text }).count())
    }

    @Test
    fun quoteCarriesBlockquoteRangePerChunk() {
        val content = "> " + (1..2000).joinToString("\n> ") { "quote $it" }
        val chunks = TelegramEntities.renderChunked(content, maxMessageLength)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= maxMessageLength)
            val quote = chunk.entities.single()
            assertEquals(MessageEntityType.BLOCKQUOTE, quote.type)
            assertEquals(0, quote.offset)
            assertEquals(chunk.text.length, quote.offset + quote.length)
            assertEntitiesValid(chunk, "quotePerChunk")
        }
    }

    @Test
    fun quoteInsideListItemChunksAsAQuote() {
        val content = (1..200).joinToString("\n") { "- > quoted item $it" }
        val chunks = TelegramEntities.renderChunked(content, 200)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= 200)
            assertEntitiesValid(chunk, "quoteInsideListItemChunking")
        }
        assertTrue(
            chunks.first().entities.any { it.type == MessageEntityType.BLOCKQUOTE },
            "quote range lost: ${chunks.first()}",
        )
    }

    @Test
    fun quotePacksAfterPriorContentWithRangeOpeningMidChunk() {
        // A short paragraph followed by a short quote share one chunk; the blockquote
        // range covers only the quote's span, not the paragraph ahead of it
        val content = "intro paragraph\n\n> quoted line"
        val chunks = TelegramEntities.renderChunked(content, maxMessageLength)
        assertEquals(1, chunks.size)
        val chunk = chunks.single()
        assertEquals("intro paragraph\n\nquoted line", chunk.text)
        val quote = chunk.entities.single()
        assertEquals(MessageEntityType.BLOCKQUOTE, quote.type)
        assertEquals("quoted line", chunk.text.substring(quote.offset, quote.offset + quote.length))
    }

    @Test
    fun inlineNodesPackWithoutSplittingRanges() {
        val content = (1..500).joinToString(" ") { "**bold $it** and `code $it` end" }
        val chunks = TelegramEntities.renderChunked(content, 300)
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= 300)
            assertEntitiesValid(chunk, "inlineNodePacking")
        }
        // Formatting is never partially applied: every bold/code range brackets text
        // that exists in its chunk
        chunks.forEach { chunk ->
            chunk.entities.forEach { entity ->
                assertTrue(entity.offset + entity.length <= chunk.text.length)
            }
        }
    }

    @Test
    fun hugeWordFlattensLosslessly() {
        val content = "abcdef" + "x".repeat(5000)
        val chunks = TelegramEntities.renderChunked(content, 40)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= 40, "chunk of ${chunk.text.length}")
            assertEquals(emptyList(), chunk.entities, "flattened chunk carries formatting")
        }
        assertEquals(content, chunks.joinToString("") { it.text })
    }

    @Test
    fun surrogatePairsStayWhole() {
        val content = "😀".repeat(3000)
        val chunks = TelegramEntities.renderChunked(content, 101)
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= 101, "chunk of ${chunk.text.length}")
            assertEquals(0, chunk.text.count { it.isHighSurrogate() } - chunk.text.count { it.isLowSurrogate() })
        }
        assertEquals(content, chunks.joinToString("") { it.text })
    }

    @Test
    fun singleBlockDocumentChunksThatBlockAlone() {
        val document = MarkdownV2.defaultParser.parse("first\n\nsecond\n\n" + "word ".repeat(2000))!!
        val chunks = TelegramEntities.renderChunked(document.firstChild!!, maxMessageLength)
        assertEquals(listOf(EntityText("first", emptyList())), chunks)
    }

    @Test
    fun lengthBoundAndValidityHoldAcrossLimitSweep() {
        for (content in sweepContents()) {
            for (length in TelegramEntities.MIN_CHUNK_LENGTH..96) {
                val chunks = TelegramEntities.renderChunked(content, length)
                chunks.forEach { chunk ->
                    assertTrue(
                        chunk.text.length <= length,
                        "len=$length gave ${chunk.text.length}: ${chunk.text.take(50).replace("\n", "\\n")}",
                    )
                    assertEntitiesValid(chunk, "sweep len=$length of ${content.replace("\n", "\\n")}")
                }
                assertTrue(chunks.isNotEmpty(), "len=$length dropped all content")
            }
        }
    }
}

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
    fun whitespaceRunNeverYieldsABlankChunk() {
        // A whitespace-only piece can never be sent — the server trims message text and
        // rejects blank text with a 400 — so it drops; pre-fix a 700-space run with a
        // 300 limit shipped blank chunks. The joined text loses only whitespace Telegram
        // would never display
        val chunks = TelegramEntities.renderChunked("a" + " ".repeat(700) + "b", 300)
        assertTrue(chunks.size >= 2)
        chunks.forEach { chunk ->
            assertTrue(chunk.text.isNotBlank(), "blank chunk")
            assertTrue(chunk.text.length <= 300, "chunk of ${chunk.text.length}")
            assertEntitiesValid(chunk, "whitespaceRun")
        }
        assertTrue(chunks.first().text.startsWith("a"))
        assertTrue(chunks.last().text.endsWith("b"))
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

    @Test
    fun trailingWhitespaceShapesChunkToTheFullRender() {
        // See the MarkdownV2 twin: a single chunk at a huge limit equals the full render
        // for every trailing-whitespace shape (this pipeline trims at the visitor
        // itself, so code-span tail spaces are gone from both sides alike)
        for (content in listOf(
            "hello\n\n` x  `",
            "text\n\n    indented code  ",
            "```\ncode line with trailing spaces   \n```",
            "para one  \npara two after hard break",
            "> quoted with trailing   \n\nfinal para",
        )) {
            assertEquals(listOf(TelegramEntities.render(content)), TelegramEntities.renderChunked(content, 100000))
        }
    }

    // ---------- blank chunks never ship / heading bodies never drop / close order ----------

    @Test
    fun footnoteWhitespaceRunNeverYieldsABlankChunk() {
        // The verbatim seam used to ship whitespace-only middle pieces — the server
        // trims message text and rejects blank text with a 400
        val pieces = TelegramEntities.renderChunked("[^1]: a" + " ".repeat(30) + "b", 6)
        assertTrue(pieces.size >= 2, "expected a split: $pieces")
        pieces.forEach { piece ->
            assertTrue(piece.text.isNotBlank(), "blank chunk shipped: $pieces")
            assertTrue(piece.text.length <= 6, "over-long chunk: $piece")
            assertEntitiesValid(piece, "footnoteWhitespaceChunk")
        }
    }

    @Test
    fun inlineSeamWhitespaceNeverYieldsABlankChunk() {
        // flushRaw at the inline seam used to ship the whitespace tail of a flattened
        // node as its own chunk
        val pieces = TelegramEntities.renderChunked("hello *world* and `code` here", 5)
        pieces.forEach { piece ->
            assertTrue(piece.text.isNotBlank(), "blank chunk shipped: $pieces")
            assertTrue(piece.text.length <= 5, "over-long chunk: $piece")
        }
    }

    @Test
    fun headingBodyNeverLostAtSmallLimits() {
        // The lead is tracked by flag, not length — only the marker may drop, never the
        // body text
        for (limit in 2..13) {
            val pieces = TelegramEntities.renderChunked("# abcdefgh`z`", limit)
            assertTrue(pieces.isNotEmpty(), "no chunks at $limit")
            val joined = pieces.joinToString("") { it.text }
            assertTrue(joined.contains("abcdefgh"), "body lost at $limit: $pieces")
            assertTrue(joined.contains("z"), "tail lost at $limit: $pieces")
            pieces.forEach { piece ->
                assertTrue(piece.text.isNotBlank(), "blank chunk at $limit: $pieces")
                assertTrue(piece.text.length <= limit, "over-long chunk at $limit: $piece")
                assertEntitiesValid(piece, "headingBody at $limit")
            }
        }
    }

    @Test
    fun oversizedItemKeepsNestedListMarkers() {
        // The string twins' own defect: the list flatten seam's text source is the
        // item body's own text (nested markers live only in rendered output, and the
        // AST plain extraction carries none), so a nested ordered marker or bullet
        // survives a limit that holds it
        val ordered = TelegramEntities.renderChunked("- 1. " + "a".repeat(60), 40)
        assertEquals(
            listOf("• 1. " + "a".repeat(35), "a".repeat(25)),
            ordered.map { it.text },
        )
        val nested = TelegramEntities.renderChunked("- - " + "c".repeat(60), 30)
        assertEquals(
            listOf("• • " + "c".repeat(26), "c".repeat(28), "c".repeat(6)),
            nested.map { it.text },
        )
        (ordered + nested).forEach { assertEntitiesValid(it, "nestedMarkerFlatten") }
    }

    @Test
    fun chunkedQuotePiecesKeepCloseOrder() {
        // The EntityText contract: nested ranges before the ranges containing them. The
        // quote re-assembly (blockquoteEntityOf) used to prepend the container — the
        // same input flipped order between the full render and the chunk pipeline
        for (content in listOf(
            "> **bold** and plenty of trailing text to overflow any small chunk limit",
            "> ```\n> code one\n> code two\n> ```",
        )) {
            TelegramEntities.renderChunked(content, 8).forEach { piece ->
                assertEntitiesValid(piece, "close order chunk of $content")
            }
            assertEntitiesValid(TelegramEntities.render(content), "close order render of $content")
        }
    }

    @Test
    fun fenceWithLeadingBlankLinesNeverYieldsABlankChunk() {
        // output() only trims the trailing side, so a fence whose content starts with
        // blank lines starts blank — pre-fix the pending blank lines flushed as an
        // all-whitespace chunk the server rejects with a 400; they drop at the split
        // instead, the MarkdownV2/HTML twins' documented whitespace exception
        val chunks =
            TelegramEntities.renderChunked(
                "```\n\n   \n" + "x".repeat(20) + "\n```",
                10,
            )
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertTrue(chunk.text.isNotBlank(), "blank chunk: <${chunk.text}>")
            assertTrue(chunk.text.length <= 10, "over-long chunk: ${chunk.text}")
            assertEntitiesValid(chunk, "leading blank fence")
        }
    }

    @Test
    fun inlineSeamKeepsInteriorWhitespace() {
        // The space after "foo " is content between two nodes; the per-node seam renders
        // each in isolation and used to trim it away (output()'s trailing trim has no
        // business in an inline group) — the chunk-joined text must equal the full
        // render's
        val content = "foo <!-- c -->bar zzzzzzzzzz"
        val chunks = TelegramEntities.renderChunked(content, 10)
        assertEquals(TelegramEntities.render(content).text, chunks.joinToString("") { it.text })
    }

    // ---------- plain-fallback carve-outs: autolink URLs and custom emoji destinations ----------

    @Test
    fun autolinkNeverDuplicatesItsUrlAtTheFlattenSeam() {
        // plainText's link fallback used to append " (url)" even when the label IS the
        // destination (an autolink): the flattened node carried the URL twice and the
        // joined chunks read "see https://e.com/x?a=1 (https://e.com/x?a=1) end". The
        // label-equals-destination carve-out keeps the joined text at the full render's
        val content = "see https://e.com/x?a=1 end"
        val chunks = TelegramEntities.renderChunked(content, 16)
        assertEquals(TelegramEntities.render(content).text, chunks.joinToString("") { it.text })
        chunks.forEach { assertEntitiesValid(it, "autolink seam") }
    }

    @Test
    fun customEmojiImageNeverLeaksItsTgUrlAtTheFlattenSeam() {
        // The tg://emoji?id= destination is internal — no render ever displays it. The
        // item's plain fallback used to surface " (tg://emoji?id=…)" as visible text;
        // the joined chunks must equal the full render's text (the fallback emoji plus
        // the item body)
        val content = "- ![😀](tg://emoji?id=5368324170671202286) tailtailtail"
        val chunks = TelegramEntities.renderChunked(content, 8)
        assertEquals(TelegramEntities.render(content).text, chunks.joinToString("") { it.text })
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= 8, "over-long chunk: $chunk")
            assertEntitiesValid(chunk, "custom emoji seam")
        }
    }

    // ---------- the eleventh audit round: the quote seam's empty inner ----------

    @Test
    fun deepQuoteChainWithBlankInnermostChildTerminates() {
        // Pre-fix the quote seam's `continue` on an empty inner re-chunk skipped the
        // node walk too and spun forever on the same child: a 120-level quote chain
        // whose innermost paragraph renders blank under the seam's own depth handling
        // hung the whole call (the render is deterministic — the retry never differs).
        // The child now skips and the walk advances; every level renders to nothing
        // here, and an empty range is no quote, so the empty list is the correct
        // result — the full render of the same content is empty alike
        assertEquals(emptyList(), TelegramEntities.renderChunked(">".repeat(120) + " <b></b>", 6))
        // The <br> spelling flattens "<br>" as plain text at the tiny limit — every
        // piece within it and valid, and the call returns
        for (piece in TelegramEntities.renderChunked(">".repeat(120) + " <br>", 2)) {
            assertTrue(piece.text.length <= 2, "over-limit piece: $piece")
            assertEntitiesValid(piece, "deepQuoteBrSeam")
        }
    }
}

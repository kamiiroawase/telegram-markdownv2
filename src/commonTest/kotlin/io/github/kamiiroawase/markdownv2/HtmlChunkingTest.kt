package io.github.kamiiroawase.markdownv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lossless chunking — `TelegramHtml.renderChunked`: every piece is at most maxLength
 * long and independently valid Telegram HTML, blocks pack whole where they fit, code
 * lines distribute across closed fences, list numbering runs across chunks, and the
 * flatten seams never split an escape unit or a surrogate pair.
 */
class HtmlChunkingTest {
    @Test
    fun shortContentYieldsSingleChunk() {
        assertEquals(listOf("hello <b>world</b>"), TelegramHtml.renderChunked("hello **world**"))
    }

    @Test
    fun emptyRenderYieldsEmptyList() {
        assertEquals(emptyList(), TelegramHtml.renderChunked("<!-- nothing -->"))
    }

    @Test
    fun belowMinChunkLengthReturnsFullRender() {
        // Chunking needs room for one escape unit — the five characters of "&amp;";
        // below that the full render returns as a single chunk
        val content = "a & b < c"
        assertEquals(listOf(TelegramHtml.render(content)), TelegramHtml.renderChunked(content, 4))
        assertEquals(listOf(TelegramHtml.render(content)), TelegramHtml.renderChunked(content, 1))
    }

    @Test
    fun longContentSplitsWithinLimit() {
        val content = "word ".repeat(2000)
        val chunks = TelegramHtml.renderChunked(content, maxMessageLength)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= maxMessageLength, "chunk of ${chunk.length}")
            assertTelegramHtmlParseable(chunk, "longContent")
        }
        // Losslessness: joining the pieces reproduces the full render
        assertEquals(TelegramHtml.render(content), chunks.joinToString(""))
    }

    @Test
    fun whitespaceRunNeverYieldsABlankChunk() {
        // A whitespace-only flattened piece can never be sent — the server trims message
        // text and rejects blank text with a 400 — so it drops; pre-fix a 700-space run
        // with a 300 limit shipped blank chunks. The joined text loses only whitespace
        // Telegram would never display
        val chunks = TelegramHtml.renderChunked("a" + " ".repeat(700) + "b", 300)
        assertTrue(chunks.size >= 2)
        chunks.forEach { chunk ->
            assertTrue(chunk.isNotBlank(), "blank chunk")
            assertTrue(chunk.length <= 300, "chunk of ${chunk.length}")
            assertTelegramHtmlParseable(chunk, "whitespaceRun")
        }
        assertTrue(chunks.first().startsWith("a"))
        assertTrue(chunks.last().endsWith("b"))
    }

    @Test
    fun codeBlockLinesDistributeAcrossClosedPres() {
        val code = (1..1000).joinToString("\n") { "line $it" }
        val content = "```\n$code\n```"
        val chunks = TelegramHtml.renderChunked(content, maxMessageLength)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= maxMessageLength)
            assertTrue(chunk.startsWith("<pre>"), "chunk not fence-opened: ${chunk.take(20)}")
            assertTrue(chunk.endsWith("</pre>"), "chunk not fence-closed: ${chunk.takeLast(20)}")
            assertTelegramHtmlParseable(chunk, "codeBlockDistribution")
        }
        // No line was lost or duplicated across the fence-per-chunk split: the code
        // lines survive exactly once, in order, spread over the chunks (consecutive
        // chunks glue their fences with no separator — undo the seam before comparing)
        val seen =
            chunks
                .joinToString("")
                .replace("</pre><pre>", "\n")
                .split('\n')
                .map { it.removePrefix("<pre>").removeSuffix("</pre>") }
                .filter { it.startsWith("line ") }
        assertEquals((1..1000).map { "line $it" }, seen)
    }

    @Test
    fun fencedCodeWithLanguageKeepsWrapperPerChunk() {
        val code = (1..1000).joinToString("\n") { "line $it" }
        val content = "```kotlin\n$code\n```"
        val chunks = TelegramHtml.renderChunked(content, maxMessageLength)
        chunks.forEach { chunk ->
            assertTrue(chunk.startsWith("<pre><code class=\"language-kotlin\">"))
            assertTrue(chunk.endsWith("</code></pre>"))
            assertTelegramHtmlParseable(chunk, "fencedWrapperPerChunk")
        }
    }

    @Test
    fun listNumberingContinuesAcrossChunks() {
        val content = (1..2000).joinToString("\n") { "$it. item $it" }
        val chunks = TelegramHtml.renderChunked(content, maxMessageLength)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= maxMessageLength)
            assertTelegramHtmlParseable(chunk, "listNumbering")
        }
        // Numbering runs across chunks: the number after a chunk's last item is exactly
        // one greater — a renumbered chunk would restart at 1
        val lastOfFirst =
            Regex("(\\d+)\\. ")
                .findAll(chunks.first())
                .last()
                .groupValues[1]
                .toInt()
        val firstOfSecond = Regex("^(\\d+)\\. ").find(chunks[1])!!.groupValues[1].toInt()
        assertEquals(lastOfFirst + 1, firstOfSecond)
        // And no item was lost: every one of the 2000 markers survives exactly once
        // (MULTILINE via the option — the inline (?m) flag is not portable to JS)
        val marker = Regex("^\\d+\\. ", setOf(RegexOption.MULTILINE))
        assertEquals(2000, marker.findAll(chunks.joinToString("\n")).count())
    }

    @Test
    fun quoteWrapperClosesPerChunk() {
        val content = "> " + (1..2000).joinToString("\n> ") { "quote $it" }
        val chunks = TelegramHtml.renderChunked(content, maxMessageLength)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= maxMessageLength)
            assertTrue(chunk.startsWith("<blockquote>"), "chunk not quote-opened: ${chunk.take(30)}")
            assertTrue(chunk.endsWith("</blockquote>"), "chunk not quote-closed: ${chunk.takeLast(30)}")
            assertTelegramHtmlParseable(chunk, "quoteWrapperPerChunk")
        }
    }

    @Test
    fun quoteInsideListItemChunksAsAQuote() {
        // Many small items, each holding a short quote: every item fits whole, so the
        // chunks keep the "• <blockquote>…</blockquote>" shape (an item larger than the
        // limit alone would flatten to text, the documented list-item trade)
        val content = (1..200).joinToString("\n") { "- > quoted item $it" }
        val chunks = TelegramHtml.renderChunked(content, 200)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 200)
            assertTelegramHtmlParseable(chunk, "quoteInsideListItemChunking")
        }
        assertTrue(chunks.first().startsWith("• <blockquote>"), "structure lost: ${chunks.first().take(40)}")
    }

    @Test
    fun inlineNodesPackWithoutSplittingTags() {
        val content = (1..500).joinToString(" ") { "**bold $it** and `code $it` end" }
        val chunks = TelegramHtml.renderChunked(content, 300)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 300)
            assertTelegramHtmlParseable(chunk, "inlineNodePacking")
        }
    }

    @Test
    fun hugeWordFlattensAtEscapeUnitBoundaries() {
        val content = "a&b<c>" + "x".repeat(5000)
        val chunks = TelegramHtml.renderChunked(content, 40)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 40, "chunk of ${chunk.length}")
            assertTelegramHtmlParseable(chunk, "hugeWordFlatten")
        }
        // Losslessness of the flatten seam: stripped of tags and entities, the pieces
        // join back to the original text
        assertEquals("a&b<c>" + "x".repeat(5000), deformatHtmlForTest(chunks.joinToString("")))
    }

    @Test
    fun entityHeavyTextNeverSplitsAnEntity() {
        val content = "&<>".repeat(2000)
        val chunks = TelegramHtml.renderChunked(content, 17)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 17, "chunk of ${chunk.length}")
            assertTelegramHtmlParseable(chunk, "entityHeavy")
        }
    }

    @Test
    fun surrogatePairsStayWhole() {
        val content = "😀".repeat(3000)
        val chunks = TelegramHtml.renderChunked(content, 101)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 101, "chunk of ${chunk.length}")
            assertEquals(0, chunk.count { it.isHighSurrogate() } - chunk.count { it.isLowSurrogate() })
        }
        assertEquals(TelegramHtml.render(content), chunks.joinToString(""))
    }

    @Test
    fun blocksPackWholeWhereTheyFit() {
        val paragraph = "first paragraph"
        val code = "```\ncode line\nmore code\n```"
        val content = "$paragraph\n\n$code\n\nlast paragraph"
        val chunks = TelegramHtml.renderChunked(content, maxMessageLength)
        // Everything fits one chunk: the paragraph packs alongside the code block and
        // the trailing paragraph, separators intact
        assertEquals(listOf(TelegramHtml.render(content)), chunks)
        assertTrue(chunks.single().startsWith("$paragraph\n\n<pre>"))
    }

    @Test
    fun unclosedTagInsideOversizedParagraphChunksBalanced() {
        val content = "a <b>" + "x".repeat(4000)
        val chunks = TelegramHtml.renderChunked(content, 100)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 100)
            assertTelegramHtmlParseable(chunk, "unclosedTagChunking")
        }
    }

    @Test
    fun singleBlockDocumentChunksThatBlockAlone() {
        val document = MarkdownV2.defaultParser.parse("first\n\nsecond\n\n" + "word ".repeat(2000))!!
        val paragraph = document.firstChild!!
        val chunks = TelegramHtml.renderChunked(paragraph, maxMessageLength)
        assertTrue(chunks.size == 1)
        assertEquals("first", chunks.single())
    }

    @Test
    fun lengthBoundHoldsAcrossLimitSweep() {
        for (content in sweepContents()) {
            for (length in TelegramHtml.MIN_CHUNK_LENGTH..96) {
                val chunks = TelegramHtml.renderChunked(content, length)
                chunks.forEach { chunk ->
                    assertTrue(
                        chunk.length <= length,
                        "len=$length gave ${chunk.length}: ${chunk.take(50).replace("\n", "\\n")}",
                    )
                    assertTelegramHtmlParseable(
                        chunk,
                        "sweep len=$length of ${content.replace("\n", "\\n")}",
                    )
                }
                assertTrue(chunks.isNotEmpty(), "len=$length dropped all content")
            }
        }
    }

    @Test
    fun blankFirstFenceLineNeverChunksAnEmptyTagPair() {
        // A blank first line counts as placed (else the joining newline would be lost)
        // but carries no content: when a later over-long line forces the split, the
        // pending blank must not flush as a standalone "<pre></pre>" message — pre-fix
        // the piece guard keyed on "a line was placed" instead of "content was placed",
        // at the split and at the tail alike (a textless message is one the Bot API
        // rejects)
        val content = "```\n\n" + "a".repeat(31) + "\n```"
        val chunks = TelegramHtml.renderChunked(content, 20)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 20)
            assertFalse(chunk == "<pre></pre>", "textless chunk: $chunk")
            assertTelegramHtmlParseable(chunk, "blankFirstFenceLine")
        }
    }

    @Test
    fun trailingWhitespaceShapesChunkToTheFullRender() {
        // See the MarkdownV2 twin: a single chunk at a huge limit equals the full render
        // for every trailing-whitespace shape (spaces inside code entities are content
        // the whole-string trim cannot reach)
        for (content in listOf(
            "hello\n\n` x  `",
            "text\n\n    indented code  ",
            "```\ncode line with trailing spaces   \n```",
            "para one  \npara two after hard break",
            "> quoted with trailing   \n\nfinal para",
        )) {
            assertEquals(listOf(TelegramHtml.render(content)), TelegramHtml.renderChunked(content, 100000))
        }
    }
}

// The plain-text view of rendered HTML, for the losslessness assertions: strips the
// tags this library emits and resolves its entities (a test-side twin of the internal
// deformatHtml)
private fun deformatHtmlForTest(rendered: String): String =
    rendered
        .replace(Regex("</?[a-zA-Z][a-zA-Z0-9-]*( [^>]*)?>"), "")
        .let(::decodeEntitiesForTest)

private fun decodeEntitiesForTest(text: String): String =
    text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")

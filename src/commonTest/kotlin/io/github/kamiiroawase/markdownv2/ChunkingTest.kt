package io.github.kamiiroawase.markdownv2

import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.BulletList
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.ListItem
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lossless chunking — `MarkdownV2.renderChunked`: blocks packed whole while they fit,
 * natural-seam splits (code lines, list items, quote children, paragraph lines, inline
 * nodes), escaped-text flattening that keeps every character, and per-chunk validity
 * and length bounds.
 */
class ChunkingTest {
    @Test
    fun renderChunkedPassesParserAndOptionsThrough() {
        val parser =
            Parser
                .builder()
                .extensions(MarkdownV2.defaultExtensions + FootnotesExtension.create())
                .build()
        assertEquals(listOf("Text\n\nThe note"), MarkdownV2.renderChunked("Text[^1]\n\n[^1]: The note", parser = parser))

        val table = "| a very long cell |\n| --- |\n| b |"
        val capped = MarkdownV2.renderChunked(table, options = RenderOptions(maxCellWidth = 5))
        assertTrue(capped.single().contains("a ve…"), "maxCellWidth was not applied: ${capped.single()}")
    }

    @Test
    fun chunkedHeadingPrefixStaysGluedToText() {
        // The hashes lead the first piece instead of becoming a bare "\#" chunk detached
        // from all text
        val pieces = MarkdownV2.renderChunked("# " + "x".repeat(50), 10)
        assertEquals("\\# xxxxxxx", pieces.first())
        pieces.forEach { piece -> assertTrue(piece.length <= 10, "over-long piece: $piece") }
    }

    @Test
    fun chunkedListItemMarkerDroppedWhenLimitTooSmall() {
        // No room for the marker plus one escape unit: text wins, the marker is dropped
        // instead of a bare "1\." piece or an escapeChunks limit below its promised ≥2
        val pieces = MarkdownV2.renderChunked("1. " + "x".repeat(20), 5)
        assertTrue(pieces.isNotEmpty())
        pieces.forEach { piece ->
            assertTrue(piece.length <= 5, "over-long piece: $piece")
            assertFalse(piece.trim() == "1\\.", "bare marker piece: $piece")
        }
    }

    @Test
    fun escapeChunksOfEmptyTextIsOneEmptyPiece() {
        // appendVerbatimChunks and flattenWithLead take escaped.first()/last()
        // unconditionally — zero pieces would throw. Unreachable through the public API
        // (the isBlank guards upstream); the test pins the internal contract
        assertEquals(listOf(""), escapeChunks("", 2))
    }

    @Test
    fun renderChunkedShortContentSingleChunk() {
        assertEquals(listOf("*hello* world"), MarkdownV2.renderChunked("**hello** world"))
    }

    @Test
    fun renderChunkedSplitsAtBlockBoundaries() {
        assertEquals(listOf("aaa", "bbb"), MarkdownV2.renderChunked("aaa\n\nbbb", 3))
    }

    @Test
    fun renderChunkedSplitsParagraphLines() {
        assertEquals(listOf("aaaa\nbbbb", "cccc"), MarkdownV2.renderChunked("aaaa\nbbbb\ncccc", 9))
    }

    @Test
    fun renderChunkedSplitsInlineNodes() {
        // Boundary spaces are content — the Text nodes are "aa " and " cc " — so the
        // pieces carry their edge whitespace instead of trimming it away
        assertEquals(listOf("aa ", "_b_", " cc ", "_d_"), MarkdownV2.renderChunked("aa *b* cc *d*", 4))
    }

    @Test
    fun renderChunkedFlatteningLosesNoWhitespace() {
        // The last-resort escaped-text flattening must keep every character, spaces
        // included (pre-fix each piece was trimmed, dropping spaces at piece edges)
        val content = "x ".repeat(3000)
        val chunks = MarkdownV2.renderChunked(content, maxLength = 4096)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.length <= 4096, "over-long chunk: $it") }
        assertEquals(3000, chunks.sumOf { chunk -> chunk.count { it == 'x' } })
        assertEquals(2999, chunks.sumOf { chunk -> chunk.count { it == ' ' } })
    }

    @Test
    fun renderChunkedOversizedItemFlatteningKeepsSpaces() {
        // Same guarantee down the list-item flattening path: every content space survives
        // ("a b " x200 = 400 spaces, the paragraph-final one is parser-stripped, and the
        // kept "• " marker adds its own — 399 + 1)
        val chunks = MarkdownV2.renderChunked("- " + "a b ".repeat(200), 50)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.length <= 50, "over-long chunk: $it") }
        assertEquals(400, chunks.sumOf { chunk -> chunk.count { it == 'a' || it == 'b' } })
        assertEquals(400, chunks.sumOf { chunk -> chunk.count { it == ' ' } })
    }

    @Test
    fun renderChunkedOversizedItemKeepsFlattenedTrailingWhitespace() {
        // Code content keeps its trailing spaces where prose cannot (the parser strips
        // EOL spaces in paragraphs, fenced blocks do not), so the flattened item's text
        // ends in " \n" — the tail piece flushes raw, never trimmed (pre-fix the final
        // trimEnd dropped both characters)
        val chunks = MarkdownV2.renderChunked("- ```\n  aaaa \n  bbbb \n  ```", 10)
        assertEquals(listOf("• aaaa \nbb", "bb \n"), chunks)
        chunks.forEach { chunk -> assertTrue(chunk.length <= 10, "over-long chunk: $chunk") }
    }

    @Test
    fun renderChunkedOversizedQuoteChildKeepsFlattenedTrailingWhitespace() {
        // Same guarantee down the quote-child flattening path: "> aaaa " keeps its
        // trailing space when it flushes mid-quote (pre-fix trimmed to "> aaaa"); the
        // text's trailing newline degrades to the bare ">" continuation line
        val chunks = MarkdownV2.renderChunked("> ```\n> aaaa \n> bbbb \n> ```", 12)
        assertEquals(listOf("> aaaa ", "> bbbb \n>"), chunks)
        chunks.forEach { chunk -> assertTrue(chunk.length <= 12, "over-long chunk: $chunk") }
    }

    @Test
    fun renderChunkedOversizedInlineNodeKeepsFlattenedTrailingWhitespace() {
        // A code span's literal may end in spaces (CommonMark strips a leading/trailing
        // pair only when both ends are spaced); the flattened tail piece keeps them
        // (pre-fix the paragraph's final trimEnd dropped the two trailing spaces)
        val chunks = MarkdownV2.renderChunked("`" + "a".repeat(20) + "  `", 8)
        assertEquals(listOf("aaaaaaaa", "aaaaaaaa", "aaaa  "), chunks)
        chunks.forEach { chunk -> assertTrue(chunk.length <= 8, "over-long chunk: $chunk") }
    }

    @Test
    fun renderChunkedKeepsWhitespaceOnlyTailPiece() {
        // The flattening may leave a tail piece that is nothing but whitespace (a code
        // span's trailing spaces again) — it is text, so the final flush must emit it
        // however blank it looks (pre-fix the isNotBlank skip dropped it outright)
        val chunks = MarkdownV2.renderChunked("`" + "a".repeat(9) + " ".repeat(9) + "`", 9)
        assertEquals(listOf("aaaaaaaaa", " ".repeat(9)), chunks)
        chunks.forEach { chunk -> assertTrue(chunk.length <= 9, "over-long chunk: $chunk") }
    }

    @Test
    fun renderChunkedSplitsCodeBlockLines() {
        assertEquals(
            listOf("```\naaaa\n```", "```\nbbbb\n```", "```\ncccc\n```"),
            MarkdownV2.renderChunked("```\naaaa\nbbbb\ncccc\n```", 12),
        )
    }

    @Test
    fun renderChunkedSplitsOversizedCodeLineIntoFencedSegments() {
        assertEquals(
            List(5) { "```\naaaa\n```" },
            MarkdownV2.renderChunked("```\n" + "a".repeat(20) + "\n```", 12),
        )
    }

    @Test
    fun renderChunkedListItemsKeepNumberingAcrossChunks() {
        assertEquals(
            listOf("1\\. aaaa", "2\\. bbbb", "3\\. cccc"),
            MarkdownV2.renderChunked("1. aaaa\n2. bbbb\n3. cccc", 10),
        )
    }

    @Test
    fun renderChunkedOversizedItemFlattensWithMarkerOnFirstPiece() {
        assertEquals(listOf("• aaaa", "bbbb"), MarkdownV2.renderChunked("- aaaabbbb", 6))
    }

    @Test
    fun renderChunkedSplitsQuoteChildren() {
        assertEquals(listOf("> aaaa", "> bbbb"), MarkdownV2.renderChunked("> aaaa\n>\n> bbbb", 8))
    }

    @Test
    fun renderChunkedOversizedQuoteChildSplitsLines() {
        assertEquals(listOf("> aaaaaa", "> aaaaaa"), MarkdownV2.renderChunked("> " + "a".repeat(12), 8))
    }

    @Test
    fun renderChunkedOversizedLineDegradesToEscapedUnits() {
        assertEquals(listOf("aaaa", "\\*bbb", "b"), MarkdownV2.renderChunked("aaaa*bbbb", 5))
    }

    @Test
    fun renderChunkedNeverSplitsEscapeUnitsAndLosesNoText() {
        // '.' is a special character that never forms an entity, so the whole paragraph
        // stays plain text and the chunks' concatenation must equal the full escape
        val content = "a.b".repeat(60)
        val chunks = MarkdownV2.renderChunked(content, 7)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 7)
            assertFalse(chunk.endsWith("\\"), "escape sequence split across chunks: $chunk")
        }
        assertEquals(MarkdownV2.escape(content), chunks.joinToString(""))
    }

    @Test
    fun renderChunkedNonPositiveMaxLengthSingleChunk() {
        val content = "word ".repeat(2000)
        assertEquals(listOf(MarkdownV2.render(content)), MarkdownV2.renderChunked(content, 0))
        assertEquals(listOf(MarkdownV2.render(content)), MarkdownV2.renderChunked(content, -5))
    }

    @Test
    fun renderChunkedMaxLengthOneSingleChunk() {
        // A positive size below 2 cannot hold even one escaped special character, so it
        // joins 0 and the negatives on the no-chunking path
        val content = "word ".repeat(2000)
        assertEquals(listOf(MarkdownV2.render(content)), MarkdownV2.renderChunked(content, 1))
    }

    @Test
    fun renderChunkedDefaultLimitSplitsHugeParagraph() {
        val content = "word ".repeat(2000)
        val chunks = MarkdownV2.renderChunked(content)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.length <= maxMessageLength) }
        assertEquals(content.count { it.isLetter() }, chunks.sumOf { chunk -> chunk.count { it.isLetter() } })
    }

    @Test
    fun renderChunkedEmptyContentYieldsNoChunks() {
        assertEquals(emptyList(), MarkdownV2.renderChunked(""))
    }

    @Test
    fun renderChunkedViaAst() {
        val document = MarkdownV2.defaultParser.parse("aaa\n\nbbb")!!
        assertEquals(listOf("aaa", "bbb"), MarkdownV2.renderChunked(document, 3))
        val paragraph = document.firstChild as Paragraph
        assertEquals(listOf("aaa"), MarkdownV2.renderChunked(paragraph, 3))
    }

    @Test
    fun renderChunkedEmptyRenderingBlocksYieldNoChunksViaAst() {
        val htmlComment = HtmlBlock()
        htmlComment.literal = "<!-- hidden -->"
        assertEquals(emptyList(), MarkdownV2.renderChunked(htmlComment, 30))
        assertEquals(emptyList(), MarkdownV2.renderChunked(TableBlock(), 30))
    }

    @Test
    fun renderChunkedTableDegradesToFencedChunks() {
        val chunks =
            MarkdownV2.renderChunked(
                "| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |",
                30,
            )
        assertEquals(4, chunks.size)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 30)
            assertTrue(chunk.startsWith("```"))
            assertTrue(chunk.endsWith("```"))
        }
    }

    @Test
    fun renderChunkedMixedDocumentAllChunksBounded() {
        val content =
            """
            # Title here

            > quoted line one
            > quoted line two

            - first item
            - second item

            ```
            code one
            code two
            ```
            """.trimIndent()
        val chunks = MarkdownV2.renderChunked(content, 30)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.isNotBlank())
            assertTrue(chunk.length <= 30, "over-long chunk: $chunk")
        }
    }

    @Test
    fun renderChunkedBareUrlDegradationLosesNoText() {
        // An empty-label link/image renders to the bare escaped URL; when that alone
        // exceeds the limit, the flattening path must carry every character (pre-fix the
        // blank plain-text extraction dropped the whole content and the call returned no
        // chunks at all)
        val url = "https://example.com/" + "a".repeat(5000)
        for (content in listOf("![]($url)", "[]($url)")) {
            val chunks = MarkdownV2.renderChunked(content, 4096)
            assertTrue(chunks.isNotEmpty(), "content was lost entirely")
            chunks.forEach { chunk -> assertTrue(chunk.length <= 4096, "over-long chunk: ${chunk.take(60)}") }
            assertEquals(MarkdownV2.escape(url), chunks.joinToString(""), "text lost for: ${content.take(20)}")
        }
    }

    @Test
    fun renderChunkedFlattenedLinkKeepsItsUrl() {
        // The flattening seam used to keep only the label — the URL vanished, where
        // toPlainText deliberately keeps the `label (url)` shape; the two fallback paths
        // must agree
        val chunks = MarkdownV2.renderChunked("[x](y)", maxLength = 3)
        assertEquals(listOf("x ", "\\(y", "\\)"), chunks)
        assertEquals("x (y)", MarkdownV2.toPlainText(chunks.joinToString("")))
    }

    @Test
    fun renderChunkedFlattenedItemDropsInvisibleInlineMarkup() {
        // The flattening fallback's plain text must agree with the renderer: "<!"/"<?"
        // inline markup drops (pre-fix only "<!--" was filtered, so declarations leaked
        // into the flattened chunks)
        val item =
            ListItem().apply {
                appendChild(
                    Paragraph().apply {
                        appendChild(Text("a".repeat(3000)))
                        appendChild(HtmlInline("<!D decl>"))
                        appendChild(Text("b".repeat(3000)))
                    },
                )
            }
        val list = BulletList().apply { appendChild(item) }
        val chunks = MarkdownV2.renderChunked(list, 20)
        chunks.forEach { chunk -> assertTrue(chunk.length <= 20, "over-long chunk: $chunk") }
        assertEquals("• " + "a".repeat(3000) + "b".repeat(3000), chunks.joinToString(""))
    }

    @Test
    fun renderChunkedFlattenedItemDropsInvisibleBlock() {
        // Same agreement for comment/declaration HTML blocks: the renderer drops them
        // (visit(htmlBlock)), so the flattened text cannot carry them either (pre-fix the
        // block literal leaked in verbatim)
        val item =
            ListItem().apply {
                appendChild(
                    HtmlBlock().apply {
                        literal = "<!-- hidden -->\n<!DOCTYPE doc>\n"
                    },
                )
                appendChild(Paragraph().apply { appendChild(Text("a".repeat(3000))) })
            }
        val list = BulletList().apply { appendChild(item) }
        val chunks = MarkdownV2.renderChunked(list, 20)
        chunks.forEach { chunk -> assertTrue(chunk.length <= 20, "over-long chunk: $chunk") }
        assertEquals("• " + "a".repeat(3000), chunks.joinToString(""))
    }

    @Test
    fun chunkLengthBoundHoldsAcrossLimitSweep() {
        // The per-chunk length bound checked across a sweep of limits, not just the
        // hand-picked ones (the sibling sweep in TruncationTest covers the render side;
        // the sweep starts at 2 because 1 and below return the full render as a single
        // chunk by contract)
        for (content in sweepContents(htmlParsingSupported)) {
            for (length in 2..96) {
                for (chunk in MarkdownV2.renderChunked(content, length)) {
                    assertTrue(
                        chunk.length <= length,
                        "len=$length gave ${chunk.length}: ${chunk.take(50).replace("\n", "\\n")}",
                    )
                }
            }
        }
    }
}

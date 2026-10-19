package io.github.kamiiroawase.markdownv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `<blockquote>`/`<blockquote expandable>` HTML-block spelling: the one block-level
 * input HTML the library maps instead of degrading. The parse rewrite turns the block
 * into a real BlockQuote (marked expandable by an inert first-child node), so every
 * pipeline — full render, truncation, chunking, in all three formats — handles it with
 * the quote machinery it already ships. The MarkdownV2 grammar marks ONLY the first
 * line ("**>"), continuation lines keep ordinary ">" markers; the entities protocol
 * carries its own expandable_blockquote type; the HTML format passes the wrapper
 * through with Telegram's expandable attribute.
 *
 * The input is the block form the parser's HTML-block recognition accepts: the open tag
 * on its own line, the content after it, the closing tag last (the known backend
 * deviation — a tag sharing its line with content parses as inline HTML — applies to
 * every block-level tag alike, `<div>` included).
 */
class ExpandableQuoteTest {
    private val expandable = "<blockquote expandable>\nquoted\n</blockquote>"
    private val regular = "<blockquote>\nquoted\n</blockquote>"

    // ---------- MarkdownV2 ----------

    @Test
    fun markdownV2RendersTheExpandableOpener() {
        val output = MarkdownV2.render(expandable)
        assertEquals("**> quoted", output)
        assertTelegramParseable(output, "mdv2Expandable")
    }

    @Test
    fun markdownV2RendersPlainBlockquoteTagAsARegularQuote() {
        val output = MarkdownV2.render(regular)
        assertEquals("> quoted", output)
        assertTelegramParseable(output, "mdv2RegularTag")
    }

    @Test
    fun markdownV2MarksOnlyTheFirstLine() {
        // The official grammar: "**>" opens the first line, continuation lines take the
        // ordinary markers
        val output = MarkdownV2.render("<blockquote expandable>\na\nb\n</blockquote>")
        assertEquals("**> a\n> b", output)
        assertTelegramParseable(output, "mdv2ExpandableContinuation")
    }

    @Test
    fun markdownV2InnerContentParsesAsMarkdown() {
        // The quote's children are what the inner source parses to — emphasis, links
        // and nested quote spellings included
        val output = MarkdownV2.render("<blockquote expandable>\n*b* [l](https://e.com/u)\n</blockquote>")
        assertEquals("**> _b_ [l](https://e.com/u)", output)
        assertTelegramParseable(output, "mdv2ExpandableInline")

        val nested = MarkdownV2.render("<blockquote expandable>\n<blockquote>\ndeep\n</blockquote>\n</blockquote>")
        assertEquals("**>> deep", nested)
        assertTelegramParseable(nested, "mdv2ExpandableNested")
    }

    @Test
    fun markdownV2TagNamesAreCaseInsensitive() {
        val output = MarkdownV2.render("<BLOCKQUOTE EXPANDABLE>\nx\n</BLOCKQUOTE>")
        assertEquals("**> x", output)
        assertTelegramParseable(output, "mdv2ExpandableCase")
    }

    @Test
    fun markdownV2MalformedOrAttriredBlocksStillDegrade() {
        // No closing tag, or any attribute beyond the bare expandable flag: the block
        // keeps the ordinary html-block degradation (escaped source in a fence)
        assertEquals(
            "```\n<blockquote expandable>\nx\n```",
            MarkdownV2.render("<blockquote expandable>\nx"),
        )
        assertEquals(
            "```\n<blockquote class=\"c\">\nx\n</blockquote>\n```",
            MarkdownV2.render("<blockquote class=\"c\">\nx\n</blockquote>"),
        )
    }

    @Test
    fun markdownV2TruncationReservesTheLead() {
        // 18 = the lead (2) plus the full prefixed line (16): fits exactly; one less
        // and the quote's first child no longer fits behind the lead
        assertEquals(
            "**> aaaa bbbb cccc",
            MarkdownV2.render("<blockquote expandable>\naaaa bbbb cccc\n</blockquote>", 18),
        )
        val tighter = MarkdownV2.render("<blockquote expandable>\naaaa bbbb cccc\n</blockquote>", 15)
        assertTrue(tighter.startsWith("**>") || tighter.isEmpty(), "lead or nothing: $tighter")
        assertTelegramParseable(tighter, "mdv2ExpandableTruncated")
    }

    @Test
    fun markdownV2ChunkingCarriesTheLeadOnTheFirstPiece() {
        // Two children (a paragraph and an interrupting heading): the lead rides the
        // first chunk's first line; the continuation chunk keeps ordinary markers
        val src = "<blockquote expandable>\nfirst words\n# head</blockquote>"
        val pieces = MarkdownV2.renderChunked(src, 16)
        assertEquals(listOf("**> first words", "> \\# head"), pieces)
        pieces.forEachIndexed { i, piece -> assertTelegramParseable(piece, "mdv2ExpandableChunk$i") }
    }

    // ---------- HTML ----------

    @Test
    fun htmlPassesTheExpandableWrapperThrough() {
        val output = TelegramHtml.render(expandable)
        assertEquals("<blockquote expandable>quoted</blockquote>", output)
        assertTelegramHtmlParseable(output, "htmlExpandable")
    }

    @Test
    fun htmlPlainBlockquoteTagRendersARegularWrapper() {
        val output = TelegramHtml.render(regular)
        assertEquals("<blockquote>quoted</blockquote>", output)
        assertTelegramHtmlParseable(output, "htmlRegularTag")
    }

    @Test
    fun htmlNestedSpellingFlattensIntoOneWrapper() {
        val output = TelegramHtml.render("<blockquote expandable>\n<blockquote>\ndeep\n</blockquote>\n</blockquote>")
        assertEquals("<blockquote expandable>deep</blockquote>", output)
        assertTelegramHtmlParseable(output, "htmlExpandableNested")
    }

    @Test
    fun htmlTruncationCountsTheWiderWrapper() {
        // 22 + 8 + 13 = 43; the truncation reserves one more for the ellipsis, so the
        // whole quote survives from 44 and one less shrinks the content under the
        // wider wrapper — the ellipsis itself lands after the closing tag
        val src = "<blockquote expandable>\naaaa bbb\n</blockquote>"
        assertEquals("<blockquote expandable>aaaa bbb</blockquote>", TelegramHtml.render(src, 44))
        assertEquals("<blockquote expandable>aaaa b</blockquote>…", TelegramHtml.render(src, 43))
    }

    @Test
    fun htmlChunkingReopensTheExpandableWrapperPerPiece() {
        val pieces =
            TelegramHtml.renderChunked(
                "<blockquote expandable>\naaaa bbbb cccc dddd eeee ffff gggg\n</blockquote>",
                60,
            )
        assertTrue(pieces.size > 1, "the quote must split: $pieces")
        pieces.forEachIndexed { i, piece ->
            assertTrue(piece.startsWith("<blockquote expandable>"), "piece $i opens: $piece")
            assertTrue(piece.endsWith("</blockquote>"), "piece $i closes: $piece")
            assertTelegramHtmlParseable(piece, "htmlExpandableChunk$i")
        }
    }

    // ---------- entities ----------

    @Test
    fun entitiesEmitTheExpandableBlockquoteRange() {
        val result = TelegramEntities.render(expandable)
        assertEquals(
            EntityText("quoted", listOf(MessageEntity(MessageEntityType.EXPANDABLE_BLOCKQUOTE, 0, 6))),
            result,
        )
        assertEntitiesValid(result, "entitiesExpandable")
    }

    @Test
    fun entitiesNestedSpellingShipsTheDocumentedOverlap() {
        // The string formats flatten a nested quote into the outer wrapper; the
        // entities twin ships both ranges over the same span — the documented
        // nested-quote deviation the server repairs silently, now across the quote
        // family's two types
        val result = TelegramEntities.render("<blockquote expandable>\n<blockquote>\ndeep\n</blockquote>\n</blockquote>")
        assertEquals(
            EntityText(
                "deep",
                listOf(
                    MessageEntity(MessageEntityType.BLOCKQUOTE, 0, 4),
                    MessageEntity(MessageEntityType.EXPANDABLE_BLOCKQUOTE, 0, 4),
                ),
            ),
            result,
        )
        assertEntitiesValid(result, "entitiesExpandableNested")
    }

    @Test
    fun entitiesTruncationKeepsTheRangeType() {
        val result = TelegramEntities.render("<blockquote expandable>\naaaa bbbb cccc\n</blockquote>", 9)
        assertEquals(MessageEntityType.EXPANDABLE_BLOCKQUOTE, result.entities.single().type)
        assertTrue(result.text.length <= 9, "budget honored: ${result.text}")
        assertEntitiesValid(result, "entitiesExpandableTruncated")
    }

    @Test
    fun entitiesChunkingReopensTheRangePerPiece() {
        val pieces =
            TelegramEntities.renderChunked(
                "<blockquote expandable>\naaaa bbbb cccc dddd eeee ffff\n</blockquote>",
                20,
            )
        assertTrue(pieces.size > 1, "the quote must split: $pieces")
        pieces.forEachIndexed { i, piece ->
            assertEquals(MessageEntityType.EXPANDABLE_BLOCKQUOTE, piece.entities.first().type, "piece $i")
            assertEntitiesValid(piece, "entitiesExpandableChunk$i")
        }
    }

    // ---------- the plain-text round trip ----------

    @Test
    fun toPlainTextStripsTheExpandableMarkers() {
        // The de-format reads the lead back off the first line and the ordinary markers
        // off the rest: an expandable quote's plain text equals a regular quote's
        val expandablePlain = MarkdownV2.toPlainText(MarkdownV2.render("<blockquote expandable>\na\nb\n</blockquote>"))
        val regularPlain = MarkdownV2.toPlainText(MarkdownV2.render("> a\n> b"))
        assertEquals(regularPlain, expandablePlain)
    }

    // ---------- the re-audit round: decoration separators, seam lead, matcher strictness ----------

    @Test
    fun decoratedChunkingSeparatesTheHeaderFromTheLead() {
        // separatorNeeded must recognize the "**>" piece head too: a header not ending
        // in a newline would otherwise glue onto the flag, and mid-line "**" reads as a
        // bold opener ahead of a bare unescaped '>' — the exact 400 the separator
        // exists to prevent (pre-fix piece 0: "Part:**> quoted aaaa bbbb")
        val pieces =
            MarkdownV2.renderChunked(
                "<blockquote expandable>\nquoted aaaa bbbb\nmore quote cccc dddd eeee ffff\n</blockquote>",
                60,
                decoration = ChunkDecoration(header = { _, _ -> "Part:" }),
            )
        assertEquals(listOf("Part:\n**> quoted aaaa bbbb\n> more quote cccc dddd eeee ffff"), pieces)
        pieces.forEachIndexed { i, piece -> assertTelegramParseable(piece, "decoratedLead$i") }
    }

    @Test
    fun chunkingSeamCarriesTheLeadWhenItFits() {
        // The structured seam reserves the lead's width while it is pending: the same
        // input that keeps the flag whole at 55 keeps it on the seam's first piece at
        // 50 (pre-fix the pieces silently downgraded to a regular quote)
        val pieces =
            MarkdownV2.renderChunked(
                "<blockquote expandable>\nquoted aaaa bbbb\nmore quote cccc dddd eeee ffff\n</blockquote>",
                50,
            )
        assertEquals(listOf("**> quoted aaaa bbbb", "> more quote cccc dddd eeee ffff"), pieces)
        pieces.forEachIndexed { i, piece -> assertTelegramParseable(piece, "seamLead$i") }
    }

    @Test
    fun matcherAdmitsNoNewlineInsideTheTag() {
        // A tag split across lines is no tag: the block keeps the ordinary html-block
        // degradation (pre-tightening the \s in the attribute gap matched the newline)
        assertEquals(
            "```\n<blockquote\nexpandable>\nx\n</blockquote>\n```",
            MarkdownV2.render("<blockquote\nexpandable>\nx\n</blockquote>"),
        )
    }
}

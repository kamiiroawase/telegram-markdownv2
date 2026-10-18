package io.github.kamiiroawase.markdownv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The decorated renderChunked overloads — ChunkDecoration headers/footers on every piece
 * across all three formats: page counters, fixed signatures, entity offsets shifted past
 * the header, the converging re-chunk when decorations eat into the budget, and the
 * fail-fast when they leave no room at all.
 */
class ChunkDecorationTest {
    @Test
    fun fixedFooterOnEveryPieceMdv2() {
        val content = "word ".repeat(2000)
        val chunks =
            MarkdownV2.renderChunked(
                content,
                maxMessageLength,
                decoration = ChunkDecoration(footer = { _, _ -> "\n\n\\- mybot" }),
            )
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= maxMessageLength, "chunk of ${chunk.length}")
            assertTrue(chunk.endsWith("\\- mybot"), "footer missing: ...${chunk.takeLast(20)}")
            assertTelegramParseable(chunk, "fixedFooter")
        }
        // Losslessness of the payload: stripping decorations joins back to the full render
        assertEquals(
            MarkdownV2.render(content),
            chunks.joinToString("") { it.removeSuffix("\n\n\\- mybot") },
        )
    }

    @Test
    fun pageNumberHeaderOnEveryPiece() {
        val content = "paragraph ".repeat(3000)
        val chunks =
            MarkdownV2.renderChunked(
                content,
                200,
                decoration =
                    ChunkDecoration(header = {
                        index,
                        total,
                        ->
                        "Part ${index + 1}/$total\n\n"
                    }),
            )
        assertTrue(chunks.size > 1)
        val total = chunks.size
        chunks.forEachIndexed { index, chunk ->
            assertTrue(chunk.length <= 200, "chunk of ${chunk.length}")
            assertTrue(chunk.startsWith("Part ${index + 1}/$total\n\n"), "page marker wrong: ${chunk.take(20)}")
            assertTelegramParseable(chunk, "pageNumber")
        }
    }

    @Test
    fun decorationRechunkingKeepsPageNumbersConsistent() {
        // The page counter's own width grows with the total ("1/9" vs "1/10"); the engine
        // re-splits until the widest decoration fits — the final numbers must be exactly
        // the piece count the reader sees
        val content = (1..2000).joinToString("\n") { "line $it" }
        val chunks =
            TelegramHtml.renderChunked(
                content,
                300,
                decoration = ChunkDecoration(header = { index, total -> "📄 ${index + 1}/$total\n" }),
            )
        assertTrue(chunks.size > 1)
        val total = chunks.size
        chunks.forEachIndexed { index, chunk ->
            assertTrue(chunk.length <= 300, "chunk of ${chunk.length}")
            assertTrue(chunk.startsWith("📄 ${index + 1}/$total\n"), "marker wrong: ${chunk.take(20)}")
            assertTelegramHtmlParseable(chunk, "rechunkPageNumbers")
        }
    }

    @Test
    fun singleChunkKeepsItsDecoration() {
        val chunks =
            TelegramEntities.renderChunked(
                "short **bold**",
                decoration = ChunkDecoration(header = { _, _ -> "Part 1/1\n\n" }),
            )
        assertEquals(
            listOf(EntityText("Part 1/1\n\nshort bold", listOf(MessageEntity(MessageEntityType.BOLD, 16, 4)))),
            chunks,
        )
        assertEntitiesValid(chunks.single(), "singleChunk")
    }

    @Test
    fun entityOffsetsShiftPastHeader() {
        val chunks = TelegramEntities.renderChunked("**bold** tail", 4096, decoration = ChunkDecoration(header = { _, _ -> ">> " }))
        val piece = chunks.single()
        assertEquals(">> bold tail", piece.text)
        assertEquals(listOf(MessageEntity(MessageEntityType.BOLD, 3, 4)), piece.entities)
        assertEntitiesValid(piece, "offsetShift")
    }

    @Test
    fun htmlDecorationMarkupPassesThroughUnescaped() {
        // Decorations are the caller's own markup: a deliberate tag goes through as-is
        val chunks = TelegramHtml.renderChunked("body text", 100, decoration = ChunkDecoration(header = { _, _ -> "<b>Head</b>\n\n" }))
        assertEquals("<b>Head</b>\n\nbody text", chunks.single())
        assertTelegramHtmlParseable(chunks.single(), "deliberateTag")
    }

    @Test
    fun emptyContentYieldsEmptyListUndecorated() {
        assertEquals(emptyList(), MarkdownV2.renderChunked("<!-- c -->", decoration = ChunkDecoration(header = { _, _ -> "x" })))
    }

    @Test
    fun oversizedDecorationFailsFast() {
        assertFailsWith<IllegalArgumentException> {
            MarkdownV2.renderChunked("content", 10, decoration = ChunkDecoration(header = { _, _ -> "0123456789ABCDEF" }))
        }
        assertFailsWith<IllegalArgumentException> {
            TelegramHtml.renderChunked("content", 8, decoration = ChunkDecoration(footer = { _, _ -> "0123456789" }))
        }
        assertFailsWith<IllegalArgumentException> {
            TelegramEntities.renderChunked("content", 3, decoration = ChunkDecoration(header = { _, _ -> "0123" }))
        }
    }

    @Test
    fun decoratedNodeOverloadPassesThrough() {
        val document = MarkdownV2.defaultParser.parse("a\n\n" + "word ".repeat(500))!!
        val chunks =
            TelegramEntities.renderChunked(
                document,
                200,
                decoration =
                    ChunkDecoration(
                        header = { index, total -> "[$index/$total] " },
                        footer = { _, _ -> "\n--" },
                    ),
            )
        assertTrue(chunks.size > 1)
        chunks.forEachIndexed { index, chunk ->
            assertTrue(chunk.text.length <= 200, "chunk of ${chunk.text.length}")
            assertTrue(chunk.text.startsWith("[$index/${chunks.size}] "), "marker wrong: ${chunk.text.take(12)}")
            assertEntitiesValid(chunk, "nodeOverload")
        }
    }
}

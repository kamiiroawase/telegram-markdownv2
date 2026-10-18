package io.github.kamiiroawase.markdownv2

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Seeded random-document property tests — the hand-rolled, zero-dependency version of a
 * fuzzing pass: a deterministic generator composes documents from the vocabulary every
 * pipeline must survive (emphasis, code, links, quotes, lists, task lists, tables,
 * footnotes, autolinks, raw HTML, emoji), and every seed asserts the invariants the
 * library promises for all three formats:
 *
 * - full renders parse (the tdlib MarkdownV2 and HTML rule ports) / validate (entities);
 * - truncation at any limit respects the limit;
 * - chunking at any limit produces in-limit pieces that each parse / validate, with no
 *   piece dropped;
 * - decorated chunking keeps every piece parseable and in limit.
 *
 * A failure names its seed; the generator is fully deterministic, so the failing
 * document reproduces exactly.
 */
class PropertyTest {
    private val words =
        listOf(
            "alpha",
            "beta",
            "gamma",
            "x",
            "somewhatlongerword",
            "a&b",
            "<tag>",
            "😀",
            "bit `code` bit",
            "*star*",
            "[bracket]",
            "~~strike~~",
            "__under__",
            "https://e.com/p?a=1",
            "foo@bar.com",
            "- dash",
            "> quote",
            "# hash",
            "1. num",
            "| pipe |",
        )

    private fun randomWord(random: Random): String = words[random.nextInt(words.size)]

    private fun randomLine(random: Random): String =
        (1..random.nextInt(1, 8)).joinToString(" ") {
            when (random.nextInt(8)) {
                0 -> "**${randomWord(random)}**"
                1 -> "*${randomWord(random)}*"
                2 -> "`${randomWord(random)}`"
                3 -> "[${randomWord(random)}](https://e.com/$it)"
                4 -> "~~${randomWord(random)}~~"
                5 -> randomWord(random)
                6 -> randomWord(random)
                else -> randomWord(random)
            }
        }

    private fun randomContent(random: Random): String =
        buildString {
            repeat(random.nextInt(1, 10)) {
                when (random.nextInt(12)) {
                    0 -> {
                        append(randomLine(random))
                    }

                    1 -> {
                        append("#".repeat(random.nextInt(1, 7))).append(' ').append(randomLine(random))
                    }

                    2 -> {
                        append("> ").append(randomLine(random)).append("\n>\n> ").append(randomLine(random))
                    }

                    3 -> {
                        repeat(random.nextInt(1, 5)) { append("- ").append(randomLine(random)).append('\n') }
                    }

                    4 -> {
                        repeat(random.nextInt(1, 4)) {
                            append(random.nextInt(1, 9)).append(". ").append(randomLine(random)).append('\n')
                        }
                    }

                    5 -> {
                        append("```\n")
                            .append(randomLine(random))
                            .append('\n')
                            .append(randomLine(random))
                            .append("\n```")
                    }

                    6 -> {
                        append("| a | b |\n| --- | --- |\n| ")
                            .append(randomWord(random))
                            .append(" | ")
                            .append(randomWord(random))
                            .append(" |")
                    }

                    7 -> {
                        append("- [ ] ").append(randomLine(random)).append("\n- [x] ").append(randomLine(random))
                    }

                    8 -> {
                        append(randomLine(random)).append(" [^1]\n\n[^1]: ").append(randomLine(random))
                    }

                    9 -> {
                        // A quoted list item holding a fenced code block: the fence
                        // opens behind the item marker and closes behind the continuation
                        // indent, so the quote seam's fence pairing must see through
                        // list structure (a bare line-start read stranded unterminated
                        // fences across chunks)
                        append("> - ```\n")
                        repeat(random.nextInt(2, 10)) {
                            append(">   code ").append(randomWord(random)).append('\n')
                        }
                        append(">   ```")
                    }

                    10 -> {
                        // The table twin: a quoted ordered item whose GFM table renders
                        // fence-wrapped behind the escaped-dot marker
                        append("> 1. | a | b |\n>    | --- | --- |\n>    | ")
                            .append(randomWord(random))
                            .append(" | ")
                            .append(randomWord(random))
                            .append(" |")
                    }

                    else -> {
                        append("<div>\n").append(randomLine(random)).append("\n</div>")
                    }
                }
                append("\n\n")
            }
        }

    @Test
    fun randomDocumentsHoldEveryInvariant() {
        for (seed in 0 until 150) {
            val random = Random(seed)
            val content = randomContent(random)
            val context = { what: String -> "$what (seed=$seed): ${content.replace("\n", "\\n").take(80)}" }

            // Full renders
            assertTelegramParseable(MarkdownV2.render(content), context("mdv2 render"))
            assertTelegramHtmlParseable(TelegramHtml.render(content), context("html render"))
            assertEntitiesValid(TelegramEntities.render(content), context("entities render"))

            // Truncation at assorted limits
            for (limit in intArrayOf(1, 7, 40, 200, maxMessageLength)) {
                assertTrue(
                    MarkdownV2.render(content, limit).length <= limit,
                    context("mdv2 truncate $limit"),
                )
                assertTrue(
                    TelegramHtml.render(content, limit).length <= limit,
                    context("html truncate $limit"),
                )
                val truncated = TelegramEntities.render(content, limit)
                assertTrue(truncated.text.length <= limit, context("entities truncate $limit"))
                assertEntitiesValid(truncated, context("entities truncate $limit"))
            }

            // Chunking at assorted limits
            for (limit in intArrayOf(12, 60, maxMessageLength)) {
                MarkdownV2.renderChunked(content, limit).forEach { piece ->
                    assertTrue(piece.length <= limit, context("mdv2 chunk $limit"))
                    assertTelegramParseable(piece, context("mdv2 chunk $limit"))
                }
                TelegramHtml.renderChunked(content, limit).forEach { piece ->
                    assertTrue(piece.length <= limit, context("html chunk $limit"))
                    assertTelegramHtmlParseable(piece, context("html chunk $limit"))
                }
                TelegramEntities.renderChunked(content, limit).forEach { piece ->
                    assertTrue(piece.text.length <= limit, context("entities chunk $limit"))
                    assertEntitiesValid(piece, context("entities chunk $limit"))
                }
            }

            // Decorated chunking stays parseable and in limit
            val decorated =
                MarkdownV2.renderChunked(
                    content,
                    100,
                    decoration = ChunkDecoration(header = { index, total -> "p${index + 1}/$total " }),
                )
            decorated.forEach { piece ->
                assertTrue(piece.length <= 100, context("mdv2 decorated"))
                assertTelegramParseable(piece, context("mdv2 decorated"))
            }
        }
    }
}

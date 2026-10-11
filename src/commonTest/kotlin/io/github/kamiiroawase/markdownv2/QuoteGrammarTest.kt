package io.github.kamiiroawase.markdownv2

import kotlin.test.Test

// Every quote/list shape the renderer can meet: the Bot API accepts an unescaped '>' only
// at a line start (see assertQuoteMarkersLegal), so quote markers must never end up
// behind a list marker or a continuation indent — the shapes a quote nested in a list
// item would naturally produce. Each corpus entry must survive render, truncation at a
// range of budgets and chunking at a range of limits with every output piece legal.
private val quoteListCorpus =
    listOf(
        "> q",
        "> a\n> b",
        "> > n",
        ">>> deep",
        "> - i",
        "> - > q",
        "> > - q",
        "- > q",
        "1. > q",
        "10. > q",
        "- > q\n- x",
        "- a\n\n  > q",
        "- > a\n  > b",
        "- > > d",
        "- > first\n\n  second para",
        "- > a\n>\n  > b",
        "- > ```\n  > code\n  > ```",
        "- > # h",
        "- a\n  - > q",
        "- > [label](https://example.com/u)",
        "- > q with > mid and 1 < 2",
        "> - a\n> - > b",
        // '>' inside code entities and link URLs is ordinary content (tdlib narrows the
        // reserved set inside code, and the URL is scanned by its own ')'-terminated
        // loop) — these pin the validator's legal states against over-tightening
        "> ```\n> > tag\n> ```",
        "> `<x>` and [l](https://example.com/a>b)",
    )

class QuoteGrammarTest {
    @Test
    fun rendersObeyTelegramsQuoteMarkerGrammar() {
        quoteListCorpus.forEach { content ->
            assertQuoteMarkersLegal(MarkdownV2.render(content), "render of: ${content.replace("\n", "\\n")}")
        }
    }

    @Test
    fun truncationsObeyTelegramsQuoteMarkerGrammar() {
        quoteListCorpus.forEach { content ->
            for (maxLength in 1..30) {
                assertQuoteMarkersLegal(
                    MarkdownV2.render(content, maxLength),
                    "render maxLength=$maxLength of: ${content.replace("\n", "\\n")}",
                )
            }
        }
    }

    @Test
    fun chunksObeyTelegramsQuoteMarkerGrammar() {
        quoteListCorpus.forEach { content ->
            for (maxLength in listOf(2, 5, 9, 17, 33, 80)) {
                MarkdownV2.renderChunked(content, maxLength).forEachIndexed { index, piece ->
                    assertQuoteMarkersLegal(
                        piece,
                        "chunk $index of $maxLength of: ${content.replace("\n", "\\n")}",
                    )
                }
            }
        }
    }
}

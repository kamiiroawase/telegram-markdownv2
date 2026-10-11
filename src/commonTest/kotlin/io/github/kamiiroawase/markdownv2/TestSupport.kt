package io.github.kamiiroawase.markdownv2

// Shared helpers of the behavior-level test suites (exact-output assertions, nothing
// about internal structure)

internal val maxMessageLength = MarkdownV2.MAX_MESSAGE_LENGTH

// The reserved set of tdlib's parse_markdown_v2 minus '\n', which the validator below
// handles on its own branch: the Bot API's MarkdownV2 parser, the authority on what it
// accepts, leaves these entity-marker characters outside its can_start_blockquote bookkeeping
private val TELEGRAM_RESERVED_MARKERS = "_*[]()~`>#+-=|{}.!".toSet()

/**
 * Asserts [output] obeys the Telegram MarkdownV2 grammar for '>' — a faithful port of the
 * can_start_blockquote rule tdlib's parse_markdown_v2 applies. An unescaped '>' may open or
 * continue a blockquote only while the flag holds: set at message start and by an unescaped
 * newline, cleared by any ordinary character (a letter, a space, a bullet), left untouched by
 * the reserved marker characters and '\r' (how glued nesting runs like ">>" stay legal). A
 * '>' the rule rejects is exactly the "Character '>' is reserved and must be escaped with
 * the preceding '\'" 400 the Bot API answers with — which is why every render, truncation
 * and chunk piece must pass here.
 *
 * '>' inside code entities is ordinary content (inside pre/code tdlib narrows the reserved
 * set to the backtick alone), and a link URL is consumed by a dedicated scan that stops at
 * the unescaped ')' — a '>' there never meets the rule — so the validator tracks those
 * three states by the library's own shapes: ``` fences, single-backtick code spans, and
 * ]( … ) link tails.
 */
internal fun assertQuoteMarkersLegal(
    output: String,
    context: String,
) {
    var canStart = true
    var inFence = false
    var inCodeSpan = false
    var inLinkUrl = false
    var index = 0
    while (index < output.length) {
        val char = output[index]
        if (char == '\\' && index + 1 < output.length && output[index + 1].code in 1..126) {
            canStart = output[index + 1] == '\n'
            index += 2
            continue
        }
        if (inLinkUrl) {
            if (char == ')') inLinkUrl = false
            index++
            continue
        }
        if (inFence || inCodeSpan) {
            when {
                char == '`' && inFence && isFenceCloseAt(output, index) -> {
                    inFence = false
                    index += 3
                }

                char == '`' && !inFence -> {
                    inCodeSpan = false
                    index++
                }

                else -> {
                    index++
                }
            }
            continue
        }
        when {
            char == '\n' -> {
                canStart = true
            }

            char == '\r' -> {}

            char == '`' && isFenceOpenAt(output, index) -> {
                inFence = true
                index += 3
            }

            char == '`' -> {
                inCodeSpan = true
                index++
            }

            char == ']' && index + 1 < output.length && output[index + 1] == '(' -> {
                inLinkUrl = true
                index += 2
            }

            char in TELEGRAM_RESERVED_MARKERS -> {
                if (char == '>' && !canStart) {
                    throw AssertionError(
                        "unquoted-marker '>' at index $index Telegram rejects ($context): $output",
                    )
                }
            }

            // An ordinary character clears the flag unless it is a UTF-16 low surrogate —
            // a continuation unit, not a character start (the UTF-8 twin in tdlib)
            else -> {
                if (char.code !in 0xDC00..0xDFFF) canStart = false
            }
        }
        index++
    }
}

// A ``` run: the library opens and closes every fence with exactly three backticks (the
// language string is filtered to letters/digits/+/-/#), so a bare length check pins it
private fun isFenceOpenAt(
    output: String,
    index: Int,
): Boolean = index + 2 < output.length && output[index + 1] == '`' && output[index + 2] == '`'

private fun isFenceCloseAt(
    output: String,
    index: Int,
): Boolean = isFenceOpenAt(output, index)

/** Resolves the library's escape sequences (\ + special char); a lone backslash stays. */
internal fun removeEscapes(text: String): String =
    buildString {
        var index = 0
        while (index < text.length) {
            if (text[index] == '\\' && index + 1 < text.length) {
                append(text[index + 1])
                index += 2
            } else {
                append(text[index])
                index++
            }
        }
    }

// Structured shapes for the limit-sweep invariant tests: every shrink/seam path (the four
// fence-wrapped kinds, lists and nesting, quotes, headings, paragraph inline nodes,
// surrogate pairs) at a range of limits. The exact-output tests pin hand-picked lengths;
// the budget arithmetic only breaks at specific boundary lengths, which a sweep crosses.
// The HTML-block shape joins only where the platform parses HTML (htmlParsingSupported).
internal fun sweepContents(htmlParsingSupported: Boolean): List<String> =
    buildList {
        add("```\nabcdefghij\nklmnopqrst\nuvwx\n```")
        add("    indented code aaaa\n    more lines bbbb\n    third cccc")
        add("| h1 | h2 |\n| --- | --- |\n| aaaa | bbbb |\n| cccc | dddd |")
        add(
            "- aaaa bbbb cccc dddd\n" +
                "- eeee ffff gggg\n" +
                "  - hhhh iiii jjjj kkkk\n" +
                "    - llll mmmm\n" +
                "- second top",
        )
        add("1. item with a long body " + "word ".repeat(12) + "\n2. short\n3. " + "more ".repeat(15))
        add("> quote line one\n> quote line two\n>\n> - quoted item one\n> - quoted item two")
        // Quotes inside list items degrade (their '>' markers cannot sit at a line start
        // behind list structure), so the sweep also crosses the degradation's budget
        // arithmetic: multi-paragraph, fenced and nested-quote bodies
        add("- quoted aaaa bbbb\n- > cccc dddd eeee\n- > > ffff gggg")
        add("1. > aaaa bbbb cccc\n2. > ```\n   > code eeee\n   > ```")
        add("> | h1 | h2 |\n> | --- | --- |\n> | aaaa | bbbb |\n> | cccc | dddd |")
        add("- | h1 | h2 |\n  | --- | --- |\n  | aaaa | bbbb |\n  | cccc | dddd |\n- tail")
        add("# " + "heading ".repeat(20))
        // Empty headings: no line group ever glues onto the marker lead, so the sweep
        // crosses the limit below the escaped hashes' length where the lead must flatten
        add("###")
        add("######")
        // A blank-bodied item is skipped by every rendering path alike
        add("- aaaa\n-\n- bbbb")
        // A heading whose only content renders to nothing: the marker lead is the whole
        // visible output (platform-independent twin of the gated comment shape)
        add("###### ![]()")
        add("para **bold** text [link](https://example.com/path) `code` more text to push length")
        add("😀😀😀 *bold 😀* `code 😀` [😀 link](https://example.com/x)")
        if (htmlParsingSupported) add("<div>\nblock html content\nmore html\n</div>")
        if (htmlParsingSupported) add("###### <!-- c -->")
    }

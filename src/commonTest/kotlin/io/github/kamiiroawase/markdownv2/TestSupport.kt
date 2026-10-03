package io.github.kamiiroawase.markdownv2

// Shared helpers of the behavior-level test suites (exact-output assertions, nothing
// about internal structure)

internal val maxMessageLength = MarkdownV2.MAX_MESSAGE_LENGTH

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
        add("> | h1 | h2 |\n> | --- | --- |\n> | aaaa | bbbb |\n> | cccc | dddd |")
        add("- | h1 | h2 |\n  | --- | --- |\n  | aaaa | bbbb |\n  | cccc | dddd |\n- tail")
        add("# " + "heading ".repeat(20))
        add("para **bold** text [link](https://example.com/path) `code` more text to push length")
        add("😀😀😀 *bold 😀* `code 😀` [😀 link](https://example.com/x)")
        if (htmlParsingSupported) add("<div>\nblock html content\nmore html\n</div>")
    }

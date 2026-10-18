package io.github.kamiiroawase.markdownv2

// Shared helpers of the behavior-level test suites (exact-output assertions, nothing
// about internal structure)

internal val maxMessageLength = MarkdownV2.MAX_MESSAGE_LENGTH

private enum class Kind { BOLD, ITALIC, UNDERLINE, STRIKE, CODE, PRE, TEXTURL, BLOCKQUOTE }

/**
 * Asserts [output] is a message tdlib's parse_markdown_v2 — the parser the Bot API
 * actually runs — accepts: a faithful port of its accept/reject decisions, so every
 * 400 it would answer with fails here. The rules, in the source's own terms:
 *
 * - a backslash followed by any char 1..126 is an escape pair, consumed wherever it
 *   appears;
 * - outside code entities the reserved set is "_*[]()~`>#+-=|{}.!" plus newline: a
 *   reserved char must open or close the entity the parser's stack expects, else the
 *   message draws "Character 'x' is reserved"; inside code/pre the set narrows to the
 *   backtick;
 * - "_" pairs by context: it closes an italic only when the next char is not "_", and
 *   "__" is one underline unit — glued underscore markers re-pair into shapes the
 *   parser leaves unterminated ("Can't find end of the entity");
 * - ">" may open a quote only while can_start_blockquote holds: set at message start
 *   and by an unescaped newline, cleared by any ordinary character, untouched by the
 *   reserved markers and CR;
 * - a newline inside a quote ends any open entity unless the next char is ">";
 * - a link's "](url)" tail is scanned by a dedicated loop that stops at the unescaped
 * ")";
 * - the input may not end with an open entity.
 *
 * Every render, truncation and chunk piece must pass here.
 */
internal fun assertTelegramParseable(
    output: String,
    context: String,
) {
    val stack = ArrayDeque<Kind>()
    var haveBlockquote = false
    var canStart = true
    var index = 0

    // The NUL past-the-end read of std::string's operator[] — past-the-end looks like
    // U+0000, which matches none of the marker comparisons below
    fun at(i: Int): Char = if (i < output.length) output[i] else '\u0000'

    fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'
    while (index < output.length) {
        val c = output[index]
        if (c == '\\' && at(index + 1).code in 1..126) {
            canStart = at(index + 1) == '\n'
            index += 2
            continue
        }
        val top = stack.lastOrNull()
        val reserved =
            when (top) {
                Kind.CODE, Kind.PRE -> {
                    c == '`'
                }

                else -> {
                    c == '_' || c == '*' || c == '[' || c == ']' || c == '(' || c == ')' ||
                        c == '~' || c == '`' || c == '>' || c == '#' || c == '+' || c == '-' ||
                        c == '=' || c == '|' || c == '{' || c == '}' || c == '.' || c == '!' || c == '\n'
                }
            }
        if (!reserved) {
            if (c != '\r' && c.code !in 0xDC00..0xDFFF) canStart = false
            index++
            continue
        }
        val isEnd =
            when {
                stack.isEmpty() -> false
                haveBlockquote && c == '\n' && (index + 1 >= output.length || output[index + 1] != '>') -> true
                top == Kind.BOLD -> c == '*'
                top == Kind.ITALIC -> c == '_' && at(index + 1) != '_'
                top == Kind.CODE -> c == '`'
                top == Kind.PRE -> c == '`' && at(index + 1) == '`' && at(index + 2) == '`'
                top == Kind.TEXTURL -> c == ']'
                top == Kind.UNDERLINE -> c == '_' && at(index + 1) == '_'
                top == Kind.STRIKE -> c == '~'
                else -> false // BLOCKQUOTE never ends at its own marker
            }
        if (isEnd) {
            if (c == '\n' && top != Kind.BLOCKQUOTE) {
                throw AssertionError("newline inside a quote ends a $top entity ($context): $output")
            }
            when (top) {
                Kind.UNDERLINE -> {
                    index++
                }

                Kind.PRE -> {
                    index += 2
                }

                Kind.TEXTURL -> {
                    if (at(index + 1) == '(') {
                        var j = index + 2
                        while (j < output.length && output[j] != ')') {
                            if (output[j] == '\\' && at(j + 1).code in 1..126) j += 2 else j++
                        }
                        if (j >= output.length) {
                            throw AssertionError("unterminated link URL ($context): $output")
                        }
                        index = j
                    }
                }

                Kind.BLOCKQUOTE -> {
                    haveBlockquote = false
                    canStart = true
                }

                else -> {}
            }
            stack.removeLast()
            index++
            continue
        }
        // begin-of-entity path
        when (c) {
            '_' -> {
                if (at(index + 1) == '_') {
                    stack.addLast(Kind.UNDERLINE)
                    index += 2
                } else {
                    stack.addLast(Kind.ITALIC)
                    index++
                }
            }

            '*' -> {
                stack.addLast(Kind.BOLD)
                index++
            }

            '~' -> {
                stack.addLast(Kind.STRIKE)
                index++
            }

            '[' -> {
                stack.addLast(Kind.TEXTURL)
                index++
            }

            '`' -> {
                if (at(index + 1) == '`' && at(index + 2) == '`') {
                    index += 3
                    var langEnd = index
                    while (langEnd < output.length && !isSpace(output[langEnd]) && output[langEnd] != '`') langEnd++
                    if (langEnd != index && langEnd < output.length && output[langEnd] != '`') index = langEnd
                    if (index < output.length && (output[index] == '\n' || output[index] == '\r')) {
                        if (at(index + 1) != output[index] && (at(index + 1) == '\n' || at(index + 1) == '\r')) {
                            index += 2
                        } else {
                            index++
                        }
                    }
                    stack.addLast(Kind.PRE)
                } else {
                    stack.addLast(Kind.CODE)
                    index++
                }
            }

            '\n' -> {
                canStart = true
                index++
            }

            '>' -> {
                if (canStart) {
                    if (!haveBlockquote) {
                        stack.addLast(Kind.BLOCKQUOTE)
                        haveBlockquote = true
                    }
                    index++
                } else {
                    throw AssertionError(
                        "Character '>' is reserved and must be escaped ($context): $output",
                    )
                }
            }

            else -> {
                throw AssertionError(
                    "Character '$c' is reserved and must be escaped ($context): $output",
                )
            }
        }
    }
    // EOF: a trailing blockquote closes here; anything else open is an error
    if (haveBlockquote && stack.isNotEmpty() && stack.last() == Kind.BLOCKQUOTE) stack.removeLast()
    if (stack.isNotEmpty()) {
        throw AssertionError("Can't find end of the ${stack.last()} entity ($context): $output")
    }
}

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

// The tag set Telegram's HTML parse mode documents support for (br included: this
// library emits a plain newline, but the parser accepts the tag)
private val HTML_SUPPORTED_TAGS =
    setOf("b", "i", "u", "s", "code", "pre", "a", "blockquote", "tg-spoiler", "tg-emoji", "br")

private val HTML_ENTITY = Regex("^&(amp|lt|gt|quot|#[0-9]+|#[xX][0-9a-fA-F]+);")

/**
 * Asserts [output] is a message tdlib's HTML parser — the parser the Bot API runs with
 * parse_mode = HTML — accepts, for the subset this library emits. The rules:
 *
 * - text never contains a bare `<` or `>` and every `&` opens a well-formed entity the
 *   parser resolves (the five named forms this renderer emits, plus numeric references);
 * - only documented tags appear, and they nest properly: a closing tag matches the
 *   innermost open one, and nothing is left open at the end;
 * - `<a>` carries a non-empty href, never nests inside another link, and its label is
 *   not blank (Telegram rejects empty link entities);
 * - `<tg-emoji>` carries a numeric emoji-id;
 * - `<pre>` holds text and at most one language-marked `<code>` wrapper (the shape
 *   Telegram's own examples document), and code never nests a second code level.
 *
 * Every HTML render, truncation and chunk piece must pass here.
 */
internal fun assertTelegramHtmlParseable(
    output: String,
    context: String,
) {
    fun fail(message: String): Nothing = throw AssertionError("$message ($context): $output")

    val stack = ArrayDeque<String>()
    // Links cannot nest, so at most one anchor is ever open — one flag suffices
    var anchorHasText = false
    var index = 0
    while (index < output.length) {
        val char = output[index]
        when (char) {
            '<' -> {
                val end = output.indexOf('>', index + 1)
                if (end == -1) fail("unterminated tag")
                val inner = output.substring(index + 1, end)
                if (inner.isEmpty()) fail("empty tag")
                val closing = inner[0] == '/'
                val body = if (closing) inner.substring(1) else inner
                val name = body.takeWhile { it.isLetterOrDigit() || it == '-' }
                if (name.isEmpty() || !name[0].isLetter()) fail("malformed tag name in <$inner>")
                val attrs = body.substring(name.length)
                val tag = name.lowercase()
                if (tag !in HTML_SUPPORTED_TAGS) fail("unsupported tag <$name>")
                if (tag == "br") {
                    index = end + 1
                    continue
                }
                val top = stack.lastOrNull()
                if (!closing) {
                    // The content restrictions bind openings only: a closing tag against
                    // its own match on top of the stack is exactly the legal close
                    if (top == "code") fail("<$tag> inside <code>")
                    if (top == "pre" && tag != "code") fail("<$tag> inside <pre>")
                    if (tag == "code" && "code" in stack) fail("nested code")
                    if (tag == "pre" && "pre" in stack) fail("nested pre")
                    if (tag == "code" && top == "pre" && !attrs.matches(Regex("^ class=\"language-[\\w+#-]*\"$"))) {
                        fail("code inside pre must carry the language class")
                    }
                    if (tag == "a") {
                        if ("a" in stack) fail("nested link entities")
                        if (!attrs.matches(Regex("^ href=\".+\"$"))) fail("anchor without a non-empty href")
                    }
                    if (tag == "tg-emoji" && !attrs.matches(Regex("^ emoji-id=\"\\d+\"$"))) {
                        fail("tg-emoji without a numeric emoji-id")
                    }
                    if (tag != "a" && tag != "code" && tag != "tg-emoji" && attrs.isNotBlank()) {
                        fail("unexpected attributes on <$tag>")
                    }
                    stack.addLast(tag)
                } else {
                    if (top != tag) fail("</$tag> closes <$top>")
                    if (tag == "a") {
                        if (!anchorHasText) fail("empty link label")
                        anchorHasText = false
                    }
                    stack.removeLast()
                }
                index = end + 1
            }

            '>' -> {
                fail("bare '>' in text")
            }

            '&' -> {
                val match = HTML_ENTITY.find(output.substring(index)) ?: fail("bare or malformed entity")
                index += match.value.length
            }

            else -> {
                if (!char.isWhitespace()) anchorHasText = true
                index++
            }
        }
    }
    if (stack.isNotEmpty()) fail("unclosed <${stack.last()}>")
}

// Structured shapes for the limit-sweep invariant tests: every shrink/seam path (the four
// fence-wrapped kinds, lists and nesting, quotes, headings, paragraph inline nodes,
// surrogate pairs) at a range of limits. The exact-output tests pin hand-picked lengths;
// the budget arithmetic only breaks at specific boundary lengths, which a sweep crosses.
internal fun sweepContents(): List<String> =
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
        add("<div>\nblock html content\nmore html\n</div>")
        add("###### <!-- c -->")
        // Glued underscore-family emphasis degrades (Visitor.atUnderscoreGlue): sibling
        // tags, nested tags and empty pairs, so the sweep crosses those degradation
        // budgets alongside the plain shapes
        add("<i>aaaa</i><i>bbbb</i> cccc <u>dddd</u><u>eeee</u>")
        add("<u>aaaa<i>bbbb</i></u> cccc <i></i> dddd")
    }

package io.github.kamiiroawase.markdownv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The promoted input extensions — task lists, autolink, footnotes — across all three
 * output formats. Task-list markers and footnote labels spell back out as visible text
 * (Telegram has no checkbox or footnote entity in any format); autolink needs no
 * renderer of its own: the parser produces core Link nodes the link rendering already
 * covers.
 */
class ExtensionsTest {
    // ---------- MarkdownV2 ----------

    @Test
    fun taskListsSpellMarkersBackOutMdv2() {
        assertEquals(
            "• \\[ \\] foo\n• \\[x\\] bar",
            MarkdownV2.render("- [ ] foo\n- [x] bar"),
        )
    }

    @Test
    fun footnoteReferenceAndDefinitionStayVisibleMdv2() {
        assertEquals(
            "Text\\[^1\\]\n\n\\[^1\\]: The note",
            MarkdownV2.render("Text[^1]\n\n[^1]: The note"),
        )
    }

    @Test
    fun autolinkBareUrlBecomesLinkEntity() {
        assertEquals(
            "See [https://e\\.com/x](https://e.com/x) here",
            MarkdownV2.render("See https://e.com/x here"),
        )
    }

    @Test
    fun autolinkEmailBecomesMailtoLink() {
        assertEquals(
            "Mail [foo@bar\\.com](mailto:foo@bar.com) now",
            MarkdownV2.render("Mail foo@bar.com now"),
        )
    }

    @Test
    fun autolinkWwwBecomesLink() {
        assertEquals(
            "[www\\.example\\.com](www.example.com) bare",
            MarkdownV2.render("www.example.com bare"),
        )
    }

    // ---------- HTML ----------

    @Test
    fun taskListsSpellMarkersBackOutHtml() {
        assertEquals(
            "• [ ] foo\n• [x] bar",
            TelegramHtml.render("- [ ] foo\n- [x] bar"),
        )
    }

    @Test
    fun footnoteReferenceAndDefinitionStayVisibleHtml() {
        assertEquals(
            "Text[^1]\n\n[^1]: The note",
            TelegramHtml.render("Text[^1]\n\n[^1]: The note"),
        )
    }

    @Test
    fun autolinkBareUrlBecomesAnchor() {
        assertEquals(
            "See <a href=\"https://e.com/x\">https://e.com/x</a> here",
            TelegramHtml.render("See https://e.com/x here"),
        )
    }

    @Test
    fun autolinkEmailBecomesMailtoAnchor() {
        assertEquals(
            "Mail <a href=\"mailto:foo@bar.com\">foo@bar.com</a> now",
            TelegramHtml.render("Mail foo@bar.com now"),
        )
    }

    // ---------- entities ----------

    @Test
    fun taskListsSpellMarkersBackOutEntities() {
        assertEquals(
            EntityText("• [ ] foo\n• [x] bar", emptyList()),
            TelegramEntities.render("- [ ] foo\n- [x] bar"),
        )
    }

    @Test
    fun footnoteReferenceAndDefinitionStayVisibleEntities() {
        assertEquals(
            EntityText("Text[^1]\n\n[^1]: The note", emptyList()),
            TelegramEntities.render("Text[^1]\n\n[^1]: The note"),
        )
    }

    @Test
    fun autolinkBareUrlBecomesTextLink() {
        val result = TelegramEntities.render("See https://e.com/x here")
        assertEquals("See https://e.com/x here", result.text)
        assertEquals(
            listOf(MessageEntity(MessageEntityType.TEXT_LINK, 4, 15, url = "https://e.com/x")),
            result.entities,
        )
        assertEntitiesValid(result, "autolinkTextLink")
    }

    @Test
    fun autolinkInsideTaskListChunkKeepsMarkers() {
        val content = (1..200).joinToString("\n") { "- [ ] task $it https://e.com/$it" }
        val chunks = TelegramHtml.renderChunked(content, 200)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 200)
            assertTelegramHtmlParseable(chunk, "autolinkTaskListChunk")
        }
        assertTrue(chunks.first().startsWith("• [ ] task 1 <a href="), "marker lost: ${chunks.first().take(40)}")
    }

    @Test
    fun footnoteDefinitionTruncatesWithLabelHtml() {
        val content = "Text[^1]\n\n[^1]: " + "x".repeat(100)
        val result = TelegramHtml.render(content, 30)
        assertEquals("Text[^1]\n\n[^1]: xxxxxxxxxxxxx…", result)
    }

    @Test
    fun footnoteDefinitionTruncatesWithLabelEntities() {
        val content = "Text[^1]\n\n[^1]: " + "x".repeat(100)
        val result = TelegramEntities.render(content, 30)
        assertEquals("Text[^1]\n\n[^1]: xxxxxxxxxxxxx…", result.text)
        assertEntitiesValid(result, "footnoteTruncation")
    }

    // ---------- footnote labels with markup-shaped characters ----------

    // The footnotes grammar admits any non-whitespace, non-']' characters in a label, so
    // '<'/'&' are legal there — and raw in HTML output they draw a 400 from the Bot API.
    // Pre-fix the HTML render and truncation interpolated the label unescaped
    @Test
    fun footnoteLabelWithMarkupEscapesHtml() {
        assertEquals(
            "Text[^a&lt;b]\n\n[^a&lt;b]: The note",
            TelegramHtml.render("Text[^a<b]\n\n[^a<b]: The note"),
        )
    }

    @Test
    fun footnoteLabelWithMarkupEscapesHtmlUnderTruncation() {
        val result = TelegramHtml.render("Text[^a<b]\n\n[^a<b]: " + "x".repeat(100), 30)
        assertTrue(result.startsWith("Text[^a&lt;b]\n\n[^a&lt;b]: "), "label lost its escaping: $result")
        assertTrue(result.endsWith("…"))
        assertTrue(result.length <= 30)
    }

    @Test
    fun footnoteLabelWithMarkupEscapesMdv2() {
        // The MarkdownV2 pipeline escaped the label all along — pinned so the three
        // formats cannot drift apart on the same source shape. '^' and '<' carry no
        // MarkdownV2 meaning, so only the brackets escape
        assertEquals(
            "Text\\[^a<b\\]\n\n\\[^a<b\\]: The note",
            MarkdownV2.render("Text[^a<b]\n\n[^a<b]: The note"),
        )
    }

    @Test
    fun footnoteLabelWithMarkupStaysLiteralInEntities() {
        assertEquals(
            EntityText("Text[^a<b]\n\n[^a<b]: The note", emptyList()),
            TelegramEntities.render("Text[^a<b]\n\n[^a<b]: The note"),
        )
    }

    // ---------- unclosed HTML tags in a blank paragraph ----------

    // A paragraph whose only content renders no text (an empty image plus an input-HTML
    // opener) skips as blank in the entities format — the opener emits no text there,
    // unlike the string formats where the tag's source stays visible. The range the
    // opener pushed survives the skip and completes over the following text, which is
    // exactly how the string twins format an unclosed tag (complete at output time):
    // all three formats link/bold the same trailing text. Pinned so the cross-format
    // agreement cannot silently drift
    @Test
    fun unclosedAnchorInBlankParagraphCompletesAcrossFormats() {
        val content = "![]()<a href=\"https://e.com\">\n\nhello"
        val entities = TelegramEntities.render(content)
        assertEquals(
            listOf(MessageEntity(MessageEntityType.TEXT_LINK, 0, 5, url = "https://e.com")),
            entities.entities,
        )
        assertEquals("hello", entities.text)
        val html = TelegramHtml.render(content)
        assertEquals("<a href=\"https://e.com\">\n\nhello</a>", html)
        assertTelegramHtmlParseable(html, "unclosedAnchorBlankParagraph")
        val mdv2 = MarkdownV2.render(content)
        assertEquals("[\n\nhello](https://e.com)", mdv2)
        assertTelegramParseable(mdv2, "unclosedAnchorBlankParagraphMdv2")
    }

    @Test
    fun unclosedBoldInBlankParagraphCompletesAcrossFormats() {
        val content = "![]()<b>\n\nhello"
        val entities = TelegramEntities.render(content)
        assertEquals(listOf(MessageEntity(MessageEntityType.BOLD, 0, 5)), entities.entities)
        assertEquals("<b>\n\nhello</b>", TelegramHtml.render(content))
        assertEquals("*\n\nhello*", MarkdownV2.render(content))
    }

    // ---------- upstream-behavior parity of the iterative stand-ins ----------

    @Test
    fun autolinkSkipsLinkLabels() {
        // Inside a link label no second level may open — the upstream visitor skips the
        // same subtree, and the iterative stand-in matches it
        assertEquals(
            "[see https://e\\.com here](https://other.com)",
            MarkdownV2.render("[see https://e.com here](https://other.com)"),
        )
    }

    @Test
    fun autolinkInsideEmphasisKeepsNesting() {
        assertEquals(
            "_bold [https://e\\.com](https://e.com) more_",
            MarkdownV2.render("*bold https://e.com more*"),
        )
    }

    @Test
    fun autolinkGluedPrefixAndTrailingDot() {
        assertEquals(
            "[ahttps://e\\.com](ahttps://e.com) glued",
            MarkdownV2.render("ahttps://e.com glued"),
        )
        assertEquals(
            "[https://e\\.com](https://e.com)\\.",
            MarkdownV2.render("https://e.com."),
        )
    }

    @Test
    fun taskMarkerBindsOnlyAtItemHeadWithWhitespace() {
        // Upper-case X counts as checked; a marker without following whitespace, or an
        // item that is nothing but the marker, stays literal text
        assertEquals("• \\[x\\] upper", MarkdownV2.render("- [X] upper"))
        assertEquals("• \\[ \\]nospace", MarkdownV2.render("- [ ]nospace"))
        assertEquals("• \\[ \\]", MarkdownV2.render("- [ ]"))
        assertEquals("text \\[ \\] not\\-a\\-list", MarkdownV2.render("text [ ] not-a-list"))
    }

    @Test
    fun orderedTaskListMarksMdv2() {
        assertEquals("1\\. \\[ \\] a\n2\\. \\[x\\] b", MarkdownV2.render("1. [ ] a\n2. [x] b"))
    }

    @Test
    fun deepNestingWithExtensionsDoesNotCrash() {
        // The reason the default set installs iterative stand-ins: the upstream
        // extensions' recursive post-processors overflow the stack on this shape on
        // mingwX64
        val content = ">".repeat(1500) + " " + "x".repeat(5000)
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun taskListTruncatesWholeItemsMdv2() {
        val content = (1..200).joinToString("\n") { "- [ ] item $it" }
        val result = MarkdownV2.render(content, 40)
        assertTrue(result.length <= 40)
        assertTrue(result.startsWith("• \\[ \\] item 1"), "marker lost: ${result.take(30)}")
        assertTrue(result.endsWith("…"))
        assertTelegramParseable(result, "taskListTruncation")
    }
}

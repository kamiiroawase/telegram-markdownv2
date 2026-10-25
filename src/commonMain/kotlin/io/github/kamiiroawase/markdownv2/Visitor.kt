package io.github.kamiiroawase.markdownv2

import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak

// Maximum nesting depth for structural rendering: deeper quotes/lists/emphasis flatten to
// plain text, preventing pathological input (e.g. thousand-level quote chains) from
// exhausting the call stack. Shared with HtmlVisitor.kt, whose structure caps alike
internal const val MAX_RENDER_DEPTH = 100

// One entry per open inline entity, whichever kind pushed it — Markdown emphasis (this
// visitor's own visits), HTML emphasis tags, HTML anchors — because their closers must
// interleave by opening order at every completion point (a container boundary or output
// time); two separate stacks cannot express that order and emitted crossed entities
// Telegram rejects
private sealed interface OpenEntity {
    // Opening order: every completion point closes what was opened after the boundary
    // entry, latest-opened first, so completed entities stay properly nested
    val seq: Int
}

// How an HTML code entity emits its backtick delimiter. LOUD opens and closes with one.
// MERGED is a code-family tag nested inside an already-open code entity: the nested span
// renders as part of the outer entity — no delimiter, content stays code-escaped (nested
// code is exactly single code). SUPPRESSED is a tag whose delimiter would glue onto a
// just-closed entity's backtick (`<code>a</code><code>b</code>`, `` `md`<code>x</code> ``):
// `` is an empty entity Telegram rejects, ``` re-parses as a pre fence — so the span
// degrades to escaped plain text with no delimiter at all. LOUD and MERGED entries own a
// htmlCodeDepth unit; a SUPPRESSED one does not (its content escapes as text)
private enum class CodeDelimiter {
    LOUD,
    MERGED,
    SUPPRESSED,
}

// marker: the MarkdownV2 closer; html: pushed by an HTML tag rather than by this
// visitor's own emphasis visits — only html-owned markers may be matched and popped by
// a closing tag, so a Markdown emphasis' own entry can never be stolen from under it.
// openPosition: for a LOUD code entry, the output position right after its opening
// backtick, and for an underscore-family entry the position right after its opening
// marker (everything the entity emitted sits at or after it; -1 otherwise).
// silent: an underscore-family entry degraded by the glue rule — it emitted no opener
// and its closer emits nothing; the content renders as plain text in place
private class OpenEmphasisMarker(
    val marker: String,
    val html: Boolean,
    val codeDelimiter: CodeDelimiter = CodeDelimiter.LOUD,
    override val seq: Int,
    val openPosition: Int = -1,
    val silent: Boolean = false,
) : OpenEntity

// Which underscore-family marker the last emission was — the glue rule (atUnderscoreGlue)
// needs the kind, not just the character: a trailing "__" that CLOSED an underline may
// legally glue onto a following marker, every other trailing marker may not
private enum class UnderscoreEmission { I_OPEN, U_OPEN, I_CLOSE, U_CLOSE }

// An open HTML anchor: its URL ("" when href-less) plus the output positions that let an
// empty label degrade to the bare escaped URL (the same trade visit(link) makes).
// nested: the anchor literalized because an outer link was open — a type-level mark, not a
// URL value: a real href sharing the old sentinel string would have closed as the sentinel
// and stranded its unclosed "[" (HtmlVisitor.kt and EntityVisitor.kt carry the same mark).
// emoji: the entry is a <tg-emoji> tag — its opener was "![" and its closer "](url)" like
// any anchor, but a closing </tg-emoji> must not pop a real <a> anchor (or the reverse),
// so the two kinds match only their own
private class OpenAnchor(
    val url: String,
    val openBracket: Int,
    val labelStart: Int,
    override val seq: Int,
    val nested: Boolean = false,
    val emoji: Boolean = false,
) : OpenEntity

// A parsed inline-HTML tag: the lowercased name, the raw attribute string, whether the
// tag closes, and whether its trailing slash read as self-closing
internal class TagParts(
    val closing: Boolean,
    val name: String,
    val attrs: String,
    val selfClosing: Boolean,
)

/**
 * Parses a complete inline-HTML tag (`<name attrs>`, `</name>`, `<name/>`) in one linear
 * left-to-right scan — no regex, so no backtracking and no engine recursion whatever the
 * length; the tagScanLinear* regression tests pin this down at 100k scale (a lazy regex
 * loop over an attribute alternation overflows the stack there). Quoted attribute values
 * follow HTML5/CommonMark and may contain `>` and `/`: neither closes the tag nor reads
 * as self-closing. A slash counts as self-closing only when it directly precedes the
 * closing `>` after the tag name, whitespace, or a value's closing quote — an unquoted
 * value swallows its trailing slash (HTML5). An unclosed quote, a missing `>`, or
 * content after the `>` makes the whole tag unmatched; the caller escapes the literal.
 * Tag names follow the CommonMark grammar `[A-Za-z][A-Za-z0-9-]*` — the hyphen keeps
 * hyphenated names like tg-spoiler whole rather than splitting them into a name and a
 * bogus attribute run (both rendering paths leave such names unmapped either way, so the
 * MarkdownV2 output does not change).
 */
internal fun parseHtmlTag(tag: String): TagParts? {
    var index = 0
    if (index >= tag.length || tag[index] != '<') return null
    index++
    val closing = index < tag.length && tag[index] == '/'
    if (closing) index++
    val nameStart = index
    if (index >= tag.length || !tag[index].isAsciiLetter()) return null
    index++
    while (index < tag.length && (tag[index].isAsciiLetterOrDigit() || tag[index] == '-')) index++
    val name = tag.substring(nameStart, index).lowercase()

    val attrsStart = index
    while (true) {
        if (index >= tag.length) return null
        when (val char = tag[index]) {
            '>' -> {
                var attrs = tag.substring(attrsStart, index)
                var selfClosing = false
                if (attrs.isNotEmpty() && attrs.last() == '/') {
                    val before = attrs.getOrNull(attrs.length - 2)
                    if (before == null || before.isWhitespace() || before == '"' || before == '\'') {
                        selfClosing = true
                        attrs = attrs.substring(0, attrs.length - 1)
                    }
                }
                // matchEntire semantics: the closing '>' must end the tag
                return if (index + 1 == tag.length) {
                    TagParts(closing, name, attrs, selfClosing)
                } else {
                    null
                }
            }

            '"', '\'' -> {
                val close = tag.indexOf(char, index + 1)
                if (close == -1) return null
                index = close + 1
            }

            else -> {
                index++
            }
        }
    }
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

// ASCII only — the HTML language attribute's grammar is the ASCII word set (see
// HtmlBlocks.htmlLanguageOf)
internal fun Char.isAsciiLetterOrDigit(): Boolean = isAsciiLetter() || this in '0'..'9'

// Attribute walking and entity decoding of HTML tags — shared by both output formats'
// inline-HTML handling (the MarkdownV2 visitor carries decoded hrefs into its link
// entities, the HTML visitor into its anchor attributes)

/**
 * Extracts one attribute's raw value from a tag's attribute string by walking name/value
 * pairs left to right (linear, no regex). Quoted values are skipped as a whole, so an
 * `href=` appearing inside another attribute's value (`title="href=x"`) cannot be mistaken
 * for the real attribute; attribute names are compared case-insensitively (HTML5), and the
 * value may be double-quoted, single-quoted or unquoted. Returns null when the attribute
 * is absent, the raw (still entity-encoded) value otherwise — decoding stays with the
 * caller, whose output format escapes differently.
 */
internal fun extractAttribute(
    attrs: String,
    name: String,
): String? {
    var index = 0
    while (index < attrs.length) {
        while (index < attrs.length && attrs[index].isWhitespace()) index++
        if (index >= attrs.length) break
        val nameStart = index
        while (index < attrs.length && attrs[index] != '=' && !attrs[index].isWhitespace()) index++
        val current = attrs.substring(nameStart, index)
        while (index < attrs.length && attrs[index].isWhitespace()) index++
        var value: String? = null
        if (index < attrs.length && attrs[index] == '=') {
            index++
            while (index < attrs.length && attrs[index].isWhitespace()) index++
            value =
                if (index < attrs.length && (attrs[index] == '"' || attrs[index] == '\'')) {
                    val quote = attrs[index]
                    index++
                    val valueStart = index
                    while (index < attrs.length && attrs[index] != quote) index++
                    attrs.substring(valueStart, index).also { if (index < attrs.length) index++ }
                } else {
                    // HTML5 unquoted value: ends at whitespace (a '>' cannot occur — the
                    // tag parser already split it off)
                    val valueStart = index
                    while (index < attrs.length && !attrs[index].isWhitespace()) index++
                    attrs.substring(valueStart, index)
                }
        }
        if (current.equals(name, ignoreCase = true)) return value
    }
    return null
}

/**
 * Extracts the href attribute from a tag's attribute string (see [extractAttribute]) and
 * decodes its entities; absent or empty yields null — the empty URL is the degradation
 * trigger in both output formats.
 */
internal fun extractHref(attrs: String): String? = extractAttribute(attrs, "href")?.takeIf { it.isNotEmpty() }?.let(::decodeHtmlEntities)

// The link destination prefix of Telegram's custom emoji syntax,
// ![fallback](tg://emoji?id=…) — the shape the Bot API documents for MarkdownV2 and the
// shape this visitor emits for both input spellings (the tg-emoji tag and the image
// whose destination already reads tg://emoji?id=…)
internal const val TG_EMOJI_URL_PREFIX = "tg://emoji?id="

/**
 * Validates a custom emoji id the way every spelling requires: custom emoji ids are
 * positive int64 values, so the candidate must be ASCII digits forming a Long above
 * zero — "0", a run overflowing int64, and Unicode digits all draw a 400 "Invalid custom
 * emoji identifier specified" from the server, so they degrade like an absent id (the
 * ASCII guard matters because toLongOrNull goes through Character.digit, which happily
 * parses Unicode digits). Returns the id, or null — the degradation trigger.
 */
internal fun validEmojiId(value: String): String? {
    if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
    val id = value.toLongOrNull() ?: return null
    return if (id > 0L) value else null
}

/**
 * Extracts a tg-emoji tag's emoji-id attribute and validates it (see [validEmojiId]).
 */
internal fun tgEmojiIdOf(attrs: String): String? = extractAttribute(attrs, "emoji-id")?.let(::decodeHtmlEntities)?.let(::validEmojiId)

/**
 * The custom emoji id of an image destination that already spells the custom emoji
 * syntax — `![fallback](tg://emoji?id=…)`, the Bot API's own MarkdownV2 form: the id
 * behind the prefix validates like every custom emoji id (see [validEmojiId]); any other
 * destination — a wrong path, a query key other than id, an unparsable id — is an
 * ordinary image and yields null.
 */
internal fun tgEmojiUrlIdOf(destination: String): String? {
    if (!destination.startsWith(TG_EMOJI_URL_PREFIX)) return null
    return validEmojiId(destination.substring(TG_EMOJI_URL_PREFIX.length))
}

/**
 * Best-effort check that a custom emoji fallback region spells exactly one regular
 * emoji — the shape Telegram requires (a custom_emoji entity must wrap one emoji; the
 * server silently ignores anything else, so shipping the syntax would lose the
 * formatting intent without any error). [label] is the already-rendered label region
 * (this visitor's escaped output): every escape pair is literal content, so only a
 * keycap base may arrive escaped and any other escaped char is text. Errs inclusive —
 * a false acceptance costs the same silent ignore the degrade would, while a false
 * rejection drops a working custom emoji, so the pictographic base set is generous
 * (whole U+1F000-1FAFF plus the classic BMP emoji blocks). One cluster means one base
 * with optional VS16/skin-tone modifiers, ZWJ-joined further bases (family emoji and
 * the like), one keycap form (base + FE0F? + 20E3), one regional-indicator pair (a
 * flag), or one subdivision-flag tag sequence (1F3F4 + tags + cancel tag).
 */
internal fun isSingleEmojiFallback(label: String): Boolean {
    // The region may carry surrounding whitespace the entity does not count — a space
    // around the fallback, or the block separator still pending at output time
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return false
    val end = emojiClusterEnd(trimmed, 0)
    // -1 (no cluster at all) never equals a non-empty label's length; a shorter end
    // means a second cluster or trailing text followed the one cluster
    return end == trimmed.length
}

// The index one past the first emoji cluster starting at [start], or -1 when [start]
// holds no cluster
private fun emojiClusterEnd(
    label: String,
    start: Int,
): Int {
    if (start >= label.length) return -1

    // An escape pair is literal content: among the keycap bases only the digit never
    // escapes ('#' and '*' are MarkdownV2 specials), so an escaped char must be one of
    // the three bases with the enclosing keycap mark behind it
    if (label[start] == '\\') {
        if (start + 1 >= label.length) return -1
        val base = label[start + 1]
        if (base !in '0'..'9' && base != '#' && base != '*') return -1
        return keycapEnd(label, start + 2)
    }
    if (label[start] in '0'..'9') return keycapEnd(label, start + 1)

    val codePoint = labelCodePointAt(label, start)
    if (codePoint == 0x1F3F4) {
        // A subdivision flag: the black flag base plus tag characters closed by the
        // cancel tag; a bare black flag falls through as an ordinary pictographic base
        var index = start + 2
        while (index < label.length && labelCodePointAt(label, index) in 0xE0020..0xE007E) {
            index += labelCharWidth(label, index)
        }
        if (index < label.length && labelCodePointAt(label, index) == 0xE007F) return index + 2
    } else if (codePoint in 0x1F1E6..0x1F1FF) {
        // A regional-indicator pair is one flag; a longer run leaves a second cluster
        // behind, which the exactly-one check rejects
        if (start + 2 >= label.length) return -1
        val second = labelCodePointAt(label, start + 2)
        return if (second in 0x1F1E6..0x1F1FF) start + 4 else -1
    }

    if (!isEmojiBase(codePoint)) return -1
    // The base plus any run of presentation modifiers and skin tones, with ZWJ joining
    // further bases into the one cluster
    var index = start
    while (true) {
        index += labelCharWidth(label, index)
        var joined = false
        while (index < label.length) {
            val char = label[index]
            if (char == '\uFE0F') {
                index++
            } else if (char == '\u200D') {
                joined = true
                index++
                break
            } else if (
                labelCharWidth(label, index) == 2 &&
                labelCodePointAt(label, index) in 0x1F3FB..0x1F3FF
            ) {
                index += 2
            } else {
                break
            }
        }
        if (!joined) return index
        // After the joiner another base must follow (a pictographic base or a single
        // regional indicator — the pieces ZWJ families and split flags build on)
        if (index >= label.length) return -1
        val next = labelCodePointAt(label, index)
        if (next !in 0x1F1E6..0x1F1FF && !isEmojiBase(next)) return -1
    }
}

// A keycap base has been consumed; the optional VS16 and the mandatory enclosing mark
// follow, or the digits were plain text
private fun keycapEnd(
    label: String,
    start: Int,
): Int {
    var index = start
    if (index < label.length && label[index] == '\uFE0F') index++
    return if (index < label.length && label[index] == '\u20E3') index + 1 else -1
}

// Generous pictographic base set: every supplementary pictographic block (assigned or
// not) plus the BMP blocks emoji draw on — see isSingleEmojiFallback for the erring
private fun isEmojiBase(codePoint: Int): Boolean =
    codePoint in 0x1F000..0x1FAFF ||
        codePoint in 0x2190..0x21FF ||
        codePoint in 0x2300..0x23FF ||
        codePoint in 0x2460..0x24FF ||
        codePoint in 0x25A0..0x25FF ||
        codePoint in 0x2600..0x27BF ||
        codePoint in 0x2900..0x297F ||
        codePoint in 0x2B00..0x2BFF ||
        codePoint == 0x00A9 ||
        codePoint == 0x00AE ||
        codePoint == 0x203C ||
        codePoint == 0x2049 ||
        codePoint == 0x2122 ||
        codePoint == 0x2139 ||
        codePoint == 0x3030 ||
        codePoint == 0x303D ||
        codePoint == 0x3297 ||
        codePoint == 0x3299

private fun labelCodePointAt(
    label: String,
    index: Int,
): Int =
    if (isSurrogatePairAt(label, index)) {
        0x10000 + ((label[index].code - 0xD800) shl 10) + (label[index + 1].code - 0xDC00)
    } else {
        label[index].code
    }

private fun labelCharWidth(
    label: String,
    index: Int,
): Int = if (isSurrogatePairAt(label, index)) 2 else 1

/**
 * Decodes the supported HTML entities (`&amp;`, `&#38;`, `&#x26;`, …) in attribute values
 * and de-formatted text; `&` without a well-formed entity stays literal. Browsers decode
 * entities in attributes before using the value, so an href extracted raw would link to
 * the wrong URL.
 */
internal fun decodeHtmlEntities(text: String): String {
    if ('&' !in text) return text
    val sb = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char != '&') {
            sb.append(char)
            index++
            continue
        }
        // An entity body is 1-10 chars here: at least one char between & and ;, at most
        // &#x10FFFF; — anything longer cannot be a supported entity and stays literal.
        // The window cap bounds the scan itself: a search from index+1 without it
        // rescans to the string's end for every '&' — quadratic on ampersand-dense
        // attribute values, the same shape parseHtmlTag's linear scan exists for (the
        // tagScanLinear* regression tests pin that one at 100k scale)
        var end = -1
        val windowEnd = minOf(text.length, index + 11)
        var probe = index + 1
        while (probe < windowEnd) {
            if (text[probe] == ';') {
                end = probe
                break
            }
            probe++
        }
        if (end == -1 || end - index < 2) {
            sb.append(char)
            index++
            continue
        }
        val decoded = decodeEntity(text.substring(index + 1, end))
        if (decoded == null) {
            sb.append(char)
            index++
        } else {
            sb.append(decoded)
            index = end + 1
        }
    }
    return sb.toString()
}

private fun decodeEntity(body: String): String? =
    when {
        body.startsWith("#") -> decodeCharRef(body.substring(1))
        else -> NAMED_ENTITIES[body]
    }

// HTML5 character references are strictly digits (or x/X-prefixed hex digits): a body
// anything else — a leading '+' or '-', say, which Kotlin's toIntOrNull happily parses —
// is not a reference and stays literal
private fun decodeCharRef(digits: String): String? {
    val codePoint =
        when {
            digits.startsWith("x") || digits.startsWith("X") -> {
                val hex = digits.substring(1)
                if (hex.isEmpty() || hex.any { it !in HEX_DIGITS }) null else hex.toIntOrNull(16)
            }

            else -> {
                if (digits.isEmpty() || digits.any { it !in '0'..'9' }) null else digits.toIntOrNull()
            }
        } ?: return null
    // 0 joins the rejects: the NUL it would decode to is not valid message text for
    // Telegram — the reference stays literal like every other malformed one
    if (codePoint <= 0 || codePoint > 0x10FFFF || codePoint in 0xD800..0xDFFF) return null
    if (codePoint <= 0xFFFF) return codePoint.toChar().toString()
    val offset = codePoint - 0x10000
    return (0xD800 + (offset shr 10)).toChar().toString() + (0xDC00 + (offset and 0x3FF)).toChar().toString()
}

// Whether [output] ends with a backtick that is not the tail of an escape sequence:
// content backticks always escape (\`), so an unescaped trailing one is a just-closed
// entity's delimiter. Parity settles escaped tails: escapes pair every backslash with
// the character after it, so an odd number of backslashes before the backtick means
// the last one belongs to an escape. Shared with the chunking node seam
internal fun endsWithUnescapedBacktick(output: CharSequence): Boolean {
    if (output.isEmpty() || output.last() != '`') return false
    var backslashes = 0
    var index = output.length - 2
    while (index >= 0 && output[index] == '\\') {
        backslashes++
        index--
    }
    return backslashes % 2 == 0
}

// The underscore twin of endsWithUnescapedBacktick: whether [output] ends with an '_'
// that is a marker rather than content. Content underscores always escape (\_), and
// parity settles the tail exactly as it does for backslashes before a backtick — an odd
// run of backslashes means the last one escapes the underscore. Shared with the
// chunking node seam
internal fun endsWithUnescapedUnderscore(output: CharSequence): Boolean {
    if (output.isEmpty() || output.last() != '_') return false
    var backslashes = 0
    var index = output.length - 2
    while (index >= 0 && output[index] == '\\') {
        backslashes++
        index--
    }
    return backslashes % 2 == 0
}

// Entity decoding for URL attribute values: the five XML predefined entities plus decimal
// and hex character references. The full HTML5 named-entity table (~2k names) is
// deliberately out of scope for URLs; anything unrecognized stays literal. Scanned
// manually — no regex, like every other scanner in this file
private const val HEX_DIGITS = "0123456789abcdefABCDEF"

private val NAMED_ENTITIES =
    mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
    )

/**
 * Renders the full AST to Telegram MarkdownV2. Unclosed HTML entities — emphasis tags and
 * anchors alike — complete at the nearest container boundary (an emphasis, strikethrough,
 * or link/image label that closes over them) or at output time, always latest-opened first
 * so the output stays properly nested; code entities cannot meaningfully span blocks and
 * complete one boundary earlier, at every block start, while anchors additionally complete
 * before the fence-wrapped blocks (a pre entity may not sit inside a link entity).
 * Tables degrade to aligned text via [renderTableLines].
 *
 * [precededByBacktick] tells the visitor that the output it produces will sit directly
 * behind an unescaped backtick of the caller's already-emitted content (the chunking
 * node seam): a code entity opening first in such a render would glue its delimiter
 * onto that backtick and takes the suppressed degradation instead. [precededByUnderscore]
 * is the underscore-family twin (see atUnderscoreGlue).
 */
internal class Visitor(
    private val depth: Int = 0,
    private val options: RenderOptions = RenderOptions(),
    private val precededByBacktick: Boolean = false,
    // The output will sit directly behind an unescaped '_' of the caller's content (the
    // chunking node seam): a '_'/'__' marker opening first in such a render would glue
    // onto it and takes the degradation instead (see atUnderscoreGlue)
    private val precededByUnderscore: Boolean = false,
    // Whether this render sits inside a list item body: the item's first line carries the
    // list marker and every continuation line the marker-width indent (applyPrefix), so a
    // blockquote's '>' markers could never land at a line start — the only position the
    // Bot API accepts them at (any ordinary character, a marker or an indent space alike,
    // clears tdlib's can_start_blockquote flag and a following '>' draws the 400 "Character
    // '>' is reserved"). A quote in that position degrades to its children's content (see
    // visit(blockQuote))
    private val inListItem: Boolean = false,
) : AbstractVisitor() {
    private val sb = StringBuilder()

    // Every open inline entity in opening order: this visitor's own Markdown emphasis,
    // HTML emphasis tags and HTML anchors alike (see OpenEntity)
    private val openEntities = ArrayDeque<OpenEntity>()

    private var nextSeq = 0

    // The kind of the last underscore-family marker emitted — read by atUnderscoreGlue
    // only when the output tail is an unescaped '_' (which is necessarily that marker);
    // null after a suppression rewinds one, where the effective tail reverts to whatever
    // preceded and its kind is unknown — treated as glue-dangerous, the conservative read
    private var lastUnderscoreEmission: UnderscoreEmission? = null

    // Depth of open Markdown link entities (Link and Image both render as links); HTML
    // anchors are tracked in [openEntities]. Telegram links cannot nest, so while any
    // link entity is open, link-like constructs degrade (see visit(link), visit(image)
    // and renderAnchor)
    private var openMarkdownLinks = 0

    // Nesting depth of open HTML code entities (<code>/<kbd>/<samp>/<tt> render as `
    // entities). Inside a code entity Telegram interprets only \` and \\, so Text children
    // switch to code escaping (see visit(text)); Markdown Code nodes always escape that way
    private var htmlCodeDepth = 0

    // Nesting depth of inline emphasis (emphasis recurses within the same visitor; deep
    // nesting flattens)
    private var inlineDepth = 0

    /**
     * Returns the rendered text, completing any unclosed entities latest-opened first
     * (by opening order, so the completed entities nest properly): markers emit their
     * closer, real anchors their ](url) — an anchor whose label rendered no visible text
     * rewinds and degrades to the bare escaped URL, the same trade visit(link) makes —
     * while sentinel and href-less anchors emit nothing. Closers append after a trimEnd
     * that keeps them clear of trailing block separators — except while an HTML code
     * entity is open: its content's trailing spaces and tabs are literal and stay; only
     * trailing newlines are cleared (the boundary-completion path completeOpenedAfter
     * trims nothing). A rewind that completed nothing else returns the rewound text
     * as-is.
     */
    fun output(): String {
        if (openEntities.isEmpty()) return sb.toString()
        val closers = StringBuilder()
        while (openEntities.isNotEmpty()) {
            when (val entity = openEntities.removeLast()) {
                is OpenEmphasisMarker -> {
                    when {
                        entity.codeDelimiter != CodeDelimiter.LOUD -> {
                            // MERGED and SUPPRESSED code entries never emit their
                            // backtick — completing them must not either, or the glue
                            // they avoided at open time reappears at the completion point
                        }

                        entity.silent -> {
                            // A glue-degraded underscore-family entry emitted no opener;
                            // its completion emits no closer either
                        }

                        entity.marker == "`" && sb.substring(entity.openPosition).trimEnd('\n').isEmpty() -> {
                            // An empty code entity (an unclosed <code> with no content,
                            // or one whose only content dropped out — a lone comment):
                            // emitting the closer would glue `` against the opener, an
                            // empty entity Telegram rejects — rewind the opener instead;
                            // the tags carried no visible text, so nothing is lost. The
                            // depth unit goes back too, so the trim below stops honoring
                            // a code entity that no longer exists. Trailing newlines are
                            // block separators appended after the children (never code
                            // the entity displays), so they do not count as content —
                            // unlike the matched-close and boundary paths, which run
                            // mid-children and compare positions exactly
                            htmlCodeDepth--
                            sb.setLength(entity.openPosition - 1)
                        }

                        (entity.marker == "||" || entity.marker == "*" || entity.marker == "~") &&
                            sb.substring(entity.openPosition).trimEnd().isEmpty() -> {
                            // An empty spoiler, bold or strikethrough entity rewinds its
                            // opener for the same reason: the closer glued straight onto
                            // the opener is the empty entity Telegram drops (pre-fix the
                            // bold and strikethrough closers emitted unconditionally),
                            // and the tags carried no visible text, so nothing is lost.
                            // Whitespace-only content counts as empty at output time
                            // alone: the body trim below strips it at code depth zero,
                            // which would strand the closer as a fresh empty entity —
                            // the underscore family reaches the same trade through
                            // outputGlue instead
                            sb.setLength(entity.openPosition - entity.marker.length)
                        }

                        (entity.marker == "_" || entity.marker == "__") && outputGlue(closers) -> {
                            // The completion's underscore closer would glue onto the
                            // trimmed tail (see atUnderscoreGlue) — the entity rewinds
                            // instead, exactly like a matched close does. The tail being
                            // its own opener covers the empty entity: "<i>" alone
                            // rewinds rather than completing into "__", which Telegram
                            // would read as an unterlined underline
                            rewindUnderscoreOpener(entity)
                        }

                        else -> {
                            closers.append(entity.marker)
                            when (entity.marker) {
                                "_" -> lastUnderscoreEmission = UnderscoreEmission.I_CLOSE
                                "__" -> lastUnderscoreEmission = UnderscoreEmission.U_CLOSE
                            }
                        }
                    }
                }

                is OpenAnchor -> {
                    when {
                        entity.nested || entity.url.isEmpty() -> {}

                        regionIsBlankResolvingMarkers(entity.labelStart) -> {
                            // A blank label means nothing visible was emitted after its [
                            // — the region resolves markers away (regionIsBlank covers the
                            // whitespace-only label too), so rewinding the bracket loses no
                            // content. Entities opened inside the label popped earlier in
                            // this loop — the pop order is strictly LIFO, so everything
                            // below this anchor pops after it — and their closers sit in
                            // this builder already; the rewind just deleted their openers
                            // with the label, and a closer with no opener is the stray
                            // marker Telegram rejects ("Can't find end of the entity" on
                            // the bold of `<a href=u><b>` ` `), so the builder drops them
                            // with the rewind
                            closers.setLength(0)
                            sb.setLength(entity.openBracket)
                            sb.append(escapeTextInContext(entity.url))
                        }

                        entity.emoji && !isSingleEmojiFallback(sb.substring(entity.labelStart)) -> {
                            // See renderEmojiTag: an invalid fallback drops the ![ opener
                            // only — the fallback text ships as plain content. Inner
                            // entities popped first left balanced markers behind (their
                            // openers sit in the region, their closers ahead in this
                            // builder), so the deletion strands nothing
                            sb.deleteRange(entity.openBracket, entity.openBracket + 2)
                        }

                        else -> {
                            // The entities above this anchor already completed — their
                            // closers precede the ](url) in the closers builder, so the URL
                            // sits outside them — while any code entity below it on the
                            // stack stays open around the anchor's whole region. The
                            // completion arms above hand no depth unit back, so the URL
                            // context reads the remaining stack, not the counter
                            // (escapeUrlInContext would count completed code entities too)
                            val codeBelow =
                                openEntities.any {
                                    it is OpenEmphasisMarker &&
                                        it.marker == "`" &&
                                        it.codeDelimiter != CodeDelimiter.SUPPRESSED
                                }
                            closers.append(
                                "](${if (codeBelow) escapeCode(entity.url) else escapeUrl(entity.url)})",
                            )
                        }
                    }
                }
            }
        }
        if (closers.isEmpty()) return sb.toString()
        // While an HTML code entity is open, trailing spaces and tabs are the entity's
        // literal content and must survive; only trailing newlines are cleared — they
        // are block separators (or a dangling <br>), never code the entity displays
        val body = if (htmlCodeDepth > 0) sb.toString().trimEnd('\n') else sb.toString().trimEnd()
        // A closer glued straight onto a quote line would parse as an entity the quote
        // itself opens, never as the outer entity's closer (see endsOnQuoteLine): the
        // newline lead lifts the closers past the quote's end, where tdlib has already
        // closed the blockquote and the marker reaches its own entity. The closing-fence
        // line takes the same lead: the glued shape is legal MarkdownV2, but the
        // de-format's fence scan drops the rest of the closing line, stranding the
        // marker there and re-opening it literally in the plain text (see
        // endsOnFenceClose)
        val quoteLead =
            when {
                endsOnQuoteLine(body) -> "\n"
                endsOnFenceClose(body) -> "\n"
                else -> ""
            }
        return body + quoteLead + closers
    }

    // The output()-time twin of atUnderscoreGlue: closers accumulate in their own builder
    // after a trimEnd lifts trailing block separators, so the tail the FIRST closer would
    // glue onto is the trimmed body's, not the raw sb's — a bare "<i>" ahead of a
    // paragraph separator must still rewind rather than complete into "__", which reads
    // as an opened underline. Later closers check the builder's own tail
    private fun outputGlue(closers: StringBuilder): Boolean =
        if (closers.isNotEmpty()) {
            endsWithUnescapedUnderscore(closers) && lastUnderscoreEmission != UnderscoreEmission.U_CLOSE
        } else {
            // The trim this output will apply before appending closers; the code-open
            // variant keeps spaces and tabs, neither of which is '_', so either tail
            // serves the check
            val body = if (htmlCodeDepth > 0) sb.toString().trimEnd('\n') else sb.toString().trimEnd()
            endsWithUnescapedUnderscore(body) && lastUnderscoreEmission != UnderscoreEmission.U_CLOSE
        }

    override fun visit(text: Text) {
        // Inside an HTML code entity the full 19-char escaping would leak backslashes:
        // Telegram renders \x (x neither ` nor \) literally inside code entities
        sb.append(if (htmlCodeDepth > 0) escapeCode(text.literal) else escapeText(text.literal))
    }

    override fun visit(document: Document) {
        visitChildren(document)
    }

    override fun visit(paragraph: Paragraph) {
        // An HTML code entity opened in an earlier block cannot meaningfully span this
        // one: complete it first, or this block's content would render code-escaped
        // inside it (see completeOpenCodeEntities)
        completeOpenCodeEntities()
        val start = sb.length
        visitChildren(paragraph)
        // A paragraph that rendered no visible text is no-content: one whose every
        // child dropped out (a lone inline comment, an empty-alt empty-URL image), or
        // one whose render is whitespace only (a hand-built Text(" ") child — the
        // parser never forms a paragraph from blank lines). The region rewinds and no
        // separator follows — the same blank-render skip the truncation (renderBlocks)
        // and chunking (chunkBlocks) paths make on the rendered shape, where the
        // separator (or the whitespace itself) would double the blank line between its
        // neighbors. Rewinding discards whitespace only: an entity marker would have
        // emitted a non-whitespace character, so nothing visible is lost
        if (regionIsBlank(start)) {
            sb.setLength(start)
        } else {
            sb.append("\n\n")
        }
    }

    // MarkdownV2 has no heading entity: the level is spelled out as escaped hashes
    // (headingPrefixOf, shared with the truncation and chunking pipelines). The prefix
    // escapes for text — completing any code entity from an earlier block first keeps it
    // outside the entity, where a "\#" would render its backslash (inside code only \`
    // and \\ are escapes)
    override fun visit(heading: Heading) {
        completeOpenCodeEntities()
        sb.append(headingPrefixOf(heading))
        visitChildren(heading)
        sb.append("\n\n")
    }

    override fun visit(blockQuote: BlockQuote) {
        // Per child: a nested quote renders with its own glued marker run and this level's
        // marker glues in front of it, every other child's raw lines (code content
        // included) take the spaced "> " — see prefixQuoteLevel, which pins the Telegram
        // grammar (nesting runs glue; "> >" is unescaped content). The "\n>\n" separator
        // keeps the blank line between the quote's paragraphs as a bare marker. An empty
        // quote keeps a single ">"
        //
        // Behind list structure (inListItem) no '>' marker can sit at a line start — the
        // only position the Bot API accepts it at — so the quote degrades there: its
        // children render as plain blocks of the item, joined by the block separator, the
        // quote formatting traded for output Telegram parses (the same trade tables and
        // raw HTML make). An all-blank quote renders nothing; the blank-item skip upstream
        // drops the emptied marker. A code entity from an earlier block completes first —
        // the quote's markers are structure, not code content. A custom emoji completes
        // here too ("all other entities can't contain each other" — a quote may not sit
        // inside one): the HTML/entities twins complete their emoji entries at the same
        // boundary, so an unclosed <tg-emoji> keeps its emoji rather than completing at
        // output time over a region the quote's text has already made non-single. Regular
        // anchors keep the documented span-over-quote behavior — markers re-pair across
        // blocks, where the HTML twin's wrapper tags leave no choice
        completeOpenCodeEntities()
        completeOpenEmojiAnchors()
        val pieces = mutableListOf<String>()
        var child = blockQuote.firstChild
        while (child != null) {
            val rendered = renderQuoteChild(child)
            if (rendered.isNotBlank()) {
                pieces +=
                    if (inListItem) {
                        rendered
                    } else {
                        prefixQuoteLevel(child is BlockQuote, rendered)
                    }
            }
            child = child.next
        }
        if (inListItem) {
            if (pieces.isNotEmpty()) {
                sb.append(pieces.joinToString("\n\n")).append("\n\n")
            }
            return
        }
        // An expandable quote (the <blockquote expandable> spelling the parse rewrite
        // marks) opens its first line with "**" ahead of the marker (see
        // expandableQuoteMarkerOf); the empty-quote fallback takes the same lead
        val lead = expandableQuoteMarkerOf(blockQuote)
        sb.append(if (pieces.isEmpty()) "$lead>" else lead + pieces.joinToString("\n>\n"))
        sb.append("\n\n")
    }

    override fun visit(bulletList: BulletList) {
        renderList(bulletList)
    }

    override fun visit(orderedList: OrderedList) {
        renderList(orderedList)
    }

    // MarkdownV2 has no thematic break entity; em dashes render cleanly without escaping,
    // unlike '-' which would need a backslash per dash
    override fun visit(thematicBreak: ThematicBreak) {
        completeOpenCodeEntities()
        sb.append(escapeText("———")).append("\n\n")
    }

    override fun visit(fencedCodeBlock: FencedCodeBlock) {
        // literal and info are String? in commonmark-kotlin, so orEmpty is required here
        // and in visit(htmlBlock); Text/Code/HtmlInline literals are declared non-null
        // upstream and are used directly
        //
        // The info string is untrusted: the first word is the language (CommonMark §4.5)
        // and the rest is metadata — keep only letters/digits/-/+/# from that word so it
        // can neither forge the fence (backticks, newlines) nor inject entities — '#'
        // completes the c#/f# tags and, like the rest of the set, renders literally
        // inside the pre entity; 32 chars is plenty
        val language =
            fencedCodeBlock.info
                .orEmpty()
                .trim()
                .takeWhile { !it.isWhitespace() }
                .filter { it.isLetterOrDigit() || it == '-' || it == '+' || it == '#' }
                .take(32)
        completeOpenCodeAndAnchorEntities()
        sb.append("```").append(language).append('\n')
        sb.append(escapeCode(fencedCodeBlock.literal.orEmpty().trimEnd('\n')))
        sb.append("\n```\n\n")
    }

    override fun visit(indentedCodeBlock: IndentedCodeBlock) {
        completeOpenCodeAndAnchorEntities()
        sb.append("```\n")
        sb.append(escapeCode(indentedCodeBlock.literal.trimEnd('\n')))
        sb.append("\n```\n\n")
    }

    override fun visit(code: Code) {
        if (htmlCodeDepth > 0) {
            // Inside an HTML code entity the code span's own backticks would pair with
            // the entity's delimiters and garble the span boundaries — keep them, escaped
            // (\` renders as a literal backtick inside a code entity)
            sb.append("\\`").append(escapeCode(code.literal)).append("\\`")
        } else if (code.literal.isEmpty()) {
            // An empty span's delimiters would glue into an empty code entity; the
            // parser never produces one, this covers hand-built ASTs
        } else if (atCodeGlue()) {
            // The span's opener would glue onto a just-closed entity's backtick — the
            // same reserved-character run the HTML tag path suppresses: degrade to
            // escaped plain text, content preserved, formatting dropped
            sb.append(escapeText(code.literal))
        } else {
            sb.append('`').append(escapeCode(code.literal)).append('`')
        }
    }

    override fun visit(emphasis: Emphasis) {
        if (!enterInline()) {
            sb.append(escapedPlainText(emphasis))
            return
        }
        // Inside an HTML code entity the '_' marker would surface as a literal character
        // (only \` and \\ count there) and change what Telegram displays — the source's
        // "*x*" would render as "_x_": drop the markers, keep the content, the same
        // degradation the HTML and entities formats make of emphasis inside code
        if (htmlCodeDepth > 0) {
            visitChildren(emphasis)
            exitInline()
            return
        }
        // Same-type emphasis cannot nest (see renderHtmlInline's same-type
        // degradation): the entry goes silent — no opener, no closer — and the content
        // renders inside the outer entity. The parser pairs most same-type runs into
        // siblings, but `_a _b_ c_`-shaped runs nest them; two open markers would
        // re-pair into siblings that drop the middle segment's formatting
        if (emphasisEntryOpen("_")) {
            val seq = nextSeq++
            openEntities.addLast(OpenEmphasisMarker("_", html = false, seq = seq, silent = true))
            visitChildren(emphasis)
            completeOpenedAfter(seq)
            openEntities.removeLast()
            exitInline()
            return
        }
        // A '_' opening onto a trailing underscore marker would glue (see
        // atUnderscoreGlue) — flatten to plain text, the depth-cap degradation
        if (atUnderscoreGlue()) {
            sb.append(escapedPlainText(emphasis))
            exitInline()
            return
        }
        val seq = nextSeq++
        val entry =
            OpenEmphasisMarker("_", html = false, seq = seq, openPosition = sb.length + 1)
        openEntities.addLast(entry)
        sb.append('_')
        lastUnderscoreEmission = UnderscoreEmission.I_OPEN
        visitChildren(emphasis)
        completeOpenedAfter(seq)
        // The closer glues onto a completion's just-emitted '_' (an unclosed <i> inside
        // the emphasis completed right here): rewind the opener instead of emitting it
        if (atUnderscoreGlue()) {
            openEntities.removeLast()
            rewindUnderscoreOpener(entry)
            exitInline()
            return
        }
        sb.append('_')
        lastUnderscoreEmission = UnderscoreEmission.I_CLOSE
        openEntities.removeLast()
        exitInline()
    }

    override fun visit(strongEmphasis: StrongEmphasis) {
        if (!enterInline()) {
            sb.append(escapedPlainText(strongEmphasis))
            return
        }
        // See visit(emphasis): inside an HTML code entity the '*' markers would surface
        // as literal characters; drop them, keep the content
        if (htmlCodeDepth > 0) {
            visitChildren(strongEmphasis)
            exitInline()
            return
        }
        // See visit(emphasis): same-type emphasis cannot nest — a silent entry, the
        // content renders inside the outer entity (`**a **b** c**` parses to nested
        // strongs, the shape this guards)
        if (emphasisEntryOpen("*")) {
            val seq = nextSeq++
            openEntities.addLast(OpenEmphasisMarker("*", html = false, seq = seq, silent = true))
            visitChildren(strongEmphasis)
            completeOpenedAfter(seq)
            openEntities.removeLast()
            exitInline()
            return
        }
        val seq = nextSeq++
        val entry =
            OpenEmphasisMarker("*", html = false, seq = seq, openPosition = sb.length + 1)
        openEntities.addLast(entry)
        sb.append('*')
        visitChildren(strongEmphasis)
        completeOpenedAfter(seq)
        // An empty entity (the parser never forms one from "*"; hand-built ASTs reach
        // it) rewinds its opener — * straight onto * is the empty entity every other
        // marker family rewinds too, and Telegram drops it with any URL it carried
        if (sb.length == entry.openPosition) {
            openEntities.removeLast()
            sb.setLength(entry.openPosition - 1)
            exitInline()
            return
        }
        sb.append('*')
        openEntities.removeLast()
        exitInline()
    }

    override fun visit(customNode: CustomNode) {
        when (customNode) {
            is Strikethrough -> {
                if (!enterInline()) {
                    sb.append(escapedPlainText(customNode))
                    return
                }
                // See visit(emphasis): inside an HTML code entity the '~' markers would
                // surface as literal characters; drop them, keep the content
                if (htmlCodeDepth > 0) {
                    visitChildren(customNode)
                    exitInline()
                    return
                }
                // See visit(emphasis): same-type emphasis cannot nest — a silent entry,
                // the content renders inside the outer entity
                if (emphasisEntryOpen("~")) {
                    val seq = nextSeq++
                    openEntities.addLast(OpenEmphasisMarker("~", html = false, seq = seq, silent = true))
                    visitChildren(customNode)
                    completeOpenedAfter(seq)
                    openEntities.removeLast()
                    exitInline()
                    return
                }
                val seq = nextSeq++
                val entry =
                    OpenEmphasisMarker("~", html = false, seq = seq, openPosition = sb.length + 1)
                openEntities.addLast(entry)
                sb.append('~')
                visitChildren(customNode)
                completeOpenedAfter(seq)
                // See visit(strongEmphasis): an empty entity rewinds its opener
                if (sb.length == entry.openPosition) {
                    openEntities.removeLast()
                    sb.setLength(entry.openPosition - 1)
                    exitInline()
                    return
                }
                sb.append('~')
                openEntities.removeLast()
                exitInline()
            }

            is TaskListItemMarker -> {
                // No checkbox entity exists in any Telegram format: the marker spells
                // back out as literal text, the same shape the unextended parse shows
                sb.append(escapeText(if (customNode.isChecked) "[x] " else "[ ] "))
            }

            is FootnoteReference -> {
                // No footnote entity either: the reference stays visible as its source
                // spelling — pre-promotion it vanished entirely under the unknown-node
                // traversal, losing the marker from the text
                sb.append(escapeText("[^${customNode.label}]"))
            }

            else -> {
                super.visit(customNode)
            }
        }
    }

    /**
     * Completes unclosed entities opened strictly after [seq] — latest-opened first —
     * before the entry owning [seq] closes itself. Runs when an inline container —
     * emphasis, strikethrough, a Markdown link or image label — closes with HTML
     * entities still open inside it (missing closing tags, or upstream parsing defects).
     * An anchor opened inside completes exactly like a matched `</a>` would: a real one
     * emits its ](url) — or rewinds, when its label rendered no visible text, the same
     * empty-label trade visit(link) makes — while sentinels and href-less anchors close
     * silently. Completing an unclosed HTML code entity ends its scope: Text after the
     * boundary escapes as plain text again.
     */
    private fun completeOpenedAfter(seq: Int) {
        while (openEntities.isNotEmpty() && openEntities.last().seq > seq) {
            when (val entity = openEntities.removeLast()) {
                is OpenEmphasisMarker -> {
                    // Only LOUD and MERGED code entries own a depth unit (see
                    // CodeDelimiter); completing a SUPPRESSED one must not drop the
                    // count below the entities really open around it
                    if (entity.marker == "`" && entity.codeDelimiter != CodeDelimiter.SUPPRESSED) htmlCodeDepth--
                    when {
                        entity.codeDelimiter != CodeDelimiter.LOUD -> {}

                        entity.silent -> {}

                        entity.marker == "`" && sb.length == entity.openPosition -> {
                            // See output(): an empty code entity rewinds its opener
                            // instead of gluing `` at the completion point
                            sb.setLength(entity.openPosition - 1)
                        }

                        (entity.marker == "||" || entity.marker == "*" || entity.marker == "~") &&
                            sb.length == entity.openPosition -> {
                            // See output(): an empty spoiler, bold or strikethrough
                            // entity rewinds alike — the closer glued onto the opener
                            // is the empty entity Telegram drops. The position compare
                            // is exact here (mid-children, whitespace is content), the
                            // blank-region tolerance belongs to output time alone
                            sb.setLength(entity.openPosition - entity.marker.length)
                        }

                        (entity.marker == "_" || entity.marker == "__") && atUnderscoreGlue() -> {
                            // The completion's underscore closer would glue onto the
                            // tail — an inner entity just completed here, or the entry's
                            // own opener when it is empty: rewind (see atUnderscoreGlue)
                            rewindUnderscoreOpener(entity)
                        }

                        else -> {
                            sb.append(entity.marker)
                            when (entity.marker) {
                                "_" -> lastUnderscoreEmission = UnderscoreEmission.I_CLOSE
                                "__" -> lastUnderscoreEmission = UnderscoreEmission.U_CLOSE
                            }
                        }
                    }
                }

                is OpenAnchor -> {
                    when {
                        entity.nested || entity.url.isEmpty() -> {}

                        regionIsBlankResolvingMarkers(entity.labelStart) -> {
                            // See output(): a blank label guarantees this anchor is the
                            // latest open entry, so the rewind discards only whitespace
                            sb.setLength(entity.openBracket)
                            sb.append(escapeTextInContext(entity.url))
                        }

                        entity.emoji && !isSingleEmojiFallback(sb.substring(entity.labelStart)) -> {
                            // See renderEmojiTag: an invalid fallback drops the ![ opener
                            // only — the fallback text ships as plain content, and inner
                            // entities already closed with balanced markers above it
                            sb.deleteRange(entity.openBracket, entity.openBracket + 2)
                        }

                        else -> {
                            sb.append("](").append(escapeUrlInContext(entity.url)).append(')')
                        }
                    }
                }
            }
        }
    }

    /**
     * Completes every open inline code entity (and everything opened inside it) before a
     * block emits its own structure: left open, the entity would swallow the block's
     * markers as code content — fence markers it cannot parse, or text-escaped prefixes
     * whose backslashes would render inside it. Code entities cannot meaningfully span
     * blocks anyway; emphasis and anchors left open keep the documented complete-at-output
     * behavior. The closers land before the trailing block separator — appended after it
     * they would glue onto the fence instead, so the separator is lifted and re-emitted
     * (the same trade the output-time completion's trim makes).
     */
    private fun completeOpenCodeEntities() {
        val outermostCode =
            openEntities.firstOrNull { it is OpenEmphasisMarker && it.marker == "`" } as? OpenEmphasisMarker
                ?: return
        completeThroughSeparator(outermostCode.seq - 1)
    }

    /**
     * The fence-wrapped twin of [completeOpenCodeEntities]: completes code entities and
     * anchors alike (everything opened inside the earliest of them) before a
     * fence-wrapped block emits. A pre entity may not sit inside a link entity — the
     * entities documentation's "Cannot be nested inside other entities" — and a Markdown
     * link never spans blocks, so an unclosed HTML anchor must not reach across one
     * either. Emphasis keeps the documented complete-at-output behavior (the pinned
     * bold-over-pre containment).
     */
    private fun completeOpenCodeAndAnchorEntities() {
        val boundary =
            openEntities
                .filter { (it is OpenEmphasisMarker && it.marker == "`") || it is OpenAnchor }
                .minOfOrNull { it.seq }
                ?: return
        completeThroughSeparator(boundary - 1)
    }

    // Completes every open custom-emoji entry (and everything opened inside it) before a
    // block boundary the emoji may not cross — a quote or a list whose child blocks emit
    // their own structure ("all other entities can't contain each other"). Regular
    // anchors deliberately stay open (see visit(blockQuote)); the emoji entries complete
    // exactly the way completeOpenCodeAndAnchorEntities completes them before fences
    private fun completeOpenEmojiAnchors() {
        val boundary =
            openEntities
                .filter { it is OpenAnchor && it.emoji }
                .minOfOrNull { it.seq }
                ?: return
        completeThroughSeparator(boundary - 1)
    }

    // Completes everything opened at or after [boundarySeq], lifting the trailing block
    // separator first and re-emitting it after the closers — appended after it they would
    // glue onto the following fence (see completeOpenCodeEntities). When the separator
    // lifted off a quote line, the closers take the same newline lead output() gives
    // them: glued onto the quote they would parse inside it (see endsOnQuoteLine)
    private fun completeThroughSeparator(boundarySeq: Int) {
        var separatorLength = 0
        while (separatorLength < sb.length && sb[sb.length - 1 - separatorLength] == '\n') separatorLength++
        sb.setLength(sb.length - separatorLength)
        if (separatorLength > 0 && endsOnQuoteLine(sb)) sb.append('\n')
        completeOpenedAfter(boundarySeq)
        repeat(separatorLength) { sb.append('\n') }
    }

    override fun visit(customBlock: CustomBlock) {
        when (customBlock) {
            is TableBlock -> {
                visitTable(customBlock)
            }

            is FootnoteDefinition -> {
                // The definition keeps its place in the document (Telegram message text
                // is linear): label spelled back out, content rendered as the blocks it
                // wraps. The content renders in the in-list-item context: a quote's '>'
                // markers must stay line-initial, and the label prefix would push them
                // mid-line — the same unplaceable position a list marker creates — so
                // quotes inside the definition degrade to their content, exactly the
                // visible text the unextended parse shows. The label escapes for text —
                // a code entity from an earlier block completes first, or the label
                // would render inside it with visible escape backslashes
                completeOpenCodeEntities()
                val body =
                    if (depth + 1 >= MAX_RENDER_DEPTH) {
                        escapedPlainText(customBlock).trimEnd('\n')
                    } else {
                        val visitor = Visitor(depth + 1, options, inListItem = true)
                        visitor.visitChildren(customBlock)
                        visitor.output().trimEnd('\n')
                    }
                if (body.isNotEmpty()) {
                    sb.append(escapeText("[^${customBlock.label}]: "))
                    sb.append(body)
                    sb.append("\n\n")
                }
            }

            else -> {
                super.visit(customBlock)
            }
        }
    }

    private fun visitTable(table: TableBlock) {
        val lines = renderTableLines(table, options)
        if (lines.isEmpty()) {
            return
        }

        completeOpenCodeAndAnchorEntities()
        sb.append("```\n")
        sb.append(escapeCode(lines.joinToString("\n")))
        sb.append("\n```\n\n")
    }

    override fun visit(link: Link) {
        val destination = link.destination.orEmpty()
        // Telegram rejects link entities with an empty URL, and links cannot nest (a nested
        // Link cannot come out of CommonMark parsing; the guard covers hand-built ASTs) —
        // degrade to the link text alone in both cases. A link entity inside an open
        // HTML code entity is barred alike ("Pre and Code can't contain nested
        // entities"): the label text stays, the link drops — the degradation the HTML
        // and entities twins make of the shape (their codeDepth guards)
        if (destination.isEmpty() || linkEntityOpen() || htmlCodeDepth > 0) {
            visitChildren(link)
            return
        }
        val entrySeq = nextSeq++
        val openBracket = sb.length
        sb.append('[')
        openMarkdownLinks++
        val labelStart = sb.length
        visitChildren(link)
        openMarkdownLinks--
        // Whatever HTML opened inside the label closes before the link does: left open it
        // would cross the ](url) boundary into invalid entities, or leak htmlCodeDepth
        // escaping onto the text after the link. Anchors opened inside are all
        // literalized sentinels (the entry guard saw to that), so they close silently
        // here instead of lingering to degrade later links
        completeOpenedAfter(entrySeq)
        // Telegram also rejects link entities with empty text: a label that renders to
        // no visible text (no children, whitespace-only, or children like a lone HTML
        // comment) degrades to the bare escaped URL — the same blank test output() applies
        // to anchors left unclosed at output time. The completion must run before the
        // test: an unclosed tag that was the label's only content now rewinds as the
        // empty entity it rendered to (its markers drop), so the blank test sees the
        // label that ships — pre-fix it passed on the tag's opener marker and the link
        // completed into a [](url)-shaped entity whose URL Telegram drops with it.
        // Rewinding is safe — a blank label left nothing open on the stack
        if (regionIsBlankResolvingMarkers(labelStart)) {
            sb.setLength(openBracket)
            sb.append(escapeTextInContext(destination))
            return
        }
        sb.append("](").append(escapeUrlInContext(destination)).append(')')
    }

    // Telegram MarkdownV2 has no images in message text: degrade to a plain link on the
    // alt text; an alt that renders to nothing degrades to the bare escaped URL (see
    // visit(link)). Nested inside another link (the README badge shape
    // [![alt](img)](target)) only the alt text survives, keeping the outer link
    // single-level — Telegram links cannot nest. The one carve-out is the Bot API's own
    // custom emoji spelling — ![fallback](tg://emoji?id=…) — which renders as custom
    // emoji, the same entity the <tg-emoji> tag path emits (see renderEmojiTag); the
    // guards mirror it: inside a link or a code entity the syntax cannot sit, so those
    // take the ordinary image degradation
    override fun visit(image: Image) {
        val destination = image.destination.orEmpty()
        if (destination.isNotEmpty() && !linkEntityOpen() && htmlCodeDepth == 0) {
            val emojiId = tgEmojiUrlIdOf(destination)
            if (emojiId != null) {
                val entrySeq = nextSeq++
                val openBracket = sb.length
                sb.append("![")
                openMarkdownLinks++
                val labelStart = sb.length
                visitChildren(image)
                openMarkdownLinks--
                // See visit(link): HTML opened inside the fallback closes before the
                // emoji does — left open it would cross the ](url) boundary; links
                // opened inside degraded by the openMarkdownLinks guard
                completeOpenedAfter(entrySeq)
                // Telegram requires the fallback: one that renders to no visible text
                // degrades to the bare escaped URL, the same trade visit(link) makes
                // (the completion runs first so an unclosed tag as the fallback's only
                // content counts as blank — see visit(link))
                if (regionIsBlankResolvingMarkers(labelStart)) {
                    sb.setLength(openBracket)
                    sb.append(escapeTextInContext(destination))
                    return
                }
                // The fallback must be exactly one regular emoji (see
                // isSingleEmojiFallback): anything else is an entity the server
                // silently ignores, so the syntax degrades to the plain fallback text
                // — the same degrade the in-link path makes of the whole tag
                if (!isSingleEmojiFallback(sb.substring(labelStart))) {
                    sb.deleteRange(openBracket, openBracket + 2)
                    return
                }
                sb
                    .append("](")
                    .append(TG_EMOJI_URL_PREFIX)
                    .append(emojiId)
                    .append(')')
                return
            }
        }
        if (destination.isEmpty() || linkEntityOpen() || htmlCodeDepth > 0) {
            visitChildren(image)
            return
        }
        val entrySeq = nextSeq++
        val openBracket = sb.length
        sb.append('[')
        openMarkdownLinks++
        val labelStart = sb.length
        visitChildren(image)
        openMarkdownLinks--
        // See visit(link): HTML opened inside the alt text closes before the link does,
        // and the blank test runs after the completion so a label whose only content
        // was an unclosed tag counts as blank
        completeOpenedAfter(entrySeq)
        // See visit(link): a label with no visible text degrades to the bare escaped URL
        if (regionIsBlankResolvingMarkers(labelStart)) {
            sb.setLength(openBracket)
            sb.append(escapeTextInContext(destination))
            return
        }
        sb.append("](").append(escapeUrlInContext(destination)).append(')')
    }

    // Telegram message text renders both break kinds as plain newlines
    override fun visit(softLineBreak: SoftLineBreak) {
        sb.append('\n')
    }

    override fun visit(hardLineBreak: HardLineBreak) {
        sb.append('\n')
    }

    override fun visit(htmlBlock: HtmlBlock) {
        val literal = htmlBlock.literal.orEmpty().trim()
        // "<!--" is a "<!" — one prefix covers comments and declarations alike
        if (literal.isEmpty() || literal.startsWith("<!") || literal.startsWith("<?")) {
            return
        }

        completeOpenCodeAndAnchorEntities()
        sb.append("```\n").append(escapeCode(literal)).append("\n```\n\n")
    }

    override fun visit(htmlInline: HtmlInline) {
        sb.append(renderHtmlInline(htmlInline.literal))
    }

    /**
     * A closing tag emits its entity marker only when it matches the innermost open HTML
     * entity, otherwise it is escaped literally — an orphan `</b>` emitting a bare `*`
     * would create an unpaired entity Telegram rejects. Innermost-only matching also
     * keeps a closer from crossing a still-open anchor (or from popping a Markdown
     * emphasis' own stack entry, which used to crash on inputs like `_x</i>_`); the
     * crossed entity completes later instead, latest-opened first. Anchors behave the
     * same; an `<a>` without href pushes the empty sentinel and its closing only pops,
     * emitting no link.
     */
    private fun renderHtmlInline(literal: String): String {
        val tag = literal.trim()
        // "<!--" is a "<!" — one prefix covers comments and declarations alike
        if (tag.startsWith("<!") || tag.startsWith("<?")) {
            return ""
        }

        val parts = parseHtmlTag(tag) ?: return escapeTextInContext(tag)
        if (parts.name == "br") return "\n"
        if (parts.name == "a") return renderAnchor(tag, parts.closing, parts.selfClosing, parts.attrs)

        val marker =
            when (parts.name) {
                "b", "strong" -> "*"
                "i", "em" -> "_"
                "s", "del", "strike" -> "~"
                "u", "ins" -> "__"
                "code", "kbd", "samp", "tt" -> "`"
                "tg-spoiler" -> "||"
                "tg-emoji" -> return renderEmojiTag(tag, parts)
                else -> return escapeTextInContext(tag)
            }
        if (parts.selfClosing) return escapeTextInContext(tag)
        if (parts.closing) {
            val top = openEntities.lastOrNull()
            if (top is OpenEmphasisMarker && top.html && top.marker == marker) {
                return closeMatchedEmphasis(top, marker)
            }
            // Zero-output entries never block a closer on their own (see the HTML
            // twin's probe): a silent degradation or a href-less sentinel emitted
            // nothing, so a legitimate closer must reach the emitting entry beneath —
            // a </code> behind an unclosed in-code <b> used to escape literally,
            // surfacing as visible text and stranding the block's remainder inside
            // the code entity. The entries pop only when a real match sits beneath;
            // otherwise everything stays open exactly as before
            var probe = openEntities.size - 1
            while (probe >= 0 && emitsNothing(openEntities[probe])) probe--
            val beneath = if (probe >= 0) openEntities[probe] as? OpenEmphasisMarker else null
            if (beneath != null && beneath.html && beneath.marker == marker) {
                completeOpenedAfter(beneath.seq)
                return closeMatchedEmphasis(beneath, marker)
            }
            return escapeTextInContext(tag)
        }
        // The '_'/'__' opener would glue onto a trailing underscore marker — degrade:
        // the entry goes silent (no opener, its closer emits nothing), the content
        // renders in place (see atUnderscoreGlue)
        if ((marker == "_" || marker == "__") && atUnderscoreGlue()) {
            openEntities.addLast(
                OpenEmphasisMarker(marker, html = true, seq = nextSeq++, silent = true),
            )
            return ""
        }
        // A '||' opener straight behind a spoiler closer would form a '||||' run — the
        // same reserved-character family tdlib re-pairs into entities it cannot close.
        // Degrade alike: a silent entry, the content renders in place (see atSpoilerGlue)
        if (marker == "||" && atSpoilerGlue()) {
            openEntities.addLast(
                OpenEmphasisMarker(marker, html = true, seq = nextSeq++, silent = true),
            )
            return ""
        }
        // An emphasis-family tag inside an open code entity emits no marker either: the
        // entity's content is literal, and `*`/`_`/`~`/`__` there would surface as
        // visible characters (inside code only ` and \ carry meaning — the entities
        // twin drops the tags outright). The entry goes silent — no opener, its closer
        // emits nothing, the same shape as the glue degradation above — and the content
        // renders code-escaped in place
        if (marker != "`" && htmlCodeDepth > 0) {
            openEntities.addLast(
                OpenEmphasisMarker(marker, html = true, seq = nextSeq++, silent = true),
            )
            return ""
        }
        // Same-type entities cannot nest (the entities twin's formatRangeOpen degrades
        // the inner range, and Telegram's entity rules bar the shape): the tag goes
        // silent — no opener, its closer emits nothing — and the content renders inside
        // the outer entity, whose marker already formats it. Two open markers of one
        // kind would otherwise re-pair into sibling entities and drop the middle
        // segment's formatting entirely (`*a*b*c*` bolds a and c, never b — the
        // HTML/entities twins show the whole region formatted as one entity). The
        // code family is exempt: a code tag inside an open code entity MERGES into it
        // below (nested code renders as single code), and a silent code entry would
        // own no depth unit while the output-time URL-context stack read counts it —
        // the anchor-completion URLs would escape by code rules they do not sit in
        if (marker != "`" && emphasisEntryOpen(marker)) {
            openEntities.addLast(
                OpenEmphasisMarker(marker, html = true, seq = nextSeq++, silent = true),
            )
            return ""
        }
        // A code-family tag never emits a second backtick in a row: nested inside an
        // open code entity it merges into it (nested code renders exactly as single
        // code), and directly behind a closed entity's backtick the delimiter would
        // glue into a reserved-character run — the span degrades to escaped plain text
        val delimiter =
            if (marker == "`" && htmlCodeDepth > 0) {
                CodeDelimiter.MERGED
            } else if (marker == "`" && atCodeGlue()) {
                CodeDelimiter.SUPPRESSED
            } else {
                CodeDelimiter.LOUD
            }
        // For a LOUD code entry, the opener backtick lands at sb.length once the caller
        // appends this method's return — openPosition is the position right after it;
        // the underscore-family and spoiler openPositions follow the same rule for their
        // own lengths
        val openPosition =
            when {
                marker == "`" && delimiter == CodeDelimiter.LOUD -> sb.length + 1
                marker == "_" || marker == "__" -> sb.length + marker.length
                marker == "||" || marker == "*" || marker == "~" -> sb.length + marker.length
                else -> -1
            }
        openEntities.addLast(
            OpenEmphasisMarker(marker, html = true, delimiter, nextSeq++, openPosition),
        )
        if (delimiter != CodeDelimiter.SUPPRESSED && marker == "`") htmlCodeDepth++
        if (delimiter == CodeDelimiter.LOUD) {
            when (marker) {
                "_" -> lastUnderscoreEmission = UnderscoreEmission.I_OPEN
                "__" -> lastUnderscoreEmission = UnderscoreEmission.U_OPEN
            }
        }
        return if (delimiter == CodeDelimiter.LOUD) marker else ""
    }

    private fun renderAnchor(
        tag: String,
        closing: Boolean,
        selfClosing: Boolean,
        attrs: String,
    ): String {
        if (selfClosing) return escapeTextInContext(tag)
        if (closing) {
            // See renderHtmlInline: </a> matches only the innermost open entity — an
            // anchor closed while something opened inside it is still open goes literal
            // rather than emitting a crossed ](url). An emoji entry never matches
            // either: the two anchor kinds pair only with their own closers (see
            // OpenAnchor.emoji), so a mistyped </a> stays visible instead of silently
            // completing the custom emoji
            val top = openEntities.lastOrNull()
            if (top is OpenAnchor && !top.emoji) {
                return closeMatchedAnchor(top, tag)
            }
            // See renderHtmlInline's probe: zero-output entries never block a closer
            // on their own — a legitimate </a> reaches the anchor beneath an unclosed
            // in-code <b> or a silent degradation (the HTML twin's </a> probe)
            var probe = openEntities.size - 1
            while (probe >= 0 && emitsNothing(openEntities[probe])) probe--
            val beneath = if (probe >= 0) openEntities[probe] as? OpenAnchor else null
            if (beneath != null && !beneath.emoji) {
                completeOpenedAfter(beneath.seq)
                return closeMatchedAnchor(beneath, tag)
            }
            return escapeTextInContext(tag)
        }
        // Telegram links cannot nest: while any link entity is open (Markdown link, image
        // link or an outer anchor), an inner anchor and its closing tag are escaped literally
        if (linkEntityOpen()) {
            openEntities.addLast(OpenAnchor("", sb.length, sb.length, nextSeq++, nested = true))
            return escapeTextInContext(tag)
        }
        // A link entity cannot sit inside an open HTML code entity ("Pre and Code
        // can't contain nested entities"): the href-less sentinel shape, whose closer
        // emits nothing — the label text continues as plain code text, the degradation
        // the HTML and entities twins make of the tag (their codeDepth guards) and
        // visit(link) makes of the Markdown spelling
        if (htmlCodeDepth > 0) {
            openEntities.addLast(OpenAnchor("", sb.length, sb.length, nextSeq++))
            return ""
        }
        // href="" is treated like a missing href: the empty URL degrades to plain text
        val href = extractHref(attrs)
        openEntities.addLast(OpenAnchor(href.orEmpty(), sb.length, sb.length + 1, nextSeq++))
        return if (href == null) "" else "["
    }

    private fun linkEntityOpen(): Boolean = openEntities.any { it is OpenAnchor } || openMarkdownLinks > 0

    // Entries that emitted no output of their own: silent emphasis degradations,
    // href-less sentinel anchors and the in-link tg-emoji sentinel (a nested ANCHOR
    // emitted its literal tag text, so it does not qualify) — the HTML twin's
    // emitsNothing twin, backing the closing-tag probes
    private fun emitsNothing(entry: OpenEntity): Boolean =
        (entry is OpenEmphasisMarker && entry.silent) ||
            (entry is OpenAnchor && !entry.nested && entry.url.isEmpty()) ||
            (entry is OpenAnchor && entry.nested && entry.emoji)

    // The matched close of an html-owned emphasis entry: every arm the innermost match
    // and the zero-output probe's beneath-match share (the entry is on top of the stack
    // at every call — callers pop the blockers away first)
    private fun closeMatchedEmphasis(
        top: OpenEmphasisMarker,
        marker: String,
    ): String {
        // A glue-degraded entry emitted nothing and closes with nothing
        if (top.silent) {
            openEntities.removeLast()
            return ""
        }
        // The '_'/'__' closer would glue onto the tail — a just-closed inner entity's
        // marker, or this entry's own opener when it is empty (<i></i>, which would
        // complete into "__", an opened underline): drop the entity's markers and keep
        // the content (see atUnderscoreGlue)
        if ((marker == "_" || marker == "__") && atUnderscoreGlue()) {
            openEntities.removeLast()
            rewindUnderscoreOpener(top)
            return ""
        }
        // An empty code pair (<code></code>, or one whose only content dropped out — a
        // lone comment) rewinds its opener: emitting the closer would glue `` — an
        // empty entity Telegram rejects; the tags carried no visible text, so nothing
        // is lost (see output() for the same trade at completion points)
        if (marker == "`" && top.codeDelimiter == CodeDelimiter.LOUD && sb.length == top.openPosition) {
            openEntities.removeLast()
            htmlCodeDepth--
            sb.setLength(top.openPosition - 1)
            return ""
        }
        // An empty spoiler pair rewinds for the same reason: || straight onto || is an
        // empty entity, and the tags carried no visible text
        if (marker == "||" && sb.length == top.openPosition) {
            openEntities.removeLast()
            sb.setLength(top.openPosition - 2)
            return ""
        }
        // An empty bold or strikethrough pair rewinds alike: * straight onto * (and ~
        // onto ~) is the empty entity every other marker family rewinds, and the tags
        // carried no visible text, so nothing is lost
        if ((marker == "*" || marker == "~") && sb.length == top.openPosition) {
            openEntities.removeLast()
            sb.setLength(top.openPosition - 1)
            return ""
        }
        openEntities.removeLast()
        // LOUD and MERGED entries own a depth unit; a SUPPRESSED one never took one
        // (and a non-code marker has none to give back either)
        if (marker == "`" && top.codeDelimiter != CodeDelimiter.SUPPRESSED) htmlCodeDepth--
        if (top.codeDelimiter != CodeDelimiter.LOUD) return ""
        when (marker) {
            "_" -> lastUnderscoreEmission = UnderscoreEmission.I_CLOSE
            "__" -> lastUnderscoreEmission = UnderscoreEmission.U_CLOSE
        }
        return marker
    }

    // Whether an open, marker-emitting entry of [marker]'s kind exists — same-type
    // entities cannot nest, and two open markers of one kind re-pair into sibling
    // entities that drop the middle segment's formatting (see renderHtmlInline's
    // same-type degradation). Silent entries emitted no marker and do not count
    private fun emphasisEntryOpen(marker: String): Boolean =
        openEntities.any { it is OpenEmphasisMarker && it.marker == marker && !it.silent }

    // The matched close of an html-owned anchor: the arms the innermost match and the
    // zero-output probe's beneath-match share (the entry is on top of the stack at
    // every call — callers pop the blockers away first)
    private fun closeMatchedAnchor(
        top: OpenAnchor,
        tag: String,
    ): String {
        openEntities.removeLast()
        if (top.nested) return escapeTextInContext(tag)
        // "" is the href-less open tag: pop the stack, emit no link
        if (top.url.isEmpty()) return ""
        // Telegram rejects link entities with empty text: a label that rendered no
        // visible text (whitespace included) degrades to the bare escaped URL,
        // rewinding the bracket — safe for the same reason as in visit(link): a blank
        // label left nothing open
        if (regionIsBlankResolvingMarkers(top.labelStart)) {
            sb.setLength(top.openBracket)
            return escapeTextInContext(top.url)
        }
        return "](${escapeUrlInContext(top.url)})"
    }

    // <tg-emoji emoji-id="…">fallback</tg-emoji> renders as MarkdownV2's own custom emoji
    // syntax, ![fallback](tg://emoji?id=…) — the entry is anchor-shaped, so the generic
    // completion machinery (output, completeOpenedAfter) closes it like any link. The id
    // validates like the HTML format's attribute (see tgEmojiIdOf); anything else
    // degrades to literal text like every unknown tag. Inside a link the syntax cannot
    // sit — tdlib would re-pair the inner ](url) as the outer label's closer, and
    // Telegram links cannot nest anyway — so the tags vanish and the fallback text
    // renders alone; inside an open code entity the entry goes silent (custom emoji
    // cannot nest in code) and the fallback renders as plain code text
    private fun renderEmojiTag(
        tag: String,
        parts: TagParts,
    ): String {
        if (parts.closing) {
            // The closer matches the innermost emoji entry wherever it sits — a real
            // entry opened inside the fallback (an unclosed <b>) completes first so its
            // marker lands inside the emoji's region, the tight close the HTML and
            // entities twins make of the same shape (their lastOrNull probes and
            // completeOpenedAfter alike; pre-fix the closer went literal whenever the
            // emoji was not the innermost entry, surfacing as visible text)
            val top =
                openEntities.lastOrNull { it is OpenAnchor && it.emoji } as? OpenAnchor
                    ?: return escapeTextInContext(tag)
            completeOpenedAfter(top.seq)
            openEntities.removeLast()
            return when {
                top.nested || top.url.isEmpty() -> {
                    ""
                }

                regionIsBlankResolvingMarkers(top.labelStart) -> {
                    // Telegram requires the fallback: one that renders to no visible
                    // text degrades to the bare escaped URL, the same trade
                    // visit(link) makes
                    sb.setLength(top.openBracket)
                    escapeTextInContext(top.url)
                }

                !isSingleEmojiFallback(sb.substring(top.labelStart)) -> {
                    // The fallback must be exactly one regular emoji (see
                    // isSingleEmojiFallback): an entity the server silently ignores
                    // never opens — drop the ![ opener only, the fallback text ships
                    // as plain inline content, the same degrade the in-link path
                    // makes of the whole tag
                    sb.deleteRange(top.openBracket, top.openBracket + 2)
                    ""
                }

                else -> {
                    "](${escapeUrlInContext(top.url)})"
                }
            }
        }
        if (parts.selfClosing) return escapeTextInContext(tag)
        // The id check runs before every context branch (the HTML/entities twins' own
        // order): an id-less or invalid tag is visible text whichever context it sits
        // in — inside an open code entity the sentinel would otherwise swallow it
        // silently, diverging from the twins' literalization
        val id = tgEmojiIdOf(parts.attrs) ?: return escapeTextInContext(tag)
        if (linkEntityOpen()) {
            // See visit(link)'s guard: while any link entity is open, the emoji syntax
            // degrades to the fallback text (the sentinel keeps a stray closer silent)
            openEntities.addLast(OpenAnchor("", sb.length, sb.length, nextSeq++, nested = true, emoji = true))
            return ""
        }
        if (htmlCodeDepth > 0) {
            // The href-less sentinel shape: no opener, a silent closer, the fallback
            // continues as plain code text
            openEntities.addLast(OpenAnchor("", sb.length, sb.length, nextSeq++, emoji = true))
            return ""
        }
        openEntities.addLast(OpenAnchor(TG_EMOJI_URL_PREFIX + id, sb.length, sb.length + 2, nextSeq++, emoji = true))
        return "!["
    }

    // Whether the output region starting at [start] renders no visible text: empty or
    // whitespace only. The label callers check it immediately after the label's children,
    // so the region runs exactly to the end of the current output; any entity marker a
    // label had opened would have emitted a non-whitespace character, so a blank label
    // region guarantees nothing was left open inside it. visit(paragraph) asks the same
    // question of a paragraph's region for its no-content skip
    private fun regionIsBlank(start: Int): Boolean {
        for (index in start until sb.length) {
            if (!sb[index].isWhitespace()) return false
        }
        return true
    }

    // The blank test with entity markup resolved, for LABEL regions: a label of only
    // markers and whitespace — a code span around spaces, an emphasis pair around
    // nothing — still renders no visible text and is the empty link Telegram rejects.
    // Outside a code span an unescaped marker char is necessarily a marker (text
    // escapes them), and an escape pair is literal content of exactly one character,
    // non-whitespace unless the escaped char itself is (which the escapers never emit
    // — no whitespace character is MarkdownV2-special); INSIDE a code span or code
    // entity only ` and \ are escapes, so the same chars are literal content there —
    // the delimiters themselves toggle that state (label regions are entity-balanced
    // when the blank test runs, and MERGED/SUPPRESSED code entries emit no delimiter)
    private fun regionIsBlankResolvingMarkers(start: Int): Boolean {
        var index = start
        var inCode = false
        while (index < sb.length) {
            val char = sb[index]
            when {
                char == '\\' && index + 1 < sb.length -> {
                    if (!sb[index + 1].isWhitespace()) return false
                    index += 2
                }

                char == '`' -> {
                    inCode = !inCode
                    index++
                }

                !inCode && (char == '*' || char == '_' || char == '~' || char == '|') -> {
                    index++
                }

                !char.isWhitespace() -> {
                    return false
                }

                else -> {
                    index++
                }
            }
        }
        return true
    }

    // Whether [output]'s last line is a quote line — one that starts with the unescaped
    // '>' only the quote grammar emits (a literal '>' in text escapes as \'>', so that
    // line starts with the backslash), or with an expandable quote's "**>" lead (the
    // first line of a <blockquote expandable> render; a bold-in-bold opener pair is the
    // only other '**' a line can start with, and the '>' behind it would always escape
    // — content '>' never emits bare — so the shape is unambiguous). A closer glued
    // straight onto such a line would parse as an entity the quote itself opens, never
    // as the outer entity's closer — tdlib closes the blockquote at the first newline
    // not followed by '>', so a newline lead placed before the closers lifts them past
    // the quote's end, where they reach their own entities (the glued shape instead
    // draws "Can't find end of the entity" / "Character ']' is reserved"). Inside an
    // open HTML code entity a '>' at a line start is literal code content and a newline
    // there would corrupt the entity's text, so depth above zero answers false
    private fun endsOnQuoteLine(output: CharSequence): Boolean {
        if (htmlCodeDepth > 0) return false
        val lineStart = output.lastIndexOf('\n') + 1
        if (lineStart >= output.length) return false
        if (output[lineStart] == '>') return true
        return output.startsWith("**>", lineStart)
    }

    // Whether [output]'s last line is a code fence's closing line — "```", optionally
    // behind the indent a list-item context gives it. Content backticks always escape
    // in both code escapers, and an open fence can never be the last line (every fence
    // render emits its close), so the shape is unambiguous. A closer glued onto it is
    // legal MarkdownV2 but hostile to the de-format, whose fence scan drops the rest of
    // the closing line — the newline lead output() inserts keeps the closer on a line
    // of its own, where the de-format's own marker and label scans resolve it
    private fun endsOnFenceClose(output: CharSequence): Boolean {
        if (htmlCodeDepth > 0) return false
        val lineStart = output.lastIndexOf('\n') + 1
        var index = lineStart
        while (index < output.length && output[index] == ' ') index++
        return output.length - index == 3 && output[index] == '`' && output[index + 1] == '`' && output[index + 2] == '`'
    }

    // URL texts and link tails pick their escaper by code context: inside an open HTML
    // code entity only \` and \\ are escapes, so the text-context escapes a URL would
    // normally take (\_, \), \[ …) render their backslashes visibly — the same context
    // switch visit(text) makes. Degradation URLs and ](url) tails both route through
    // these, at every completion point
    private fun escapeUrlInContext(url: String): String = if (htmlCodeDepth > 0) escapeCode(url) else escapeUrl(url)

    private fun escapeTextInContext(text: String): String = if (htmlCodeDepth > 0) escapeCode(text) else escapeText(text)

    // Whether the output ends with a backtick that is not the tail of an escape
    // sequence. Content backticks always escape (\`), so at code-depth zero an
    // unescaped trailing one is necessarily a just-closed entity's delimiter — the
    // glue a new code opener must not touch. Parity settles escaped tails: escapes
    // pair every backslash with the character after it, so an odd number of
    // backslashes before the backtick means the last one belongs to an escape
    private fun atCodeGlue(): Boolean = if (sb.isEmpty()) precededByBacktick else endsWithUnescapedBacktick(sb)

    /**
     * Whether emitting a '_' or '__' marker now would glue it onto a trailing underscore
     * marker — the one shape family tdlib's parser re-pairs into entities Telegram rejects
     * ("Can't find end of the Italic/Underline entity"): "_" closes an italic only when
     * the next character is not '_', and "__" reads as one underline unit, so two touching
     * markers re-parse as a different nesting (a just-closed italic '_' followed by any
     * marker, or two openers with no content between). The single legal adjacency is an
     * underline CLOSER "__" on the left — "__X__Y__" and "__X___Y_" re-parse cleanly — so
     * only that emission kind clears the glue. An empty render checks the seam flag
     * instead (the chunking node seam passes the previous atom's tail in). Every emission
     * point — openers, matched closers, completion loops — degrades on a positive answer:
     * an opener pushes a silent entry and emits nothing, a closer rewinds its opener; the
     * entity's formatting drops, its content stays.
     */
    private fun atUnderscoreGlue(): Boolean =
        if (sb.isEmpty()) {
            precededByUnderscore
        } else {
            endsWithUnescapedUnderscore(sb) && lastUnderscoreEmission != UnderscoreEmission.U_CLOSE
        }

    // Whether the output ends with an unescaped '||' — a just-closed spoiler entity's
    // closer: a '||' opener touching it would form a '||||' run, and paired-marker runs
    // are the shape family tdlib re-pairs into entities it cannot close (the underscore
    // twin's problem). Content pipes always escape (\|) at code depth zero, so an
    // unescaped trailing pair is necessarily a marker — and at code depth above zero a
    // spoiler tag never reaches this check (its silent-entry branch fires first)
    private fun atSpoilerGlue(): Boolean {
        if (sb.length < 2 || sb[sb.length - 1] != '|' || sb[sb.length - 2] != '|') return false
        var backslashes = 0
        var index = sb.length - 3
        while (index >= 0 && sb[index] == '\\') {
            backslashes++
            index--
        }
        return backslashes % 2 == 0
    }

    // Drops a closing underscore-family entity's markers entirely: the opener rewinds
    // (its position is recorded on the entry), the closer never emits. Safe only where
    // nothing is open inside the entity — every caller pops it as the innermost entry —
    // so no still-recorded position lies inside the rewound range
    private fun rewindUnderscoreOpener(marker: OpenEmphasisMarker) {
        sb.deleteRange(marker.openPosition - marker.marker.length, marker.openPosition)
        lastUnderscoreEmission = null
    }

    private fun enterInline(): Boolean {
        if (inlineDepth >= MAX_RENDER_DEPTH) return false
        inlineDepth++
        return true
    }

    private fun exitInline() {
        inlineDepth--
    }

    // Continuation lines are indented to the marker width via applyPrefix (shared with
    // the truncation and chunking pipelines). A blank-bodied item is no-content and skips,
    // its marker consumed — the same walk forEachListItem makes, so the full render,
    // truncation and chunking agree on which items exist and how they number. A code
    // entity from an earlier block completes first — the marker and indent are structure,
    // not code content — and a custom emoji completes alike: item bodies render through
    // child visitors whose own quote/fence markers must not land inside the emoji's
    // fallback (see visit(blockQuote); the HTML twin's renderList completes the same set)
    private fun renderList(list: Node) {
        completeOpenCodeEntities()
        completeOpenEmojiAnchors()
        val start = sb.length
        val marker = listMarkerOf(list)
        var item = list.firstChild
        while (item != null) {
            if (item is ListItem) {
                val prefix = marker()
                val body = renderChild(item)
                if (body.isNotBlank()) {
                    sb.append(applyPrefix(prefix, body)).append('\n')
                }
            }
            item = item.next
        }
        // The final '\n' completes the block separator the last emitted item's own '\n'
        // started; a list whose every item is no-content (a lone "- " marker) renders to
        // nothing and separates nothing — the same trade visit(paragraph) makes for a
        // nothing-render
        if (sb.length > start) sb.append('\n')
    }

    // Renders a nested block (quote body, list item) in a child visitor; at the depth cap
    // the subtree flattens to plain text (see MAX_RENDER_DEPTH). Trailing newlines are
    // trimmed here — callers prepend their own prefixes and separators. The only caller
    // renders list item bodies, so the child visitor carries inListItem (quotes inside
    // the item degrade; a nested list's own items keep degrading through renderList →
    // renderChild)
    private fun renderChild(node: Node): String =
        if (depth + 1 >= MAX_RENDER_DEPTH) {
            escapedPlainText(node).trimEnd('\n')
        } else {
            val visitor = Visitor(depth + 1, options, inListItem = true)
            visitor.visitChildren(node)
            visitor.output().trimEnd('\n')
        }

    // The quote-child variant: the node renders through its own full block shape —
    // visit(node), not visitChildren(node) — so a nested blockQuote child arrives with
    // its own marker run for the caller to glue onto (see visit(blockQuote)). Depth
    // capping and trailing-newline trimming match renderChild. inListItem propagates: a
    // quote degraded behind list structure still renders its children in list-item
    // context, where any deeper quote degrades alike
    private fun renderQuoteChild(node: Node): String =
        if (depth + 1 >= MAX_RENDER_DEPTH) {
            escapedPlainText(node).trimEnd('\n')
        } else {
            val visitor = Visitor(depth + 1, options, inListItem = inListItem)
            node.accept(visitor)
            visitor.output().trimEnd('\n')
        }

    // appendPlainText flattens with no output-format escaping (the entities protocol
    // needs none); the degradation paths above land that text in MarkdownV2 output, so it
    // escapes on the way in — text-escaped outside code entities, code-escaped inside one
    // (a glue degradation can fire while an HTML code entity is open, and a \_ there
    // would show the backslash). The HTML twin's appendEscapedHtmlPlainText is the same
    // shape. Block-level children of the flattened subtree separate with the blank line
    // appendPlainText places between them — the same separator the structured render
    // emits, so a depth-capped subtree still reads as the blocks it flattened
    private fun escapedPlainText(node: Node): String {
        val plain = StringBuilder()
        appendPlainText(plain, node)
        val text = plain.toString()
        return if (htmlCodeDepth > 0) escapeCode(text) else escapeText(text)
    }
}

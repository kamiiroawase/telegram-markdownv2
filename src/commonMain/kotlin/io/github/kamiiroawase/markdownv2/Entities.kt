package io.github.kamiiroawase.markdownv2

/**
 * The MessageEntity types [TelegramEntities] emits, with the wire names the Bot API
 * expects in the `type` field (see [MessageEntity.wireName]).
 */
public enum class MessageEntityType(
    /** The Bot API's spelling of this type, as [MessageEntity.entitiesJson] emits it. */
    public val wireName: String,
) {
    /** `*bold*` — Markdown strong emphasis, or a `<b>`/`<strong>` tag. */
    BOLD("bold"),

    /** `*italic*` — Markdown emphasis, or an `<i>`/`<em>` tag. */
    ITALIC("italic"),

    /** A `<u>`/`<ins>` tag — CommonMark has no underline construct of its own. */
    UNDERLINE("underline"),

    /** `~~strike~~` — GFM strikethrough, or an `<s>`/`<del>`/`<strike>` tag. */
    STRIKETHROUGH("strikethrough"),

    /** A `<tg-spoiler>` tag. */
    SPOILER("spoiler"),

    /** A Markdown code span, or a `<code>`-family tag. */
    CODE("code"),

    /** A fenced or indented code block; carries [MessageEntity.language] when the fence names one. */
    PRE("pre"),

    /** A Markdown link or image (degraded to its alt text), or an `<a href>` tag; carries [MessageEntity.url]. */
    TEXT_LINK("text_link"),

    /** A Markdown block quote. */
    BLOCKQUOTE("blockquote"),

    /**
     * An `<blockquote expandable>` HTML block — the open tag on its own line, the
     * closing tag the block's last line — the collapsible quote variant; plain
     * CommonMark input has no spelling of its own (every render entry's KDoc states
     * the recognized form).
     */
    EXPANDABLE_BLOCKQUOTE("expandable_blockquote"),

    /** A `<tg-emoji emoji-id="…">` tag; carries [MessageEntity.customEmojiId]. */
    CUSTOM_EMOJI("custom_emoji"),
}

/**
 * One Telegram message entity: a [type] range over the message text, positioned by
 * [offset] and [length] in UTF-16 code units — the unit Kotlin strings already count, so
 * `text.substring(offset, offset + length)` on the accompanying text is always correct.
 *
 * The optional fields mirror the Bot API's MessageEntity: [url] for TEXT_LINK,
 * [language] for PRE, [customEmojiId] for CUSTOM_EMOJI; a null field emits nothing in
 * [entitiesJson].
 */
public data class MessageEntity(
    public val type: MessageEntityType,
    public val offset: Int,
    public val length: Int,
    public val url: String? = null,
    public val language: String? = null,
    public val customEmojiId: String? = null,
)

/**
 * Rendered message content for the Bot API's entities protocol: the [text] is exactly
 * what Telegram displays (no escape sequences, no markup) and [entities] describes its
 * formatting by ranges over that text. Send `text` with `entities` — no parse_mode — and
 * the "can't parse entities" class of 400s cannot happen: the text is plain, and ranges
 * the library computes are within bounds by construction.
 *
 * A value object: equality and copying compare both parts, and [EMPTY] is the shared
 * nothing-rendered instance. The entities are in close order — nested ranges before the
 * ranges containing them — and always nest by containment rather than cross.
 */
public data class EntityText(
    /** The plain, display text — offsets of [entities] index into this string. */
    public val text: String,
    /** The formatting ranges over [text], in close order (nested ranges first). */
    public val entities: List<MessageEntity>,
) {
    /**
     * The entities as the Bot API's JSON array — the exact `entities` field value of a
     * sendMessage request body: `[{"offset":0,"length":2,"type":"bold"},…]`, optional
     * fields included only when present. Hand-rolled for this fixed shape (no JSON
     * dependency in a formatting library); the string-bearing fields escape per RFC 8259.
     */
    public fun entitiesJson(): String =
        entities.joinToString(prefix = "[", postfix = "]", separator = ",") { entity ->
            buildString {
                append("{\"offset\":").append(entity.offset)
                append(",\"length\":").append(entity.length)
                append(",\"type\":\"").append(jsonEscape(entity.type.wireName)).append('"')
                entity.url?.let { append(",\"url\":\"").append(jsonEscape(it)).append('"') }
                entity.language?.let { append(",\"language\":\"").append(jsonEscape(it)).append('"') }
                entity.customEmojiId?.let {
                    append(",\"custom_emoji_id\":\"").append(jsonEscape(it)).append('"')
                }
                append('}')
            }
        }

    public companion object {
        /** The empty render: no text, no entities. */
        public val EMPTY: EntityText = EntityText("", emptyList())
    }
}

// RFC 8259 string escaping for the JSON this file emits: the two mandatory escapes, the
// named short forms, and \u for the remaining control characters — nothing else needs
// escaping in JSON
private fun jsonEscape(text: String): String =
    buildString(text.length + 8) {
        for (char in text) {
            when (char) {
                '"' -> {
                    append("\\\"")
                }

                '\\' -> {
                    append("\\\\")
                }

                '\n' -> {
                    append("\\n")
                }

                '\r' -> {
                    append("\\r")
                }

                '\t' -> {
                    append("\\t")
                }

                '\b' -> {
                    append("\\b")
                }

                '\u000C' -> {
                    append("\\f")
                }

                else -> {
                    if (char.code < 0x20) {
                        append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
    }

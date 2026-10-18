package io.github.kamiiroawase.markdownv2

/**
 * Per-chunk decoration for the decorated [MarkdownV2.renderChunked] twins: a [header]
 * and [footer] wrapped around every piece — page counters ("Part 2/5"), source links,
 * bot signatures. Both receive the piece's zero-based index and the total piece count
 * (so their width may vary with the count itself), and default to adding nothing.
 *
 * A value object for the string kinds it produces; the functions are the caller's own
 * markup generators — deterministic ones keep the re-chunking engine's convergence
 * simple (a header that grows every time it is asked can keep the pieces re-splitting).
 */
public class ChunkDecoration public constructor(
    /** Prepended to every chunk; receives its zero-based index and the total count. */
    public val header: (index: Int, total: Int) -> String = { _, _ -> "" },
    /** Appended to every chunk; receives its zero-based index and the total count. */
    public val footer: (index: Int, total: Int) -> String = { _, _ -> "" },
)

/**
 * The chunk-decoration engine shared by all three output formats: wraps a raw chunker
 * (which takes the effective content limit and returns pieces) so every piece carries
 * its [ChunkDecoration] while staying within [maxLength].
 *
 * The decorations' width can vary with the piece count itself. The engine handles that
 * with a converging re-chunk: it chunks at the content limit left after reserving the
 * widest decoration seen so far, decorates, and re-runs with the reserve raised to the
 * new maximum while any piece overflows. The reserve strictly grows on every iteration
 * (an overflowing piece was within the limit, so its decoration alone exceeded the
 * reserve), so the loop terminates — in practice after one or two rounds; a decoration
 * so wide that no content room remains fails fast with [IllegalArgumentException].
 *
 * The decoration strings insert verbatim: they are the caller's own markup, so for the
 * string formats any special characters are the caller's to escape (`MarkdownV2.escape`
 * / `TelegramHtml.escape`), while a deliberate link or entity goes through unescaped.
 * The entities variant shifts every range by its header's length.
 */
internal fun decorateChunks(
    maxLength: Int,
    minLimit: Int,
    chunk: (limit: Int) -> List<String>,
    decoration: ChunkDecoration,
    // When the header must not glue directly onto the piece's first character — the
    // MarkdownV2 twin passes a predicate for the one shape its grammar forbids: a quote
    // marker '>' that has left the line start. The separator newline counts toward the
    // piece budget, so the re-chunk loop accounts for it by construction
    separatorNeeded: (head: String, piece: String) -> Boolean = { _, _ -> false },
): List<String> {
    var reserve = 0
    while (true) {
        val limit = maxLength - reserve
        require(limit >= minLimit) {
            "chunk decorations leave no room for content under maxLength=$maxLength " +
                "(reserved $reserve of it); shorten the decorations or raise maxLength"
        }
        val pieces = chunk(limit)
        if (pieces.isEmpty()) return pieces
        val total = pieces.size
        var widest = 0
        val decorated =
            pieces.mapIndexed { index, piece ->
                val head = decoration.header(index, total)
                val tail = decoration.footer(index, total)
                val separator = if (separatorNeeded(head, piece)) "\n" else ""
                widest = maxOf(widest, head.length + separator.length + tail.length)
                head + separator + piece + tail
            }
        if (decorated.all { it.length <= maxLength }) return decorated
        reserve = widest
    }
}

/** The [EntityText] twin of [decorateChunks]: ranges shift by each piece's header length. */
internal fun decorateEntityChunks(
    maxLength: Int,
    minLimit: Int,
    chunk: (limit: Int) -> List<EntityText>,
    decoration: ChunkDecoration,
): List<EntityText> {
    var reserve = 0
    while (true) {
        val limit = maxLength - reserve
        require(limit >= minLimit) {
            "chunk decorations leave no room for content under maxLength=$maxLength " +
                "(reserved $reserve of it); shorten the decorations or raise maxLength"
        }
        val pieces = chunk(limit)
        if (pieces.isEmpty()) return pieces
        val total = pieces.size
        var widest = 0
        val decorated =
            pieces.mapIndexed { index, piece ->
                val head = decoration.header(index, total)
                val tail = decoration.footer(index, total)
                widest = maxOf(widest, head.length + tail.length)
                EntityText(
                    head + piece.text + tail,
                    piece.entities.map { entity ->
                        MessageEntity(
                            entity.type,
                            entity.offset + head.length,
                            entity.length,
                            entity.url,
                            entity.language,
                            entity.customEmojiId,
                        )
                    },
                )
            }
        if (decorated.all { it.text.length <= maxLength }) return decorated
        reserve = widest
    }
}

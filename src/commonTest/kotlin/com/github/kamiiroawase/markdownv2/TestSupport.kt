package com.github.kamiiroawase.markdownv2

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

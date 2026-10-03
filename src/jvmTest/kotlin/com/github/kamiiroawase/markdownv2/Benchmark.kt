package com.github.kamiiroawase.markdownv2

import org.commonmark.node.HtmlInline
import org.commonmark.node.Paragraph
import java.util.Locale

// JVM rendering performance benchmark, the data source of the README performance section:
// ./gradlew benchmark — the full suite repeats ROUNDS times with the cases interleaved,
// and each scenario keeps its best (minimum) round median; single-threaded, iterations
// sized by input after warm-up. Timing covers the full commonmark parse plus
// MarkdownV2 render/truncate pipeline. Interference like CPU frequency scaling or thermal
// windows can slow an entire single pass several-fold (a 4x spread was observed on the
// truncation-heavy scenario on the same machine and code), so the best round estimates the
// machine's capability far more reproducibly than any one pass. Numbers still vary per
// machine; treat them as order-of-magnitude references.

private class BenchmarkCase(
    val name: String,
    val inputBytes: Long,
    val action: () -> Unit,
)

private const val ROUNDS = 5

fun main() {
    val cases = buildCases()
    // Global warm-up: run every scenario a few times first, so the first case alone does
    // not absorb the class-loading/singleton-init bias
    repeat(3) {
        cases.forEach { it.action() }
    }
    println("java ${System.getProperty("java.version")} / ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    println()
    println("| Scenario | Input | Median | Throughput |")
    println("| --- | --- | --- | --- |")
    val medians = LongArray(cases.size) { Long.MAX_VALUE }
    repeat(ROUNDS) {
        cases.forEachIndexed { index, case ->
            // Small inputs get more iterations: per-run noise dominates their timings
            val warmup =
                if (case.inputBytes < 10_000) {
                    15
                } else if (case.inputBytes < 100_000) {
                    5
                } else {
                    3
                }
            val iterations =
                if (case.inputBytes < 10_000) {
                    100
                } else if (case.inputBytes < 100_000) {
                    20
                } else {
                    10
                }
            medians[index] = minOf(medians[index], medianNanos(warmup, iterations, case.action))
        }
    }
    cases.forEachIndexed { index, case ->
        println(
            "| ${case.name} | ${formatBytes(
                case.inputBytes,
            )} | ${formatNanos(medians[index])} | ${formatThroughput(case.inputBytes, medians[index])} |",
        )
    }
}

// Input data is English throughout except the table case: wide characters are exactly
// what display-width alignment measures, so the table cells deliberately keep CJK — the
// README figures were measured with these exact inputs, do not translate that content
private fun buildCases(): List<BenchmarkCase> {
    val short =
        listOf(
            "**bold** with *italic*, `code`, [link](https://example.com/a) and a list:",
            "- first item",
            "- second item",
        ).joinToString("\n")

    val typical =
        buildString {
            appendLine("# Project notes")
            appendLine()
            appendLine("Overview paragraph, introducing the content and structure, with **emphasis** and plain text.")
            repeat(12) { section ->
                appendLine()
                appendLine("## Section $section")
                appendLine()
                appendLine("Body of section $section, adding detail and examples.")
                appendLine()
                appendLine("- point one")
                appendLine("- point two")
                appendLine("- point three")
                appendLine()
                appendLine("```kotlin")
                appendLine("fun example$section(): Int = $section")
                appendLine("```")
            }
            appendLine()
            appendLine("| Name | Value | Note |")
            appendLine("| --- | :--: | --- |")
            repeat(8) { appendLine("| item$it | ${it * 12} | remark$it |") }
        }

    // 3600 chars; the render stays within 4096, no truncation
    val atLimit = "Long paragraph of plain text. ".repeat(120)

    // ~42KB; multi-block structure-preserving truncation to 4096
    val overlong = (1..400).joinToString("\n\n") { "Paragraph $it: " + "word ".repeat(18) }

    // Over-long single line; escaped plain-text fallback
    val plainFallback = "special*char_content " + "x".repeat(50_000)

    val table =
        buildString {
            appendLine("| 列一 | 列二 | 列三 | 列四 |")
            appendLine("| --- | :--: | --: | --- |")
            repeat(60) { row -> appendLine("| 数据$row | 中文单元 $row | ${row * 3} | 备注文字$row |") }
        }

    // 500 lines of code; the render exceeds 4096 and goes through code-block shrinking
    val code = "```kotlin\n" + (1..500).joinToString("\n") { "val line$it = \"filler line $it\" // comment" } + "\n```"

    // Inline tags and anchors mixed; the render stays within 4096
    val htmlInline =
        (
            "text<b>bold</b>and<i>italic</i>" + "content ".repeat(20) +
                "<a href=\"https://example.com/x\">link</a>\n\n"
        ).repeat(20)

    // Beyond 100 levels; triggers deep flattening
    val deepNesting = ">".repeat(200) + " deep quote body " + "x".repeat(100)

    // 100k-scale unclosed tag: linear-scan regression for the hand-written tag parser,
    // fed as an inline node via the AST
    val maliciousLiteral = "<b " + "x".repeat(100_000)
    val malicious =
        Paragraph().apply {
            appendChild(HtmlInline(maliciousLiteral))
        }

    // A million characters: peak throughput under the render-full-then-truncate strategy
    val huge = "large document body text ".repeat(40_000)

    fun stringCase(
        name: String,
        content: String,
    ): BenchmarkCase =
        BenchmarkCase(name, content.toByteArray(Charsets.UTF_8).size.toLong()) {
            // The realistic bot pipeline truncates to the Telegram limit; the render default
            // (maxLength 0) would disable truncation and silently change what is measured
            MarkdownV2.render(content, MarkdownV2.MAX_MESSAGE_LENGTH)
        }

    return listOf(
        stringCase("Short text (typical chat message)", short),
        stringCase("Typical reply (headings/lists/code/table)", typical),
        stringCase("Plain paragraph near the limit (no truncation)", atLimit),
        stringCase("Overlong multi-paragraph (truncated to 4096)", overlong),
        stringCase("Overlong single line (plain-text fallback)", plainFallback),
        stringCase("Large table (60 rows x 4 cols, degraded)", table),
        stringCase("Large code block (500 lines, truncated)", code),
        stringCase("Inline HTML mix", htmlInline),
        stringCase("Deeply nested quote (200 levels, flattened)", deepNesting),
        BenchmarkCase(
            "Malicious unclosed tag (tag-scan linearity)",
            maliciousLiteral.toByteArray(Charsets.UTF_8).size.toLong(),
        ) {
            MarkdownV2.render(malicious, MarkdownV2.MAX_MESSAGE_LENGTH)
        },
        stringCase("Huge document (1M characters)", huge),
    )
}

private fun medianNanos(
    warmup: Int,
    iterations: Int,
    action: () -> Unit,
): Long {
    repeat(warmup) { action() }
    val samples = LongArray(iterations)
    for (index in samples.indices) {
        val start = System.nanoTime()
        action()
        samples[index] = System.nanoTime() - start
    }
    samples.sort()
    return samples[samples.size / 2]
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1_000_000 -> "%.1f MB".format(Locale.ROOT, bytes / 1_000_000.0)
        else -> "%.1f KB".format(Locale.ROOT, bytes / 1_000.0)
    }

private fun formatNanos(nanos: Long): String =
    when {
        nanos >= 10_000_000 -> "%.1f ms".format(Locale.ROOT, nanos / 1_000_000.0)
        else -> "%.0f us".format(Locale.ROOT, nanos / 1_000.0)
    }

private fun formatThroughput(
    bytes: Long,
    nanos: Long,
): String {
    val mbPerSecond = bytes * 1_000.0 / nanos
    return if (mbPerSecond < 10) {
        "%.1f MB/s".format(Locale.ROOT, mbPerSecond)
    } else {
        "%.0f MB/s".format(Locale.ROOT, mbPerSecond)
    }
}

# telegram-markdownv2

[![Build](https://github.com/kamiiroawase/telegram-markdownv2/actions/workflows/build.yml/badge.svg)](https://github.com/kamiiroawase/telegram-markdownv2/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kamiiroawase/telegram-markdownv2.svg)](https://central.sonatype.com/artifact/io.github.kamiiroawase/telegram-markdownv2)
[![License: Unlicense](https://img.shields.io/badge/license-Unlicense-blue.svg)](LICENSE)

Kotlin Multiplatform 库：把 **CommonMark（含 GFM 表格与删除线）转换为 Telegram MarkdownV2 或 HTML**，并对超长内容做保持结构完整的截断或无损分片（Telegram 消息上限 4096 字符，分片按序发送即可完整送达——文本不丢字符，接缝处实体按段闭合）。为 LLM 机器人输出而生——模型回复是任意长度、任意结构的 Markdown，还经常带 Telegram 渲染不了的构造。

[English version](README.en.md)

## 使用

发布于 [Maven Central](https://central.sonatype.com/artifact/io.github.kamiiroawase/telegram-markdownv2)，版本跟随 `v*` git tag。支持 Android（minSdk 23）、JVM 11+、JS、Wasm、Linux（x64/Arm64）、macOS（Arm64）、Windows（mingwX64）、iOS（设备与模拟器）。

```kotlin
repositories { mavenCentral() }

dependencies {
    // KMP 消费方在 commonMain 引用根坐标（Gradle module metadata 自动解析平台变体）；
    // 需要钉住具体变体时加后缀：-android / -jvm / -js / -wasm-js / -linuxx64 /
    // -linuxarm64 / -macosarm64 / -mingwx64 / -iosarm64 / -iosx64 / -iossimulatorarm64
    implementation("io.github.kamiiroawase:telegram-markdownv2:2.4.1")
}
```

```kotlin
import io.github.kamiiroawase.markdownv2.MarkdownV2
import io.github.kamiiroawase.markdownv2.TelegramHtml

// parse_mode = MarkdownV2
val text = MarkdownV2.render("**hello** world")                        // 直接发送
val clipped = MarkdownV2.render(reply, MarkdownV2.MAX_MESSAGE_LENGTH)  // 超限截断，仍是合法 MarkdownV2
val parts = MarkdownV2.renderChunked(reply)                            // 无损分片，每段 ≤ 4096 且各自合法，按序发送
val literal = MarkdownV2.escape(userInput)                             // 手动拼 MarkdownV2 时转义动态文本
val fallback = MarkdownV2.toPlainText(rejectedPart)                    // 被拒收时还原纯文本、不带 parse_mode 重发

// parse_mode = HTML —— 两个入口共享解析器、RenderOptions 与截断/分片语义
val html = TelegramHtml.render("**hello** <tg-spoiler>world</tg-spoiler>")   // tg-spoiler、tg-emoji 原样透传
val htmlParts = TelegramHtml.renderChunked(reply)                            // 同样无损分片，每段独立合法 HTML
val htmlLiteral = TelegramHtml.escape(userInput)                             // 转义 & < >（属性值另转义 "）
```

- 官方 18 个特殊字符加反斜杠共 19 个全量转义；行内代码 / 代码块 / 链接 URL 按各自规则转义
- 不支持的构造自动降级：表格转等宽对齐的代码块（单元格截断上限与字符宽度度量可经 `RenderOptions` 定制）、HTML 块映射为实体或字面文本、列表项内的引用块降级为原内容（Bot API 只接受行首的未转义 `>`，跟在列表标记或续行缩进后的 `>` 会导致整条消息被 400 拒收）、会与相邻 `_`/`__` 标记粘连的下划线族强调降级（解析器会把紧贴的 `_`/`__` 重新配对成无法闭合的实体）
- 截断与分片保结构：代码围栏闭合、未闭合实体补全、引用与列表前缀逐行保留，绝不劈开转义序列和代理对；结构化内容完全放不下时回退为转义纯文本
- 需要更多 Markdown 扩展（footnotes、autolink 等）时在 `MarkdownV2.defaultExtensions` 之上传入自建 `Parser`；未知扩展节点按纯内容遍历，不崩溃

### HTML 端的差异

- 转义处处一致：文本中 `&` `<` `>`，属性值中另加 `"`——没有 MarkdownV2 那套按上下文收窄的规则
- 引用是 `<blockquote>` 包装标签而非行首 `>` 前缀，因此**列表项内的引用保留引用格式**（MarkdownV2 端只能降级）；全空的引用不输出（MarkdownV2 端保留一个裸 `>` 标记）
- 行内输入 HTML 中的 `<tg-spoiler>` 与带数字 `emoji-id` 的 `<tg-emoji>` 原样透传——MarkdownV2 表达不了的构造；代码围栏带语言时映射为 `<pre><code class="language-x">`（Telegram 据此做语法高亮）
- 分片的下限是 5 字符（一个转义单元 `&amp;` 的长度，对应 `TelegramHtml.MIN_CHUNK_LENGTH`）；低于它返回完整渲染单片

## 已知限制

- 解析后端与 commonmark-java 的行为差异：HTML 块识别较弱（`<!-- -->`、`<!DOCTYPE>` 按段落内联处理）；强调内部的闭合 HTML 标签（如 `*a<b>x</b>*` 中的 `</b>`）不被识别为行内 HTML
- 超过 100 层的引用 / 列表 / 行内强调平铺为纯文本（防病态输入栈溢出）
- 病态深嵌套的主要耗时在上游解析器：数千层嵌套列表（约 4MB 输入）端到端秒级，九成在 commonmark-kotlin 解析；接受不可信输入的服务应自行限制输入规模
- 超长内容先完整渲染再收缩（两遍策略）；截断收缩按重试收敛，最坏 O(n²)——4096 默认上限下无感
- MarkdownV2 不是普通 Markdown：`__` 是下划线不是粗体，不要把渲染结果再当普通 Markdown 二次加工
- HTML 端的块级输入 HTML（`<div>` 等）仍降级为 `<pre>` 转义文本，不透传；可折叠引用（`<blockquote expandable>`）同样不支持

## 开发

```bash
git clone https://github.com/kamiiroawase/telegram-markdownv2.git
cd telegram-markdownv2
./gradlew build        # JDK 21+：编译全部 target + 宿主可执行测试 + 格式检查
./gradlew jvmTest      # 单独跑某个平台
./gradlew benchmark    # 性能基准：常规消息 ~0.1ms 量级，百万字符 ~10ms；发版时 CI 复跑输出附在 GitHub Release
```

430 个行为级测试（输入/输出断言），全部平台运行；其中 HTML 端的每条输出还过一道 tdlib HTML 解析器的规则移植（`assertTelegramHtmlParseable`），MarkdownV2 端同理（`assertTelegramParseable`）。质量门禁全挂在 `build` 上：Spotless 格式、`explicitApi()` 显式 API、binary-compatibility-validator 公开 API 快照（有意变更时跑 `./gradlew apiDump`）。

## 许可

[The Unlicense](LICENSE) —— 公共领域，随意使用。

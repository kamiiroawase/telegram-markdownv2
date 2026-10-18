# telegram-markdownv2

[![Build](https://github.com/kamiiroawase/telegram-markdownv2/actions/workflows/build.yml/badge.svg)](https://github.com/kamiiroawase/telegram-markdownv2/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kamiiroawase/telegram-markdownv2.svg)](https://central.sonatype.com/artifact/io.github.kamiiroawase/telegram-markdownv2)
[![License: Unlicense](https://img.shields.io/badge/license-Unlicense-blue.svg)](LICENSE)

Kotlin Multiplatform 库：把 **CommonMark（含 GFM 表格与删除线）转换为 Telegram MarkdownV2、HTML 或 entities 协议**，并对超长内容做保持结构完整的截断或无损分片（Telegram 消息上限 4096 字符，分片按序发送即可完整送达——文本不丢字符，接缝处格式化按段收口，每段独立可发送）。为 LLM 机器人输出而生——模型回复是任意长度、任意结构的 Markdown，还经常带 Telegram 渲染不了的构造。

[English version](README.en.md)

## 使用

发布于 [Maven Central](https://central.sonatype.com/artifact/io.github.kamiiroawase/telegram-markdownv2)，版本跟随 `v*` git tag。支持 Android（minSdk 23）、JVM 11+、JS、Wasm、Linux（x64/Arm64）、macOS（Arm64）、Windows（mingwX64）、iOS（设备与模拟器）。

```kotlin
repositories { mavenCentral() }

dependencies {
    // KMP 消费方在 commonMain 引用根坐标（Gradle module metadata 自动解析平台变体）；
    // 需要钉住具体变体时加后缀：-android / -jvm / -js / -wasm-js / -linuxx64 /
    // -linuxarm64 / -macosarm64 / -mingwx64 / -iosarm64 / -iosx64 / -iossimulatorarm64
    implementation("io.github.kamiiroawase:telegram-markdownv2:3.0.1")
}
```

```kotlin
import io.github.kamiiroawase.markdownv2.ChunkDecoration
import io.github.kamiiroawase.markdownv2.MarkdownV2
import io.github.kamiiroawase.markdownv2.TelegramEntities
import io.github.kamiiroawase.markdownv2.TelegramHtml

// parse_mode = MarkdownV2
val text = MarkdownV2.render("**hello** world")                        // 直接发送
val clipped = MarkdownV2.render(reply, MarkdownV2.MAX_MESSAGE_LENGTH)  // 超限截断，仍是合法 MarkdownV2
val parts = MarkdownV2.renderChunked(reply)                            // 无损分片，每段 ≤ 4096 且各自合法，按序发送
val literal = MarkdownV2.escape(userInput)                             // 手动拼 MarkdownV2 时转义动态文本
val fallback = MarkdownV2.toPlainText(rejectedPart)                    // 被拒收时还原纯文本、不带 parse_mode 重发

// parse_mode = HTML —— 三个入口共享解析器、RenderOptions 与截断/分片语义
val html = TelegramHtml.render("**hello** <tg-spoiler>world</tg-spoiler>")   // tg-spoiler、tg-emoji 原样透传
val htmlParts = TelegramHtml.renderChunked(reply)                            // 同样无损分片，每段独立合法 HTML
val htmlLiteral = TelegramHtml.escape(userInput)                             // 文本转义 & < >；属性值由渲染器另转义 "

// 不带 parse_mode —— entities 协议：纯文本 + 格式化区间，从根上消灭 400
val message = TelegramEntities.render(reply)          // EntityText(text = 纯显示文本, entities = 区间列表)
sendMessage(text = message.text, entities = message.entitiesJson())
val messageParts = TelegramEntities.renderChunked(reply)   // 同样无损分片，每段实体的偏移按段重基准

// 分片装饰：每片带头尾（页码 / 签名），三个入口都有对应重载
val pages = MarkdownV2.renderChunked(
    reply,
    decoration = ChunkDecoration(header = { i, n -> "第 ${i + 1}/$n 页\n\n" }),
)
```

- 官方 18 个特殊字符加反斜杠共 19 个全量转义；行内代码 / 代码块 / 链接 URL 按各自规则转义
- `<tg-spoiler>` 映射为原生 `||…||` 剧透标记，`<tg-emoji emoji-id="…">` 与 Bot API 自身的自定义 emoji 写法 `![后备](tg://emoji?id=…)`（图片语法）都映射为 `![后备](tg://emoji?id=…)`——三种输出格式均支持自定义 emoji 与剧透
- 不支持的构造自动降级：表格转等宽对齐的代码块（单元格截断上限与字符宽度度量可经 `RenderOptions` 定制）、HTML 块映射为实体或字面文本、列表项内的引用块降级为原内容（Bot API 只接受行首的未转义 `>`，跟在列表标记或续行缩进后的 `>` 会导致整条消息被 400 拒收）、会与相邻 `_`/`__` 标记粘连的下划线族强调降级（解析器会把紧贴的 `_`/`__` 重新配对成无法闭合的实体）
- 截断与分片保结构：代码围栏闭合、未闭合实体补全、引用与列表前缀逐行保留，绝不劈开转义序列和代理对；结构化内容完全放不下时回退为转义纯文本
- 输入侧默认启用任务列表（`- [ ]`/`- [x]`，标记按字面回写）、autolink（裸 URL/邮箱自动成链接）与脚注（引用与定义标签保持可见）；需要更多扩展（heading-anchor、ins 等）时在 `MarkdownV2.defaultExtensions` 之上传入自建 `Parser`，未知扩展节点按纯内容遍历，不崩溃

### HTML 端的差异

- 转义处处一致：文本中 `&` `<` `>`，属性值中另加 `"`——没有 MarkdownV2 那套按上下文收窄的规则
- 引用是 `<blockquote>` 包装标签而非行首 `>` 前缀，因此**列表项内的引用保留引用格式**（MarkdownV2 端只能降级）；全空的引用不输出（MarkdownV2 端保留一个裸 `>` 标记）
- 行内输入 HTML 中的 `<tg-spoiler>` 与带数字 `emoji-id` 的 `<tg-emoji>` 原样透传，图片写法 `![后备](tg://emoji?id=…)`（Bot API 自身的自定义 emoji 语法）映射为 `<tg-emoji>`；代码围栏带语言时映射为 `<pre><code class="language-x">`（Telegram 据此做语法高亮，语言名限 ASCII——超出该属性语法的语言降级为无语言 `<pre>`）
- 分片的下限是 5 字符（一个转义单元 `&amp;` 的长度，对应 `TelegramHtml.MIN_CHUNK_LENGTH`）；低于它返回完整渲染单片

### entities 端的差异

- 文本就是 Telegram 显示的原文，没有任何转义规则——"400 can't parse entities" 这一类失败不存在，纯文本兜底不再需要
- 格式走区间而非标记：引用是 blockquote 区间（列表项内照样保留），`<tg-spoiler>`/`<tg-emoji>` 映射为原生 spoiler/custom_emoji 实体，带语言的代码块映射为带 `language` 的 pre 实体
- 偏移按 Bot API 规则使用 UTF-16 码元——Kotlin 字符串本身就是，`EntityText` 可直接进 `sendMessage(text, entities)`；`entitiesJson()` 输出请求体格式的 JSON 数组（手写序列化，不引依赖）
- 截断时区间钳制到幸存文本（跨界区间缩短、界外区间丢弃）；分片时结构化格式逐段重建（拆开的引用每段带自己的 blockquote 区间），跨缝的内联区间只留在持有其起点的段里——与两个字符串格式的接缝闭合纪律一致
- 分片的下限是 2 字符（一个代理对，对应 `TelegramEntities.MIN_CHUNK_LENGTH`）

## 集成（ktgram）

[ktgram](https://github.com/vendelieu/telegram-bot)（`eu.vendeli:telegram-bot`）的 action 上可以直接接线三种格式——`options { parseMode = ... }` 或 `entities(...)`（版本与 DSL 细节以 ktgram 文档为准）：

```kotlin
import eu.vendeli.tgbot.api.message.sendMessage
import eu.vendeli.tgbot.types.component.ParseMode
import eu.vendeli.tgbot.types.msg.EntityType
import eu.vendeli.tgbot.types.msg.MessageEntity
import io.github.kamiiroawase.markdownv2.MessageEntity as TmMessageEntity
import io.github.kamiiroawase.markdownv2.MessageEntityType
import io.github.kamiiroawase.markdownv2.TelegramEntities
import io.github.kamiiroawase.markdownv2.TelegramHtml

// HTML：parse_mode = HTML
sendMessage(TelegramHtml.render(reply))
    .options { parseMode = ParseMode.HTML }
    .send(user, bot)

// entities：不发 parse_mode，直接传区间（字段与 Bot API 一致）
fun TmMessageEntity.toKtgram() = MessageEntity(
    type = when (type) {
        MessageEntityType.BOLD -> EntityType.Bold
        MessageEntityType.ITALIC -> EntityType.Italic
        MessageEntityType.UNDERLINE -> EntityType.Underline
        MessageEntityType.STRIKETHROUGH -> EntityType.Strikethrough
        MessageEntityType.SPOILER -> EntityType.Spoiler
        MessageEntityType.CODE -> EntityType.Code
        MessageEntityType.PRE -> EntityType.Pre
        MessageEntityType.TEXT_LINK -> EntityType.TextLink
        MessageEntityType.BLOCKQUOTE -> EntityType.Blockquote
        MessageEntityType.CUSTOM_EMOJI -> EntityType.CustomEmoji
    },
    offset = offset,
    length = length,
    url = url,
    language = language,
    customEmojiId = customEmojiId,
)

val formatted = TelegramEntities.render(reply)
sendMessage(formatted.text)
    .entities(formatted.entities.map { it.toKtgram() })
    .send(user, bot)
```

**分页编辑模式**：把分片 + 装饰与 `editMessageText` 组合，一条消息原地翻页，而不是连发一串——先发第一页，把返回的 `Message` 存下，翻页时编辑它：

```kotlin
val pages = TelegramEntities.renderChunked(
    reply,
    decoration = ChunkDecoration(footer = { i, n -> if (n > 1) "\n\n第 ${i + 1}/$n 页" else "" }),
)

var page = 0
val first = sendMessage(pages[0].text)
    .entities(pages[0].entities.map { it.toKtgram() })
    .sendReturning(user, bot)
    .getOrNull() ?: return

// 翻页按钮的回调里：
editMessageText(first.messageId, pages[++page].text)
    .entities(pages[page].entities.map { it.toKtgram() })
    .send(user, bot)
```

其他框架同理：任何能设 `parse_mode` 或传 `entities` 的 Bot API 客户端（ktor、ktor-client 裸调 REST 也行）都可以直接消费这三个入口的输出——`entitiesJson()` 就是请求体里 `entities` 字段的现成值。

## 已知限制

- 解析后端与 commonmark-java 的行为差异：HTML 块识别较弱（`<!-- -->`、`<!DOCTYPE>` 按段落内联处理）；强调内部的闭合 HTML 标签（如 `*a<b>x</b>*` 中的 `</b>`）不被识别为行内 HTML
- 超过 100 层的引用 / 列表 / 行内强调平铺为纯文本（防病态输入栈溢出）；平铺的连续块之间没有分隔符，深层内容可能黏连成词
- 病态深嵌套的主要耗时在上游解析器：数千层嵌套列表（约 4MB 输入）端到端秒级，九成在 commonmark-kotlin 解析；接受不可信输入的服务应自行限制输入规模
- 超长内容先完整渲染再收缩（两遍策略）；截断收缩按重试收敛，最坏 O(n²)——4096 默认上限下无感
- MarkdownV2 不是普通 Markdown：`__` 是下划线不是粗体，不要把渲染结果再当普通 Markdown 二次加工
- 官方 task-list / autolink 扩展的后处理器递归遍历块树，深嵌套输入会栈溢出（mingwX64 实测 1500 层引用）——默认集合挂的是库内行为对齐的迭代版替代品（internal 实现，产出与官方一致的节点类型）；footnotes 官方版无此问题，直接采用
- HTML 端的块级输入 HTML（`<div>` 等）仍降级为 `<pre>` 转义文本，不透传；可折叠引用（`<blockquote expandable>`）同样不支持
- entities 端对引用套引用（`> > x`）产出嵌套的 blockquote 区间——Bot API 格式化规则禁止 blockquote 嵌套，但服务端发送时静默修复（丢弃内层区间），消息照常送达；对原始区间数组做严格校验的消费方需自行去重
- 分片的"不丢字符"有三个纯空白例外：接缝触发时待拼接的纯空白行丢弃（代码块顶部或中部皆是）；超出单块预算的纯空白代码行整行丢弃；全空白的扁平化分片丢弃（服务端拒绝空白消息）——丢失的都是 Telegram 永远不会显示、或会整条拒收的空白
- 分片还有一个结构标记例外：上限小到放不下"结构标记 + 一个内容单元"时（自定义的极小 maxLength），列表标记（`• `、`10. `）或标题井号丢弃以保住正文——正文文本永不丢弃，与三格式一致的 lead-drop 取舍（各入口 `renderChunked` 的 KDoc 已注明）
- 列表项内的代码块：续行缩进（对齐列表标记宽度）落在 pre 实体内部，三格式显示的代码行都带缩进噪声且互有差异（MarkdownV2 端每行前置标记宽度的空格，HTML/entities 端仅续行缩进）——列表连续性与代码块有效性不可兼得时的既定取舍

## 开发

```bash
git clone https://github.com/kamiiroawase/telegram-markdownv2.git
cd telegram-markdownv2
./gradlew build        # JDK 21+：编译全部 target + 宿主可执行测试 + 格式检查
./gradlew jvmTest      # 单独跑某个平台
./gradlew benchmark    # 性能基准：常规消息 ~0.1ms 量级，百万字符 ~10ms；发版时 CI 复跑输出附在 GitHub Release
```

API 参考（Dokka）随 main 分支推送自动部署到 [GitHub Pages](https://kamiiroawase.github.io/telegram-markdownv2/)。

705 个测试——704 个行为级（输入/输出断言）外加 1 个种子化随机文档的性质测试（150 个种子，生成器覆盖强调/代码/链接/引用/列表/任务列表/表格/脚注/autolink/原始 HTML/emoji 的随机组合，对三格式断言全部不变量），全部平台运行；其中 HTML 端的每条输出过一道 tdlib HTML 解析器的规则移植（`assertTelegramHtmlParseable`），MarkdownV2 端同理（`assertTelegramParseable`），entities 端过结构校验（区间在界内、按包含嵌套、按 close order 排列、不劈开代理对）。质量门禁全挂在 `build` 上：Spotless 格式、`explicitApi()` 显式 API、binary-compatibility-validator 公开 API 快照（有意变更时跑 `./gradlew apiDump`）。

## 许可

[The Unlicense](LICENSE) —— 公共领域，随意使用。

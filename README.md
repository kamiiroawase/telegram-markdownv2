# telegram-markdownv2

[![Build](https://github.com/kamiiroawase/telegram-markdownv2/actions/workflows/build.yml/badge.svg)](https://github.com/kamiiroawase/telegram-markdownv2/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kamiiroawase/telegram-markdownv2.svg)](https://central.sonatype.com/artifact/io.github.kamiiroawase/telegram-markdownv2)
[![License: Unlicense](https://img.shields.io/badge/license-Unlicense-blue.svg)](LICENSE)

Kotlin Multiplatform 库：把 **CommonMark（含 GFM 表格与删除线）转换为 Telegram MarkdownV2**，并对超长内容做保持结构完整的截断或无损分片（Telegram 消息上限 4096 字符，分片按序发送即还原全文）。为 LLM 机器人输出而生——模型回复是任意长度、任意结构的 Markdown，还经常带 Telegram 渲染不了的构造。

[English version](README.en.md)

## 一、使用指南

### 它是什么

一句话：Markdown 进，Telegram 能直接显示的文本出。把 CommonMark/GFM 内容交给 `MarkdownV2.render(...)`，拿到可直接配合 Bot API `parse_mode = MarkdownV2` 发送的消息文本：

- 基于 [commonmark-kotlin](https://github.com/darriousliu/commonmark-kotlin)（commonmark-java 的 KMP 移植）解析，输出 Telegram 方言的 MarkdownV2
- 官方 18 个特殊字符加反斜杠共 19 个全量转义；行内代码 / 代码块 / 链接 URL 按各自的规则转义
- 可选截断（`maxLength` 传正数时启用，默认 0 不截断），截断后仍是合法 MarkdownV2
- 超长内容无损分片（`renderChunked`）：每段不超过上限且各自合法，按序发送还原全文
- 拒收兜底（`toPlainText`）：把渲染结果还原为纯文本，任意输入不丢字符、永不抛异常
- 不支持的构造自动降级：表格转对齐的代码块、HTML 转实体映射或字面文本——语义细节见下文「降级与边界语义」，注意事项见「常见坑与已知限制」

支持平台：Android（minSdk 23）、JVM 11+、JS、Wasm、Linux（x64/Arm64）、macOS（Arm64）、Windows（mingwX64）、iOS（设备与模拟器）。

### 第一步：引入依赖

发布于 [Maven Central](https://central.sonatype.com/artifact/io.github.kamiiroawase/telegram-markdownv2)，版本跟随 `v*` git tag。

```kotlin
repositories {
    mavenCentral()
}
```

版本目录：

```toml
[versions]
telegramMarkdownv2 = "1.3.0"

[libraries]
telegram-markdownv2 = { module = "io.github.kamiiroawase:telegram-markdownv2", version.ref = "telegramMarkdownv2" }
```

单平台项目直接引用根坐标（Gradle module metadata 会解析到对应平台变体）：

```kotlin
dependencies {
    implementation(libs.telegram.markdownv2)
}
```

需要钉住具体平台变体时（Maven 依赖、锁坐标等场景）：

| 平台 | 坐标 |
| --- | --- |
| Android | `io.github.kamiiroawase:telegram-markdownv2-android:1.3.0` |
| JVM | `io.github.kamiiroawase:telegram-markdownv2-jvm:1.3.0` |
| JS | `io.github.kamiiroawase:telegram-markdownv2-js:1.3.0` |
| Wasm | `io.github.kamiiroawase:telegram-markdownv2-wasm-js:1.3.0` |
| Linux x64 | `io.github.kamiiroawase:telegram-markdownv2-linuxx64:1.3.0` |
| Linux Arm64 | `io.github.kamiiroawase:telegram-markdownv2-linuxarm64:1.3.0` |
| macOS Arm64 | `io.github.kamiiroawase:telegram-markdownv2-macosarm64:1.3.0` |
| Windows | `io.github.kamiiroawase:telegram-markdownv2-mingwx64:1.3.0` |
| iOS arm64（设备） | `io.github.kamiiroawase:telegram-markdownv2-iosarm64:1.3.0` |
| iOS x64（模拟器） | `io.github.kamiiroawase:telegram-markdownv2-iosx64:1.3.0` |
| iOS arm64（模拟器） | `io.github.kamiiroawase:telegram-markdownv2-iossimulatorarm64:1.3.0` |

KMP 消费方在 commonMain 引用根坐标即可：

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies { implementation("io.github.kamiiroawase:telegram-markdownv2:1.3.0") }
    }
}
```

### 第二步：调用

```kotlin
import io.github.kamiiroawase.markdownv2.MarkdownV2

val mdv2 = MarkdownV2.render("**hello** world")
// *hello* world —— 可直接用于 parse_mode = MarkdownV2 的 sendMessage

val full = MarkdownV2.render(longModelReply)                                   // 默认不截断（maxLength = 0）
val clipped = MarkdownV2.render(longModelReply, MarkdownV2.MAX_MESSAGE_LENGTH) // 截断到 Telegram 上限
val clipped100 = MarkdownV2.render(longModelReply, 100)                        // 任意正数自定义上限；<= 0 一律不截断
```

> ⚠️ **JS/Wasm 平台注意**：输入含 HTML 块、`<a href>` 锚点或以 HTML 标签开头时，`render`/`renderChunked` 会在解析阶段直接抛异常，这两个平台的调用务必 try/catch 兜底——详见「常见坑与已知限制」第一条。

### 进阶：手动拼 MarkdownV2 时转义动态文本（escape）

`render` 在转换 Markdown 时已自动转义正文中的特殊字符；`escape` 服务于绕开 `render`、自己拼 MarkdownV2 字符串的场景：格式标记（`*`、`` ` ``、`[]()` 等）自己写，而用户输入等动态片段必须先经 `escape` 转义——否则其中恰好出现的 `_`、`.`、`[` 等字符会让 Telegram 拒收整条消息。它对官方 18 个特殊字符加反斜杠共 19 个全部前置 `\`，使任意文本按字面渲染（纯字符串变换，不经过解析器，JS/Wasm 上不受上游解析缺陷影响）：

```kotlin
val literal = MarkdownV2.escape("2*3=6!")    // 2\*3\=6\!，按字面渲染

val name = MarkdownV2.escape(userInput)       // 动态文本先转义
val msg = "*欢迎* $name！"                    // 自己写的格式标记不需要转义
sendMessage(chatId, msg)                      // parse_mode = MarkdownV2
```

整条消息都是纯文本（不需要任何格式）时，对全文调用 `escape` 即可；反过来，不要对 `render` 的输出再 `escape`——输出里已含转义序列，再转义会把反斜杠本身显示出来。

### 进阶：超长内容分多条消息（renderChunked）

不想丢弃超限内容时，用 `renderChunked` 把渲染结果切成多段，每段不超过上限（默认 4096）且各自是合法 MarkdownV2，按顺序发送即可：

```kotlin
val parts = MarkdownV2.renderChunked(longModelReply) // List<String>，每段 ≤ 4096
parts.forEach { part -> sendMessage(chatId, part) }  // 依次发送即还原完整内容
```

拆分与退化语义见「降级与边界语义」；`maxLength` 传 1 或更小（分片至少要装得下一个转义字符，占两位）时返回整段渲染的单元素列表，内容渲染为空时返回空列表。

某一段被 Bot API 拒收（理论上不应发生）时，用 `MarkdownV2.toPlainText` 把这段已渲染文本还原成 Telegram 显示所见的纯文本、不带 `parse_mode` 重发即可兜底（还原口径见「降级与边界语义」）：

```kotlin
for (part in parts) {
    try {
        sendMessage(chatId, part)                          // parse_mode = MarkdownV2
    } catch (e: TelegramApiRequestException) {             // 400 can't parse entities 等
        sendMessage(chatId, MarkdownV2.toPlainText(part))  // 兜底：不带 parse_mode 的纯文本
    }
}
```

### 进阶：自定义表格降级（RenderOptions）

表格降级涉及两个没有唯一答案的度量选择：字符的显示宽度与单元格的截断上限。默认按 East Asian Width 近似度量（CJK / emoji 宽字符记 2，组合符 / 变体选择符记 0，其余记 1；星平面码点记 2），单元格按 32 个显示宽度截断。两者都可通过 `RenderOptions` 覆盖（注意 `maxCellWidth` 不得小于省略号 `…` 在所用度量下的显示宽度——默认度量下 `…` 记 1，任何 ≥1 的上限都合法；自定义度量把 `…` 记宽时，过小的上限会在构造 `RenderOptions` 时抛 `IllegalArgumentException`）：

```kotlin
// 例：CJK 字体语境下把 Ambiguous 字符（×、© 之类）按宽字符对齐，单元格放宽到 40 列
val options = RenderOptions(
    maxCellWidth = 40,
    displayWidthOf = { codePoint ->
        if (codePoint == '×'.code) 2 else defaultDisplayWidth(codePoint)
    },
)
val text = MarkdownV2.render("| a | × |\n| --- | --- |", options = options)
```

### 进阶：自定义解析器与 AST 渲染

默认解析器是 CommonMark + GFM 表格 + 删除线。需要更多扩展（如 footnotes、autolink、task-list）时，传入自建解析器；也可以直接渲染已解析的 AST 节点（未知扩展节点按纯内容遍历，不会崩溃）：

```kotlin
// 在默认扩展之上追加
val parser = Parser.builder()
    .extensions(MarkdownV2.defaultExtensions + FootnotesExtension.create())
    .build()
val text = MarkdownV2.render(content, parser = parser)

// 或渲染任意已解析节点（Node 重载）；截断对单个块节点同样保留其结构
// （列表标记、表格围栏等不会因超长截断丢失）
val document: Node = parser.parse(content)!!
val text2 = MarkdownV2.render(document, MarkdownV2.MAX_MESSAGE_LENGTH)
```

> 注：解析后端与官方 commonmark-java 的行为差异（HTML 块识别、强调内的闭合标签）见「常见坑与已知限制」。

### 降级与边界语义

- **表格按代码块降级**：Telegram 没有表格，本库输出等宽对齐的围栏代码块；单元格按 32 个显示宽度截断，截断超长表格时按整行保留。宽度表是 East Asian Width 的近似（CJK/emoji 按两倍显示宽度对齐，组合符、变体选择符等零宽字符按 0 宽计，Ambiguous 字符按窄字符计、星平面码点一律记 2），宽度度量与截断上限可经 `RenderOptions` 自定义，对齐要求高的场景用 `RenderOptions.displayWidthOf` 换成自己的度量
- **行内 HTML 的降级与补全语义**：`<b>`/`<strong>` → `*`、`<i>`/`<em>` → `_`、`<s>`/`<del>`/`<strike>` → `~`、`<u>`/`<ins>` → `__`、`<code>`/`<kbd>`/`<samp>`/`<tt>` → 行内代码实体（内容按代码实体规则转义，仅转义反引号与反斜杠；内嵌的 Markdown 行内代码同样转义其反引号以免实体边界错乱；嵌套的同族标签并入外层实体——嵌套代码的显示就是单层代码，黏连的反引号会拼出 Telegram 拒收的空实体（`` `` ``）或被重读为代码围栏（```` ``` ````）；紧跟在已闭合代码实体之后的同族标签或行内代码退化为转义纯文本——`` `a``b` `` 式的分隔符黏连是 Telegram 解析不了的保留字符 run；空内容（或仅含不可见标记）的代码标签对整体消失，闭合、悬空于段落末尾与边界补全的情形一致；跨块未闭合的代码实体在紧随的围栏类块——代码块 / 表格 / HTML 块——之前补全，不吞围栏标记）、`<br>` → 换行、`<a href>` → 链接、HTML 块转代码块；未配对的闭合、自闭合与嵌套标签一律按字面转义，闭合标签只与最内层未闭实体匹配——跨过未闭合锚点的 `</b>`、匹配到 Markdown 强调自有标记的 `</i>` 按字面转义；未闭合的行内实体（HTML 强调与 `<a>` 锚点）在最近的容器边界——强调、删除线、链接标签——或输出时按后开先闭补全（`*<a href="u">x*` → `_[x](u)_`），输出始终保持实体合法嵌套
- **Telegram 链接实体的限制与退化**：链接不能嵌套——链接内再嵌套的图片/链接/锚点降级为纯文本或字面标签；空 URL 链接退化为纯文本，空链接文本 / 图片替代文本 / 空锚点退化为转义后的裸 URL（Telegram 拒绝空文本链接实体）；链接标签内未闭合的 HTML 实体在链接闭合前补全、残留的嵌套锚点不会吞掉后续链接
- **href 的解析口径**：属性名不区分大小写，属性值支持双引号 / 单引号 / 无引号写法（引号值内的 `>` 与 `/` 按 HTML5 计入值本身，无引号值末尾的 `/` 同理，均不会提前闭合标签或误判为自闭合）；href 中的 HTML 实体（`&amp;`、`&#38;`、`&#x26;` 等）先解码再作链接，无法识别的引用（如 `&#0;`，其 NUL 不是合法消息文本）保持字面，其他属性值内部的 `href=` 字样不会被误认
- **截断的结构保持**：代码围栏保持闭合、未闭合的强调 / 链接自动补全、引用与列表前缀逐行保留、绝不劈开转义序列和代理对；结构化内容完全放不下时回退为转义纯文本
- **分片的接缝语义**：块整块装填，超限块沿代码行 / 列表项 / 引用子块 / 段落行 / 行内节点的自然接缝拆分，列表编号跨段连续，空列表项按无内容跳过（全文渲染、截断与分片三处一致，有序编号仍按源序号消耗）；标题 `#` 前缀与列表标记黏附于首个分片，放不下时宁可丢弃标记也不产出裸标记片——首个行内节点去掉前缀即可放下时，直接丢弃前缀、保留节点的渲染结构，而非为保前缀把节点平铺为纯文本；连可保的节点都没有时（空标题：无内容，或全部行组渲染为空——前缀即全部可见输出），前缀按转义单元切分平铺，既不产出超限分片也不返回空列表；接缝仍放不下时退化为转义纯文本——文本无损，链接保留 `label (url)` 形式，链接目标不丢；提取不到纯文本但渲染出可见内容的块（分隔线渲染为 `———` 而无文本可提取）改以还原后的渲染文本退化，可见输出不丢——截断回退则保持文本优先，分隔线的装饰让位于其后的正文
- **toPlainText 的还原口径**：实体标记去除（黏连的标记 run 按先闭后开解析，`_a__b_`——两个斜体相邻的渲染结果——还原为 `ab`）、代码内容保留（引用内的代码块逐行剥除引用标记、代码原样保留）、引用前缀剥除、转义按上下文解开，`[label](url)` 变为 `label (url)` 以保住链接目标（空 label 或空 URL 退化为非空一侧）；任意输入降级为尽力而为且不丢字符，永不抛异常

### 常见坑与已知限制

- **JS/Wasm 上 HTML 会抛异常（重要）**：上游 commonmark-kotlin 在这两个平台扫描 HTML 块时直接崩溃（`Regex("]]>")` 在 JS RegExp 引擎中非法），`<a href>` 锚点与以 HTML 标签开头的文档同样触发，待上游修复；段落中间的 `<b>`、`<br>` 等行内标签不受影响。**这两个平台的调用务必 try/catch 兜底**（捕获后把原文按纯文本发送），或预先滤除输入中的 HTML——LLM 输出恰恰常含 HTML
- **MarkdownV2 不是 Markdown**：`__` 是下划线不是粗体、官方 18 个特殊字符加反斜杠共 19 个在实体外必须转义——本库已全部处理，但不要把渲染结果再当普通 Markdown 二次加工
- **解析后端与 commonmark-java 的行为差异**：上游 commonmark-kotlin 的 HTML 块识别比官方弱（`<!-- -->`、`<!DOCTYPE>` 等按段落内联处理）；此外**强调内部的闭合 HTML 标签**（如 `*a<b>x</b>*` 中的 `</b>`）不会被识别为行内 HTML，其 `>` 会并入相邻文本（上游 emphasis 与行内 HTML 扫描的交互问题）。依赖此行为的输入在两种后端下渲染结果可能不同
- **超深嵌套平铺**：超过 100 层的引用 / 列表 / 行内强调降级为平铺纯文本（防病态输入栈溢出）；截断时放不下的深层结构整体退化为转义纯文本，不逐层重缩（防嵌套重渲染指数膨胀）
- **超长内容渲染两遍**：先完整渲染判断长度、超限再分块收缩；4096 字符上限下开销可忽略，不为超大输入做单遍渲染优化
- **截断收缩按重试收敛（最坏 O(n²)）**：列表项或引用行放不下时，整棵子树重渲染、预算减去超出量再试——直接丢行会连实体闭合符一起丢，生成非法 MarkdownV2，故不允许；超出量最小 1 字符（续行缩进开销），精确卡边界的对抗性输入每轮只前进 1 字符而每轮成本是整棵子树。重试仅限顶层（嵌套逐层重试会指数膨胀，深层结构直接退化为转义纯文本），4096 默认上限下无感；自定义超大 `maxLength` 配合对抗性输入可放大此路径

## 二、参与代码贡献

### 环境准备

```bash
git clone https://github.com/kamiiroawase/telegram-markdownv2.git
cd telegram-markdownv2
./gradlew build
```

从源码构建需要 JDK 21+（已启用 Gradle toolchain 自动解析）。

### 项目结构

```
src/
├── commonMain/kotlin/io/github/kamiiroawase/markdownv2/
│   ├── MarkdownV2.kt      公共 API 与顶层流程（render / escape / 默认解析器）
│   ├── Visitor.kt         AST → MarkdownV2 的全文渲染（行内 HTML 映射、未闭合实体补全）
│   ├── Blocks.kt          截断与分片共用的块级内核（单块渲染、块形态分类、列表标记与列表项遍历、行/引用前缀）
│   ├── Truncation.kt      超长内容的结构化截断
│   ├── Chunking.kt        超长内容的无损分片（renderChunked）
│   ├── Deformat.kt        已渲染 MarkdownV2 的纯文本还原（toPlainText 拒收兜底）
│   ├── Escape.kt          转义规则与原子单元截断（转义序列 / 代理对）
│   ├── PlainText.kt       迭代式纯文本提取（表格单元格与各兜底路径共用）
│   ├── RenderOptions.kt   渲染自定义项（表格单元格截断上限、字符显示宽度度量）
│   └── TableRenderer.kt   GFM 表格降级为等宽对齐文本
├── commonTest/            行为级测试，按功能分文件（输入/输出断言，与 AST 无关）：
│   ├── RenderTest.kt      渲染（转义、各块、HTML、表格）
│   ├── TruncationTest.kt  结构化截断（render + maxLength）
│   ├── ChunkingTest.kt    无损分片（renderChunked）
│   ├── PlainTextTest.kt   纯文本还原（toPlainText）
│   └── TestSupport.kt     共享测试助手（maxMessageLength、removeEscapes）
├── <platform>Test/        各平台 expect/actual 开关（JS/Wasm 跳过 HTML 解析用例）
└── jvmTest/               另含性能基准（./gradlew benchmark）
```

### 构建与测试

286 个行为级测试（输入/输出断言，与 AST 无关），覆盖全部转义规则、每种块的渲染、截断与分片路径、代理对与转义边界（含 10 万级恶意输入的线性扫描回归测试——行内 HTML 标签解析为手写单遍扫描，全库不使用正则，从设计上不存在回溯与栈溢出风险）。注意：其中 42 个测试依赖 HTML 解析，在 JS/Wasm 上因上游 commonmark-kotlin 的解析缺陷而整体空跑或跳过其 HTML 断言（静默通过，见测试类 KDoc 与 `htmlParsingSupported`），待上游修复后自动生效。CI 中：JVM、Android 单元测试与 JS、Wasm（Node）、Linux x64 原生测试在 ubuntu job 执行；iOS 模拟器与 macOS Arm64 测试在 macOS job 执行；Windows（mingwX64）测试在 windows job 执行；Linux Arm64 仅交叉编译验证——Kotlin/Native 官方不支持 Linux ARM64 作为构建/测试宿主（见 [宿主支持表](https://kotlinlang.org/docs/native-target-support.html)），上游支持后可补宿主 job：

```bash
./gradlew build             # 编译全部 target + 宿主可执行的测试 + 格式检查
./gradlew jvmTest           # 单独跑某个平台
```

注：`kotlin-js-store/yarn.lock` 不在 dependabot 覆盖范围内——其 npm 生态要求锁文件旁有 `package.json`，而 Kotlin/JS 只在该目录存锁文件（上游限制，KMP 项目通行做法是手动维护）；npm 依赖变化后重跑任一 JS/Wasm 构建任务即可再生成锁文件，安全告警仍由 GitHub 依赖图谱照常扫描。

### 质量门禁（PR 必须全绿）

- **格式**：Spotless + ktlint 挂在 `build` 上检查；提交前跑 `./gradlew spotlessApply` 一键格式化
- **API**：commonMain 启用 `explicitApi()`，公开声明必须显式写可见性修饰符并附 KDoc
- **API 兼容性**：binary-compatibility-validator 为全部 target 快照公开 API（`api/` 目录），`apiCheck` 挂在 `build` 上，无意破坏公开 API 的 PR 直接失败；有意变更时跑 `./gradlew apiDump` 更新快照并在 PR 中说明
- **API 文档与变更记录**：Dokka 从 KDoc 生成 API 参考（`./gradlew dokkaGeneratePublicationHtml`，CI 上传 HTML 产物）；用户可见的行为变化记入 [CHANGELOG.md](CHANGELOG.md) 的 Unreleased 段
- **CI**：`build.yml` 在 main 推送与所有 PR 上执行上述全部检查（另有 macOS 与 Windows job 跑 iOS 模拟器、macOS Arm64 与 mingwX64 原生测试）并断言发布产物齐全；`release.yml` 在推 `v*` tag 时先跑与 main 相同的完整构建与测试（tag 可能指向未经 CI 的提交，而 Central 发布不可撤回），再把各平台产物发布为 GitHub Release 附件，附带当次 CI 复跑的 benchmark 输出，并校验两份 README 的版本坐标与 CHANGELOG 的版本段已随 tag 同步更新（README 缺新版本号或残留上一版本号、CHANGELOG 缺新版本段即失败）
- **版本号来自 git tag（仅当 HEAD 恰在 tag 上）**：release 工作流的 tag 检出即此情形，取 tag 版本号；其余一律 `0.0.0-SNAPSHOT`——tag 之后的提交不再复用已发布版本号，本地 `publishToMavenLocal` 也就不会覆盖同名已发布工件。CI 需完整克隆（`fetch-depth: 0`）才能推导版本

### 提交流程

1. fork 仓库，从 main 建特性分支
2. 改动代码并补配套测试，`./gradlew build` 本地全绿后再推送
3. PR 说明动机与行为变化；bug 修复请附最小复现输入与期望输出
4. 涉及 HTML 相关行为时注意上游 commonmark-kotlin 在 JS/Wasm 的解析缺陷（测试开关 `htmlParsingSupported`），在 PR 中说明取舍

## 三、性能测试数据

`./gradlew benchmark` 可复跑（实现见 [Benchmark.kt](src/jvmTest/kotlin/io/github/kamiiroawase/markdownv2/Benchmark.kt)：JVM 单线程，先全局预热，全套场景多轮交错重复、每场景取各轮中位数的最佳值——CPU 频率与热窗等环境干扰可使单轮结果偏差数倍，最佳轮远比单轮可复现；计时覆盖 commonmark 解析与渲染/截断全程，吞吐按 UTF-8 字节计）。下表为 Windows 11 / JDK 21 / Intel x86-64 上的实测参考值（2026-10，最佳轮口径），绝对值随机器浮动，量级更有参考意义。为防表格静默老化，后续每次发版都会在 GitHub Release 附件附带当次 CI（ubuntu runner）复跑的完整输出（`benchmark.txt`），随时可与下表核对量级：

| 场景 | 输入 | 耗时（最佳轮中位数） | 吞吐 |
| --- | --- | --- | --- |
| 短文本（典型聊天消息） | 0.1 KB | ~6 µs | ~17 MB/s |
| 典型回复（标题/列表/代码/表格混排） | 2.0 KB | ~0.1 ms | ~20 MB/s |
| 接近上限的纯段落（免截断） | 3.6 KB | ~18 µs | ~200 MB/s |
| 超长多段落（结构化截断到 4096） | 42.7 KB | ~0.39 ms | ~109 MB/s |
| 超长单行（转义纯文本兜底） | 50.0 KB | ~0.49 ms | ~102 MB/s |
| 大表格（60 行 × 4 列，降级对齐） | 3.2 KB | ~0.14 ms | ~23 MB/s |
| 大代码块（500 行，截断收敛） | 21.3 KB | ~0.1 ms | ~211 MB/s |
| 行内 HTML 混排 | 4.7 KB | ~97 µs | ~48 MB/s |
| 未闭合行内实体（边界补全） | 2.7 KB | ~53 µs | ~50 MB/s |
| 深嵌套引用（200 层，平铺降级） | 0.3 KB | ~47 µs | ~6.8 MB/s |
| 十万级未闭合标签（标签扫描线性回归） | 100 KB | ~0.86 ms | ~116 MB/s |
| 超大文档（百万字符，两遍策略） | 1.0 MB | ~9.4 ms | ~107 MB/s |
| 已渲染文本还原（toPlainText 兜底） | 64.0 KB | ~0.24 ms | ~267 MB/s |

几点解读：

- 常规消息（≤ 4096 字符）大多在 0.1 ms 量级内完成，对机器人收发路径的开销可忽略
- 结构越碎越贵：同样字节量下，多级标题/列表/表格的解析与逐块渲染远慢于单一纯文本段落
- 未闭合行内实体的边界补全（锚点跨强调边界、闭合标签交错、整段未闭合）与闭合标签的常规渲染同量级——修复前这些形状产出交叉实体或直接崩溃
- 恶意输入（十万级未闭合标签）仍为线性耗时（~0.9 ms）——行内 HTML 标签解析为手写单遍线性扫描、全库不使用正则，从设计上不存在灾难性回溯或引擎栈溢出
- 超大文档受「先完整渲染、超限再分块收缩」的两遍策略影响（见已知限制），百万字符约 10 ms

## 许可

[The Unlicense](LICENSE) —— 公共领域，随意使用。

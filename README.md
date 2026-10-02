# telegram-markdownv2

[![Build](https://github.com/kamiiroawase/telegram-markdownv2/actions/workflows/build.yml/badge.svg)](https://github.com/kamiiroawase/telegram-markdownv2/actions/workflows/build.yml)
[![JitPack](https://jitpack.io/v/kamiiroawase/telegram-markdownv2.svg)](https://jitpack.io/#kamiiroawase/telegram-markdownv2)
[![License: Unlicense](https://img.shields.io/badge/license-Unlicense-blue.svg)](LICENSE)

Kotlin Multiplatform 库：把 **CommonMark（含 GFM 表格与删除线）转换为 Telegram MarkdownV2**，并对超长内容做保持结构完整的截断（Telegram 消息上限 4096 字符）。为 LLM 机器人输出而生——模型回复是任意长度、任意结构的 Markdown，还经常带 Telegram 渲染不了的构造。

[English version](README.en.md)

## 一、使用指南

### 它是什么

一句话：Markdown 进，Telegram 能直接显示的文本出。把 CommonMark/GFM 内容交给 `MarkdownV2.render(...)`，拿到可直接配合 Bot API `parse_mode = MarkdownV2` 发送的消息文本；`maxLength` 传正数时截断超长内容（默认 0 不截断），且截断后仍是合法 MarkdownV2——加粗、代码块、链接不会被截坏。完整特性如下：

- 基于 [commonmark-kotlin](https://github.com/darriousliu/commonmark-kotlin)（commonmark-java 的 KMP 移植）解析，输出 Telegram 方言的 MarkdownV2
- 官方 18 个特殊字符加反斜杠共 19 个全量转义；行内代码 / 代码块 / 链接 URL 按各自的规则转义
- 不支持的构造自动降级：表格渲染为对齐的围栏代码块（CJK/emoji 按两倍显示宽度对齐，组合符、变体选择符等零宽字符按 0 宽计；单元格默认按 32 个显示宽度截断，宽度度量与截断上限可经 `RenderOptions` 自定义）、HTML 块转代码块、行内 HTML 映射为实体（`<b>` → `*`、`<br>` → 换行、`<a href>` → 链接；HTML code 类标签 `<code>`、`<kbd>` 等的内容按代码实体规则转义，仅转义反引号与反斜杠）或按字面转义——未配对的闭合、自闭合与嵌套标签一律按字面转义，空 URL 链接退化为纯文本，链接内再嵌套的图片/链接/锚点降级为纯文本或字面标签（Telegram 链接实体不能嵌套）；`href` 属性名不区分大小写，属性值支持双引号 / 单引号 / 无引号写法，href 中的 HTML 实体（`&amp;`、`&#38;`、`&#x26;` 等）先解码再作链接，其他属性值内部的 `href=` 字样不会被误认
- 可选截断（`maxLength` 传正数时启用，默认 0 不截断），截断后仍是合法 MarkdownV2：代码围栏保持闭合、未闭合的强调 / 链接自动补全、引用与列表前缀逐行保留、绝不劈开转义序列和代理对；结构化内容完全放不下时回退为转义纯文本；超过 100 层的引用 / 列表 / 行内强调降级为平铺纯文本，病态嵌套输入不会耗尽调用栈
- 超长内容无损分片（`renderChunked`）：把渲染结果切成多段，每段不超过上限且各自是合法 MarkdownV2——块整块装填、超限块沿代码行 / 列表项 / 引用子块 / 段落行 / 行内节点的自然接缝拆分、接缝仍放不下时退化为转义纯文本（文本无损），按顺序发送即可还原完整内容

支持平台：Android（minSdk 23）、JVM 11+、JS、Wasm、Linux（x64/Arm64）、macOS（Arm64）、Windows（mingwX64）、iOS（设备与模拟器）。

### 第一步：引入依赖

通过 [JitPack](https://jitpack.io/#kamiiroawase/telegram-markdownv2) 引用，版本跟随 `v*` git tag。

```kotlin
repositories {
    maven("https://jitpack.io")
    mavenCentral() // commonmark-kotlin 传递依赖
}
```

版本目录：

```toml
[versions]
telegramMarkdownv2 = "1.1.0"

[libraries]
telegram-markdownv2-jvm = { module = "com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-jvm", version.ref = "telegramMarkdownv2" }
```

按平台引用变体：

```kotlin
dependencies {
    implementation(libs.telegram.markdownv2.jvm)
}
```

各平台变体坐标（JitPack 缺少 common metadata，**根坐标不可用**，须按变体引用）：

| 平台 | 坐标 |
| --- | --- |
| Android | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-android:1.1.0` |
| JVM | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-jvm:1.1.0` |
| JS | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-js:1.1.0` |
| Wasm | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-wasm-js:1.1.0` |
| Linux x64 | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-linuxx64:1.1.0` |
| Linux Arm64 | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-linuxarm64:1.1.0` |
| macOS Arm64 | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-macosarm64:1.1.0` |
| Windows | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-mingwx64:1.1.0` |
| iOS arm64（设备） | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-iosarm64:1.1.0` |
| iOS x64（模拟器） | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-iosx64:1.1.0` |
| iOS arm64（模拟器） | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-iossimulatorarm64:1.1.0` |

KMP 消费方在 commonMain 中无法直接引用本库 API（JitPack 无 common metadata）；可按 source set 引用各平台变体，或把 GitHub Release 附件搭成自建 Maven 仓库解决：

```kotlin
kotlin {
    sourceSets {
        jvmMain.dependencies { implementation("com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-jvm:1.1.0") }
        // 其余平台同理替换变体
    }
}
```

### 第二步：调用

```kotlin
import com.github.kamiiroawase.markdownv2.MarkdownV2

val mdv2 = MarkdownV2.render("**hello** world")
// *hello* world —— 可直接用于 parse_mode = MarkdownV2 的 sendMessage

val full = MarkdownV2.render(longModelReply)                                   // 默认不截断（maxLength = 0）
val clipped = MarkdownV2.render(longModelReply, MarkdownV2.MAX_MESSAGE_LENGTH) // 截断到 Telegram 上限
val clipped100 = MarkdownV2.render(longModelReply, 100)                        // 任意正数自定义上限；<= 0 一律不截断

val literal = MarkdownV2.escape("a*b_c")                 // a\*b\_c，任意文本按字面渲染
```

编辑消息重发前先 render 一次；若渲染结果仍被 Telegram 拒收（理论上不应发生），把原文按纯文本发送是最后兜底。

### 进阶：超长内容分多条消息（renderChunked）

不想丢弃超限内容时，用 `renderChunked` 把渲染结果切成多段，每段不超过上限（默认 4096）且各自是合法 MarkdownV2，按顺序发送即可：

```kotlin
val parts = MarkdownV2.renderChunked(longModelReply) // List<String>，每段 ≤ 4096
parts.forEach { part -> sendMessage(chatId, part) }  // 依次发送即还原完整内容
```

块整块装填；放不下的块沿自然接缝拆分（代码行、列表项、引用子块、段落行，再到行内节点），列表编号跨段连续；单个接缝仍超过上限时退化为转义纯文本——结构降级、文字一个不丢。`maxLength` 传 1 或更小（分片至少要装得下一个转义字符，占两位）时返回整段渲染的单元素列表，内容渲染为空时返回空列表。

### 进阶：自定义表格降级（RenderOptions）

表格降级涉及两个没有唯一答案的度量选择：字符的显示宽度与单元格的截断上限。默认按 East Asian Width 近似度量（CJK / emoji 宽字符记 2，组合符 / 变体选择符记 0，其余记 1；星平面码点记 2），单元格按 32 个显示宽度截断。两者都可通过 `RenderOptions` 覆盖：

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

> 注：本库的解析后端是 [commonmark-kotlin](https://github.com/darriousliu/commonmark-kotlin)，其 HTML 块识别比官方 commonmark-java 弱（`<!-- -->`、`<!DOCTYPE>` 等按段落内联处理）；此外**强调内部的闭合 HTML 标签**（如 `*a<b>x</b>*` 中的 `</b>`）不会被识别为行内 HTML，其 `>` 会并入相邻文本（上游 emphasis 与行内 HTML 扫描的交互问题）。依赖此行为的输入在两种后端下渲染结果可能不同。

### 常见坑与已知限制

- **JS/Wasm 上 HTML 受限**：解析依赖 commonmark-kotlin 在这两个平台扫描 HTML 块时会崩溃（`Regex("]]>")` 在 JS RegExp 引擎中非法），`<a href>` 锚点与以 HTML 标签开头的文档同样受影响，待上游修复；段落中间的 `<b>`、`<br>` 等行内标签不受影响
- **MarkdownV2 不是 Markdown**：`__` 是下划线不是粗体、官方 18 个特殊字符加反斜杠共 19 个在实体外必须转义——本库已全部处理，但不要把渲染结果再当普通 Markdown 二次加工
- **表格按代码块降级**：Telegram 没有表格，本库输出等宽对齐的围栏代码块；单元格按 32 个显示宽度截断，截断超长表格时按整行保留。宽度表是 East Asian Width 的近似（Ambiguous 字符按窄字符计、星平面码点一律记 2），对齐要求高的场景可用 `RenderOptions.displayWidthOf` 换成自己的度量
- **HTML 锚点跨强调边界**：`*<a href="u">x*` 这类锚点在 Markdown 强调内开、强调外闭的输入，链接补全会跨越强调边界（输出 `_[x_](u)` 这类交叉嵌套），Telegram 会拒收——按纯文本发送兜底；纯强调类交错（如 `*a<b>b* </b>`）已在强调边界自动补全，输出合法
- **超深嵌套平铺**：超过 100 层的引用 / 列表 / 行内强调降级为平铺纯文本（防病态输入栈溢出）；截断时放不下的深层结构整体退化为转义纯文本，不逐层重缩（防嵌套重渲染指数膨胀）
- **超长内容渲染两遍**：先完整渲染判断长度、超限再分块收缩；4096 字符上限下开销可忽略，不为超大输入做单遍渲染优化
- **版本号来自 git tag**：本地无 tag 时构建为 `0.0.0-SNAPSHOT`；CI 需完整克隆（`fetch-depth: 0`）才能推导版本

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
├── commonMain/kotlin/com/github/kamiiroawase/markdownv2/
│   ├── MarkdownV2.kt      公共 API 与顶层流程（render / escape / 默认解析器）
│   ├── Visitor.kt         AST → MarkdownV2 的全文渲染（行内 HTML 映射、未闭合实体补全）
│   ├── Truncation.kt      超长内容的结构化截断
│   ├── Chunking.kt        超长内容的无损分片（renderChunked）
│   ├── Escape.kt          转义规则与原子单元截断（转义序列 / 代理对）
│   ├── PlainText.kt       迭代式纯文本提取（表格单元格与各兜底路径共用）
│   ├── RenderOptions.kt   渲染自定义项（表格单元格截断上限、字符显示宽度度量）
│   └── TableRenderer.kt   GFM 表格降级为等宽对齐文本
├── commonTest/            行为级测试（输入/输出断言，与 AST 无关）
├── <platform>Test/        各平台 expect/actual 开关（JS/Wasm 跳过 HTML 解析用例）
└── jvmTest/               另含性能基准（./gradlew benchmark）
```

### 构建与测试

210 个行为级测试（输入/输出断言，与 AST 无关），覆盖全部转义规则、每种块的渲染、截断与分片路径、代理对与转义边界（含 10 万级恶意输入的线性回溯回归测试——本库仅有的正则保持线性复杂度，无灾难性回溯）。CI 中：JVM、Android 单元测试与 JS、Wasm（Node）、Linux x64 原生测试在 ubuntu job 执行；iOS 模拟器与 macOS Arm64 测试在 macOS job 执行；其余原生目标（Windows、Linux Arm64）仅交叉编译验证，可在对应宿主上跑 `mingwX64Test` / `linuxArm64Test`：

```bash
./gradlew build             # 编译全部 target + 宿主可执行的测试 + 格式检查
./gradlew jvmTest           # 单独跑某个平台
```

### 质量门禁（PR 必须全绿）

- **格式**：Spotless + ktlint 挂在 `build` 上检查；提交前跑 `./gradlew spotlessApply` 一键格式化
- **API**：commonMain 启用 `explicitApi()`，公开声明必须显式写可见性修饰符并附 KDoc
- **CI**：`build.yml` 在 main 推送与所有 PR 上执行上述全部检查（另有 macOS job 跑 iOS 模拟器与 macOS Arm64 测试）并断言发布产物齐全；`release.yml` 在推 `v*` tag 时把各平台产物发布为 GitHub Release 附件

### 提交流程

1. fork 仓库，从 main 建特性分支
2. 改动代码并补配套测试，`./gradlew build` 本地全绿后再推送
3. PR 说明动机与行为变化；bug 修复请附最小复现输入与期望输出
4. 涉及 HTML 相关行为时注意上游 commonmark-kotlin 在 JS/Wasm 的解析缺陷（测试开关 `htmlParsingSupported`），在 PR 中说明取舍

## 三、性能测试数据

`./gradlew benchmark` 可复跑（实现见 [Benchmark.kt](src/jvmTest/kotlin/com/github/kamiiroawase/markdownv2/Benchmark.kt)：JVM 单线程，先全局预热再按输入规模迭代计时取中位数；计时覆盖 commonmark 解析与渲染/截断全程，吞吐按 UTF-8 字节计）。下表为 Windows 11 / JDK 21 / Intel x86-64 上的实测参考值（2026-10），绝对值随机器浮动，量级更有参考意义：

| 场景 | 输入 | 耗时（中位数） | 吞吐 |
| --- | --- | --- | --- |
| 短文本（典型聊天消息） | 0.1 KB | ~0.08 ms | ~1.3 MB/s |
| 典型回复（标题/列表/代码/表格混排） | 2.0 KB | ~0.5 ms | ~4 MB/s |
| 接近上限的纯段落（免截断） | 3.6 KB | ~0.09 ms | ~39 MB/s |
| 超长多段落（结构化截断到 4096） | 42.7 KB | ~1.1 ms | ~37 MB/s |
| 超长单行（转义纯文本兜底） | 50.0 KB | ~0.9 ms | ~56 MB/s |
| 大表格（60 行 × 4 列，降级对齐） | 3.2 KB | ~0.6 ms | ~5.5 MB/s |
| 大代码块（500 行，截断收敛） | 21.3 KB | ~0.3 ms | ~71 MB/s |
| 行内 HTML 混排 | 4.7 KB | ~0.4 ms | ~12 MB/s |
| 深嵌套引用（200 层，平铺降级） | 0.3 KB | ~0.14 ms | ~2.2 MB/s |
| 十万级未闭合标签（正则线性回归） | 100 KB | ~5.1 ms | ~20 MB/s |
| 超大文档（百万字符，两遍策略） | 1.0 MB | ~11.6 ms | ~86 MB/s |

几点解读：

- 常规消息（≤ 4096 字符）全程在 1 ms 以内，对机器人收发路径的开销可忽略
- 结构越碎越贵：同样字节量下，多级标题/列表/表格的解析与逐块渲染远慢于单一纯文本段落
- 恶意输入（十万级未闭合标签）仍为线性耗时（~5 ms），无灾难性回溯——这正是本库仅有的正则保持线性复杂度的设计目标
- 超大文档受「先完整渲染、超限再分块收缩」的两遍策略影响（见已知限制），百万字符约 12 ms

## 许可

[The Unlicense](LICENSE) —— 公共领域，随意使用。

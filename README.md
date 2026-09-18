# FlowAI Communication

Kotlin + Jetpack Compose Android 产品逻辑 MVP，包名 `com.flowai.communication`。

## 产品总纲与阶段范围

[产品总纲原文](docs/PRODUCT_VISION.md) 记录完整定位与长期规划；[阶段对照与路线](docs/ROADMAP.md) 区分已有 MVP、未实现能力和后续版本。总纲中的系统入口、输入法与工具集成属于后续阶段。

## 运行

用 Android Studio 打开本目录，选择 JDK 17 或 21，安装 Android SDK 34 / Build Tools 34.0.0，同步 Gradle 后运行 app。支持 Android 8.0（API 26）及以上。

本机也可以执行 `powershell -ExecutionPolicy Bypass -File .\build.ps1`。脚本仅为当前构建选择 JDK，不修改系统环境设置。首次构建需要联网获取依赖；App 运行不需要网络、API Key 或权限。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 验收路径

1. 首页 → Demo A → 分析当前沟通。应展示“任务完成进度”、缺少明确完成时间，以及给出时间 / 汇报进度 / 确认紧急程度三个动作。
2. 点击“给出明确完成时间” → 自然、简洁、正式三种回复。可以编辑后复制；示例“今晚十点”必须由用户核实。另两个动作应给出不同回复。
3. 返回首页 → Demo B → 分析 → 提取任务与事件。应显示组会：明天 15:00、A203，以及小王准备 PPT、小李整理数据，均截止今晚 22:00。
4. Demo B 的“生成会议事件”仅展示事件；“确认安排”展示候选回复。
5. 粘贴任意其他聊天 → 通用 Mock 状态，不套用 Demo 的人员、地点和时间；提取动作显示空结果说明。
6. 空文本无法分析；标签后无内容会报错。文本上限 20,000 字符。不保留最近分析历史，仅保留当前这一次分析；返回首页或结束会话即清除。
7. 旋转屏幕保留当前分析（由 ViewModel 内存保留，不使用 SavedStateHandle）；进程重建后不恢复任何内容，需重新导入聊天。分享进来的文本也不会被重建后的任务重新导入（见下方"分享文本不会被重复导入"）。
8. 在微信等应用选中文字 → 分享 → FlowAI，应直接打开并预填该文字，输入页显示"内容来自系统分享"。

以上路径已在 API 34 模拟器上自动跑通并逐步截图，详见 [验证记录](TEST_RESULTS.md)。旋转仅验证了配置变更中的状态保留，未验证横屏布局；分享仅验证了 `SEND` intent 接收，未经过真实微信 IPC 交接。空文本时"分析当前沟通"按钮为禁用状态，而非弹出错误。

### 分享文本不会被重复导入

进程被杀后任务被重建时，Android 会从任务记录里重新投递原始的 `SEND` intent（含 `EXTRA_TEXT`），因此聊天原文有可能被再次导入——这与"进程重建后不恢复内容"的约定冲突。实测确认：把 extra 从 `getIntent()` 上移除**不能**解决，因为任务记录仍保留原始 intent。

当前做法：`ConsumedShareStore` 记住"这条分享已消费"。只持久化文本的 64 位哈希与时间戳，**不存原文**，15 分钟后过期，因此主动重复分享同一段文本仍可正常导入。

仍有的残余风险：原文本身仍留在系统的任务记录中，直到用户把该任务从最近任务里划掉。应用能阻止再次导入，但无法从任务记录中抹除载荷。彻底消除需在消费后 `finishAndRemoveTask()`，代价是应用从最近任务中消失，暂未采用。

### 分享入口支持的载荷

分享过滤器声明为 `text/*`（而不是仅 `text/plain`）：微信等客户端分享样式文本时会用 `text/html`，只声明 `text/plain` 会**匹配不到**，FlowAI 不会出现在分享列表里。

取文本时优先用 `EXTRA_TEXT`；缺失或本身是标记时回退 `EXTRA_HTML_TEXT`（平台在"样式文本无法表示为纯文本"时的回退），并把标记压平为纯文本——`<br>` / `</p>` 会转成真实换行，以保证"每行一条消息"的约定不被破坏。此外还有 `ClipData` 兜底（`ShareCompat.IntentReader` 不读 `ClipData`，而有些发送方只放在那里），但仅作兜底且要求内容可信，避免命令行 intent 留下的短串被当成聊天文本。

### 划词入口（`ACTION_PROCESS_TEXT`）

在任意 App 里**选中文字** → 系统选择工具栏 → 「用 FlowAI 分析」，输入页显示"内容来自划词选择"。零新增权限、零政策风险，且天然是用户主动触发。

**局限**：只覆盖可选文本控件（`EditText`、WebView、`textIsSelectable`、Compose `SelectionContainer`）。微信/QQ 的消息气泡通常不可选，所以它拿不到整段对话——它是**新增**入口，不是分享的替代。

若真机上入口无效，用 `adb logcat -s FlowAI` 查看原因：会区分"收到 / 重复投递 / 非文本类型 / 没有可用文本（并打印 extras 键名）"与"根本没收到 intent"。

### V2 技术路线

悬浮助手、截屏 OCR、无障碍、输入法的平台约束、开源参照与推荐实施顺序见 [V2 技术路线](docs/V2_TECH_ROADMAP.md)。

## 架构

`文本 → DialogueParser → Message[] → ContextCapsule → ConversationStateBuilder → ConversationState → NextActionEngine → Top-3 NextAction → ChatToActionEngine → ActionResult`

`ActionResult` 包含候选回复或 `ActionObject.Task / Event / Decision`。UI 通过 ViewModel 调用 Repository；Repository 编排解析和三个 Engine 接口。`LlmService` 组合 Engine 边界，`MockLlmService` 为完全本地、确定性的实现。后续可替换为真实服务，不改变领域模型。

两个 Demo 按解析后的说话人和消息正文匹配；改动内容后进入通用模板，避免错误复用固定结论。不存在心理分数、情绪打分、网络请求、自动发送或日历写入。共识与分歧未明确出现时显示为空。

解析约定：每个非空行一条消息，支持中文或英文冒号；“我/自己/me”映射为 ME，其余前两个不同标签映射 OTHER / OTHER_2，更多标签或无标签映射 UNKNOWN。数字时间和 HTTP URL 不作说话人前缀。当前固定模型不保存真实姓名，人物姓名仅出现在原文和 Demo 任务字段中；复杂聊天导出格式尚不支持。

`system/` 仅含接口与 Stub，返回 null / false；Manifest 没有系统权限、Service 或分享 intent-filter。截图入口明确禁用。SkillRegistry 仅预留扩展边界，未引入技能执行框架。

## 文件树

```text
FlowAICommunication/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/
├── build.ps1
├── README.md / TEST_RESULTS.md
├── docs/PRODUCT_VISION.md / docs/ROADMAP.md
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/                分版本主题、图标和备份规则
        │   └── java/com/flowai/communication/
        │       ├── MainActivity.kt
        │       ├── data/
        │       │   ├── model/       Message, ContextCapsule, ConversationState,
        │       │   │               NextAction, ActionObject, ActionResult
        │       │   └── repository/  ConversationRepository, DemoConversations
        │       ├── domain/         DialogueParser, ConversationStateBuilder,
        │       │                   NextActionEngine, ChatToActionEngine,
        │       │                   ConsumedShareStore, SharedText        │       ├── ai/             LlmService, MockLlmService, prompts/
        │       ├── skill/          Skill, SkillRegistry, builtin/
        │       ├── system/         OverlayContextProvider, AccessibilityContextProvider,
        │       │                   ScreenCaptureProvider, ShareReceiver (均有 Stub)
        │       └── ui/
        │           ├── FlowViewModel.kt
        │           ├── home/       HomeScreen, InputScreen
        │           ├── analysis/   AnalysisScreen
        │           ├── action/     ActionScreen
        │           └── components/ Components
        └── test/java/com/flowai/communication/
            ├── FlowEngineTest.kt
            ├── FlowSessionTest.kt
            ├── ShareConsumptionTest.kt
            └── SharedTextTest.kt
```

## 已完成与边界

完整实现首页、文本导入、状态展示、Top-3 动作、回复生成和编辑复制、Task/Event 提取页面、系统分享文本入口、两个 Demo、模型和 Engine 基础测试。

没有实现登录、数据库、语音、RAG、长期记忆、多模型、OCR 或自动发消息。任务和事件均为草稿，不写入外部应用；相对时间保持原样，未绑定具体日期。测试结果见 TEST_RESULTS.md。

## 下一阶段

- OverlayContextProvider：悬浮入口与用户主动触发；届时再处理悬浮权限、生命周期和设备适配。
- AccessibilityContextProvider：用户授权后获取当前窗口的可用上下文，适配不同聊天界面。
- ScreenCaptureProvider：MediaProjection 用户授权、截图和 OCR。
- ShareReceiver：文本分享已接入（微信等 App 选中文字 → 分享 → FlowAI 直接打开分析）；图片分享与 OCR 待后续。

系统能力应继续输出 ContextCapsule，复用已有领域流水线。

## 构建版本

使用本机已有 AGP 8.3.2、Kotlin 1.9.24 和 Gradle 8.9，Compose Compiler 1.5.14、Compose BOM 2024.06.00。兼容依据：[Compose / Kotlin 对照表](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)、[AGP 8.3](https://developer.android.com/build/releases/past-releases/agp-8-3-0-release-notes)。这些是固定构建版本，不代表最新版本。

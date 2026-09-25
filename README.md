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

## 首页结构

首页是**一屏不滚动**的，上面只有两条互不依赖的路：

- **路径 A · 复制文本**：`粘贴聊天` 进入输入页；`Demo A` 一键载入示例聊天。
- **路径 B · 桌宠截图**：主按钮随权限与运行状态变化（`去授权` / `开启桌宠` / `关闭桌宠`）；`截屏分析` 走框选截屏链路。
- 底部常驻一行：桌宠皮肤 / 使用说明 / 引擎设置，以及「本机分析 · 内容不离开手机」的状态条。

两张卡片用 `weight(1f)` 平分头部以下的全部高度，因此在任何屏幕高度下都不会把第二条路挤出屏幕，也不需要滚动。

卡片内部把富余高度**全部**交给中部的图形（按可用空间伸缩，空间不足时缩到没有），标签和按钮因此总是先拿到自己的完整高度。早先版本把富余高度分给上下两个 `Spacer`：内容一旦高于卡片它们就归零，按钮被推过 `Card` 的裁剪边界而消失——屏幕较矮、系统字体放大（真机记录里的 2.0x）、或出现两行提示条时都会复现。摘要与提示条各限两行是同一个道理；卡片还会用 `BoxWithConstraints` 量出自身实际高度，低于 260dp 时摘要收成一行、内外间距收紧。

卡片中部的图形是 Canvas 现画的（与桌宠、皮肤预览一致），不增加安装包体积。原先堆在首页下方的说明文字和低频入口移到了「使用说明」页。

## 验收路径

1. 首页 → Demo A → 分析当前沟通。应展示“任务完成进度”、缺少明确完成时间，以及给出时间 / 汇报进度 / 确认紧急程度三个动作。
2. 点击“给出明确完成时间” → 自然、简洁、正式三种回复。可以编辑后复制；示例“今晚十点”必须由用户核实。另两个动作应给出不同回复。
3. 提取任务与事件（组会 明天 15:00 / A203、小王 PPT、小李数据，均今晚 22:00）：`Demo B` 按钮已从首页移除，这条路径**没有界面入口**，无法人工复核。提取本身是 demo-only 的——`MockLlmService` 用 `isDemo(context, DemoConversations.B)` 判定，所以现在**任何真实输入点「提取事项」都只会得到空结果**，引擎侧逻辑仍由 `FlowEngineTest` 覆盖。
4. 同 3：Demo B 的“生成会议事件”仅展示事件；“确认安排”展示候选回复——同样只能由单元测试覆盖。
5. 粘贴任意其他聊天 → 通用 Mock 状态，不套用 Demo 的人员、地点和时间；提取动作显示空结果说明。
6. 空文本无法分析；标签后无内容会报错。文本上限 20,000 字符。不保留最近分析历史，仅保留当前这一次分析；返回首页或结束会话即清除。
7. 旋转屏幕保留当前分析（由 ViewModel 内存保留，不使用 SavedStateHandle）；进程重建后不恢复任何内容，需重新导入聊天。分享进来的文本也不会被重建后的任务重新导入（见下方"分享文本不会被重复导入"）。
8. 在微信等应用选中文字 → 分享 → FlowAI，应直接打开并预填该文字，输入页显示"内容来自系统分享"。

以上路径已在 API 34 模拟器上自动跑通并逐步截图，详见 [验证记录](TEST_RESULTS.md)。旋转仅验证了配置变更中的状态保留，未验证横屏布局；分享仅验证了 `SEND` intent 接收，未经过真实微信 IPC 交接。空文本时"分析当前沟通"按钮为禁用状态，而非弹出错误。

**真机验收**（Android 16 / Redmi，2026-09-19）见 [真机验收记录](docs/verification/DEVICE_ACCEPTANCE.md)：8 条路径中 7 条自动通过、旋转由人工确认；并记录了两处真机才暴露的问题（深色模式下保持亮色、2.0x 字体下顶栏标题换行）。

**当前构建（1.1.0，截屏"先识别文字再分析"改版后）全功能验证**见 [当前构建全功能验证记录](docs/verification/FEATURE_VERIFICATION.md)：8 条验收路径 + V2 入口（桌宠 / 皮肤 / 助手面板 / 截屏 / 远程模型配置）逐条复核，`163` 项单测全过、Lint 0 错误；并从载荷层面确认截屏图片不再上传、只分析本机识别出的文字。

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

### 悬浮入口：桌宠（V2 增量）

- **会话生命周期**：`domain/CaptureSession.kt` 把 Just-in-Time Context 落成状态机（`IDLE`/`ACTIVE`/`EXPIRED`），所有入口共用。纯 Kotlin、无 Android 依赖、时间可注入，因此超时与释放行为可单测。结束原因区分 `USER_ENDED`/`TIMED_OUT`/`SUPERSEDED`。
- **桌宠**：`system/pet/`（`PetSkin` / `PetSkinStore` / `PetView`）—— 悬浮入口是一只用 Canvas 程序化绘制的桌宠，零图片资源，不增加 APK 体积。点它打开助手面板并播放粒子爆发 + 光波的点击特效；空闲时会自己做动作（眨眼、蹦跳、摇摆、拉伸、转圈、打盹、冒爱心）。长按桌宠循环切换皮肤（共 6 款，默认「小蓝团」），选择会持久化；应用首页可打开带预览的皮肤选择页，选择立即应用到运行中的桌宠。**桌宠只作为入口，不会自动读取任何内容**。需要 `SYSTEM_ALERT_WINDOW`（在系统设置页授予，首页有引导）。
- 已知平台限制：系统「设置」等安全敏感界面会隐藏非系统覆盖窗口，"分享"与"划词"入口因此必须保留。

### 截屏识别（V2 增量，测试版）

首页「截屏分析」→ **拖动框选聊天区域**（或选「整屏识别」）→ 系统授权 → 截屏并识别框内文字 → 进入同一个分析流程（来源显示"内容来自截屏识别"）。

- **先选区域再识别**：只识别框内内容。这既是精度优化（排除状态栏与应用外壳），也是 Just-in-Time Context 的要求——读得越少越好。框外会压暗，让你看清将识别哪一块。
- **完全离线**：使用 ML Kit **捆绑版中文模型**，模型在 APK 内，不依赖 Google Play services、不联网。
- **只用一次**：Android 14 起每次截屏都要重新授权；识别完立即释放 `MediaProjection` 与 Bitmap，不持续截屏、不保留历史。
- **体积**：APK 约 **22 MB**（只发 arm64-v8a；捆绑 OCR 原生库每 ABI 要 7–12 MB，因此不打包其他 ABI）。
- 已知限制：`FLAG_SECURE` 界面（网银、部分视频）会截出黑屏，不做绕过；小字有误识别；只识别当前屏，暂不做滚动拼接。

### 三风格回复卡片（V2 增量，DeepSeek 在线）

点桌宠一键（或「使用说明」页的「截屏识别 → 回复卡片」）→ 框选/整屏截屏 → **本机 OCR 只取纯文本** → DeepSeek 串行生成三条回复（温暖 / 毒舌 / 冷静科学）→ 悬浮回复卡片浮在聊天应用上方，逐条打字机呈现、各自可复制。用户全程不离开当前对话。

- **隐私不变式：原图绝不上传**。截屏 PNG 只在本机解码给 OCR，识别完立即删除缓存文件；提交给 API 的只有 OCR 纯文本（截 6000 字）+ 固定人设 system prompt。卡片底部常驻该说明。
- **三人设**：`ai/AiStyle.kt` 固定三个实例（温柔共情 / 幽默犀利但文明 / 理性客观不带情绪），顺序固定、串行请求（不并发），头部显示「正在生成 n/3…」；某一路失败只该区块显示友好原因 + 「重试这一条」，不拖死后续风格。
- **取消语义**：关闭卡片（×）或悬浮服务销毁 → 协程 Job 取消 → OkHttp 在途请求立即 cancel；最小化**不**取消（结果还在，点桌宠即恢复）。APP 退后台不取消——卡片本就悬浮在其他应用上方使用。
- **窗口纪律**：`TYPE_APPLICATION_OVERLAY` + 不加 `FLAG_LAYOUT_NO_LIMITS`，系统自动避开状态栏/挖孔；拖拽松手按卡片中心吸边回弹；淡入淡出 200ms；点击卡片外部不关闭（误触丢失代价高）。
- **失败兜底**：401/403→Key 无效、429→限流、5xx→服务不可用、超时/网络异常/响应格式异常均有友好文案；未配置远程模型或未同意上传时不发起请求。全部捕获、不崩溃。
- **与面板的分工**：卡片接管桌宠一键（截屏→OCR→三风格回复）；助手面板保留粘贴/手动分析/追问聊天入口，截屏分析旧路径不变。
- 验收记录与截图（37–42）见 [回复卡片功能核验报告](docs/verification/REPLY_CARD_FEATURE.md)。

### 已知的平台限制（实测）

- **桌宠在微信里不可见**：微信声明并获得了 `HIDE_OVERLAY_WINDOWS`，系统会隐藏其窗口上方的非系统覆盖窗口。因此**分享入口与划词入口是必需的**，不能只靠桌宠。
- **无障碍读不到微信**：实测微信不向无障碍框架暴露任何界面内容（同一探针读计算器有 160 个节点、读微信为零）。**无障碍路线已否决**，详见 [PoC 结论](docs/verification/A11Y_READABILITY_POC.md)。

## 架构

`文本 → DialogueParser → Message[] → ContextCapsule → ConversationStateBuilder → ConversationState → NextActionEngine → Top-3 NextAction → ChatToActionEngine → ActionResult`

`ActionResult` 包含候选回复或 `ActionObject.Task / Event / Decision`。UI 通过 ViewModel 调用 Repository；Repository 编排解析和三个 Engine 接口。`LlmService` 组合 Engine 边界，`MockLlmService` 为完全本地、确定性的实现。后续可替换为真实服务，不改变领域模型。

两个 Demo 按解析后的说话人和消息正文匹配；改动内容后进入通用模板，避免错误复用固定结论。不存在心理分数、情绪打分、网络请求、自动发送或日历写入。共识与分歧未明确出现时显示为空。

解析约定：每个非空行一条消息，支持中文或英文冒号；“我/自己/me”映射为 ME，其余前两个不同标签映射 OTHER / OTHER_2，更多标签或无标签映射 UNKNOWN。数字时间和 HTTP URL 不作说话人前缀。当前固定模型不保存真实姓名，人物姓名仅出现在原文和 Demo 任务字段中；复杂聊天导出格式尚不支持。

`system/` 承载 V2 平台入口：分享 / 划词 / 截屏 / 区域框选 / 助手面板窗口，以及悬浮桌宠（`system/pet/`）。`OverlayContextProvider`、`AccessibilityContextProvider`、`ScreenCaptureProvider`、`ShareReceiver` 为接口级预留。SkillRegistry 仅预留扩展边界，未引入技能执行框架。

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
        │       ├── system/         分享/划词/截屏入口、区域框选、助手面板窗口、
        │       │                   pet/（桌宠皮肤与动画）
        │       └── ui/
        │           ├── FlowViewModel.kt / Page.kt
        │           ├── home/       HomeScreen（两条路的单屏首页）, InputScreen
        │           ├── analysis/   AnalysisScreen
        │           ├── action/     ActionScreen, EventCalendarCard
        │           ├── chat/       ChatScreen
        │           ├── card/       三风格回复卡片（ReplyCard / TypewriterText / 拖拽数学）
        │           ├── panel/      AssistantPanel
        │           ├── settings/   EngineSettingsScreen
        │           ├── skins/      PetSkinScreen
        │           ├── help/       HelpScreen（首页收纳出的说明与备用入口）
        │           └── components/ Components
        └── test/java/com/flowai/communication/
            ├── FlowEngineTest.kt / FlowSessionTest.kt
            ├── ShareConsumptionTest.kt / SharedTextTest.kt
            ├── CaptureSessionTest.kt / CaptureRegionTest.kt
            ├── OcrSpeakerAssemblyTest.kt / RemoteEngineFallbackTest.kt
            └── system/pet/PetSkinTest.kt
```

## 已完成与边界

完整实现首页、文本导入、状态展示、Top-3 动作、回复生成和编辑复制、Task/Event 提取页面、系统分享文本入口、悬浮桌宠与皮肤、截屏 OCR 入口、两个 Demo、模型和 Engine 基础测试。

没有实现登录、数据库、语音、RAG、长期记忆、多模型或自动发消息。任务和事件均为草稿，不写入外部应用；相对时间保持原样，未绑定具体日期。测试结果见 TEST_RESULTS.md。

## 下一阶段

- OverlayContextProvider：悬浮入口与用户主动触发；届时再处理悬浮权限、生命周期和设备适配。
- AccessibilityContextProvider：用户授权后获取当前窗口的可用上下文，适配不同聊天界面。
- ScreenCaptureProvider：MediaProjection 用户授权、截图和 OCR。
- ShareReceiver：文本分享已接入（微信等 App 选中文字 → 分享 → FlowAI 直接打开分析）；图片分享与 OCR 待后续。

系统能力应继续输出 ContextCapsule，复用已有领域流水线。

## 构建版本

使用本机已有 AGP 8.3.2、Kotlin 1.9.24 和 Gradle 8.9，Compose Compiler 1.5.14、Compose BOM 2024.06.00。兼容依据：[Compose / Kotlin 对照表](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)、[AGP 8.3](https://developer.android.com/build/releases/past-releases/agp-8-3-0-release-notes)。这些是固定构建版本，不代表最新版本。
# FLOW-AI

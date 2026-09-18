# 验证记录

> **真机验收已完成**（Android 16 / Redmi，2026-09-19）：8 条路径中 7 条自动通过、旋转由人工确认。
> 详见 [真机验收记录](docs/verification/DEVICE_ACCEPTANCE.md)。

## 一、模拟器真机级验收（2026-09-18 本轮新增）

首次完成**自动化模拟器验收**，补上了此前"adb 不可用、从未跑过自动化验收"的缺口。

### 环境

| 项 | 值 |
| --- | --- |
| 模拟器 | Android Emulator（`emulator` 包，独立安装于 `.tools/android-sdk/emulator`） |
| 硬件加速 | **WHPX**，`emulator -accel-check` 返回 `WHPX(10.0.26200) is installed and usable` |
| AVD | `flowai34`，设备档案 `pixel_6`，位于用户主目录下的 `.android/avd/flowai34.avd` |
| 系统镜像 | `system-images;android-34;google_apis;x86_64`（rev 14） |
| 镜像校验 | SHA1 `e0f6c9a0691aa27bd597d0deb1bcfdc943ac8ca7`，大小 1,563,721,130 字节，**与官方 manifest 一致** |
| 运行时 | Android 14（API 34），x86_64，1080x2400 @ 420dpi |
| 被测 APK | `FlowAICommunication-debug.apk`，SHA-256 `40fc9f9a…91d0`，与上一轮交付**逐字节一致** |
| 应用版本 | versionName 0.1.1 / versionCode 2，targetSdk 34 |

被测 APK 与项目根目录交付件哈希完全相同，且所有源文件修改时间均早于 APK 构建时间，因此本轮结果对应**当前源码**。

### 逐条验收结果

| # | README 验收路径 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 | 首页 → Demo A → 分析当前沟通 | ✅ 通过 | `02`、`03` |
| 2 | 明确完成时间 → 自然/简洁/正式，可编辑复制；另一动作给出不同回复 | ✅ 通过 | `04`、`05`、`06` |
| 3 | Demo B → 提取任务与事件（组会 明天 15:00 / A203、小王 PPT、小李数据，均今晚 22:00） | ✅ 通过 | `09` |
| 4 | 生成会议事件仅展示事件；确认安排展示候选回复 | ✅ 通过 | `10`、`11` |
| 5 | 其他聊天 → 通用 Mock，不套用 Demo 人员/地点/时间；提取动作显示空结果说明 | ✅ 通过 | `13`、`14` |
| 6 | 空文本无法分析；标签后无内容报错；上限 20,000 字符 | ✅ 通过（见下方说明） | `15-label-no-content-error`、`16` |
| 7 | 旋转屏幕保留当前分析 | ✅ 通过 | `17`、`18`、`19` |
| — | 进程重建后不恢复任何内容 | ✅ 修复后通过（手动输入与分享文本均不恢复） | `20`、`22`、`23`、`24` |
| — | 结束会话清除内容 | ✅ 通过 | `21` |
| 8 | 系统分享文本入口 | ✅ 已修复 `text/*` 与 `EXTRA_HTML_TEXT`；新增划词入口；真机微信链路待确认 | `16`、`26`、`27`、`28`、`29` |

截图存于 `docs/verification/screenshots/`（22 张，含各步骤画面与结果）。

### 关键核对点

- **Demo A**：话题"任务完成进度"、阶段"跟进"、未解决问题含"尚未明确具体完成时间"，Top-3 为"给出明确完成时间 / 汇报当前完成进度 / 确认任务是否紧急"，与 README 描述完全一致。
- **候选回复**："今晚十点"示例旁明确标注"'今晚十点'和【占位内容】是示例，不是已知承诺；不会自动发送"，符合"示例必须由用户核实"的约定。
- **不同动作产生不同回复**：动作 1 得到三条含具体时间的回复；动作 2 得到三条含"【内容】"占位的进度汇报回复，二者不同。
- **复制**：点击"复制这条回复"后界面出现"已复制到剪贴板"，logcat 出现 `ClipboardListener` 活动记录。（API 34 上 `cmd clipboard` 已不可用，无法直接读回剪贴板内容。）
- **Demo B 提取**：界面显示"识别到 1 个事件 · 2 个任务"，事件为组会 明天 15:00 / A203 / 老师、小王、小李；两个任务截止均为今晚 22:00。另注"Mock 草稿，未写入系统日历或待办"。
- **生成会议事件**："识别到 1 个事件 · 0 个任务"，仅展示事件，与"仅展示事件"一致。
- **通用 Mock**：输入 `Alice: Can the report be ready today` / `me: maybe not` → 话题"待确认的沟通话题"，注"通用 Mock 模板，不是真实 AI 分析；不会套用 Demo 的人员、时间或任务"，且**未出现** A203、小王、明天 15:00 等 Demo 内容。
- **空结果说明**：通用文本下"查看可提取事项"返回"当前 Mock 没有识别到可提取事项。请使用 Demo B 验证任务与事件流程。"
- **字符上限**：注入 24,500 字符 → 界面精确显示 `20000 / 20,000`，截断生效。
- **会话清除**：点"结束并清除本次内容"后回到首页并显示"本次内容已清除"，不再残留分析。
- **进程重建**：`am kill` 确认进程消失（pidof 为空）后重建，**分析结果未被恢复**；`am force-stop` + LAUNCHER intent 冷启动回到干净首页，无任何残留文本或分析。

### 与文档不符之处（本轮实测发现）

1. **"空文本无法分析"实为 UI 层禁用，而非报错。**
   `InputScreen` 使用 `enabled = text.isNotBlank()`，文本框为空时"分析当前沟通"按钮直接 `enabled="false"`（已在 UI 层核对到该属性），因此空输入**不会触发** `ConversationRepository.analyze()` 的 `require(messages.isNotEmpty())` 异常，用户看不到"请先输入有效的聊天内容"。
   仅当输入**能被解析出说话人前缀但正文为空**时（实测输入 `Alice:`）才会落入该异常分支并显示错误文案。
   建议：README 第 6 条可改为"空文本时分析按钮不可用；只有标签没有内容时提示'请先输入有效的聊天内容'"。当前行为体验更好，但文档描述不准确。

2. **【已修复】分享进来的文本会跨进程重建保留，与 README 第 7 条的承诺不符。**
   实测（修复前）：分享 `MARKER_BETA me: hello there` → 输入页正常显示"内容来自系统分享"与原文 → `am kill` 确认进程消失（`pidof` 为空）→ 经 LAUNCHER 冷启动后，**该文本与"内容来自系统分享"标记被完整恢复**。
   机制：`MainActivity.onCreate` 读取的是 Activity 被重新投递的原始 `SEND` intent（`consumeSharedText(intent)` → `vm.consumeShare()`），因此绕过了 `super.onCreate(null)`、`onSaveInstanceState` 清空与禁用 `LocalSaveableStateRegistry` 这三层防御——它们只拦应用自身的状态保存，拦不住 Android 重新投递 intent。
   对照：**手动输入**的文本在同样的进程死亡后**不会**恢复，因此差异确实来自分享 intent，而非通用行为。

   **修复过程中的关键发现（用独立探针 App 证实）**：在 `onCreate` 中把 `EXTRA_TEXT` 从 `getIntent()` 上 `removeExtra` 掉**并不能解决问题**。探针日志显示：
   ```text
   ONCREATE  | action=SEND | hasExtra=true  | text=SECRET_PAYLOAD_XYZ   ← 载荷又回来了
   ONCREATE  | AFTER_STRIP | hasExtra=false                            ← 应用内确实被清掉
   ONNEWINTENT | action=MAIN                                           ← 启动器 intent
   ```
   即"清掉 extra"只在**当前进程内**生效，`system_server` 的任务记录仍保留原始 intent，每次任务重建都会重新投递。因此**应用侧必须自己记住"这条分享已经消费过"**。

   **已实施的修复**：
   - 新增 `domain/ConsumedShareStore.kt`：`ConsumedShareStore` 接口 + `InMemoryConsumedShareStore`（默认，会话级）与 `PrefsConsumedShareStore`（跨进程存活）。
   - **隐私取舍**：只持久化文本的 64 位哈希与时间戳，**绝不存原文**；条目 15 分钟后自动过期，因此用户主动重复分享同一段文本仍可正常导入。
   - `FlowViewModel.consumeShare()` 先查 `wasConsumed` 再导入；`MainActivity` 通过 `viewModelFactory` 注入 `PrefsConsumedShareStore`，因为分享入口的生命周期长于任何单个 ViewModel。
   - 新增 `ShareConsumptionTest`（8 项），覆盖"重建后不重新导入""会话已结束不被复活""不同文本仍可导入""过期标记不阻塞导入""仅精确匹配同一文本"。

   **修复后实测**（同一台模拟器、同一复现步骤）：冷启动回到干净首页，原文与"内容来自系统分享"标记**均未恢复**；同时回归确认新分享、不同文本分享、进程死亡后的不同文本分享、以及 Demo A 主流程全部正常。

   **仍未消除的残余风险（无法从应用侧解决，已如实记录）**：聊天原文本身仍留在 `system_server` 的任务记录中，直到该任务被用户从最近任务里划掉。应用能阻止"再次导入"，但无法从任务记录里抹除载荷。若要彻底消除，需在消费后调用 `finishAndRemoveTask()`，代价是应用会从最近任务中消失，对常规使用体验影响较大，本轮**未采用**。

3. **【已修复】微信分享无法导入（真机反馈）。**
   症状：从微信分享文字到 FlowAI 无效果。
   根因有两处，都会导致失败：
   - **Manifest 只声明了 `text/plain`**。微信等客户端分享样式文本时会用 `text/html`，`text/plain` 过滤器**根本匹配不到**，FlowAI 不会出现在分享列表里（或分享后无任何反应）。已在真机/模拟器上用 `pm query-activities -a android.intent.action.SEND -t text/html` 核实：修复前解析不到，修复后可解析。
   - **只读 `EXTRA_TEXT`**。`EXTRA_HTML_TEXT` 是平台在"样式文本无法表示为纯文本"时的回退，微信会用到。原实现遇到只有 `EXTRA_HTML_TEXT` 的载荷会得到空文本并**静默什么都不做**。

   修复内容：
   - Manifest 改为 `text/*`（平台支持的最宽文本族过滤器），并加上 `android:label`，使分享列表中显示"FlowAI 分析沟通"而非裸包名。
   - 新增 `domain/SharedText.kt`：优先取 `EXTRA_TEXT`，缺失或本身是标记时回退 `EXTRA_HTML_TEXT`，并把标记压平为纯文本（`<br>`/`</p>` 转真实换行，保证"每行一条消息"的约定不被破坏；解码实体；剔除 script/style）。
   - `MainActivity` 增加 `FlowAI` tag 日志（`adb logcat -s FlowAI`），非文本类型与空载荷都会留下可诊断记录，不再静默失败。
   - 新增 `SharedTextTest`（15 项）。

   修复后实测（模拟器）：`text/html` + `EXTRA_HTML_TEXT`（无 `EXTRA_TEXT`）的微信式载荷被正确导入、显示"内容来自系统分享"、标签被去除，并成功走完整管线（界面显示"已解析 2 条消息"）；`image/png` 分享被正确忽略并记录日志；`text/plain` 与进程重建回归均正常。

   **仍需真机确认**：模拟器只能复现"微信式载荷形状"，无法复现微信本身的 IPC 交接。若真机仍失败，`adb logcat -s FlowAI` 现在会直接给出原因（非文本类型 / 空载荷 / 未收到 intent）。

4. **旋转测试的保真度受限。**
   模拟器未实际发生横竖屏布局切换（系统接受 `user_rotation=1` 后画面仍为 1080x2400），因此本次仅验证了"配置变更过程中 ViewModel 保留分析结果"，**未验证横屏布局本身**。真机横屏渲染仍需人工确认。

### 验证边界（仍未被覆盖）

- **分享入口只能部分验证**：本轮通过 `am start -a android.intent.action.SEND -t …` 触发，覆盖了 `text/plain`、`text/html` + `EXTRA_HTML_TEXT`、以及 `image/png`（应忽略）三种载荷形状，并确认了进入输入页、显示"内容来自系统分享"、标签压平、20,000 字符截断。但**未经过真实微信的 IPC 交接**，因此 `singleTask` + `onNewIntent` 在真实第三方应用分享链路中的表现、微信实际发出的 action/MIME/extras、以及厂商 ROM 差异，仍未验证。`adb logcat -s FlowAI` 可在真机上直接给出结论。
- 未在 API 26（minSdk 下限）上验证。
- 未覆盖真机字体/密度适配、性能与内存表现。
- 未执行 `adb shell cmd clipboard get`（API 34 已移除该命令），复制内容未做端到端读回校验。

### 本轮代码变更与重新验证

针对上述第 2、3 条缺陷，以及新增的划词入口，本轮**修改了代码**（此前各轮均为只改文档）：

- 新增 `app/src/main/java/com/flowai/communication/domain/ConsumedShareStore.kt`。
- 新增 `app/src/main/java/com/flowai/communication/domain/SharedText.kt`（两种入口的载荷解析；HTML 压平；`ClipData` 兜底）。
- 新增 `app/src/main/res/values/strings.xml`（分享列表与划词工具栏的显示名）。
- 修改 `AndroidManifest.xml`：分享过滤器 `text/plain` → `text/*` 并加 `android:label`；**新增 `ACTION_PROCESS_TEXT` 过滤器（划词入口）**。
- 修改 `MainActivity.kt`：改用 `SharedText.resolve`（兼容 `EXTRA_HTML_TEXT` 与 `ClipData`）；新增 `FlowAI` tag 诊断日志，能区分"收到 / 重复投递 / 非文本 / 无可用文本"。
- 修改 `FlowViewModel.kt`、`InputScreen.kt`、`ContextCapsule.kt`：新增 `SourceType.PROCESS_TEXT` 与"内容来自划词选择"提示。
- 新增 `ShareConsumptionTest.kt` 与 `SharedTextTest.kt`。

本轮**已重新执行**全部三项（`clean` 后完整重跑，非增量）：

- `:app:testDebugUnitTest`：**77 项**（FlowEngineTest 18 + FlowSessionTest 17 + ShareConsumptionTest 11 + SharedTextTest 31），0 失败，0 错误。
- `:app:assembleDebug`：通过，版本 0.1.1。
- `:app:lintDebug`：0 错误，0 条提示 / 警告。

新 APK：`app/build/outputs/apk/debug/app-debug.apk`，7,990,586 字节，SHA-256 `4ecbcf1139ab74cd7c6622ad66bd93d970cf09ef92769d4ec7cef15224649bd2`。
该 APK 已装机复验：划词入口导入并走完整管线、重复投递被识别且不重新导入、空白分享触发的 `ClipData` 垃圾（实测为 `-n`）不再被导入、HTML 分享与进程重建回归均正常。

#### 新增：划词入口（`ACTION_PROCESS_TEXT`）

在任意 App 选中文字 → 系统选择工具栏 → 「用 FlowAI 分析」。零新增权限、零政策风险，且天然符合 Just-in-Time Context（用户明确选中才触发）。实测：`pm query-activities -a android.intent.action.PROCESS_TEXT -t text/plain` 能解析到 FlowAI，投递后正确进入输入页并显示"内容来自划词选择"，可完成分析。

**局限（必须记住）**：只覆盖可选文本控件，**微信/QQ 的消息气泡通常不可选**，因此它拿不到整段对话——它是新增入口，**不是** `ACTION_SEND` 的替代。

#### 发现并修复的第三个问题：`ClipData` 垃圾被当成聊天文本

加入 `ClipData` 兜底后（`ShareCompat.IntentReader` 源码确认它不读 `ClipData`，而有些发送方只把文本放在那里），真机实测发现：**空白 `EXTRA_TEXT` 的分享会让兜底读到命令行 intent 留下的组件名 `-n`，2 个字符的垃圾被导入成聊天文本**。
修复后 `ClipData` 仅作兜底：只在 extras 未产出可用文本、且该串含不低于 4 个非空白字符时才采用。设备复验：同样的空白分享现在停在上页、不导入任何内容，日志如实记录。

#### 附带：诊断日志现在不会说谎

`adb logcat -s FlowAI` 会区分：`received SHARE/PROCESS_TEXT`（入口收到）、`repeat ... (not re-imported)`（重复投递，已被去重）、`ignored intent: action=... extras=... clip=...`（非文本或无可解析内容）。**这是真机排查微信分享的唯一手段**——注意它只表示"入口收到了什么"，最终是否导入仍由去重规则决定。

注意：项目根目录的 `FlowAICommunication-debug.apk` 仍是**修复前**的旧交付件（7,979,442 字节，SHA-256 `40fc9f9a…`），本轮未覆盖。其 `TEST_RESULTS` 中记录的哈希仍然对应那个旧文件；如需把新构建作为交付件，请从 `app/build/outputs/apk/debug/` 复制覆盖并同步更新哈希与 `.sha256`。

### 发现的产品缺陷（非验收项，待决策，本轮未改）

- **分享会无条件清空进行中的会话。** `onNewIntent` → `consumeShare()` → `openInput()` → `clearSession()`。若用户正在查看某次分析，此时从其他应用分享新文本进来，当前分析会被立即丢弃且无提示。真机使用中这条路径很容易被触发，建议改为"先提示是否替换当前会话"或"新开一次会话"。该缺陷与上述已修复项位于**同一条代码路径**，本轮刻意未改动，以免把两个行为变更混在一起。

---

## 二、上一轮自动验证记录（2026-09-18，修复前基线）

本机 JBR 21.0.5、Gradle 8.9、Android SDK 34、Build Tools 34.0.0。

### 自动验证（修复前基线）

- `:app:testDebugUnitTest`：35 项（FlowEngineTest 18 + FlowSessionTest 17），0 失败，0 错误。
- `:app:assembleDebug`：通过，生成 Android 8.0+ 调试安装包（版本 0.1.1）。
- `:app:lintDebug`：0 错误，0 条提示 / 警告。

### 单元测试范围

Demo A 消息顺序和说话人、中文 / 英文冒号、多说话人、时间 / URL 原文保留、空消息、空输入、超长输入、状态和 Top-3 排序、三种回复、不同动作产生不同回复、Demo B 的一个 Event / 两个 Task、单独生成事件、未知场景保守回退、修改 Demo 时间后的回退、模型字段、禁用的系统 Stub、拒绝不属于当前分析的动作、分享来源写入 ContextCapsule；以及会话生命周期：结束释放、未分析草稿释放、返回与结束语义、回复编辑、新会话丢弃旧结果、ViewModelStore 清空释放、分享文本打开与重复分享去重。

### Lint 提示

无。

### 交付文件（上一轮）

- APK：`FlowAICommunication-debug.apk`（7,979,442 字节）。
- SHA-256：`40fc9f9a96e2e7253c57e123d9d5567cf1c336e50d69593a64604efa148391d0`。
- 单元测试 HTML：`app/build/reports/tests/testDebugUnitTest/index.html`。
- Lint HTML：`app/build/reports/lint-results-debug.html`。

---

## 三、复现本轮模拟器验收

SDK 已就绪于 `.tools/android-sdk`（`local.properties` 已指向），AVD 已创建，无需重复下载。

```powershell
# 在仓库根目录执行；路径按需替换为你的实际位置
$sdk = "$PWD\.tools\android-sdk"
$adb = "$sdk\platform-tools\adb.exe"
$env:ANDROID_HOME = $sdk; $env:ANDROID_SDK_ROOT = $sdk

# 启动模拟器
& "$sdk\emulator\emulator.exe" -avd flowai34 -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect

# 等待启动完成后安装并运行
& $adb wait-for-device
& $adb install -r ".\FlowAICommunication-debug.apk"
& $adb shell am start -n com.flowai.communication/.MainActivity
```

模拟器占用磁盘约 5.3 GB（系统镜像 4.16 GB + emulator 1.01 GB + cmdline-tools 0.17 GB），均位于项目内 `.tools/android-sdk`（AVD 数据位于用户主目录下的 `.android/avd`）。

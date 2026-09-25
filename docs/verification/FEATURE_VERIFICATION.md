# 当前构建全功能验证记录（1.1.0 · 截屏先识别文字再分析）

验证日期：2026-09-24。
验证环境：Android Emulator，AVD `Pixel_API_36`，**API 36**，端口 `emulator-5556`，1080x2400。
被测版本：`com.flowai.communication` **1.1.0**（versionCode 27），对应当前源码（截屏"先本机识别文字、再分析文字"改版之后）。
方式：`adb` 驱动模拟器，逐条操作并抓取界面层级；自动检查在 ASCII 路径副本上执行 `:app:testDebugUnitTest :app:lintDebug`。

> **截图只入库模拟器画面。** 本轮新增截图（`34`–`36`）均为模拟器上 FlowAI 自身界面，不含任何第三方应用内容，符合 [BEFORE_PUSHING](../BEFORE_PUSHING.md) 的隐私约定。真机截图与含真实聊天的画面不入库。

> 本轮重点：确认**截屏识别改版**（图片不再上传，改为"本机 OCR 文字 → 分析文字"）落地后，README 的 8 条验收路径与 V2 平台入口（桌宠 / 皮肤 / 助手面板 / 截屏 / 远程模型配置）仍全部可用。

---

## 一、自动检查（测试 + Lint）

在 ASCII 路径副本上执行 README 的命令 `./gradlew :app:testDebugUnitTest :app:lintDebug`，`BUILD SUCCESSFUL`。

### 单元测试：163 项，0 失败 / 0 错误 / 0 跳过

| 测试套件 | 用例数 | 覆盖 |
| --- | --- | --- |
| `SharedTextTest` | 41 | 分享 / 划词载荷解析、HTML 压平、`ClipData` 兜底、去重 |
| `FlowSessionTest` | 24 | 会话生命周期：结束释放、草稿释放、返回语义、回复编辑、新会话丢弃旧结果 |
| `FlowEngineTest` | 18 | 解析、状态、Top-3、三种回复、Demo A/B 提取、未知场景保守回退 |
| `CaptureSessionTest` | 16 | Just-in-Time Context 状态机（`IDLE`/`ACTIVE`/`EXPIRED`、超时、释放原因） |
| `OcrSpeakerAssemblyTest` | 15 | OCR 行 → 说话人归并 |
| `ShareConsumptionTest` | 11 | 进程重建后不重新导入分享、过期标记、仅精确匹配同一文本 |
| `OcrTextAssemblerTest` | 9 | OCR 文本拼装（多行对话还原） |
| `RemoteEngineFallbackTest` | 9 | 远程调用失败 → 自动回落本机 Mock |
| `PetSkinTest` | 8 | 桌宠皮肤枚举、默认值、循环切换、持久化 |
| `CaptureRegionTest` | 7 | 框选区域归一化、裁剪边界 |
| `OcrPreviewFlowTest` | 5 | 截屏 → OCR → 分析 的流程编排 |
| **合计** | **163** | **0 失败 / 0 错误 / 0 跳过** |

### Lint：0 错误，17 警告（构建通过）

警告均为提示级，不影响构建与运行：

- `UnusedResources` ×11 —— 主要是**改版后不再被调用的图片上传路径**所遗留的资源（见下文"休眠代码"）。
- `OldTargetApi`（targetSdk 34）、`GradleDependency`（有更新的依赖版本）、`UnusedAttribute`、`HardcodedText`、`Deprecated` 各若干 —— 均为既有取舍，非本轮引入的缺陷。

---

## 二、README 验收路径（1–8）逐条结果

| # | README 验收路径 | 本轮结果 |
| --- | --- | --- |
| 1 | 首页 → Demo A → 分析当前沟通 | ✅ 话题"任务完成进度"、阶段"跟进"、缺明确完成时间，Top-3 三动作齐全 |
| 2 | 明确完成时间 → 自然/简洁/正式三回复，可编辑复制；另一动作给出不同回复 | ✅ 三条文案到位；点复制后出现"已复制到剪贴板"；动作 2 回复与动作 1 不同 |
| 3 | Demo B → 提取任务与事件 | ✅ "识别 1 个事件 · 2 个任务"；组会 明天 15:00 / A203；小王准备 PPT、小李整理数据，均今晚 22:00；"写入系统日历"为草稿按钮 |
| 4 | 生成会议事件仅展示事件；确认安排展示候选回复 | ✅ 生成事件"1 个事件 · 0 个任务"、无任务卡片；确认安排三条回复含 明天15点 / A203 / 今晚22点 |
| 5 | 其他聊天 → 通用 Mock、不套用 Demo | ✅ 话题"待确认的沟通话题"、注"通用 Mock 模板…"、"已解析 1 条消息"；脚本核对 **Demo 实体泄漏 = 空**（无 A203 / 小王 / 明天 15:00） |
| 6 | 空文本无法分析；标签后无内容报错；上限 20,000 | ✅ 空输入 → 按钮 `enabled=text.isNotBlank()` 禁用 + 提示"输入框为空：粘贴或选择一个示例后再分析"；输入 `Alice:` → 错误"请先输入有效的聊天内容"；计数器显示 `6 / 20,000`（上限在位） |
| 7 | 旋转保留分析；进程重建不恢复；分享不重复导入 | ⚠️ 旋转**仅人工确认**（模拟器不真正切横屏布局，见下）；"ViewModel 内存保留 + 进程重建不恢复 + 分享去重"由 `ShareConsumptionTest`(11)、`FlowSessionTest`(24) 覆盖 ✅ |
| 8 | 微信等应用分享 → 预填、显示"内容来自系统分享" | ✅ `SEND` intent 投递后输入页显示"内容来自系统分享"并预填原文；**附加**验证划词入口 `PROCESS_TEXT` → "内容来自划词选择"并预填 |

> 路径 6 的措辞与 README 脚注一致：**空文本时"分析当前沟通"为禁用态，而非弹错误**；只有"有说话人标签、无正文"（如 `Alice:`）才落入 `require` 分支并显示"请先输入有效的聊天内容"。
>
> 路径 8 用 `am start -a android.intent.action.SEND` 触发，覆盖了入口与预填；**未经过真实微信 IPC 交接**（与 README/DEVICE_ACCEPTANCE 的既有边界一致）。`%s` 在 `adb --es` 下是字面量、不会转成换行，真机分享发送的是真实 `\n`，预填机制本身已验证。

---

## 三、V2 平台入口与界面（README 8 条之外）

| 功能 | 结果 | 证据 |
| --- | --- | --- |
| 首页 V2 分区（桌宠 / 助手面板 / 截屏分析） | ✅ 渲染正常，且文案明示 OCR-first 策略 | `34-home-v2-pet-capture.png` |
| 桌宠皮肤选择页 | ✅ 6 款皮肤（小蓝团 / 小狐 / 薄荷 / 樱兔 / 星夜 / 团团）；点选后"使用中"标记即时移动、选择持久化 | `35-pet-skins.png` |
| 分析引擎设置页 | ✅ 8 个服务商预设（DeepSeek / 通义千问 / 智谱 GLM / Moonshot / OpenAI / Ollama 本机 / Claude / Gemini）；点预设自动填好地址与模型名（DeepSeek → `https://api.deepseek.com/chat/completions`、`deepseek-chat`）；3 种接口格式（OpenAI 兼容 / Anthropic Messages / Gemini generateContent）；API Key 框注"留空即在本机分析" | `36-engine-settings-presets.png` |
| 应用内截屏入口 | ✅ "截屏分析聊天内容"按钮 + "开启静音截屏（免授权弹窗）"开关在位 | `34`、正文 |
| 助手面板（悬浮） | ✅ 面板内就地分析 + 自动执行首条动作回复 + 面板内追问聊天（本轮早段验证） | 见下文 OCR-first 小节 |

首页截屏分区文案逐字为："**框选确认后先在本机识别截屏中的聊天文字，再把文字交给模型分析；截屏图片不离开手机。**"——与改版后的实际行为一致。

---

## 四、截屏"先识别文字、再分析"改版（本轮核心）

改版前截屏走"把图片交给远程模型读图"；**改版后图片不再离开手机**，统一为：

```
截屏帧 → OcrPipeline.recognize(本机 ML Kit 捆绑中文模型) → 文字
      → ConversationRepository.analyze(文字, SourceType.SCREENSHOT)
识别失败 → 抛出带引导语的错误（黑屏 / 无文字 / 超时 / 未知），不静默
```

核心实现见 `system/FrameAnalysis.kt`：先 `OcrPipeline.recognize(bitmap)`（IO 线程），成功则把**文字**交给 `repository.analyze(...)`，失败则按原因给出与 `R.string.capture_failure_*` 一致的引导语。Bitmap 所有权留在调用方，识别完即释放。

### 端到端证据

- **日志链**（本轮早段）：`ocr lines=6 plausible=4` → `recognised chars=60; analysing as text` → 远程文字分析成功（`engine ok via OPENAI_COMPAT, chars=979`）。
- **Mock 服务器请求日志**（`vision-request.log`）：改版后**最新**的分析请求全部是 `content=string … analysis=True`（纯文字，`chars=60`），**不再含** `content_parts=['text','image_url']`。日志里残留的 6 条 `image_url` 记录均来自改版前的旧构建。这从载荷层面证明：**截屏图片不再作为图片上传，只有识别出的文字离开分析路径**。
- **面板就地闭环**：桌宠一键 / 面板按钮截屏 → 面板内"正在识别截屏文字并分析…" → 出结果并自动执行首条动作 → 面板内可继续追问（`sendChat` 带上历史快照）。
- **降级韧性**：远程调用失败（实测死端口、以及长时间空闲后的陈旧连接 `unexpected end of stream`）时，`RemoteLlmService` 自动回落本机 `MockLlmService`，面板仍出结果——由 `RemoteEngineFallbackTest`(9) 覆盖，并在本轮实测复现。

### 休眠代码（在库、无调用方）

`ConversationRepository.analyzeScreenshot(imageBase64)` 与 `RemoteLlmService` 的图片载荷分支仍保留在代码中，但**改版后已无调用方**（`FrameAnalysis` 只走文字路径）。它们是旧"读图直析"路线的遗留，也是 Lint `UnusedResources` 警告的主要来源。保留不影响运行；若确认不再回退到读图路线，可在后续清理。

---

## 五、未覆盖 / 仍需人工确认

| 项 | 原因 |
| --- | --- |
| 横屏布局 | 模拟器接受 `user_rotation=1` 后画面仍为 1080x2400，未真正切横屏；本轮只验证"配置变更中 ViewModel 保留分析"，横屏渲染需人工确认（与既有记录一致） |
| 真实微信端到端（多选 → 分享 → FlowAI） | 需人工在微信内操作；应用侧已用真机日志抓到的载荷形状（`SEND_MULTIPLE` + `message/rfc822` + `EXTRA_TEXT`）复现通过 |
| 桌宠一键截屏的触摸注入 | 悬浮窗触摸无法由 `adb` 注入驱动；与面板截屏按钮共用同一代码路径，已通过面板路径验证 |
| 剪贴板读回校验 | API 34+ 已移除 `cmd clipboard get`，复制内容未做端到端读回（界面"已复制到剪贴板"提示在位） |
| API 26（minSdk 下限）、性能 / 内存 / 耗电 | 未在低版本镜像测量 |

---

## 六、复现方式

```powershell
# 自动检查（在 ASCII 路径副本上，避免中文路径干扰 Gradle）
./gradlew :app:testDebugUnitTest :app:lintDebug

# 界面验收（模拟器）
$adb = "<sdk>\platform-tools\adb.exe"
& $adb -s emulator-5556 install -r ".\app\build\outputs\apk\debug\app-debug.apk"
& $adb -s emulator-5556 shell am start -n com.flowai.communication/.MainActivity

# 分享 / 划词入口
& $adb -s emulator-5556 shell am start -a android.intent.action.SEND -t "text/plain" --es android.intent.extra.TEXT "我：进度怎么样" -n com.flowai.communication/.MainActivity
& $adb -s emulator-5556 shell am start -a android.intent.action.PROCESS_TEXT -t "text/plain" --es android.intent.extra.PROCESS_TEXT "我：会议纪要发我一下" -n com.flowai.communication/.MainActivity

# 截屏识别诊断日志
& $adb -s emulator-5556 logcat -s FlowAI
```

新增截图存于 `docs/verification/screenshots/`：`34-home-v2-pet-capture.png`、`35-pet-skins.png`、`36-engine-settings-presets.png`（均为模拟器上 FlowAI 自身界面）。

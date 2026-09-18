# V2 技术路线（吸收开源实现与平台调研）

更新日期：2026-09-18。来源：平台 API 调研（androidx 源码、Android 官方文档）+ 同类开源的工程实践。**本文只写技术路线，不改变第一阶段 Mock 的产品边界。**

参照的开源实现：

| 项目 | 为什么值得读 |
| --- | --- |
| [ciddwd/overlay-translator（屏译）](https://github.com/ciddwd/overlay-translator) | **与本文档需求最接近的现代实现**：Kotlin + Compose + minSdk 26，悬浮球 + MediaProjection + 端侧 OCR + 多 LLM + 分享/划词入口 + TTS。V2 的坑它基本都踩过并在 README 记录了结论 |
| [gkd-kit/gkd](https://github.com/gkd-kit/gkd) | 无障碍 + 悬浮窗的成熟大型工程；读"常驻服务 + 悬浮开关 + 规则订阅"结构与国产 ROM 授权引导 |
| [yhaolpz/FloatWindow](https://github.com/yhaolpz/FloatWindow) | 悬浮窗封装的经典写法（库偏老，建议只读不依赖） |
| [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) | 用 shell 权限绕开 MediaProjection 每会话授权（代价：多装一个 App） |
| [Julow/Unexpected-Keyboard](https://github.com/Julow/Unexpected-Keyboard) | 最小 IME，适合通读 `InputMethodService` 生命周期 |

---

## 已实施：两个零权限入口

### 1. `ACTION_PROCESS_TEXT`（划词入口，已实现）

用户在**任意 App 里选中文字** → 系统选择工具栏 → 「用 FlowAI 分析」。这是最短路径入口，且天然满足 Just-in-Time Context：用户明确选中才触发。

- API 23+；`EXTRA_PROCESS_TEXT` 取文本；`EXTRA_PROCESS_TEXT_READONLY` 是 boolean（**注意：网上流传的"READONLY extra 里放文本"是错的**）
- 不想替换选中文本就**不要** `setResult`；本应用只读，故不 setResult
- `android:label` 就是工具栏里显示的名字；`exported=true` 与 `CATEGORY_DEFAULT` 必需
- **局限（重要）**：只覆盖可选文本控件（`EditText`、WebView、`textIsSelectable`、Compose `SelectionContainer`）。**微信/QQ 的消息气泡通常不可选**，所以它拿不到整段对话——它是新增入口，不是 `ACTION_SEND` 的替代
- 系统默认只显示少数几项，用户可能要展开 overflow 才看到本应用

### 2. `ACTION_SEND` 加固（已实现）

- 过滤器从 `text/plain` 放宽为 **`text/*`**：只声明 `text/plain` 时，发送方用 `text/html` 就匹配不到，应用不会出现在分享列表
- 载荷解析顺序：`EXTRA_TEXT` → `EXTRA_HTML_TEXT` → `ClipData`
- **`ClipData` 兜底是必需的**：`ShareCompat.IntentReader`（androidx 源码已核对）**完全不读 `ClipData`**，而有些发送方只把文本放在那里
- **`ClipData` 只作兜底且要求内容可信**：命令行 intent 会在 `ClipData` 里留下组件名之类的短串（实测为 `-n`），曾导致 2 个字符的垃圾被当成聊天文本导入。现在仅在"extras 未产出可用文本"且"该串有不低于 4 个非空白字符"时才采用

### 仍未闭合：跨进程重建后重复导入

任务被重建时 Android 会从任务记录重新投递原始 intent。实测确认**清掉 extra 没用**——任务记录仍保留原始载荷。因此应用侧用 `ConsumedShareStore` 记住"已消费"（只存哈希与时间戳，不存原文，15 分钟过期）。

残余风险：**原文本身仍留在 `system_server` 的任务记录中**，直到该任务被划掉。应用能阻止再次导入，但抹不掉载荷。

---

## 待实施，按推荐顺序

### 第 3 步：悬浮窗助手（约 1 周）

`SYSTEM_ALERT_WINDOW`（无运行时授权，走 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`）+ `TYPE_APPLICATION_OVERLAY`。

**三个必须预先设计对的坑：**

1. **Android 12「不可信触摸」**：覆盖窗口必须"足够透明"，否则穿过它的触摸被系统拦截。对策：悬浮球窗口用 `WRAP_CONTENT` 只包球体、周围全透明；大范围遮罩用 `FLAG_NOT_TOUCHABLE` + alpha 0。（**精确透明度阈值未能取得官方原文，落地前必须核对**）
2. **Android 12 `HIDE_OVERLAY_WINDOWS`**：任何 App 声明该权限后，其窗口上方的非系统覆盖窗口会被隐藏。**银行/支付类很可能这么做，微信未验证** → "只靠悬浮窗做入口"是最大产品风险。
3. **国产 ROM**：小米/OPPO/vivo/华为另有"后台弹出界面/悬浮窗"开关与杀后台，屏译专门做了引导页。

持 `SYSTEM_ALERT_WINDOW` 是"允许后台启动 Activity"的豁免条件之一（API 29+），对"点悬浮球直接拉起界面"很关键。

**它只解决"入口常驻"，单独无法解决"读到对话"。**

### 第 4 步：MediaProjection + 区域 OCR（约 1–2 周）

**这一步才真正解决"接收整段聊天文本"，且不触碰 Play 高危权限。**

Android 14（本应用 targetSdk 34，直接受影响）三条硬要求：

1. **每个 capture session 都要重新征得用户同意**（一个会话 = 一次 `createVirtualDisplay()`）。**不要缓存复用 `createScreenCaptureIntent()` 的返回 Intent**——这是官方点名的反模式
2. 必须声明 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限与 `foregroundServiceType="mediaProjection"`，且**必须先 `startForeground(...)` 再 `getMediaProjection()`**，否则 `SecurityException`
3. 需要 `registerCallback`（否则 `createVirtualDisplay` 抛 `IllegalStateException`）——**把握较高但未核对原文，落地前验证**

补充：用 `MediaProjectionConfig.createConfigForDefaultDisplay()` 限定全屏，避免用户选单个 App 导致尺寸/坐标变化。`FLAG_SECURE` 窗口截出黑屏（不绕过、不承诺对金融/视频可用）。绕开每会话授权需 Shizuku。

**OCR 选型**：ML Kit Text Recognition v2 的 **bundled 中文模型**（`com.google.mlkit:text-recognition-chinese`）——约 +4MB/ABI，**完全离线、不依赖 Google Play services**，是 MVP 首选。**不要用 unbundled 版**（模型靠 GMS 动态下载，下载完成前返回空结果，国内无 GMS 机型大概率不可用）。中文密排更强可选 PaddleOCR + ONNX（屏译的做法），代价是模型管理。

**真正的成本不在 OCR，而在 OCR 结果的"消息切分 / 去重 / 时间线重建"——这比识别本身难，是核心难点。**

### 第 5 步：无障碍服务（可选，且需接受分发风险）

技术上更能直接读到界面文本（`canRetrieveWindowContent`、`FLAG_RETRIEVE_INTERACTIVE_WINDOWS = 64`），且 `AccessibilityService.takeScreenshot()` 自 **API 30** 可用、**不需要 MediaProjection 与授权弹窗**（需 `canTakeScreenshot="true"`）。

但两条硬约束：

- **Google Play 基本不可行**：官方规定 Accessibility API 不是为自动化设计，只有"核心功能服务残障用户"才能标 `IsAccessibilityTool`，否则须论证"没有更窄的 API 能达到同样效果"。而"读取其他 App 聊天内容给自家 AI"这一需求本身几乎无法论证。有公开案例因该权限被拒 4 次、移除后 2 天过审；另需注意**声明粘性**——任一 track 还有带该权限的活跃版本，声明就删不掉、拒审持续。Android 13+ 侧载 App 默认处于 Restricted settings，用户须手动允许才能启用无障碍
- **可读性未知**：微信/QQ 当前版本能否读到消息文本**没有任何实测数据**，是最大单点不确定性。列表只渲染可视区，需模拟滚动 + 去重才能拼出整段对话

**先做 PoC 验证可读性，再决定是否投入。** 若做：`packageNames` 白名单 + 显式开关 + 用完 `disableSelf()`。

### 第 6 步：自定义输入法（最后，且建议 fork）

**它解决"输出"不解决"接收"**：`InputConnection` 只覆盖当前聚焦输入框，拿不到上方消息列表。所以 AI 键盘只能"帮写回复"，要上下文仍须叠加 OCR 或无障碍。

工作量：最小可用 IME 约 1–2 周；做到日常可用是人年级别。**建议 fork HeliBoard / FlorisBoard 加 AI 候选条，不要从零写。** 约束：不能静默成为默认输入法（须引导用户到设置启用）；遇到密码类 `inputType` 必须完全不采集、不联网、不留存；尊重 `IME_FLAG_NO_PERSONALIZED_LEARNING`。

---

## Just-in-Time Context 的落实清单

平台自带的范式可直接照抄：

- **会话对象化**：`CaptureSession` 明确 start/stop、超时自动结束、退出即释放（`MediaProjection.stop()` / `disableSelf()` / `Bitmap.recycle()`）
- **只在内存处理**：文本不落盘、日志脱敏、**不要用 SharedPreferences 存对话**（`ConsumedShareStore` 只存哈希不存原文，是符合此原则的）
- **UI 显性化**：悬浮球状态、常驻通知、结束反馈
- `Activity.setRecentsScreenshotEnabled(false)`（API 33）避免聊天截图残留在最近任务缩略图；敏感界面 `FLAG_SECURE`
- **范围最小化**：只 OCR 框选区域；无障碍用 `packageNames` 白名单
- 端侧优先，云端调用单独开关 + 明确告知
- 参考 `ClipDescription.EXTRA_IS_SENSITIVE`（API 33）语义：接收方据此不预览、不持久化

---

## 需要在真机上自行确认的事（本调研无法替代）

1. **微信/QQ/DingTalk 实际分享的 MIME type 与 extras 组合** → 用 `adb logcat -s FlowAI` 抓一周真实数据再定稿过滤器
2. **微信/QQ 当前版本的无障碍节点可读性**（最大技术风险，决定第 5 步是否成立）
3. Android 12「不可信触摸」的精确透明度阈值
4. 微信是否声明 `HIDE_OVERLAY_WINDOWS`（决定悬浮球是否可见）
5. Android 14 是否强制 `MediaProjection.registerCallback()`
6. ML Kit 中文识别的实际延迟（官方未给数值）

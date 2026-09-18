# 就地助手面板（V2）

日期：2026-09-19。目标：让用户在**不离开当前应用**的情况下获得沟通分析 —— 即产品总纲里"悬浮助手是核心入口"的落地。

## 为什么是这个形态

最初的计划是"点悬浮球 → 框选截屏 → OCR → 分析"，让助手偷偷读取屏幕。**这条路被平台堵死了**：

- **无障碍读界面**：实测微信不向无障碍框架暴露任何内容（见 [无障碍 PoC](A11Y_READABILITY_POC.md)）
- **截屏 OCR**：用户设备启用了 HyperOS 的**屏幕共享保护**（`screen_share_protection_on=1`），对受保护界面截屏得到黑帧（实测 `screen capture produced no text`，帧统计为空）

因此改为**由用户主动提供内容**，助手只负责分析并就地展示。这不是退让：

- 符合平台安全策略，不需要用户关掉屏幕保护
- 符合总纲的 Just-in-Time Context（用户主动调用、只获取必要内容、不持续监听）
- 在任何设备上都能用，不依赖特定 ROM 设置

## 实现

| 文件 | 作用 |
| --- | --- |
| `ui/panel/AssistantPanel.kt` | 面板本体（Compose）：输入、分析、状态与建议、候选回复、复制 |
| `system/AssistantPanelWindow.kt` | 把面板挂成 `TYPE_APPLICATION_OVERLAY` 窗口；自备 lifecycle / ViewModelStore / SavedStateRegistry（Compose 在 WindowManager 里需要） |
| `system/FloatingAssistantService.kt` | 悬浮球；**点击改为打开面板**（原先只是把应用拉到前台，那是装饰） |
| `MainActivity` | `--ez show_assistant true` 可直接打开面板；用于测试与将来的应用内入口 |
| `ui/home/HomeScreen.kt` | 新增「打开助手面板」按钮，不依赖悬浮窗也能用 |

**关键实现点**：

- 面板窗口用 `FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH`，且高度 `WRAP_CONTENT`、`Gravity.BOTTOM` —— 只占底部一小块，**下面的应用仍然可见**，这正是"就地"的含义。
- 复用与应用相同的 `ConversationRepository` + `MockLlmService`，因此面板结果与完整界面**完全一致**。
- 面板明确写出"助手不会读取屏幕内容；请把要分析的聊天粘贴到下面"，不假装能读屏。

## 实测（模拟器，Android 14）

| 项 | 结果 |
| --- | --- |
| 面板能否弹出 | ✅ 日志 `assistant panel shown`，界面元素齐全 |
| 浮在应用之上 | ✅ 底部面板浮于 FlowAI 之上（在微信上方同理） |
| 输入聊天内容 | ✅ 输入框可输入 |
| 分析 | ✅ 显示话题 / 沟通状态 / 未解决 / 沟通信号 / 推荐下一步 |
| 点选动作 → 候选回复 | ✅ 自然 / 简洁 / 正式三条，各带「复制这条」 |
| 收起 | ✅ |

截图：`46-assistant-panel.png`、`47-panel-analysis.png`、`48-panel-replies.png`、`49-panel-replies-scrolled.png`。

## 关于截屏路线的复核（用户关闭屏幕共享保护之后）

用户关闭了 HyperOS 的屏幕共享保护（`screen_share_protection_on = 0`），因此重新复核了"截屏能否读到微信"：

| 证据 | 结果 |
| --- | --- |
| `settings get secure screen_share_protection_on` | ✅ 已为 `0` |
| `screencap`（系统级截图） | ✅ 463.7 KB，画面正常 |
| `screenrecord`（与 FlowAI 用同一套 MediaProjection） | ⚠️ 3 秒仅 67.6 KB，**且关闭保护前也是同一数量级** |

**结论：未能确认。** 两条线索互相矛盾：

- `screencap` 正常，说明屏幕内容本身可被捕获
- `screenrecord` 体积异常且关保护前后一致，暗示 **MediaProjection 在这台设备上可能整体受限**（HyperOS 或把录屏能力保留给系统自带录屏）

因此**不能仅凭"关闭了保护"就断定 FlowAI 的截屏能拿到画面**。要定论需要看实际帧内容（帧亮度统计），而这需要一个可用的 APK 加上人工授权——反复重装会清除悬浮窗权限，因此留待用户自行验证。

**这件事的现状**：助手面板（本文件主体）**不依赖截屏**，已经可用。截屏是否作为附加能力接回，取决于上面的验证结果。

## 面板的收起行为（用户反馈后修复）

用户指出两处体验缺陷，均已修正：

| 缺陷 | 原因 | 修复 |
| --- | --- | --- |
| 点「在完整界面中打开」后**面板仍盖在上面** | `openFullApp()` 只启动 Activity，没有收起面板 | 先 `hide()` 再启动；已实测（覆盖窗口 2 → 1） |
| 点面板**外部**不收起 | 声明了 `FLAG_WATCH_OUTSIDE_TOUCH`，但**从未处理 `ACTION_OUTSIDE`** —— 声明该标志本身不会做任何事 | 用 `OutsideTouchFrameLayout` 包裹 ComposeView 并处理 `ACTION_OUTSIDE` |

关于外部点击的说明：`ACTION_OUTSIDE` 只在触摸落在窗口边界之外时投递，而 adb 注入的触摸到不了覆盖窗口，因此**这一条无法自动验证**，需人工确认。

## 已知限制
- **未在真机验证**：每次重装 APK 会清除"显示在其他应用上层"权限，因此留待用户自行安装后测试。
- **adb 无法驱动悬浮球**：注入的触摸到不了覆盖窗口（系统安全限制），所以"点球开面板"这一步只能人工验证；面板本身已通过 `--ez show_assistant true` 验证。
- **输入方式受限**：目前是"粘贴剪贴板"或手动输入。**理想形态是配合划词入口**——在微信里选中文字 → 用 FlowAI 分析 → 面板带着这段文字直接弹出，省掉粘贴动作。尚未接通。
- 面板是只读展示 + 复制；不写入日历/待办，不自动发送。

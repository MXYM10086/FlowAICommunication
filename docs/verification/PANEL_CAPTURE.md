# 面板内截屏分析（V2）

日期：2026-09-19。为助手面板增加"截屏分析"入口：在面板里点一下 → 框选聊天区域 → 识别 → 结果回到面板。

## 真机验证结果（Redmi / Android 16 / HyperOS，0.7.0）

用户人工操作，日志全程同一进程（`27469`），**连续 3 次全部成功**：

```text
02:16:35.538  assistant panel suspended              ← 面板挂起
02:16:36.702  panel capture region: full screen
02:16:41.169  capture ready: 1440x3006 @ 560 dpi     ← 真机分辨率
02:16:42.388  panel capture: active=true chars=83    ← OCR 成功
02:16:42.432  panel deliverCapture: chars=83 suspended=true
02:16:42.484  assistant panel resumed                ← 面板恢复
02:16:42.484  panel visible after capture: true      ← ✅ 重现成功
```

| 次数 | 识别字符数 | 面板恢复 |
| --- | --- | --- |
| 1 | 83 | ✅ |
| 2 | 163 | ✅ |
| 3 | 173 | ✅ |

**关键结论**：上一轮那个"进程被系统回收、面板随窗口消失"的问题**未再出现**，证明"只挂起不销毁"的修法与根因对应。

同时真机确认：**点击面板外部可正常收起面板**（此前只声明 `FLAG_WATCH_OUTSIDE_TOUCH` 而未处理 `ACTION_OUTSIDE`，是缺陷）。

## 流程

```
面板「截屏分析（框选聊天区域）」
  → 面板隐藏（关键：覆盖窗口会出现在截图里，必须先移除）
  → 区域选择（半透明，能看到下方聊天）
  → MediaProjection 授权
  → 截帧 + 裁剪到所选区域
  → 端侧 OCR
  → 结果回填面板输入框，面板重新显示
```

## 实测（模拟器，Android 14）

| 环节 | 结果 |
| --- | --- |
| 面板出现「截屏分析」按钮 | ✅ |
| 点击后**面板先行移除** | ✅ 覆盖窗口由 2 → 1（只剩悬浮球） |
| 区域选择 | ✅ 日志精确记录所选区域 |
| 授权 → 截帧 → 裁剪 | ✅ `frame 600x700 avgLuma=218` —— 确认裁到所选区域且**非黑屏** |
| 端侧 OCR | ✅ `chars=56`（框选）/ `chars=249`（全屏） |
| 结果回传面板 | ✅ `panel capture returned: chars=249` → `deliverCapture: panelOpen=true` → `panel deliverCapture: chars=249` |
| **面板重新显示** | ❌ **未成功** —— 见下 |

## 修掉的两个缺陷（都是我的接线/生命周期错误）

1. **面板没有先隐藏。** 面板按钮原本直接绑定 `onStartCapture` 回调，**绕过了 `startCapture()`**，因此 `hide()` 从未执行 —— 面板会被截进画面并当作聊天文字识别。改为按钮调用 `beginCapture()`（内部先 `hide()`）。已由"覆盖窗口 2 → 1"验证。

2. **回传结果无人接收（`onCreate` vs `onNewIntent`）。** `MainActivity` 是 `singleTask` 且通常已在任务栈中，因此 `CaptureForPanelActivity` 返回时的 `FLAG_ACTIVITY_SINGLE_TOP` 启动触发的是 **`onNewIntent`**，而回传逻辑只写在 `onCreate`。已抽出 `handleAssistantIntents()` 并在两处调用。修复后回传日志完整出现。

## 剩余问题：面板在截屏返回后不重新显示 —— 已修复

**根因**：面板在截屏前被 `hide()` **销毁并置空**（`view = null`），返回时走 `show()` **重建** ComposeView 与整个 lifecycle。而这段时间里要经过授权弹窗与授权页 —— 面板的状态（用户输入的文字、lifecycle 对象）在重建前无处安放，实测还伴随进程被系统静默回收：

```
18:05:19.669  21106  panel deliverCapture: chars=249      ← 已重建
18:05:21.236  21265  floating assistant starting          ← 新进程，21106 已死
```

**修复：截屏期间只挂起，不销毁。**

- 新增 `suspendPanel()` / `resumePanel()`：只把根视图从 `WindowManager` 摘下 / 重新挂上，**保留对象、Compose 状态与 lifecycle**。
- `beginCapture()` 改为 `suspendPanel()`（原为 `hide()`）；`deliverCapture()` 在有挂起时 `resumePanel()`，否则才 `show()`。
- `hide()` 仍用于真正的关闭（收起按钮、点外部、打开完整界面），会一并清空 `rootView` 与挂起标志。

**修复后实测（模拟器）**：

```
assistant panel suspended                    ← 截屏前只挂起（覆盖窗口 2 → 1）
panel capture: active=true chars=251
panel deliverCapture: chars=251 suspended=true
assistant panel resumed                      ← 恢复，同一进程 21677
panel visible after capture: true
capture released → capture session released
```

截图 `54-panel-capture-restored.png` 可见：面板恢复后**输入框里已填入识别出的文字**，Compose 状态完整保留。全程同一进程，未再出现进程回收。

## 已知边界

- 依赖 `SYSTEM_ALERT_WINDOW`；每次重装 APK 该权限会被 HyperOS 清除。
- 该设备（Redmi / HyperOS）在关闭「屏幕共享保护」后 MediaProjection 才可用；**开启时截到的是空帧**（`screen capture produced no text`）。
- 真机尚未验证本流程（重装会清权限，留待用户自行安装测试）。

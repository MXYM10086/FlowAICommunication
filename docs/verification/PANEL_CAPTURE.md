# 面板内截屏分析（V2）

日期：2026-09-19。为助手面板增加"截屏分析"入口：在面板里点一下 → 框选聊天区域 → 识别 → 结果回到面板。

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

## 剩余问题：面板在截屏返回后不重新显示

**根因（日志证实）**：进程在回传之后被系统杀死。

```
18:05:19.669  21106  panel deliverCapture: chars=249      ← 面板已重新挂载
18:05:21.236  21265  floating assistant starting          ← 新进程，21106 已死
```

面板是 `WindowManager` 覆盖窗口，**随进程一起消失**。新服务实例没有面板状态，因此界面上看不到面板（前台退回桌面）。logcat 中**没有 ANR 或 lowmemorykiller 记录**，属于系统静默回收。

**影响**：OCR 与数据链路完全正常（249 字符已识别并回传），只是"结果呈现"这一步在进程被杀时丢失。

**可能的修法（未实施）**：
- 让服务在 `onCreate` 时检查是否存在待呈现的捕获结果（需要一个进程外的小存储），若有则自动重开面板；
- 或在回传后**先**把面板显示出来、再让捕获 Activity 结束，缩短服务处于后台的时间窗；
- 或把面板改为**常驻**（捕获期间不销毁，只临时移除视图），避免跨进程状态丢失。

## 已知边界

- 依赖 `SYSTEM_ALERT_WINDOW`；每次重装 APK 该权限会被 HyperOS 清除。
- 该设备（Redmi / HyperOS）在关闭「屏幕共享保护」后 MediaProjection 才可用；**开启时截到的是空帧**（`screen capture produced no text`）。
- 真机尚未验证本流程（重装会清权限，留待用户自行安装测试）。
